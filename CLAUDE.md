# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
bb run                        # start eca-cli TUI
bb run --trust                # start with auto-approved tool calls
bb run --eca /path/to/eca     # specify ECA binary explicitly
bb run --workspace /path      # set workspace root (default: cwd)
bb run --model <model>        # specify model
bb run --agent <agent>        # specify agent
bb run --version / --help     # what the release binary answers as `eca-cli --version` / `--help`
bb nrepl                      # start nREPL for development
bb upgrade-eca [--force]      # check for / install the latest ECA server now (automatic on `bb run`)
bb test                       # unit tests
bb dist [--platforms linux-amd64,macos-aarch64]  # release artifacts into dist/, exactly like CI
bb release-version            # the version a release of HEAD gets: 0.1.<commit count>
```

In-app commands (type in input, press Enter):

```
/model      open model picker (Ctrl+L also works)
/agent      open agent picker
/new        start fresh chat (deletes current chat, clears UI)
/chats      browse and resume previous chats
```

Requires Babashka 1.12.215+. No separate build step — `bb run` compiles and runs directly. `bb.edn` `:paths` is only `src` (what ships); tasks add `test/` and `build/` through `:extra-paths` as needed.

ECA server logs go to `~/.local/state/eca/eca-cli.log` (`$XDG_STATE_HOME`). Tail this when debugging.

## Architecture

eca-cli is a Babashka TUI client for the ECA (Editor Code Assistant) server. It speaks the ECA JSON-RPC protocol over stdin/stdout (same as editor plugins). The LLM pulls context via ECA's built-in tools — the user just sends messages.

### Data flow

```
ECA process stdout
  → reader thread (server.clj)
  → LinkedBlockingQueue
  → drain-queue-cmd (state.clj) — polls every 50ms
  → :eca-tick message
  → update-state (state.clj)
  → charm.clj render loop
  → view (view.clj)
```

Responses to requests (e.g. `chat/prompt` → `{chatId, model}`) are routed through the queue via a callback that calls `.put queue` — so all server messages flow through the same path.

Requests *from* the server (an `:id` and a `:method`, e.g. `chat/askQuestion`) arrive like notifications; their handler answers later with `protocol/send-response!`. One eca-cli doesn't handle gets `send-error-response!` (method not found), never silence: the server would wait forever. Only advertise a capability (`protocol/initialize-params`) once its requests are handled.

### Startup

`core/parse-args` handles `--help`, `--version` and invalid options (unknown flags and stray arguments exit 1 with a hint) without touching the workspace or the server. Otherwise `core/-main` resolves the ECA binary *before* the TUI takes over the terminal (`upgrade/resolve-binary!`, which may download/update the managed binary with progress on stderr; fatal errors print and exit 1), sends the terminal's `enacs` sequence (`terminal/enable-alt-charset!`, see below), then starts charm.clj.

### charm.clj Elm loop

`eca-cli.program/run` (a copy of charm's `run` with a different loop) drives the TUI with three fns. charm's loop handles one message per 10 ms tick and renders after each: typing/pasting capped at ~100 keys/s, and a big paste overflowed core.async's 1024 pending puts, killing charm's input thread. Ours wakes on the first message, drains the queue (up to 1000), renders once. It also turns on bracketed paste: pasted text comes between `:paste-start`/`:paste-end` keys, and `state.clj` keeps its newlines/tabs/leading `/` `@` as text. charm's text input doesn't scroll or wrap, so `view/prompt-input` lays the input out itself (rows at the width, newlines, a window around the cursor).

The three fns:
- `state/make-init` — returns a fn that spawns ECA, starts the reader thread, returns `[initial-state (batch init-cmd drain-queue-cmd)]`. Must never throw: charm calls it outside its try/finally, so a spawn failure becomes a `:disconnected` state instead.
- `state/update-state` — pure dispatch: returns `[new-state cmd-or-nil]`
- `view/view` — pure render: returns a string from state

The drain loop starts at init and is self-sustaining: the `:eca-tick` handler returns a new `drain-queue-cmd` until the mode is `:disconnected`. If the cmd fn returns nil, charm.clj drops it — so the tick message is always `{:type :eca-tick :msgs [...]}` even when the queue is empty.

`initialize` is asynchronous: its response is `.put` on the queue as `:eca-initialize-response`; the handler sends `initialized` and switches to `:ready`. There is deliberately no timeout — the server answers only after loading the user's config, which may run commands (e.g. fetching a provider token). The view shows a "Connecting…" spinner with elapsed time meanwhile.

### State modes

`:connecting` → `:ready` ↔ `:chatting` ↔ `:approving`, and any of them → `:disconnected`

- `:connecting` — waiting for the `initialize` response
- `:ready` — input focused, awaiting user message
- `:chatting` — LLM active, streaming. The input stays live: Enter steers (`chat/promptSteer`, text in `:steered` until the server echoes it as a user message), Alt+Enter queues (`:queued`); both are sent as the next prompt when the turn ends (`chat/send-pending`), or given back to the input by Esc / ↑ (`take-back-pending`). Only `commands/runs-during-turn?` commands run mid-turn; other `/…` text steers. Alt+Enter arrives as an Alt'ed `"\r"` (ESC CR), see `chat/alt-enter?`.
- `:approving` — a tool call (main chat or sub-agent) waiting on `y`/`n`/`Y`; more may wait in `:approval-queue`
- `:asking` — the server asked a question (`chat/askQuestion`, `question.clj`), answered from the input area; more may wait in `:questions`. Approvals and questions don't interrupt each other: `chat/settle-mode` picks what comes next once one is answered (then `:chatting` while `:turn-started-at` says a turn runs, else `:ready`). A server `progress: running` marks a turn as running even when the server started it.
- `:disconnected` — server exited (or failed to spawn / refused `initialize`). The reader thread queues a `:reader-error` whose `:error` is a ready-to-show report (exit code + tail of the server log, `server/exit-report`). Only scrolling and quitting work; quitting skips the protocol `shutdown` (also skipped in `:connecting`).

### Tool call lifecycle

`toolCallPrepare` (many, streaming args) → `toolCallRun` (approval decision point) → `toolCallRunning` → `toolCalled` or `toolCallRejected`

All five events go through one path (`chat/tool-fields` → `upsert-tool-call`). Payloads are detected by `details.type`, never by tool name: `fileChange` → `:edit` (diff), `shellCommand` → `:shell` (per-command breakdown with approval keys), `subagent` → `:subagent-chat-id` (the call gets `:sub-items`, and that chat's content is routed into them via `:subagent-chats`). Note that the server sends the short tool name (`spawn_agent`, `task`) plus `server: "eca"`, not `eca__…`.

The task tool (`server "eca"`, `name "task"`, `chat/task-call?`) is not a chat item: its `task` details feed `:tasks` (`chat/content->tasks`), drawn as the task panel above the prompt (`view/task-panel-lines`, Ctrl+T toggles `:tasks-expanded?`); its calls still go through `:tool-calls` so they can be approved. The server clears a finished plan on the next prompt by sending empty details.

Approvals: the server decides who asks. Trust mode and remembered approvals are applied server-side, so eca-cli only asks when `toolCallRun` says `manualApproval: true` (a hook can force that even in trust mode, so never auto-approve on the client). Calls waiting in parallel, from the main chat or a sub-agent, queue up: one in `:pending-approval`, the rest in `:approval-queue`; each is answered with the chat id it came from. `Y` approves with `save: "session"`, which makes the server remember the tool (or, for shell/git, the commands) for the chat and its sub-agents. Trust is sent with every prompt once known (`[:opts :trust]`: `--trust`, `/trust`, or the server's `selectTrust`, e.g. from `chat.defaultTrust`); an explicit `false` keeps a new chat from falling back to the default.

### View rendering

No viewport component — `view.clj` uses manual line-slice: `:chat-lines` is a flat vec of rendered strings kept in state, rebuilt via `view/rebuild-lines` after content changes. `render-chat` slices the last N visible lines adjusted by `:scroll-offset`.

- Items render in `view/blocks.clj`. Message text (assistant, streaming, user) goes through `markdown/render`: babashka's built-in nextjournal.markdown parses it, eca-cli lays the AST out for the width (`markdown/highlight.clj` colours fenced code). Finished items are memoized by value and width, so only the reply still streaming re-renders.
- A server tick applies its whole batch of messages with `view/*rebuild-deferred*` bound: the handlers' `rebuild-lines` calls collapse into one rebuild per batch.
- Colours come from `theme.clj` roles via `theme/paint`, mostly on the ANSI 16 palette so the terminal's theme picks the actual colours (dark or light); `NO_COLOR` drops them, keeping bold/italic.
- The status line (`view/status.clj`) is a list of segments with priorities; on a narrow terminal the least important are dropped (or shortened) until it fits.
- JLine constraints every renderer must respect: each line is parsed on its own, so styles never carry over to the next line (every line opens its styles and ends with a reset); a line wider than the terminal is cut *and loses all its colours*, so wrap or fit to the width; trailing spaces are dropped even when styled, so no full-width background bars and no badge ending a line with a space.

### Key files

- `src/eca_cli/program.clj` — the event loop (charm's `run`, draining the queue per render) and bracketed paste
- `src/eca_cli/server.clj` — process spawn, Content-Length JSON-RPC framing (synchronized writes that return false instead of throwing once the server is gone), reader thread, exit report, queue drain
- `src/eca_cli/protocol.clj` — message constructors, request ID tracking, response correlation
- `src/eca_cli/state.clj` — Elm state machine, ECA content handlers, all key bindings
- `src/eca_cli/chat.clj` — chat content handlers, tool-call lifecycle, approvals queue, chat routing/switching, prompt submission
- `src/eca_cli/commands.clj` — slash commands: local registry, server commands (`chat/queryCommands`), dispatch, `/` picker items
- `src/eca_cli/question.clj` — `chat/askQuestion`: question queue, `:asking` keys, answers
- `src/eca_cli/picker.clj` — the pickers (model, agent, variant, chat, `@`/`#`): filtering, selection, `at-label`
- `src/eca_cli/mcp.clj` — `:mcps` (`tool/serverUpdated` / `tool/serverRemoved`), the `/mcp` panel and its per-state actions (`actions-for` → `protocol/mcp-server-action!`), the status-line slot
- `src/eca_cli/providers.clj` — `/providers`: the `providers/list` panel (login state, source, expiry, models, quota), log in through `login.clj`'s flow, `providers/logout`
- `src/eca_cli/history.clj` — `/rollback`, `/flag`, `/flags` (jump / fork / remove), `/timeline`: two-step pickers over the chat's `:user` items (`:content-id` from the server's echo) and `:flag` items. Rollback and add-flag make the server clear the chat and replay it.
- `src/eca_cli/view.clj` — pure rendering: chat lines (cached, batched), gutter (connecting / working), approval prompt, pickers, login, layout
- `src/eca_cli/view/blocks.clj` — one renderer per chat item type: messages, tool calls (diffs, argument/output boxes), thinking, hooks, links, system notes
- `src/eca_cli/view/status.clj` — the status line: segments, priorities, context-window bar, usage/cost/quota
- `src/eca_cli/markdown.clj` — markdown → styled, width-wrapped lines (lists, quotes, code, tables); falls back to plain text on failure
- `src/eca_cli/markdown/highlight.clj` — table-driven syntax highlighting for fenced code
- `src/eca_cli/theme.clj` — colour/attribute roles, SGR helpers, `NO_COLOR`
- `src/eca_cli/wrap.clj` — ANSI/width-aware word wrap for plain text
- `src/eca_cli/chats.clj` — EDN persistence of workspace → chat-id map
- `src/eca_cli/upgrade.clj` — ECA binary resolution and the managed install: latest GitHub release (1h check cache), sha256-verified download, staged + atomic install, `eca-version` marker, offline fallback
- `src/eca_cli/terminal.clj` — sends terminfo `enacs`: JLine draws `─│┌…` via the alternate charset, which under screen/tmux terminfo (SO/SI) shows as `qqqq`/`xxxx` unless G1 was designated as DEC graphics first
- `src/eca_cli/version.clj` — eca-cli's version from the `ECA_CLI_VERSION` jar resource (`dev` in a checkout); sent as `clientInfo.version`
- `build/eca_cli/dist.clj` — `bb dist` / `bb release-version` (not shipped)
- `install` — the curl-able install/update script
- `.github/workflows/ci.yml` — tests, build, per-OS smoke tests, release

### Distribution and releases

Every push to `main` is a release, no tag needed (`.github/workflows/ci.yml`): unit tests + lint, `bb dist`, a smoke test of each binary on its own OS (Linux x86_64/arm64, macOS Intel/Apple silicon), a GitHub release tagged `0.1.<commit count>` with notes listing the commits since the previous release, then the `install` script run against it on Linux and macOS. PRs run everything but the release; docs-only pushes don't trigger it.

- Binaries are self-contained: a babashka binary with the uberjar appended — bb runs the `-main` of a jar glued to itself, so users need neither bb nor a JVM. No GraalVM: one Linux job builds every platform by concatenation. Linux x86_64 embeds bb's fully static (musl) build, so it runs on NixOS/Alpine; bb's arm64 Linux build still needs glibc.
- The embedded bb is the release CI tests with (`BB_VERSION` in the workflow); `bb dist` embeds the version of the bb running it unless `--bb-version` says otherwise.
- Version: `eca-cli.dist/version-line` (`0.1`, bump by hand for milestones) + `git rev-list --count HEAD`, so a commit always gets the same version (needs full history: `fetch-depth: 0`). `bb dist` stamps it into the jar as the `ECA_CLI_VERSION` resource.
- charm.clj's JVM-only deps (JLine, core.async) are excluded in `bb.edn`: bb embeds its own, and they'd bloat the jar ~10x.
- Assets: `eca-cli-<linux|macos>-<amd64|aarch64>.tar.gz` (holding `eca-cli`, owned by root:root), `eca-cli.jar` (`bb eca-cli.jar` anywhere), each with a `sha256sum -c` compatible `.sha256`. The `install` script relies on these names: it downloads the platform tarball from the latest (or `--version`) release, checks its sha256, runs `--version` on it next to the destination, then renames it over the old binary.

### ECA binary discovery order

Same as eca-emacs:

1. `--eca` flag (a path, or a command on PATH)
2. `eca` on PATH — used as-is, never updated by eca-cli
3. Managed binary `~/.cache/eca/eca-cli/eca` (`$XDG_CACHE_HOME`) — auto-installed and auto-updated to the latest release; on Linux x86_64 the static build (runs on NixOS/musl). A missing `eca-version` marker forces a reinstall.

### Session persistence

eca-cli generates a new chat's id itself (`chat/submit-prompt`, as the protocol recommends) and persists it to `~/.local/state/eca/eca-cli-chats.edn` (`$XDG_STATE_HOME`) keyed by workspace path; tests redirect that file (`test-support/isolated-chats-file`). Each startup begins a fresh session — no auto-resume. Use `/chats` to explicitly resume a previous chat. `/new` deletes the current chat and removes its entry from the chats file.

Chat routing: `chat/contentReceived` is shown only for the chat on screen, or nested under a spawn call for its sub-agents; other chats' messages are dropped. `chat/opened` for another chat (the server forked one off this one: `/fork`, `/btw`) switches the view to it (`chat/switch-to-chat`); the `chat/prompt` response never takes `:chat-id` back. `chat/cleared` only applies to the chat on screen.

### Slash commands

`commands/dispatch-command` handles Enter on `/…`: eca-cli's registry first (with arguments, a name the server also has goes to the server, e.g. `/model x/y`); otherwise the text is sent as a prompt and the server runs its own command (`/compact`, `/fork`, MCP prompts, skills…). Text that only starts with a slash (a path) is a message. A name neither knows is rejected only once the server's list is loaded. The list comes from `chat/queryCommands` into `:server-commands`: fetched after `initialize` and whenever the `/` picker opens.

### Context references (`@` / `#`)

As in eca-emacs: `@` at the start of a word opens a picker of contexts (`chat/queryContext`: files, directories, MCP resources; `cursor` and the deprecated `repoMap` left out), `#` one of paths (`chat/queryFiles`). Labels are relative to the workspace when inside it, directories end in `/` (`picker/at-label`); server items sit in the picker as `[label context]`. Answers carry the query they were for and are dropped when the user typed on. Picking with `@` adds the context to `:pending-contexts` and `@label` to the text; with `#`, only `#label`.

On send (`chat/start-turn`), the contexts are the picked ones whose `@label` is still in the text, plus workspace files named by hand with `@` (only tokens that look like paths, with a `/` or `.`, so pasted code's `@Override` or `#include` stay text). The text the server gets spells them out (`expand-refs`): `@/full/path`, and the bare full path for `#`. The chat shows what was typed.

### MCP and provider actions

`/mcp`: Enter on a server opens its actions (`mcp/actions-for`, by status); they're notifications and the result comes back as `tool/serverUpdated`. Enable/disable rewrite the user's ECA config file, and log out drops the server's stored OAuth credentials (server-side, global): only start/stop are harmless to try against a real config. `/providers` logs in through the same `:login` flow as a prompt needing login; with no pending message the login ends in `:ready` with a note.

### Protocol reference

ECA protocol spec: `../eca/docs/protocol.md`
