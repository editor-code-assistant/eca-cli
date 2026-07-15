# Align eca-cli vocabulary with upstream ECA

eca-cli originally used "session" for two unrelated things: the list of past conversations (`/sessions`, `sessions.clj`, `eca-cli-sessions.edn`) and run-scoped tool approvals (`:session-trusted-tools`). Upstream `eca` (server) and `eca-emacs` are consistent: **chat** = one conversation (`chatId`, `chat/list`, `chat/delete`), **session** = one ECA server connection / run scope (approval `save: 'session'`, eca-emacs's `session` connection object). We adopt the upstream split — rename the past-conversation seam onto "chat" (`/chats`, `chats.clj`, `open-chat-picker`, `eca-cli-chats.edn`) and keep `:session-trusted-tools` as correctly run-scoped.

This is why `:session-trusted-tools` (session) sits next to `/chats` (chat) rather than sharing a word: they are genuinely different concepts in the ECA model. Cutover is hard (no external users yet); the on-disk file migrates old `eca-cli-sessions.edn` once via fallback read.
