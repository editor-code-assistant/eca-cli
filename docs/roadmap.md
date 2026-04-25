# eca-bb Roadmap

A phased plan to make eca-bb a minimal, reliable, pi-like TUI client for ECA server.

Read [assessment.md](assessment.md) for the philosophical grounding behind this roadmap.

---

## Phase Overview

| Phase | Name | Focus | Detail |
|-------|------|-------|--------|
| [1a](#phase-1a-reliable-core) | Reliable Core | Everything works, nothing leaks | [detail](roadmap/phase-1a-reliable-core.md) |
| [1b](#phase-1b-login-hardening) | Login Hardening | End-to-end auth with real credentials | [detail](roadmap/phase-1b-login-hardening.md) |
| [2](#phase-2-model--agent-identity) | Model & Agent Identity | Know what you're running, change it | [detail](roadmap/phase-2-model-agent-identity.md) |
| [3](#phase-3-session-continuity) | Session Continuity | Quit and resume, start fresh | — |
| [4](#phase-4-command-system) | Command System | Slash commands as the extensibility seam | — |
| [5](#phase-5-message-steering) | Message Steering | Influence a running prompt | — |
| [6](#phase-6-rich-interaction) | Rich Interaction | Server-initiated Q&A | — |
| [7](#phase-7-power-features) | Power Features | Context injection, jobs, rollback/fork | — |

---

## Phase 1a: Reliable Core

**Goal:** Every part of the fundamental chat loop works correctly and visibly. ECA's full initialization lifecycle is handled. No credentials required.

See [roadmap/phase-1a-reliable-core.md](roadmap/phase-1a-reliable-core.md) for the full implementation plan, tests, and stopping criteria.

---

## Phase 1b: Login Hardening

**Goal:** The login flow works end-to-end against real providers. Requires working credentials.

See [roadmap/phase-1b-login-hardening.md](roadmap/phase-1b-login-hardening.md) for the full implementation plan, tests, and stopping criteria.

---

## Phase 2: Model & Agent Identity

**Goal:** The user knows exactly what model and agent they're running, and can change either without restarting.

### What to build

**Model list from `config/updated`.**  
Store the available models and agents received from ECA. These are the options for switching.

**Model picker (Ctrl+L / `/model`).**  
A simple inline selector showing available models. Selecting one sends `chat/selectedModelChanged` to ECA and updates the status bar. This mirrors pi's Ctrl+L.

**Agent picker (`/agent`).**  
Same pattern for agents. Selecting one updates state and the status bar.

**Status bar model/agent display.**  
The status bar should clearly show `model • agent` (or just model if no agent) so the user always knows their current context.

**`chat/selectedModelChanged` and `chat/selectedAgentChanged` notifications.**  
These are client notifications (no response expected) that inform ECA of the user's selection. The next `chat/prompt` will use the selected model/agent.

### Stopping criteria

- `config/updated` is handled and model/agent lists are stored in state
- Ctrl+L opens a model selector; selecting a model updates state and notifies ECA
- `/agent` opens an agent selector
- Status bar shows current model and agent at all times
- Model change takes effect on the next sent message

---

## Phase 3: Session Continuity

**Goal:** Quit and come back. Start fresh. Know which chat you're in.

### What to build

**Chat persistence.**  
When a chat is established (chat-id received), persist it to disk (e.g., `~/.cache/eca/eca-bb-session.json` keyed by workspace path). On next startup, offer to resume.

**`--resume` flag.**  
`bb run --resume` (or equivalent) picks up the last chat-id for the current workspace and sends it with the next `chat/prompt`, continuing the session. ECA server already maintains chat history server-side.

**`/new` command.**  
Start a fresh chat. Clears the chat-id, clears the local items list, sends `chat/clear` to ECA (to free server memory), and resets to `:ready`.

**`/sessions` command.**  
Calls `chat/list` on ECA and shows available chats in a simple selector. The user picks one, eca-bb sends `chat/open` and stores the new chat-id.

**Chat title in status bar.**  
Once sessions are named/listed, show the current chat title (or chat-id prefix) in the status bar.

### Stopping criteria

- Chat-id is persisted to disk after first exchange
- `bb run` in the same workspace resumes the previous session automatically (or prompts to)  
- `/new` starts a fresh chat, confirmed by a new chat-id on next send  
- `/sessions` shows a selectable list of previous chats and opens the chosen one
- Status bar shows current chat identity

---

## Phase 4: Command System

**Goal:** A slash command system that is the primary extensibility seam for eca-bb.

### What to build

**Command registry.**  
A simple map of command name → handler function. Commands are triggered by typing `/name` in the input and pressing Enter. The registry is populated at startup and is the single place to add new commands.

**Command autocomplete.**  
When the user types `/`, show a list of available commands (filtered as they type). This replaces the raw text input with a command picker temporarily, returning to normal input on selection or Escape.

**Built-in commands.**  

| Command | Action |
|---------|--------|
| `/model` | Open model picker (Phase 2) |
| `/agent` | Open agent picker (Phase 2) |
| `/new` | New chat (Phase 3) |
| `/sessions` | Session browser (Phase 3) |
| `/clear` | Clear chat display (local only, no ECA call) |
| `/help` | Show available commands |
| `/quit` | Exit cleanly |

**`/login` command.**  
Manually trigger the login flow for a provider (calls `providers/list` then login). Useful when tokens expire mid-session.

### Stopping criteria

- Typing `/` shows a filtered list of available commands
- All built-in commands from the table above are wired and functional
- `/help` lists all registered commands with descriptions
- Adding a new command requires only registering it in the command map — no other changes

---

## Phase 5: Message Steering

**Goal:** Send messages to influence a running prompt without stopping it — the eca-bb equivalent of pi's message queue.

### What to build

**Message queue.**  
While in `:chatting` mode, pressing Enter queues the typed message rather than discarding it. The queue is stored in state. A visual indicator (e.g. `[1 queued]` in the status bar) shows pending messages.

**Steering (`chat/promptSteer`).**  
The queued message is sent as a steer at the next tool-call boundary via `chat/promptSteer`. ECA injects it into the running LLM turn. If the prompt finishes before the steer is consumed, the message is sent as a regular `chat/prompt` instead.

**Escape behaviour update.**  
Escape in `:chatting` mode with no chat-id: return to `:ready` (current behaviour). Escape with a chat-id: stop the prompt via `chat/promptStop` and restore queued messages to the input, as pi does.

**Follow-up mode.**  
Alt+Enter queues a follow-up message, delivered only after the agent fully completes (i.e., sent as `chat/prompt` once `progress: finished` arrives).

### Stopping criteria

- Typing while the agent is working queues the message with a visual count indicator
- Queued messages are delivered as steers at the next tool boundary
- Escape with a running prompt stops it and restores queued messages to the input
- Alt+Enter queues a follow-up delivered after completion
- No message is ever silently lost

---

## Phase 6: Rich Interaction

**Goal:** Handle server-initiated dialogue — the model can ask the user questions directly.

### What to build

**`chat/askQuestion` handler.**  
ECA server can send a `chat/askQuestion` request with a question, optional predefined options, and an `allowFreeform` flag. eca-bb must:
1. Pause the chat display and show the question prominently  
2. If options are provided, show a numbered/lettered selector  
3. If `allowFreeform` is true, also allow typing a custom answer  
4. Send the response back via the JSON-RPC response (it's a request, not a notification)  
5. Resume the chat display

**`:asking` mode.**  
A new state mode, similar to `:approving`, that takes over the input area with the question UI. The text input handles freeform answers; digit/letter keys select predefined options; Escape sends a null/cancelled response.

**Question display in chat.**  
The question and the user's answer should appear in the chat history as a distinct content type (e.g., `:question` item), so the exchange is readable in context.

### Stopping criteria

- `chat/askQuestion` requests are handled without hanging the reader thread
- Options are presented and selectable by key
- Freeform input works when `allowFreeform` is true
- Escape sends a cancelled response (ECA handles gracefully)
- Question and answer appear in the chat history
- The ECA response to the question is sent within a reasonable timeout

---

## Phase 7: Power Features

**Goal:** Surface the remaining ECA capabilities that reward power users — context injection, background jobs, and session surgery.

### What to build

**Context injection (`@` file references).**  
Typing `@` in the input triggers a fuzzy file search (using `chat/queryFiles`) and inserts the selected file as a context in the `chat/prompt` call. This mirrors pi's `@` shortcut exactly. The context appears as a reference in the user message display.

**Background jobs panel.**  
ECA tracks long-running background jobs (dev servers, watchers, etc.) via `jobs/list` and `jobs/updated`. eca-bb should show a compact jobs indicator in the status bar (e.g., `[2 jobs]`) that the user can expand into a panel listing job names, statuses, and elapsed times. Killing a job (`jobs/kill`) should be possible from the panel.

**Chat rollback (`/rollback`).**  
A command that shows the chat history and lets the user pick a message to roll back to. Sends `chat/rollback` to ECA. ECA sends `chat/cleared` followed by the kept messages, which eca-bb re-renders. This is the eca-bb equivalent of pi's `/tree` for branching.

**Chat fork (`/fork`).**  
Fork the current chat at a selected message. Sends `chat/fork` to ECA, which creates a new chat. eca-bb receives `chat/opened` with the new chat-id and switches to it. The forked chat appears in `/sessions`.

**`config/updated` for MCP tools.**  
Handle `tool/serverUpdated` notifications to show MCP server status changes in the status bar (e.g., a tool server going from `starting` to `running`, or `requires-auth`).

### Stopping criteria

- `@` in the input opens a file picker; selected file is attached to the next prompt
- Status bar shows a job count when background jobs are active
- Jobs panel lists running jobs with elapsed time; kill works from the panel
- `/rollback` lets the user pick a message and rolls the chat back to that point
- `/fork` creates a new chat from the current one and switches to it
- MCP server status changes are visible in the status bar
