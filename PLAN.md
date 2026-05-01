# eca-cli

Babashka TUI client for ECA (Editor Code Assistant).

## What

Terminal-first AI coding assistant that talks the ECA protocol over stdin/stdout. Uses charm.clj for the TUI. Unlike editor plugins that push context via @mentions, the LLM pulls context via ECA's built-in tools (filesystem, grep, git, shell).

## Status

**MVP-0 complete and shipped.** Full chat loop working: spawn → initialize → stream → approve → shutdown. Code review pass done. Live on `main`.

## Architecture

```
reader thread → LinkedBlockingQueue → program/cmd (drain batch) → update → view
```

- `server.clj` — spawn ECA process, JSON-RPC Content-Length framing, reader thread + queue
- `protocol.clj` — message constructors, request ID tracking, response correlation
- `state.clj` — Elm state machine (init/update), mode dispatch
- `view.clj` — pure rendering: chat lines, tools, approval, status bar
- `core.clj` — entry point, arg parsing, charm.clj program/run

## MVP-0 (complete)

- Spawn + initialize + shutdown
- Single chat with streaming text
- Tool calls with approval (y/n/Y)
- Trust mode (`--trust` flag, safe by default)
- Prompt stop (Esc)
- Graceful shutdown + terminal cleanup on crash
- ECA stderr → `~/.cache/eca/eca-cli.log` (no terminal bleed)
- Manual line-slice scroll (up/down/k/j)

## MVP-1 (follow-up)

- Model/agent pickers
- Reasoning blocks
- Trust from config file
- File context (`--file`, `/file`)
- Usage display
- Collapsible tool blocks
- Word-wrap / scroll accuracy for long lines

## Running

```bash
# Requires bb 1.12.215+
bb run

# Dev REPL
bb nrepl-server
```

## Key Protocol Messages

```
initialize (req)     → {processId, clientInfo, capabilities, workspaceFolders}
initialized (notif)  → {}
chat/prompt (req)    → {chatId?, message, model?, agent?} → {chatId, model, status}
chat/promptStop (notif) → {chatId}
chat/toolCallApprove (notif) → {chatId, toolCallId}
chat/toolCallReject (notif)  → {chatId, toolCallId}
shutdown (req) → null
exit (notif) → {}
```

Content types: `text`, `progress`, `usage`, `toolCallPrepare`, `toolCallRun`, `toolCallRunning`, `toolCalled`, `toolCallRejected`, `reasonStarted`, `reasonText`, `reasonFinished`.
