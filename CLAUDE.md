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
bb nrepl                      # start nREPL for development
bb upgrade-eca                # download and install pinned ECA binary
```

In-app commands (type in input, press Enter):

```
/model      open model picker (Ctrl+L also works)
/agent      open agent picker
/new        start fresh chat (deletes current chat, clears UI)
/sessions   browse and resume previous chats
```

Requires Babashka 1.12.215+. No separate build step — `bb run` compiles and runs directly.

ECA server logs go to `~/.cache/eca/eca-cli.log`. Tail this when debugging.

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

### charm.clj Elm loop

`program/run` drives the TUI with three fns:
- `state/make-init` — returns a fn that spawns ECA, starts the reader thread, returns `[initial-state init-cmd]`
- `state/update-state` — pure dispatch: returns `[new-state cmd-or-nil]`
- `view/view` — pure render: returns a string from state

The drain loop is self-sustaining: `:eca-tick` handler always returns a new `drain-queue-cmd`, keeping the queue polled indefinitely. If the cmd fn returns nil, charm.clj drops it — so the tick message is always `{:type :eca-tick :msgs [...]}` even when the queue is empty.

### State modes

`:connecting` → `:ready` ↔ `:chatting` ↔ `:approving`

- `:connecting` — waiting for `initialize` response
- `:ready` — input focused, awaiting user message
- `:chatting` — LLM active, streaming; input blurred
- `:approving` — tool call waiting on `y`/`n`/`Y`

### Tool call lifecycle

`toolCallPrepare` (many, streaming args) → `toolCallRun` (approval decision point) → `toolCallRunning` → `toolCalled` or `toolCallRejected`

`manualApproval: true` on `toolCallRun` triggers `:approving` mode unless `:trust` is set or the tool name is in `:session-trusted-tools` (populated by `Y`).

### View rendering

No viewport component — `view.clj` uses manual line-slice: `:chat-lines` is a flat vec of rendered strings kept in state, rebuilt via `view/rebuild-chat-lines` after every content change. `render-chat` slices the last N visible lines adjusted by `:scroll-offset`.

### Key files

- `src/eca_cli/server.clj` — process spawn, Content-Length JSON-RPC framing, reader thread, queue drain
- `src/eca_cli/protocol.clj` — message constructors, request ID tracking, response correlation
- `src/eca_cli/state.clj` — Elm state machine, ECA content handlers, all key bindings
- `src/eca_cli/view.clj` — pure rendering: chat lines, tool icons, approval prompt, status bar
- `src/eca_cli/sessions.clj` — EDN persistence of workspace → chat-id map
- `src/eca_cli/upgrade.clj` — ECA binary download and version check

### ECA binary discovery order

1. `--eca` flag
2. `~/.cache/eca/eca-cli/eca` (eca-cli managed, installed via `bb upgrade-eca`)
3. `which eca` (PATH)
4. `~/.cache/nvim/eca/eca` (nvim plugin, Linux)
5. `~/Library/Caches/nvim/eca/eca` (nvim plugin, macOS)
6. `~/.emacs.d/eca/eca` (emacs plugin)

### Session persistence

Chat-ids are persisted to `~/.cache/eca/eca-cli-sessions.edn` keyed by workspace path. Each startup begins a fresh session — no auto-resume. Use `/sessions` to explicitly resume a previous chat. `/new` deletes the current chat and removes its entry from the sessions file.

### Protocol reference

ECA protocol spec: `../eca/docs/protocol.md`
