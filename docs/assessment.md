# eca-bb Assessment: Towards a Pi-like ECA Client

## What is Pi?

Pi is a minimal terminal coding harness. Its defining trait is not what it contains but what it deliberately leaves out. It ships with four tools (`read`, `write`, `edit`, `bash`), a session model, and a clean TUI — and then gets out of the way. Sub-agents, plan mode, permission popups, MCP, background tasks — none of these are baked in. They're extension points. The philosophy is: **a minimal, transparent core that you extend to fit your workflow, not the other way around**.

Pi's TUI reflects this. The footer shows the model, token usage, cost, and context window in real time. The editor is the centre of gravity. Commands are slash-based. Keybindings are customisable. Sessions are persisted as branching JSONL trees. Everything is observable and recoverable.

## What is ECA Server?

ECA server is a rich JSON-RPC agent backend. It handles the full complexity of LLM interaction: multi-model support, tool calling, MCP server management, context injection, sub-agents, background jobs, session persistence, rollback, forking, and provider auth. It exposes all of this over a clean protocol that any client can speak.

The key insight is that **ECA is to eca-bb what pi's agent framework is to pi's TUI**. The heavy lifting — tool execution, context management, sub-agents, streaming — lives in ECA server. The client's job is to be an excellent, focused UI layer.

## Where eca-bb Stands Today

eca-bb currently uses roughly 20% of the ECA protocol:

| Area | Protocol Coverage |
|------|------------------|
| Lifecycle (init/shutdown) | ✅ Full |
| Basic chat prompt/response | ✅ Partial (login status just added) |
| Tool approval | ✅ Full |
| Login/auth flow | ✅ Just implemented |
| Model/agent selection | ❌ None |
| Session management | ❌ None |
| Startup progress | ❌ None |
| Server messages | ❌ None |
| Message steering | ❌ None |
| Chat rollback/fork | ❌ None |
| Ask question | ❌ None |
| Context injection | ❌ None |
| Background jobs | ❌ None |
| Config updates | ❌ None |
| Usage/cost tracking | ⚠️ Stored but not displayed |

There are also known correctness gaps: the escape key was broken, login status was silently dropped, `Client closed connection.` bled into the TUI, and `pending-requests` were orphaned on every session restart.

## The Pi Alignment

Pi and eca-bb should share a philosophy, not a feature list. The principles that should guide eca-bb:

**Minimal core.** Every feature in eca-bb should earn its place. If ECA server handles it, eca-bb shouldn't duplicate it. The TUI's job is to surface what ECA exposes, cleanly.

**Transparent.** The user should always know: what model they're on, what it's costing, how much context remains, what's happening right now. Pi's footer is a model for this.

**Keyboard-driven.** Everything reachable by keyboard. No mouse dependency. Works in tmux alongside other tools.

**Composable.** eca-bb should work as one pane in a tmux layout, not demand full-screen ownership.

**Recoverable.** Sessions persist. You can quit and come back. You can roll back. You can steer a running prompt. Nothing is irreversibly lost.

**Extensible at the seams.** The slash command system is the natural extension point. Commands are the eca-bb equivalent of pi's extensions for anything that doesn't belong in the core.

## What eca-bb is Not

- It is not a general coding agent harness (that's pi)
- It is not responsible for tool execution or context management (that's ECA server)
- It does not need an extension system in the TypeScript sense — ECA's agent/MCP/context system already provides that layer
- It does not need sub-agents, plan mode, or MCP management UI (ECA handles these; eca-bb exposes their outputs)

## Summary

eca-bb's opportunity is to be the definitive TUI client for ECA server: minimal, reliable, transparent, and keyboard-driven. The roadmap that follows takes it there in phases, each one delivering a usable increment.
