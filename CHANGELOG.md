# Changelog

All notable changes to eca-cli. Format inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Every push to `main` is released as `0.1.<commit count>` (see [releases](https://github.com/editor-code-assistant/eca-cli/releases)); entries here are grouped by roadmap phase and refactor phase.

## Unreleased

Plug-and-play pass: eca-cli ships as a single binary, installs and updates its own ECA server, and works on NixOS and inside tmux.

UX pass: chats render as markdown, the status line becomes a colourful modeline in the spirit of eca-emacs, and the whole UI takes on the terminal's colour theme.

Catch-up pass: eca-cli gains the features of the ECA editor clients it was missing (eca-emacs as reference), and fixes the places where it didn't match the server.

First-impression pass: eca-cli opens on a welcome screen with ECA's logo, Ctrl+C behaves as in Claude Code, and the README shows a demo.

### Added

- File changes name their file, as in eca-emacs: a collapsed edit reads `✓ Editing http.clj +9 −2` (was `Editing +9 −2`), and the expanded diff starts with the file's path, relative to the working directory.
- Shell calls read as in Claude Code: the whole command instead of the server's cropped summary (its line breaks kept, up to three rows, a `cd <working dir> &&` prefix dropped), then the first lines of its output under a `⎿` and `… +N lines (ctrl+o to view)`. A failed call shows its exit code and its stderr in red; one with no output says `(no output)`, one still running says `running…`. Expanded, the output box no longer shows the server's `Exit code:` / `Stdout:` headers.
- The transcript (`Ctrl+O`) reads like a pager: it opens on the newest block, focused; `j`/`k` (or `↑`/`↓`) move, `Space` (or `Enter`) folds, `h`/`l` (or `←`/`→`) fold and unfold as in a tree, stepping to a parent or into the first sub-item, `e`/`E` unfold/fold everything, `g`/`G` go to the first/last block, `q` returns. `y` copies the focused block (a message, a thought, a diff, a command and its output) through the terminal (OSC 52) and `pbcopy`/`wl-copy`/`xclip`/`xsel`; the footer says what was copied and lists the keys. Messages can be focused in the transcript, to copy them. While an approval or a question waits, letters still answer it, and `Esc` clears focus to type in the prompt again.

- Print mode for scripts and pipes, as `claude -p`, `codex exec` or `gemini -p`: `eca-cli -p "…"` (also whenever stdin or stdout isn't a terminal) runs the prompt, prints the answer and exits. Piped input is the prompt's context (`git diff | eca-cli -p "review this"`) or the prompt itself. `--output-format json` prints a result object (answer, chat id, model, usage, error, rejected tool calls), `stream-json` the server's chat events as they come, then the result. Tool calls that would ask are rejected unless `--trust`; exit codes say whether the turn failed. `-o` also writes the answer to a file, `--ephemeral` deletes the chat afterwards.
- `eca-cli "prompt"` starts the chat with a prompt; `-c, --continue` continues the workspace's latest chat (any client's), in the TUI or print mode; `-f, --file` attaches files and directories (alone, they wait in the input); `--title` names the chat; `--variant` joins `--model` and `--agent`, which now show from the start (and stay over the server's defaults until you pick others).
- `--list-models` and `--list-chats` (`--json` for JSON); `--log-level`, `--verbose` and `--config-file`, passed to the ECA server.
- `Ctrl+J` inserts a new line, as in Claude Code (it used to send or steer, like Enter); so do `Shift+Enter` in terminals that tell it from Enter and `\` then Enter. Enter still sends, or steers while the agent works; Alt+Enter queues.
- Keyboard shortcuts, after the other ECA clients, Claude Code, OpenCode and pi. **Shift+Tab** cycles agents (code ↔ plan). **Alt+T** cycles the variant (reasoning effort). **Alt+P** (or Ctrl+L) picks a model. **Ctrl+P / F2** (Shift+F2 back) cycles recent models, remembered across sessions. **Ctrl+Y** toggles trust. **Ctrl+N** starts a new chat. **Ctrl+R** searches the prompt history. **Ctrl+G** edits the prompt in `$VISUAL`/`$EDITOR`. **Esc Esc** on an empty prompt opens `/rollback`. **?** on an empty prompt (or `/keys`) lists all the keys. The model, agent, variant and trust keys, `/model`, `/agent` and `/variant` work while the agent works too, keeping the draft. An approval or question arriving while a picker is open waits for it, so letters typed to filter don't answer it.
- `Ctrl+O` toggles a full-screen transcript for inspecting, expanding and scrolling through the current chat; `PgUp` opens it too, without losing a draft or pending approval/question.
- `--fullscreen` preserves the previous full-screen experience.
- A welcome screen opens each new chat, in the spirit of Claude Code's: ECA's logo drawn in braille, eca-cli's and the server's versions, the model, agent and workspace, tips to get started (`/init` while the project has no AGENTS.md) and the latest chats to resume with `/chats` ("Welcome back!"). It fits itself to the terminal's width, and replaces the server's default welcome text; a `welcomeMessage` of your own still shows below it.
- Ctrl+C works as in Claude Code instead of quitting at once: it clears what's typed, or stops the agent while it works (like Esc); otherwise the first press shows `Press Ctrl+C again to exit` in the status line's place, and a second one within 2 seconds quits. Ctrl+D on an empty input does the same. `/quit` still quits right away.
- `demo.gif` in the README, recorded by `bb demo`: eca-cli in tmux against a scripted ECA server, in a demo project under a temporary HOME, so it needs no account and shows no one's data.
- Shell calls waiting for approval show the command and, when it helps, each chained command coloured by what `Y` would do with it: already remembered, would be remembered, or always asks. `Y` names the commands it remembers and is hidden when there's nothing to remember.
- `/trust` toggles trust mode for the current chat, right away (`chat/update`); the indicator follows the server's `selectTrust` (e.g. `chat.defaultTrust`, a resumed chat's trust).
- The server's commands work (Phase 10, partial): `/compact`, `/init`, `/context` (the context breakdown with its per-category legend, completing Phase 20), `/costs`, `/quota`, `/fork`, `/btw`, `/resume`, `/export`, `/doctor`, … plus MCP prompts, custom prompts and skills. The `/` picker lists them (`chat/queryCommands`) next to eca-cli's own, tagged by kind, best name matches first; picking one that takes arguments puts it in the input, with its signature shown above. Typed with arguments, a command both have (`/model x/y`) goes to the server.
- A chat the server forks off the current one (`/fork`, `/btw`) is shown and remembered; other chats' messages stay out of view.
- The agent can ask you questions (`chat/askQuestion`, Phase 10): the `ask_user` tool, which the server disabled for eca-cli until it advertised support, and the choices of the server's `/login`. The question takes over the input: ↑/↓ and Enter pick an option, typing gives your own answer when allowed, Esc cancels. Questions asked together are answered in turn, approvals and questions never interrupt each other, and what you were typing comes back afterwards. The answer stays in the chat, on the `ask_user` call's line.
- Server requests eca-cli doesn't support are answered with an error instead of leaving the server waiting.
- `/variant` (Phase 19) picks the model's variant (e.g. reasoning effort, `-` for none); the variant, picked or suggested by the server, is now sent with each prompt.
- `/rename <title>` renames the chat (`chat/update`); `metadata` content keeps the title up to date.
- Images the agent produces (`image` content) are saved to a temp file whose path shows in the chat, since terminals can't show them inline.
- `chat/statusChanged`: `Stopping…` while a stop winds down; turns the server runs itself show as running. The approval prompt shows how long the turn has been going.
- Pasting works like in an editor: bracketed paste keeps a pasted text's newlines (and a leading `/` or `@`) as text instead of sending the first line, and the input wraps over several rows (the cursor's ones when long) instead of running off the screen.
- `/rollback` (Phase 11): pick one of your messages, then drop the messages after it, undo the file changes tools made since, or both (`chat/rollback`); a dropped message comes back to the input. Your messages now keep the id the server gives them.
- Flags (Phase 14): `/flag <text>` sets a checkpoint (`chat/addFlag`) after your latest message or the agent's latest tool call, drawn as a rule across the chat; `/flags` jumps to one, forks a new chat from it (`chat/fork`) or removes it. `/timeline` jumps to any of your messages or flags.
- Task panel (Phase 15): the agent's plan (its task tool) shows above the prompt as in eca-emacs: what it's on and the progress (`Task: … (2/5)`), `Creating tasks…` while the first plan comes in; Ctrl+T unfolds the checklist (done struck through, the one in progress highlighted, high priority marked, blocked ones saying what they wait for). Task tool calls no longer clutter the chat.
- Message steering (Phase 9): the input stays live while the agent works. Enter steers the running prompt (`chat/promptSteer`, injected at the next turn boundary), Alt+Enter queues a message for when it's done, both shown above the prompt. Whatever wasn't consumed is sent when the turn ends; Esc (stop) or ↑ on an empty input gives it back to the input instead (`chat/promptSteerRemove`). `/trust`, `/help`, `/clear` and `/quit` still run mid-turn. The gutter says which keys do what.
- `@` adds folders, MCP resources and images besides files (`chat/queryContext`, Phase 12), labelled relative to the workspace as in eca-emacs; `#` puts a file's path in the message for the agent to read, without attaching it (`chat/queryFiles`). As eca-emacs does, the server gets them spelled out (`@/full/path`, and the bare full path for `#`), and `@`/`#` paths typed by hand or recalled from the history count too.
- `/providers` (Phase 18, partial), like eca-emacs's providers panel: each provider's login state (logged in, a key from the config or the environment, local), when the login expires, its models and subscription quota. Enter logs in (or adds an API key) through the usual login flow, or logs out (`providers/logout`).
- `/mcp` manages servers (Phase 17, partial): Enter on one offers what its state allows, as in eca-emacs: start, stop, connect (OAuth), enable, disable, log out. A server removed from the config (`tool/serverRemoved`) leaves the panel and the status line.

- Markdown rendering (Phase 8) for replies, prompts and the welcome text: headings, emphasis, inline code, links, nested and task lists, quotes, GFM tables (cells wrap to fit), and fenced code with syntax highlighting. Built on babashka's bundled nextjournal.markdown; falls back to plain text if rendering fails.
- `theme.clj`: colour roles on the terminal's ANSI palette, so eca-cli follows dark and light themes alike. `NO_COLOR` is honoured.
- Redesigned status line: agent badge, model and variant, workspace and chat title, MCP health dot, running jobs, last turn time and tok/s, a context-window bar drawn from the server's `contextBreakdown` with its auto-compact marker (Phase 20, partial), context %, tokens, cost or subscription quota, and SAFE / ● TRUST. On narrow terminals the least important segments go first.
- While a prompt runs, a gutter line says what the agent is doing (thinking, writing, running tools), for how long, and that Esc interrupts; a pending approval is flagged above the prompt and the divider turns yellow.
- `url` chat content renders as links; tool calls and thoughts show how long they took.
- Self-contained `eca-cli` binaries for Linux and macOS (x86_64 and arm64), so no Babashka, JVM or clone is needed: every push to `main` is tested, built, smoke-tested on each platform and published as a GitHub release, along with `eca-cli.jar` for any platform with Babashka.
- `install` script: `bash <(curl -fsSL https://raw.githubusercontent.com/editor-code-assistant/eca-cli/main/install)` installs or updates the binary (in `~/.local/bin` by default), verifying its checksum and that it runs before replacing the old one.
- `--version` and `--help`. Unknown options and stray arguments are reported instead of being silently ignored.
- `bb dist` and `bb release-version` build the release artifacts locally, the same way CI does.

### Fixed

- The server's own messages (the output of `/context`, `/costs`, `/skills`…, notices and errors) are shown as server notes, as in eca-emacs: in their own colour and marked `•`, instead of passing for (and merging into) the assistant's reply. `/context`'s grid and legend keep their layout.
- Replayed unfinished tools, thoughts, hooks and unanswered questions stay live until final; confirmed turn completion releases orphan activity without dismissing questions. Removing chat items no longer skips or duplicates later terminal output.
- Resizing and switching to/from the transcript preserve terminal history and live-row alignment; rapid resizes are debounced, and clearing or switching chats appends a new section instead of repainting old history.
- Short terminals keep the input cursor, selected question option and picker selection visible.
- The welcome waits for initialization tasks as well as startup facts, so late model discovery is included before it becomes terminal history.
- A `HOME` set for one run was ignored (the JVM takes the home directory from the user's account), so eca-cli kept reading and writing the real home's chats, cache and `~/` paths. `$HOME` now comes first.
- Sub-agents: their content landed in the main chat (and their `finished` ended the main turn) because eca-cli looked for a `subagentDetails` field and an `eca__spawn_agent` name the server never sends. Spawn calls are now recognised by their `subagent` details.
- A sub-agent tool call that needed approval hung the chat with nothing to approve; it now asks, answered with the sub-agent's chat id.
- Tool calls asking for approval in parallel replaced each other, leaving all but the last waiting forever; they now queue and are asked in turn, and calls answered or stopped elsewhere drop out.
- `Y` trusted the whole tool on the client: approving one `ls` auto-ran every later shell command. It now asks the server to remember the approval for the chat (`save: "session"`), which for shell and git remembers only the approved commands.
- Trust mode only auto-approved on the client: it was never sent to the server, didn't cover sub-agents, and overrode hooks that ask on purpose. It's now sent with each prompt and decided by the server. With `chat.defaultTrust` set, eca-cli showed SAFE while the server auto-approved everything.
- A task tool configured to ask hung the chat (eca#584).
- Any `/…` that wasn't one of eca-cli's commands was rejected as unknown, server commands included; text merely starting with a path (`/home/me/x is empty`) too.
- After resuming a chat, the next message landed above the chat's last reply, and the new reply was glued onto it.
- `@`-file contexts stuck to every later message instead of only the one that mentioned them.
- A login started by hand (`/login`) ended showing a running turn, with nothing running; it now says it's done and returns to the prompt.
- A login the server couldn't start (e.g. an unknown provider) left an empty login panel up; it now says why.
- When a login triggered by a message finished, that message could be sent twice: once on `providers/updated`, once on the login's answer.
- An answer to an older `@` filter could replace the current one's results.
- Tests wrote to the real chats file; they now use a temp one.
- A multi-line input area (an approval with a diff, a question) pushed the screen past the terminal's height; the chat now gives up the lines.
- Typing and pasting were capped at ~100 characters a second (charm handles one key per 10 ms tick and renders after each), and pasting more than ~1300 characters killed keyboard input for good (core.async's pending-put limit crashed charm's input thread). eca-cli runs its own event loop (`program.clj`): it handles everything queued, then renders once. 2000 pasted characters now take a fraction of a second.
- About 25 state tests never ran: a missing paren nested them inside another test.
- Dividers and boxes rendered as `qqqq…` / `xxxx` inside tmux/screen: JLine draws box glyphs through the terminal's alternate charset, which with `screen-256color`/`tmux-256color` terminfo (SO/SI) requires `enacs` first. eca-cli now sends the terminal's `enacs` on startup (`terminal.clj`).
- "Error: Stream closed" on NixOS/musl: `bb upgrade-eca` installed the glibc-linked build, which can't run there. Linux x86_64 now uses the static build, like the editor plugins.
- `bb` printed a warning about the `run` task overriding a built-in on every invocation.
- Leading indentation was stripped from every chat line: nested lists, code and diff context lines lost their indent.
- Expanded tool calls never showed their output: the server sends `outputs`, eca-cli read `output`.
- Sub-agent blocks got a row per streamed text chunk and per tool-call event; text now accumulates and each tool call is one row.
- The context % divided cumulative session tokens by the context window, passing 100% in long chats; it now comes from the server's context breakdown.
- API keys typed at a login prompt were echoed; secret fields are masked.
- The failed-MCP hint pointed at the old log location; `⚠` was doubled on some notices; the focused-block highlight was unreadable on light themes.

### Changed

- Inline is now the default: a compact live area starts below the launch command and grows with content, while completed chat stays in native terminal scrollback after exit. `bb demo`, which records the README's GIF, uses this layout with its isolated scripted server.
- ECA server management matches the editor plugins: no pinned version. On startup eca-cli resolves `--eca` → `eca` on PATH → a managed binary in `~/.cache/eca/eca-cli/`, which it installs on first run and updates to the latest GitHub release (checked at most hourly). Downloads are sha256-verified, checked to run, and swapped in atomically, with an `eca-version` marker. Offline, the installed binary is used with a notice. No `curl`/`unzip` needed anymore. Dropped the scavenging of nvim/emacs plugin binaries.
- `bb upgrade-eca` checks for and installs the latest release now; `--force` reinstalls.
- Connecting is asynchronous with no 10s `initialize` timeout (a config fetching a provider token can take longer): a "Connecting to ECA server…" spinner shows elapsed time, and points at the server log when it drags on.
- When the server dies, eca-cli shows its exit code and the last lines of its log, and enters a `:disconnected` mode (scroll + quit only) instead of crashing on the next write. Quitting while connecting/disconnected is immediate.
- `initialize` sends eca-cli's PID so the server exits on its own if eca-cli is killed.
- `--workspace` is canonicalized (relative paths no longer produce `file://.` URIs).
- `initialize` reports eca-cli's real version (`dev` when run from source) instead of a hardcoded `0.1.0`.
- Tests are no longer on the main classpath (`bb.edn` `:paths ["src"]`); the `test` task adds them. charm.clj's JVM-only dependencies are excluded, since Babashka embeds JLine and core.async.
- User messages sit behind a coloured bar instead of reverse video; consecutive tool calls and thoughts stack without blank lines.
- Pickers mark the selection in the accent colour, align descriptions in a muted column and show the match count; the MCP and jobs panels use coloured status dots instead of emoji; `/help` lists commands in an aligned column.
- Chat rendering is cached per item and rebuilt once per batch of server messages, so long chats stay smooth while a reply streams.
- eca-cli picks a new chat's id itself (as the protocol recommends) instead of learning it from the first prompt's response, so every message can be told apart by chat.
- Login retries continue the same chat instead of starting a new one.

## Phase 11 — Power Features *(partial)*

`@`-file context injection and the background-jobs panel shipped; `/rollback` + `/fork` remain (see `docs/roadmap.md` Phase 11).

### Added

- `@`-file context injection (#4): typing `@` at start-of-input or after a space opens a fuzzy file picker (`chat/queryFiles`); the selection is inserted inline as `@<path>` and sent as the `contexts` array on the next `chat/prompt`. Inline `@<token>` styled bold in the user-message renderer.
- `/jobs` background-jobs panel (#3): new `src/eca_cli/jobs.clj` — `:jobs` state slice, `jobs/updated` push handler, `[N jobs]` status-bar slot, on-demand `jobs/readOutput` popup, and a `d`→confirm→`jobs/kill` flow. Push-only refresh, no polling.

## Phase 7 — MCP Integration

### Added

- `/mcp` panel + status-bar slot + `tool/serverUpdated` handler (#2): new `src/eca_cli/mcp.clj` — MCP state slice, event-driven init (one `tool/serverUpdated` per configured server at startup), aggregate-health status-bar slot (`MCPs: 3/4 ⚠`), per-server status rows with tool count, and a `[connect]` affordance dispatching `mcp/connectServer` on `requires-auth` rows.

## Refactor — session/chat vocabulary alignment

Aligns eca-cli's vocabulary with upstream ECA (#7, closes #6). No behaviour change; transparent data migration. `bb test` 86 tests / `bb itest` 25 tests green.

### Changed

- ns `eca-cli.sessions` → `eca-cli.chats`; command `/sessions` → `/chats`; persistence file `eca-cli-sessions.edn` → `eca-cli-chats.edn` with a one-time legacy-fallback read so no resume history is lost; picker `open-session-picker` → `open-chat-picker`, `:kind :session` → `:chat`.
- **Deliberately unchanged:** run-scoped approval state (`:session-trusted-tools`), server payload keys (`:sessionTokens`/`:sessionCost`), and tmux's own `session` term — these already match upstream.

## Refactor Phase B — structural cleanup

Finishes the structural debt from the Phase A audit. 85/85 tests green at every commit.

### Added

- `README.md`, `LICENSE` (Apache 2.0), `CHANGELOG.md` at project root.
- `src/eca_cli/view/blocks.clj` — extracted per-item block renderers from `view.clj` (assistant text, user message, tool-call expanded/collapsed, thinking, hook, system) plus ANSI styling constants.
- `chat/handle-config-updated`, `chat/handle-chat-opened`, `chat/handle-chat-cleared` — extracted from `state.clj`'s `handle-eca-notification`.
- 4 shutdown-lifecycle deftests in `test/eca_cli/lifecycle_test.clj` covering protocol-level sequence order, cmd-quit composition, exception resilience, and Ctrl+C / `/quit` equivalence.
- 7 block-navigation deftests in `test/eca_cli/chat_test.clj`.
- 6 new Alt-prefixed keybindings: `Alt+↑` / `Alt+↓` (jump top-level blocks, skip sub-items), `Alt+g` / `Alt+G` (focus first / last block), `Alt+c` / `Alt+o` (collapse / expand all). Documented in README with terminal-compatibility note.
- New test files: `test/eca_cli/lifecycle_test.clj`, `test/eca_cli/chat_test.clj`, `test/eca_cli/view/blocks_test.clj`.

### Changed

- `state.clj`: 272 → 252 LOC. `handle-eca-notification` now delegates each chat-domain case in one line.
- `view.clj`: 265 → 149 LOC. Composer + chat-area + overlay renderers retained; block renderers moved out.
- `chat.clj`: 462 → 561 LOC. Absorbed three new notification handlers and six new key dispatch arms.

## Refactor Phase A — structural namespace split

Split monolithic `state.clj` (1121 LOC) into feature-scoped namespaces aligned with the ECA editor-plugin convention. No behavioural changes; 73/73 tests green at every commit.

### Added

- `chat.clj` — content handlers, tool-call lifecycle, `send-chat-prompt!`, focus helpers, key dispatch for `:ready` / `:chatting` / `:approving` modes, `chat/contentReceived` notification handler.
- `picker.clj` — picker overlay state + key dispatch for `:picking` mode (model / agent / session selection); `printable-char?` helper.
- `login.clj` — provider login cmd builders, `providers/updated` notification handler, key dispatch for `:login` mode.
- `view/rebuild-lines` — leaf utility called from every feature ns.
- Pre-refactor test hardening: 7 deftests covering the approval flow (`y`/`n`/`Y`), Enter-to-send prompt, `:window-size` resize, and Ctrl+C shutdown.
- `docs/refactor/00-assessment.md` — eca-cli-vs-ECA-editor-plugin checklist audit.
- `docs/refactor/01-phase-a-plan.md` — refactor plan with outcome section recording final LOC vs targets, mid-flight plan revisions, and final dep graph.
- `docs/refactor/02-phase-b-plan.md` — Phase B plan with audit-grounded detail.
- `docs/refactor/03-feature-gaps-roadmap-proposal.md` — maps user-visible feature gaps onto roadmap phases.

### Changed

- `state.clj` reduced from 1121 → 272 LOC; now a clean dispatcher (runtime events → global keys → per-mode delegation).
- `commands.clj` replaced 4-line stub with a full slash-command registry, `dispatch-command`, `open-command-picker`, and `cmd-*` handlers (~114 LOC).
- `sessions.clj` extended (29 → 56 LOC) to absorb chat-list/open/delete cmd builders.
- `view.clj` gained `rebuild-lines`.
- `docs/roadmap.md` renumbered: Tool-Call Diff Display promoted to Phase 6, MCP Integration to Phase 7, Markdown Rendering demoted to Phase 8 — ECA-required items now precede UX polish.

## Phase 5 — Rich Display

### Added

- Collapsible tool blocks in chat with status icons and expandable arguments / output.
- Thinking blocks: live "thinking…" indicator that becomes a collapsed "Thought" block once reasoning completes.
- Hook execution blocks: surface ECA hook lifecycle (running / ok / failed) with optional output panes.
- Sub-agent nesting: when a tool call spawns a sub-agent, that sub-agent's content renders nested under the spawn block.
- Focus model: `Tab` / `Shift+Tab` cycle through focusable items; `↑` / `↓` arrows navigate within focus; `Enter` toggles `:expanded?`; `Esc` clears focus.

### Fixed

- Sub-agent task prompts render as assistant text rather than user input.
- Status icons use ANSI-coloured 1-wide chars to avoid JLine layout artifacts on focus swap.

## Phase 4 — Command System

### Added

- Slash-command registry (`/model`, `/agent`, `/new`, `/sessions`, `/clear`, `/help`, `/quit`, `/login`).
- Interactive command picker triggered by typing `/` in an empty input.
- `commands.clj` namespace as the extensibility seam — adding a new command means adding one entry.

## Phase 3 — Session Continuity

### Added

- Session persistence (`~/.cache/eca/eca-cli-sessions.edn`) keyed by workspace path.
- `/new` — start a fresh chat (deletes the current chat-id from sessions).
- `/sessions` — browse and resume previous chats via picker.
- `chat/opened` and `chat/list` protocol handling for replay and switching.
- Workspace isolation: each workspace path keeps its own most-recent chat-id.
- ECA binary upgrade: `bb upgrade-eca` downloads and installs the pinned ECA binary.

## Phase 2.5 — UX Polish *(delivered between phases)*

### Added

- `alt-screen true` — fullscreen mode; native mouse selection via Shift+drag.
- DECSET 1000 mouse reporting — mouse-wheel scroll (3 lines per tick).
- Input history: `↑` / `↓` in `:ready` mode recalls previously sent messages.
- `PgUp` / `PgDn` — full-page scroll through chat history.

## Phase 2 — Model & Agent Identity

### Added

- Model and agent lists populated from ECA's `config/updated` notification.
- Ctrl+L and `/model` open a model picker; selection sends `chat/selectedModelChanged`.
- `/agent` picker; selection sends `chat/selectedAgentChanged`.
- Status bar shows current `model • agent` at all times.

### Fixed

- Effective-opts model override bug — explicit `--model` flag now wins over config defaults.

## Phase 1b — Login Hardening

### Added

- End-to-end provider login flow tested against real credentials.
- Login cancellation, re-trigger, and timeout handling.

## Phase 1a — Reliable Core

### Added

- Connection lifecycle (`initialize` / `initialized` / shutdown) with visible status.
- Streaming chat with the LLM.
- Tool-call approval flow (`y` / `n` / `Y` for approve / reject / approve-and-trust).
- Reader-error detection — ECA EOF surfaces as a system message rather than a silent hang.
- Echo suppression — ECA echoes user messages back; eca-cli consumes the echo via `:echo-pending` flag rather than rendering it twice.
- Charm.clj integration — the Elm-loop runtime driving the TUI.
- Initial test suite: protocol, view, state.

## MVP-0 — Initial scaffold

### Added

- JSON-RPC transport layer over stdin/stdout.
- TUI core (`core.clj`, `state.clj`, `view.clj`).
- ECA process spawn and reader thread.
- TUI mockups for all major UI states.
- `CLAUDE.md` with architecture and dev commands.
