# Changelog

All notable changes to eca-bb. Format inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). No formal versioning yet — entries are grouped by roadmap phase and refactor phase.

## Unreleased

## Refactor Phase B — structural cleanup

Finishes the structural debt from the Phase A audit. 85/85 tests green at every commit.

### Added

- `README.md`, `LICENSE` (Apache 2.0), `CHANGELOG.md` at project root.
- `src/eca_bb/view/blocks.clj` — extracted per-item block renderers from `view.clj` (assistant text, user message, tool-call expanded/collapsed, thinking, hook, system) plus ANSI styling constants.
- `chat/handle-config-updated`, `chat/handle-chat-opened`, `chat/handle-chat-cleared` — extracted from `state.clj`'s `handle-eca-notification`.
- 4 shutdown-lifecycle deftests in `test/eca_bb/lifecycle_test.clj` covering protocol-level sequence order, cmd-quit composition, exception resilience, and Ctrl+C / `/quit` equivalence.
- 7 block-navigation deftests in `test/eca_bb/chat_test.clj`.
- 6 new Alt-prefixed keybindings: `Alt+↑` / `Alt+↓` (jump top-level blocks, skip sub-items), `Alt+g` / `Alt+G` (focus first / last block), `Alt+c` / `Alt+o` (collapse / expand all). Documented in README with terminal-compatibility note.
- New test files: `test/eca_bb/lifecycle_test.clj`, `test/eca_bb/chat_test.clj`, `test/eca_bb/view/blocks_test.clj`.

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
- `docs/refactor/00-assessment.md` — eca-bb-vs-ECA-editor-plugin checklist audit.
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

- Session persistence (`~/.cache/eca/eca-bb-sessions.edn`) keyed by workspace path.
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
- Echo suppression — ECA echoes user messages back; eca-bb consumes the echo via `:echo-pending` flag rather than rendering it twice.
- Charm.clj integration — the Elm-loop runtime driving the TUI.
- Initial test suite: protocol, view, state.

## MVP-0 — Initial scaffold

### Added

- JSON-RPC transport layer over stdin/stdout.
- TUI core (`core.clj`, `state.clj`, `view.clj`).
- ECA process spawn and reader thread.
- TUI mockups for all major UI states.
- `CLAUDE.md` with architecture and dev commands.
