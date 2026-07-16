# eca-cli

Domain glossary for eca-cli, a Babashka TUI client for the ECA (Editor Code Assistant) server. Vocabulary aligns with upstream `eca` (server) and `eca-emacs` — those repos are the source of truth for shared terms.

## Language

**Chat**:
One conversation with the LLM, identified by a `chatId`. The unit the ECA server persists, lists (`chat/list`), opens, and deletes. In eca-cli this is what the past-conversation browser shows.
_Avoid_: Session (for a conversation)

**Session**:
One ECA server connection / run scope — the lifetime of a single eca-cli invocation and its ECA process. Owns run-scoped state such as remembered tool approvals. Matches ECA's `save: 'session'` approval scope and eca-emacs's `session` connection object.
_Avoid_: Chat (for a connection), Run

**Workspace**:
The root directory eca-cli operates on (default: cwd). The key under which a chat is remembered across runs.
