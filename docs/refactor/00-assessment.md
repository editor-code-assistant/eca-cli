# eca-cli Refactor Assessment

Date: 2026-05-01
Source: ECA `docs/development.md` (https://github.com/editor-code-assistant/eca/blob/master/docs/development.md)
Goal: Align eca-cli with ECA editor-plugin conventions and improve LLM-maintainability.

## Sibling editor patterns

One file per feature concern is the consistent rule across the ecosystem.

- **eca-emacs**: `eca-chat.el`, `eca-mcp.el`, `eca-process.el`, `eca-api.el`, `eca-diff.el`, `eca-completion.el`, `eca-config.el`, `eca-jobs.el`, `eca-providers.el`, `eca-util.el`, `eca-rewrite.el`, `eca-editor.el`, `eca-settings.el`, `eca-table.el`.
- **eca-desktop**: `server.ts`, `protocol.ts`, `rpc.ts`, `chat-state.ts`, `session-store.ts`, `session-manager.ts`, `log-store.ts`.
- **eca-nvim**: ~20 test files, one per feature.
- **ECA core**: layered `src/eca/{server,handlers,messenger,db,llm_api,llm_providers/,features/{chat,mcp,tools/}}`.

eca-cli deviates: `src/eca_cli/state.clj` is 1121 LOC and folds chat handlers, key bindings, MCP-adjacent state, sessions UI, approval, and reasoning into a single namespace.

## Checklist coverage

Items copied verbatim from `development.md` "Supporting a new editor" checklist.

| Item | eca-cli status | Notes |
|---|---|---|
| Auto-download server | done | `upgrade.clj` + cache discovery |
| User-specify server path/args | done | `--eca` flag |
| Start/stop server from editor | partial | start only (init), no in-app stop/restart |
| Show server status | done | `:connecting`/`:ready` in status bar |
| stdin/stdout JSONRPC | done | `server.clj` |
| stderr access for debug/logs | missing | logs at `~/.cache/eca/eca-cli.log`, no in-app viewer |
| `initialize` / `initialized` | done | |
| `exit` / `shutdown` | verify | confirm in `protocol.clj` |
| Open chat window | done | |
| Send via `chat/prompt` | done | |
| Clear / reset chat | partial | `/new` deletes; no separate reset |
| `chat/contentReceived` | done | `state.clj:372` |
| Agents / models pickers | done | `/model`, `/agent` |
| `chat/queryContext` add contexts | missing | |
| Tool approve / reject | done | y / n / Y |
| Tool details (diff view) | missing | no diff render for file changes |
| Reason content blocks | done | `state.clj:301` |
| MCP summary (running / failed / pending) | missing | no MCP UI |
| `/`-command autocomplete via `chat/queryCommands` | missing | `commands.clj` is 4-line stub |
| Usage costs / tokens | done | status bar |
| Keybindings: navigate blocks, clear chat | partial | scroll yes, block-jump unclear |
| MCP details window | missing | |
| MCP server update notifications | missing | |
| Basic plugin docs | partial | `CLAUDE.md` + `DESIGN.md`, no `README` / `CHANGELOG` / `LICENSE` |

## Gaps for LLM-maintainability

1. **`state.clj` 1121 LOC monolith.** Largest hit. LLM edits to one feature risk touching unrelated logic. Target split: `chat.clj` (content handlers), `update.clj` or `keys.clj` (mode dispatch / keymap), `mcp.clj` (new), `commands.clj` (real impl), `approval.clj`, `picker.clj` (model / agent / sessions overlays).
2. **No `mcp.clj`.** MCP protocol notifications dropped on floor; checklist requires UI.
3. **No real `commands.clj`.** 4-line stub; needs `chat/queryCommands` integration + autocomplete.
4. **No `context.clj`.** `chat/queryContext` missing entirely.
5. **No `diff.clj`.** Tool-call file changes not rendered as diff. Hard in TUI but unified-diff text is feasible.
6. **No `log.clj` / stderr panel.** Checklist wants in-editor stderr access. TUI could expose via `/logs` or toggle panel.
7. **No `README` / `CHANGELOG` / `LICENSE`** at project root. Explicit checklist item.
8. **`bb.edn` test list hardcoded.** Adding a test namespace requires bb.edn edit. Sibling editors auto-discover.

## Proposed phases

### Phase A — Structural refactor (no feature change)

A1. Split `state.clj` by feature concern. `state.clj` retains state shape + init + top-level dispatch only.
A2. Add empty namespaces matching emacs / desktop layout: `chat.clj`, `mcp.clj`, `commands.clj`, `context.clj`, `diff.clj`, `log.clj`.
A3. Add `README.md`, `LICENSE`, `CHANGELOG.md`.

### Phase B — Checklist gaps

B1. MCP UI — `/mcp` panel + status-bar indicator.
B2. `chat/queryCommands` autocomplete in input.
B3. `chat/queryContext` add-context UI.
B4. Tool-call diff rendering for file edits.
B5. In-app stderr / log viewer.
B6. Block-navigation keybindings.

### Phase C — Ecosystem alignment

C1. Track `development.md` checklist as repo issue per template ([example](https://github.com/editor-code-assistant/eca/issues/5)).
C2. Mirror integration-test naming convention from `bb integration-test`.

## Source data

- ECA dev doc: `https://raw.githubusercontent.com/editor-code-assistant/eca/master/docs/development.md`
- Sibling repo paths surveyed:
  - `/home/sam/workspace/sbs/eca-project/eca-emacs/`
  - `/home/sam/workspace/sbs/eca-project/eca-nvim/`
  - `/home/sam/workspace/sbs/eca-project/eca-desktop/`
  - `/home/sam/workspace/sbs/eca-project/eca-webview/`
  - `/home/sam/workspace/sbs/eca-project/eca/`

## Current eca-cli file inventory

```
src/eca_cli/commands.clj       4 LOC
src/eca_cli/core.clj          40 LOC
src/eca_cli/protocol.clj     138 LOC
src/eca_cli/server.clj       161 LOC
src/eca_cli/sessions.clj      29 LOC
src/eca_cli/state.clj       1121 LOC   <- monolith
src/eca_cli/upgrade.clj       60 LOC
src/eca_cli/view.clj         257 LOC
src/eca_cli/wrap.clj          87 LOC
total                      1897 LOC
```
