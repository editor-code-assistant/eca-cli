# Changelog

All notable changes to eca-cli. Format inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Every push to `main` is released as `0.1.<commit count>` (see [releases](https://github.com/editor-code-assistant/eca-cli/releases)); entries here are grouped by roadmap phase and refactor phase.

## Unreleased

Plug-and-play pass: eca-cli ships as a single binary, installs and updates its own ECA server, and works on NixOS and inside tmux.

UX pass: chats render as markdown, the status line becomes a colourful modeline in the spirit of eca-emacs, and the whole UI takes on the terminal's colour theme.

Catch-up pass: eca-cli gains the features of the ECA editor clients it was missing (eca-emacs as reference), and fixes the places where it didn't match the server.

### Added

- Shell calls waiting for approval show the command and, when it helps, each chained command coloured by what `Y` would do with it: already remembered, would be remembered, or always asks. `Y` names the commands it remembers and is hidden when there's nothing to remember.
- `/trust` toggles trust mode for the current chat, right away (`chat/update`); the indicator follows the server's `selectTrust` (e.g. `chat.defaultTrust`, a resumed chat's trust).
- The server's commands work (Phase 10, partial): `/compact`, `/init`, `/context` (the context breakdown with its per-category legend, completing Phase 20), `/costs`, `/quota`, `/fork`, `/btw`, `/resume`, `/export`, `/doctor`, … plus MCP prompts, custom prompts and skills. The `/` picker lists them (`chat/queryCommands`) next to eca-cli's own, tagged by kind, best name matches first; picking one that takes arguments puts it in the input, with its signature shown above. Typed with arguments, a command both have (`/model x/y`) goes to the server.
- A chat the server forks off the current one (`/fork`, `/btw`) is shown and remembered; other chats' messages stay out of view.

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

- Sub-agents: their content landed in the main chat (and their `finished` ended the main turn) because eca-cli looked for a `subagentDetails` field and an `eca__spawn_agent` name the server never sends. Spawn calls are now recognised by their `subagent` details.
- A sub-agent tool call that needed approval hung the chat with nothing to approve; it now asks, answered with the sub-agent's chat id.
- Tool calls asking for approval in parallel replaced each other, leaving all but the last waiting forever; they now queue and are asked in turn, and calls answered or stopped elsewhere drop out.
- `Y` trusted the whole tool on the client: approving one `ls` auto-ran every later shell command. It now asks the server to remember the approval for the chat (`save: "session"`), which for shell and git remembers only the approved commands.
- Trust mode only auto-approved on the client: it was never sent to the server, didn't cover sub-agents, and overrode hooks that ask on purpose. It's now sent with each prompt and decided by the server. With `chat.defaultTrust` set, eca-cli showed SAFE while the server auto-approved everything.
- A task tool configured to ask hung the chat (eca#584).
- Any `/…` that wasn't one of eca-cli's commands was rejected as unknown, server commands included; text merely starting with a path (`/home/me/x is empty`) too.
- After resuming a chat, the next message landed above the chat's last reply, and the new reply was glued onto it.
- `@`-file contexts stuck to every later message instead of only the one that mentioned them.
- Tests wrote to the real chats file; they now use a temp one.
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
