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

### Startup

`core/parse-args` handles `--help`, `--version` and invalid options (unknown flags and stray arguments exit 1 with a hint) without touching the workspace or the server. Otherwise `core/-main` resolves the ECA binary *before* the TUI takes over the terminal (`upgrade/resolve-binary!`, which may download/update the managed binary with progress on stderr; fatal errors print and exit 1), sends the terminal's `enacs` sequence (`terminal/enable-alt-charset!`, see below), then starts charm.clj.

### charm.clj Elm loop

`program/run` drives the TUI with three fns:
- `state/make-init` — returns a fn that spawns ECA, starts the reader thread, returns `[initial-state (batch init-cmd drain-queue-cmd)]`. Must never throw: charm calls it outside its try/finally, so a spawn failure becomes a `:disconnected` state instead.
- `state/update-state` — pure dispatch: returns `[new-state cmd-or-nil]`
- `view/view` — pure render: returns a string from state

The drain loop starts at init and is self-sustaining: the `:eca-tick` handler returns a new `drain-queue-cmd` until the mode is `:disconnected`. If the cmd fn returns nil, charm.clj drops it — so the tick message is always `{:type :eca-tick :msgs [...]}` even when the queue is empty.

`initialize` is asynchronous: its response is `.put` on the queue as `:eca-initialize-response`; the handler sends `initialized` and switches to `:ready`. There is deliberately no timeout — the server answers only after loading the user's config, which may run commands (e.g. fetching a provider token). The view shows a "Connecting…" spinner with elapsed time meanwhile.

### State modes

`:connecting` → `:ready` ↔ `:chatting` ↔ `:approving`, and any of them → `:disconnected`

- `:connecting` — waiting for the `initialize` response
- `:ready` — input focused, awaiting user message
- `:chatting` — LLM active, streaming; input blurred
- `:approving` — tool call waiting on `y`/`n`/`Y`
- `:disconnected` — server exited (or failed to spawn / refused `initialize`). The reader thread queues a `:reader-error` whose `:error` is a ready-to-show report (exit code + tail of the server log, `server/exit-report`). Only scrolling and quitting work; quitting skips the protocol `shutdown` (also skipped in `:connecting`).

### Tool call lifecycle

`toolCallPrepare` (many, streaming args) → `toolCallRun` (approval decision point) → `toolCallRunning` → `toolCalled` or `toolCallRejected`

`manualApproval: true` on `toolCallRun` triggers `:approving` mode unless `:trust` is set or the tool name is in `:session-trusted-tools` (populated by `Y`).

### View rendering

No viewport component — `view.clj` uses manual line-slice: `:chat-lines` is a flat vec of rendered strings kept in state, rebuilt via `view/rebuild-chat-lines` after every content change. `render-chat` slices the last N visible lines adjusted by `:scroll-offset`.

### Key files

- `src/eca_cli/server.clj` — process spawn, Content-Length JSON-RPC framing (synchronized writes that return false instead of throwing once the server is gone), reader thread, exit report, queue drain
- `src/eca_cli/protocol.clj` — message constructors, request ID tracking, response correlation
- `src/eca_cli/state.clj` — Elm state machine, ECA content handlers, all key bindings
- `src/eca_cli/view.clj` — pure rendering: chat lines, tool icons, approval prompt, connecting indicator, status bar
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

Chat-ids are persisted to `~/.local/state/eca/eca-cli-chats.edn` (`$XDG_STATE_HOME`) keyed by workspace path. Each startup begins a fresh session — no auto-resume. Use `/chats` to explicitly resume a previous chat. `/new` deletes the current chat and removes its entry from the chats file.

### Protocol reference

ECA protocol spec: `../eca/docs/protocol.md`
