# eca-cli Roadmap

A phased plan to make eca-cli a minimal, reliable, pi-like TUI client for ECA server.

Read [assessment.md](assessment.md) for the philosophical grounding behind this roadmap.

---

## Phase Overview

| Phase | Name | Focus | Status | Detail |
|-------|------|-------|--------|--------|
| [1a](#phase-1a-reliable-core) | Reliable Core | Everything works, nothing leaks | ✅ Complete | [detail](roadmap/phase-1a-reliable-core.md) |
| [1b](#phase-1b-login-hardening) | Login Hardening | End-to-end auth with real credentials | ✅ Complete | [detail](roadmap/phase-1b-login-hardening.md) |
| [2](#phase-2-model--agent-identity) | Model & Agent Identity | Know what you're running, change it | ✅ Complete | [detail](roadmap/phase-2-model-agent-identity.md) |
| [3](#phase-3-session-continuity) | Session Continuity | Quit and resume, start fresh | ✅ Complete | [detail](roadmap/phase-3-session-continuity.md) |
| [4](#phase-4-command-system) | Command System | Slash commands as the extensibility seam | ✅ Complete | [detail](roadmap/phase-4-command-system.md) |
| [5](#phase-5-rich-display) | Rich Display | Expandable tool blocks, thinking, sub-agent nesting | ✅ Complete | [detail](roadmap/phase-5-rich-display.md) |
| [6](#phase-6-tool-call-diff-display) | Tool-Call Diff Display | Render file-edit tool calls as unified diffs | ✅ Complete | [#25](https://github.com/editor-code-assistant/eca-cli/issues/25) |
| [7](#phase-7-mcp-integration) | MCP Integration | Status indicator, details panel, server update notifications | ✅ Complete | — |
| [8](#phase-8-markdown-rendering) | Markdown Rendering | md→ANSI; `url`/OSC-8; wide-table horizontal-scroll | ⬜ Unbuilt | — |
| [9](#phase-9-message-steering) | Message Steering | Influence a running prompt | ⬜ Unbuilt | — |
| [10](#phase-10-server-driven-interaction) | Server-Driven Interaction | `chat/askQuestion`, `chat/queryCommands` autocomplete, log viewer | ⬜ Unbuilt | — |
| [11](#phase-11-power-features) | Power Features | `@`-refs ✅ · jobs ✅ · rollback + fork remain | 🟡 Partial | — |
| [12](#phase-12-context-expansion) | Context Expansion | Full `chat/queryContext` picker (dirs/URLs/cursor/repoMap/MCP-resources); image-attach | 🆕 Planned | — |
| [13](#phase-13-chat-management) | Chat Management | Rename + per-chat trust, server-side clear, `chat/statusChanged`, history pagination | 🆕 Planned | — |
| [14](#phase-14-chat-navigation) | Chat Navigation | Flags/bookmarks, timeline jump, export transcript | 🆕 Planned | — |
| [15](#phase-15-task-panel) | Task Panel | `TaskDetails` — checklist, N/M progress, priority, `blockedBy` graph | 🆕 Planned | — |
| [16](#phase-16-granular-approval) | Granular Approval | Per-shell-command approve & remember (`save:'session'`, `approvalKey`) | 🆕 Planned | — |
| [17](#phase-17-mcp-write-management) | MCP Write Management | MCP CRUD + start/stop/enable/disable/logout, OAuth (L) | 🆕 Planned | — |
| [18](#phase-18-providers--config) | Providers & Config | Providers panel (auth, logout, cost badges); in-app global-config editor | 🆕 Planned | — |
| [19](#phase-19-variant-picker) | Variant Picker | Interactive model/agent variant selector | 🆕 Planned | — |
| [20](#phase-20-context-breakdown) | Context Breakdown | `usage.contextBreakdown` segmented bar + `autoCompactPercentage` threshold | 🆕 Planned | — |
| [21](#phase-21-multi-session--remote) | Multi-Session & Remote | Multi-workspace/concurrent servers; remote ECA host connect (L) | 🆕 Planned | — |

**Status legend:** ✅ Complete · 🟡 Partial · 📋 Spec'd (issue open) · ⬜ Unbuilt · 🆕 Planned (numbered, not yet detailed).

**Phase numbering is frozen identity** — shipped phases are marked done in place, never renumbered; all new work is appended as 12+. This is a stable-identity roadmap, not a reordered one. The frozen reconciliation (drift corrections, scope cuts, phase 12–21 assignment) was decided in [#24](https://github.com/editor-code-assistant/eca-cli/issues/24) — a closed decision record. This file is the live ordered plan; #24 is not edited.

## Execution order

Separate axis from the frozen numbering above. Principle: **ECA-checklist-required first** → **broad-value** → **L-sized speculative phases to the tail**, with one named value-exception. Re-weigh here (low churn) as priorities shift — do not touch #24.

- **Next:** `6 Diff` → `8 Markdown` *(value-exception — highest daily-visibility wart, promoted above required items)* → `12 Context Expansion`
- **Near:** `9 Steering` → `10 Server-Driven` → `11 Rollback/Fork`
- **Later:** `15 Task Panel` · `16 Granular Approval` · `20 Context Breakdown` · `19 Variant` · `13 Chat Mgmt` · `14 Chat Nav` · `18 Providers & Config`
- **Deferred (L):** `17 MCP Write Management` · `21 Multi-Session & Remote`

### Scope cuts — editor-client-only

Out of scope for a standalone TUI client (need a host buffer/cursor/LSP feed eca-cli lacks — see the `standalone TUI client` vs `editor client` distinction in `CONTEXT.md`): `rewrite/prompt` (in-buffer rewrite), `completion/inline` (ghost text), `editor/getDiagnostics` (client-supplied diagnostics), inline image **render** (kitty/iterm/sixel — GUI-bound). Repeat/resend prompt is covered by ↑/↓ input-history recall.

## Outstanding maintenance

Tracked items that aren't tied to a phase but should be addressed at some point.

### Production-ready distribution via [bbin](https://github.com/babashka/bbin)

When eca-cli is ready for end-users (not just developers cloning the repo), it should be installable via `bbin install` so the standard install path is one command, not "clone, install Babashka, run `bb upgrade-eca`, run `bb run`". Action items:

1. **Audit `bb.edn`** for bbin compatibility. The `:tasks` map needs at least a default task with stable CLI args so `bbin install` can wire `eca-cli` as a script-on-PATH. Reference: [bbin docs on script installation](https://github.com/babashka/bbin?tab=readme-ov-file#script-installation).
2. **Tighten config + cache paths to follow XDG conventions.** Currently `~/.cache/eca/eca-cli-chats.edn`, `~/.cache/eca/eca-cli.log`, and `~/.cache/eca/eca-cli/eca` (the upgrade-eca-managed binary) are hardcoded under `~/.cache/eca/`. For bbin distribution we should:
   - Respect `XDG_CACHE_HOME` (cache files) and `XDG_CONFIG_HOME` (any future config) when set, falling back to `~/.cache` and `~/.config`.
   - Centralise path resolution in one helper (probably `sessions.clj` or a new `paths.clj`) so future additions don't drift.
3. **Tighten logging.** ECA server stderr currently goes to `~/.cache/eca/eca-cli.log`. For installed users we should:
   - Document the log path in `--help` output and in startup banners ("Logs: $XDG_CACHE_HOME/eca-cli/server.log").
   - Add log rotation or size-cap (currently the log grows unbounded).
   - Ensure the log directory is created on first run rather than failing silently.
4. **Document install via bbin** in README — assuming successful audit, the canonical install becomes `bbin install io.github.editor-code-assistant/eca-cli`.

Trigger: post Phase 7 (MCP) — **now reached** (Phase 7 shipped); the feature surface is stable enough to start treating eca-cli as something end-users would install, and the Phase 10 in-app log viewer is also a good moment to revisit log paths.

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

## Phase 2.5: UX Polish *(delivered between phases)*

**Goal:** Smooth everyday interaction — not a planned phase, delivered incrementally.

- `alt-screen true` — fullscreen mode; native mouse selection (Shift+drag) works out of the box
- Mouse wheel scroll — DECSET 1000 mouse reporting lets the app own scroll; 3 lines per tick
- Input history — Up/Down in `:ready` recalls previously sent messages
- PgUp/PgDn — full-page scroll through chat history
- Integration tests for all of the above (Phase 3 tests in `integration_test.clj`)

---

## Phase 3: Session Continuity

**Goal:** Quit and come back. Start fresh. Know which chat you're in.

See [roadmap/phase-3-session-continuity.md](roadmap/phase-3-session-continuity.md) for the full implementation plan, tests, and stopping criteria.

### What to build

**Own ECA binary (`bb upgrade-eca`).**  
Download and manage a pinned ECA binary at `~/.cache/eca/eca-cli/eca`, independent of editor plugins. Discovery order updated to prefer this over nvim/emacs locations. Startup version check warns if the running binary doesn't match the pinned version.

**Chat-id persistence.**  
After first exchange, write chat-id to `~/.cache/eca/eca-cli-chats.edn` keyed by workspace path. On restart, read it and resume silently — no prompt, no flag.

**`chat/opened` and `chat/cleared` handlers.**  
`chat/opened` is the canonical notification when a chat is created or replayed — store chat-id and title. `chat/cleared` signals the server wants us to wipe local items (used before replay and after `/new`).

**`/new` command.**  
Clears the chat-id, deletes the chat on the server, wipes local items, removes from disk. Next message starts a fresh session.

**`/chats` command.**  
Calls `chat/list`, shows a picker of available chats. Selecting one sends `chat/open` — server replays the chat via `chat/cleared` → `chat/opened` → `chat/contentReceived`.

**Chat title in status bar.**  
Show the current chat title (truncated) from `chat/opened`.

### Stopping criteria

- Chat-id persisted; restart in same workspace resumes automatically
- `chat/opened` stores chat-id and title; `chat/cleared` wipes local items
- `/new` starts a fresh chat (old messages gone, new chat-id on next send)
- `/chats` shows selectable list of previous chats and opens chosen one
- Status bar shows current chat title when available

---

## Phase 4: Command System

**Goal:** A slash command system that is the primary extensibility seam for eca-cli.

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
| `/chats` | Chat browser (Phase 3) |
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

## Phase 5: Rich Display

**Goal:** Replace the flat text-only chat display with structured, interactive blocks — collapsible tool calls, thinking blocks, and nested sub-agent content — matching the UX fidelity of nvim's ECA buffer.

See [roadmap/phase-5-rich-display.md](roadmap/phase-5-rich-display.md) for the full implementation plan, tests, and stopping criteria.

### What to build

**Extended item model.** Tool-call items gain `:args-text`, `:out-text`, `:expanded?`, and `:sub-items`. A new `:thinking` item type stores model reasoning blocks.

**Collapsible rendering.** `render-item-lines` dispatches collapsed (1-line) vs. expanded (args + output block) per item type. Collapsed by default. `eca__spawn_agent` shows `▸ N steps` when it has nested sub-agent content.

**Thinking blocks.** ECA's `thinking` content type is captured and displayed as `▸ Thought` (collapsed) / `▾ Thought + text` (expanded). Currently ignored.

**Sub-agent nesting.** Replaces the interim `parentChatId` suppression. Sub-agent `contentReceived` is routed to the parent `eca__spawn_agent` tool call's `:sub-items` via the `subagentChatId` protocol link, and rendered indented under it when expanded.

**Tab focus + Enter toggle.** Tab/Shift+Tab navigates between focusable items (tool-call, thinking). Enter/Space toggles `:expanded?`. Escape clears focus. Scroll adjusts to keep the focused item visible.

### Stopping criteria

- Collapsed tool-call renders 1 line; expanded shows args + output block
- Thinking content creates a `:thinking` item; expands to show model reasoning
- Sub-agent content appears nested under `eca__spawn_agent`, not suppressed or in main flow
- Tab focuses next tool-call/thinking item; Enter toggles; Escape clears focus
- Scroll adjusts to keep focused item visible

---

## Phase 6: Tool-Call Diff Display

**Goal:** When a tool call modifies a file, the expanded view should render a unified diff so the user can see exactly what changed before approving or after the call completes.

**Full spec: [#25](https://github.com/editor-code-assistant/eca-cli/issues/25).** The summary below is corrected against it — the ticket is authoritative.

### What to build

**The server computes the diff — eca-cli colourises, does not compute.** ECA ships a pre-computed unified diff in the tool-call `fileChange` detail (`{type, path, diff, linesAdded, linesRemoved}`), populated at `toolCallRun` (approval) through `toolCalled`. eca-cli must *not* extract before/after, run an LCS, or match tool names. Detection is by **payload presence** (`details.type == "fileChange"`), not tool name — automatically covering `edit_file`/`write_file`/`apply_patch`/future tools.

**Diff colouriser leaf.** A pure `render-diff` in `blocks.clj` colours the server's diff string by leading char: `-` red, `+` green, `@@` dim, context grey. Reads on light + dark terminals. >500 rendered lines truncate with `[truncated]`. Collapsed one-liner shows `+N −M`.

**Two mount points, one renderer.** Pre-work F01 unifies the approval surface onto `render-item-lines` (force-expanded) + the `[y/n/Y]` line; F02 classifies the `fileChange` detail into an `:edit` key at ingest. The diff then appears in both the approval prompt and the completed block for free. Non-edit tool calls fall back to the existing arguments/output renderer.

### Stopping criteria

- File-edit tool calls render as a unified diff in the expanded block
- Diff colours work in light and dark terminals
- Approval mode shows the diff before the y/n/Y prompt
- Non-edit tool calls fall back to the existing output renderer
- Large diffs (>500 lines) truncate with a "[truncated]" footer

---

## Phase 7: MCP Integration

**Goal:** First-class MCP server support — users see which MCPs are running, can drill into details when one fails, and react to server status changes without restarting.

### What to build

**Status indicator.**
The status bar shows MCP health at a glance: `MCPs: 3/4 ✓` or `MCPs: 2/4 ⚠` if one has failed. Hidden when no MCPs are configured.

**`/mcp` panel.**
A new picker-style overlay listing each configured MCP server: name, status (`running` / `failed` / `pending` / `requires-auth`), exposed tools count, last error message if failed. Selecting an entry expands its details. Escape closes the panel.

**`tool/serverUpdated` notification handler.**
ECA emits status changes for MCP servers. The handler updates the in-memory MCP map, refreshes the status bar, and updates the panel if open.

**`mcp.clj` namespace.**
New ns for MCP state, notification handler, and `/mcp` command handler. Pattern matches `login.clj` / `picker.clj`.

### Stopping criteria

- Status bar shows MCP count and aggregate health when MCPs are configured
- `/mcp` opens a panel listing servers with status, tool count, and any error
- `tool/serverUpdated` notifications update the panel and status bar live
- Failed MCPs surface their error message in the panel
- `requires-auth` MCPs surface a hint to run the relevant login flow
- Panel keybindings: arrow nav, Enter to expand, Escape to close

---

## Phase 8: Markdown Rendering

**Goal:** Render assistant and user text through a markdown→ANSI converter so that bold, italic, code spans, fenced code blocks, headers, lists, and tables display as formatted output rather than raw syntax.

### What to build

**Markdown→ANSI converter.**
A lightweight pass over text items before they are split into lines. Outputs ANSI escape sequences for bold (`\e[1m`), italic (`\e[3m`), dim (code spans), and resets. Fenced code blocks get a visual border and a language label. Headers are bold. Lists indent correctly. Tables render with column alignment.

**Library evaluation.**
Babashka can load Java libraries via `:mvn/version`. Candidates: `commonmark-java` (CommonMark spec-compliant, extensible) and `flexmark-java` (fast, configurable). Evaluate for binary size, Babashka compatibility, and ANSI output support. If neither fits, a purpose-built single-pass tokenizer covering the 80% case (bold, italic, code, headers, lists) is the fallback.

**Scope.**
Applies to `:assistant-text` and `:user` items. Tool output (`:out-text` in tool-call items) and thinking text are rendered plain — they are code/prose from tools, not model-authored markdown.

**`url` content type.**
With markdown rendering in place, `url` items (`{:type "url" :title "..." :url "..."}`) are a natural fit here: render as `title (url)` inline, or as an OSC 8 hyperlink where the terminal supports it.

### Stopping criteria

- Assistant text with `**bold**` renders with ANSI bold, not literal asterisks
- Fenced code blocks display with a border and language label
- Lists indent correctly; headers are visually distinct
- `url` content items render as linked text in chat
- Plain-text fallback if the markdown library is unavailable at runtime

---

## Phase 9: Message Steering

**Goal:** Send messages to influence a running prompt without stopping it — the eca-cli equivalent of pi's message queue.

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

## Phase 10: Server-Driven Interaction

**Goal:** Handle server-initiated dialogue and server-side metadata that wasn't covered earlier — `chat/askQuestion`, server-supplied slash commands via `chat/queryCommands`, and an in-app log viewer for users who don't tail files directly.

### What to build

**`chat/askQuestion` handler.**  
ECA server can send a `chat/askQuestion` request with a question, optional predefined options, and an `allowFreeform` flag. eca-cli must:
1. Pause the chat display and show the question prominently  
2. If options are provided, show a numbered/lettered selector  
3. If `allowFreeform` is true, also allow typing a custom answer  
4. Send the response back via the JSON-RPC response (it's a request, not a notification)  
5. Resume the chat display

**`:asking` mode.**  
A new state mode, similar to `:approving`, that takes over the input area with the question UI. The text input handles freeform answers; digit/letter keys select predefined options; Escape sends a null/cancelled response.

**Question display in chat.**  
The question and the user's answer should appear in the chat history as a distinct content type (e.g., `:question` item), so the exchange is readable in context.

**`chat/queryCommands` server-side autocomplete.**  
Phase 4 built a local registry. ECA also exposes server-side commands via `chat/queryCommands` (e.g. agent-defined slash commands). When the user types `/` and the input picker opens, query the server in addition to the local registry and merge results.

**In-app log viewer (`/logs`).**  
A panel that reads `~/.cache/eca/eca-cli.log` and shows the tail with auto-scroll, primarily for users / contributors who don't know to tail the file directly. Read-only. Keyboard scrolling, Escape to close. Useful when something goes wrong and the user can't be expected to know the path.

### Stopping criteria

- `chat/askQuestion` requests are handled without hanging the reader thread
- Options are presented and selectable by key
- Freeform input works when `allowFreeform` is true
- Escape sends a cancelled response (ECA handles gracefully)
- Question and answer appear in the chat history
- `/`-autocomplete includes both local and server-side commands, deduplicated
- `/logs` panel shows recent log lines and scrolls

---

## Phase 11: Power Features

**Goal:** Surface the remaining ECA capabilities that reward power users — context injection, background jobs, and session surgery.

**Status: 🟡 Partial.** `@`-file context injection and the jobs panel have shipped to `main`; `/rollback` and `/fork` remain.

### What to build

**Context injection (`@` file references). ✅ Shipped.**  
Typing `@` in the input triggers a fuzzy file search (using `chat/queryFiles`) and inserts the selected file as a context in the `chat/prompt` call. This mirrors pi's `@` shortcut exactly. The context appears as a reference in the user message display.

**Background jobs panel. ✅ Shipped.**  
ECA tracks long-running background jobs (dev servers, watchers, etc.) via `jobs/list` and `jobs/updated`. eca-cli shows a compact jobs indicator in the status bar (e.g., `[2 jobs]`) that the user can expand into a panel listing job names, statuses, and elapsed times. Killing a job (`jobs/kill`) is possible from the panel.

**Chat rollback (`/rollback`). ⬜ Remaining.**  
A command that shows the chat history and lets the user pick a message to roll back to. Sends `chat/rollback` to ECA. ECA sends `chat/cleared` followed by the kept messages, which eca-cli re-renders. This is the eca-cli equivalent of pi's `/tree` for branching.

**Chat fork (`/fork`). ⬜ Remaining.**  
Fork the current chat at a selected message. Sends `chat/fork` to ECA, which creates a new chat. eca-cli receives `chat/opened` with the new chat-id and switches to it. The forked chat appears in `/chats`.

---

## Planned phases (12–21)

Numbered identities frozen in [#24](https://github.com/editor-code-assistant/eca-cli/issues/24), not yet spec'd into detailed tickets. One-liners below; each gets a full "what to build" + stopping criteria when it's spec'd into an issue. See [Execution order](#execution-order) for scheduling.

## Phase 12: Context Expansion

**Goal:** Full `chat/queryContext` picker — directories, URLs, cursor, repoMap, MCP resources — beyond the file-only `queryFiles` shipped in Phase 11. Plus image-**attach** (`ImageContext`).

## Phase 13: Chat Management

**Goal:** Rename + per-chat trust (`chat/update`); server-side clear (`chat/clear`); `chat/statusChanged`; history pagination (`chat/history` cursors, "load older").

## Phase 14: Chat Navigation

**Goal:** Flags/bookmarks (`chat/addFlag`/`removeFlag`); timeline (jump to message/flag); export transcript.

## Phase 15: Task Panel

**Goal:** `TaskDetails` — checklist, N/M progress, priority, `blockedBy` dependency graph.

## Phase 16: Granular Approval

**Goal:** Per-shell-command approve & remember (`save:'session'`, `approvalKey`), beyond the current tool-name-only approval.

## Phase 17: MCP Write Management

**Goal (L):** MCP CRUD + start/stop/enable/disable/logout, OAuth beyond connect, `tool/serverRemoved`. Phase 7 delivered read-only MCP (part 1).

## Phase 18: Providers & Config

**Goal:** Providers panel (auth status, `providers/logout`, model + cost badges); in-app global-config JSONC editor.

## Phase 19: Variant Picker

**Goal:** Interactive model/agent **variant** selector (baseline today: status-bar display only).

## Phase 20: Context Breakdown

**Goal:** `usage.contextBreakdown` segmented bar + `autoCompactPercentage` threshold + legend (baseline today: bare context-%).

## Phase 21: Multi-Session & Remote

**Goal (L):** Multi-workspace / concurrent servers, cross-session list, workspace switcher; remote ECA host/port connect + discovery.

### Stopping criteria

- `@` in the input opens a file picker; selected file is attached to the next prompt
- Status bar shows a job count when background jobs are active
- Jobs panel lists running jobs with elapsed time; kill works from the panel
- `/rollback` lets the user pick a message and rolls the chat back to that point
- `/fork` creates a new chat from the current one and switches to it
