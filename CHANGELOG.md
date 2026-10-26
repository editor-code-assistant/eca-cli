# Changelog

All notable changes to eca-cli. Format inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Every push to `main` is released as `0.1.<commit count>` (see [releases](https://github.com/editor-code-assistant/eca-cli/releases)); entries here are grouped by roadmap phase and refactor phase.

## Unreleased

Plug-and-play pass: eca-cli ships as a single binary, installs and updates its own ECA server, and works on NixOS and inside tmux.

### Added

- Self-contained `eca-cli` binaries for Linux and macOS (x86_64 and arm64), so no Babashka, JVM or clone is needed: every push to `main` is tested, built, smoke-tested on each platform and published as a GitHub release, along with `eca-cli.jar` for any platform with Babashka.
- `install` script: `bash <(curl -fsSL https://raw.githubusercontent.com/editor-code-assistant/eca-cli/main/install)` installs or updates the binary (in `~/.local/bin` by default), verifying its checksum and that it runs before replacing the old one.
- `--version` and `--help`. Unknown options and stray arguments are reported instead of being silently ignored.
- `bb dist` and `bb release-version` build the release artifacts locally, the same way CI does.

### Fixed

- Dividers and boxes rendered as `qqqq…` / `xxxx` inside tmux/screen: JLine draws box glyphs through the terminal's alternate charset, which with `screen-256color`/`tmux-256color` terminfo (SO/SI) requires `enacs` first. eca-cli now sends the terminal's `enacs` on startup (`terminal.clj`).
- "Error: Stream closed" on NixOS/musl: `bb upgrade-eca` installed the glibc-linked build, which can't run there. Linux x86_64 now uses the static build, like the editor plugins.
- `bb` printed a warning about the `run` task overriding a built-in on every invocation.

### Changed

- ECA server management matches the editor plugins: no pinned version. On startup eca-cli resolves `--eca` → `eca` on PATH → a managed binary in `~/.cache/eca/eca-cli/`, which it installs on first run and updates to the latest GitHub release (checked at most hourly). Downloads are sha256-verified, checked to run, and swapped in atomically, with an `eca-version` marker. Offline, the installed binary is used with a notice. No `curl`/`unzip` needed anymore. Dropped the scavenging of nvim/emacs plugin binaries.
- `bb upgrade-eca` checks for and installs the latest release now; `--force` reinstalls.
- Connecting is asynchronous with no 10s `initialize` timeout (a config fetching a provider token can take longer): a "Connecting to ECA server…" spinner shows elapsed time, and points at the server log when it drags on.
- When the server dies, eca-cli shows its exit code and the last lines of its log, and enters a `:disconnected` mode (scroll + quit only) instead of crashing on the next write. Quitting while connecting/disconnected is immediate.
- `initialize` sends eca-cli's PID so the server exits on its own if eca-cli is killed.
- `--workspace` is canonicalized (relative paths no longer produce `file://.` URIs).
- `initialize` reports eca-cli's real version (`dev` when run from source) instead of a hardcoded `0.1.0`.
- Tests are no longer on the main classpath (`bb.edn` `:paths ["src"]`); the `test` task adds them. charm.clj's JVM-only dependencies are excluded, since Babashka embeds JLine and core.async.

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
