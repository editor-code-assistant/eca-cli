# Feature Gaps → Roadmap Proposal

The Phase A assessment audited eca-bb against the ECA `development.md` editor-plugin checklist and surfaced gaps. Some are structural (Phase B). Others are user-visible features and belong on the [roadmap](../roadmap.md). This doc proposes how the feature gaps map to existing or new roadmap phases.

## Current roadmap state

Phases 1a–5 complete. Planned but not detailed:

| Phase | Name | Focus |
|---|---|---|
| 6 | Markdown Rendering | Render assistant text as formatted ANSI |
| 7 | Message Steering | Influence a running prompt |
| 8 | Rich Interaction | Server-initiated Q&A |
| 9 | Power Features | Context injection, jobs, rollback/fork |

## Feature gaps from Phase A assessment

| Gap | Source | Size | Recommended home |
|---|---|---|---|
| Tool-call diff rendering for file changes | development.md "Support tools details: showing a file change like a diff" | medium | **Phase 6** (ECA-required, promoted ahead of markdown) |
| MCP UI: status + details panel + server updates | development.md "Show MCPs summary", "Open MCP details window", "Receive MCP server updates" | large | **Phase 7** (ECA-required) |
| Markdown rendering for assistant text | general UX (not on ECA checklist) | medium | **Phase 8** (was 6 — demoted behind ECA requirements) |
| `chat/queryCommands` autocomplete | development.md "Support chat commands (`/`) auto completion, querying via `chat/queryCommands`" | small | Folded into **Phase 10** (Server-Driven Interaction) |
| In-app stderr / log viewer | development.md "Allow check eca server process stderr for debugging/logs" | small-medium | Folded into **Phase 10** (Server-Driven Interaction) |
| `chat/queryContext` add-context UI | development.md "Present and add contexts via `chat/queryContext` request" | medium | **Phase 11** (Power Features — context injection) |
| Block-navigation keybindings completion | development.md "keybindings: navigate through chat blocks/messages" | small | **Phase B (structural)** — bindings are polish on Phase 5, not a new feature |

## Proposed roadmap (post-Phase B)

```
Phase 1a: Reliable Core              ✅
Phase 1b: Login Hardening            ✅
Phase 2:  Model & Agent Identity     ✅
Phase 3:  Session Continuity         ✅
Phase 4:  Command System             ✅
Phase 5:  Rich Display               ✅
Phase 6:  Tool-Call Diff Display     NEW — ECA-required
Phase 7:  MCP Integration            NEW — ECA-required
Phase 8:  Markdown Rendering         (was 6 — demoted behind ECA requirements)
Phase 9:  Message Steering           (was 7)
Phase 10: Server-Driven Interaction  (was 8) — folds in chat/queryCommands + log viewer
Phase 11: Power Features             (was 9) — keeps chat/queryContext + jobs + rollback
```

ECA-required items occupy Phases 6 + 7. Markdown was originally Phase 6 but has been demoted because it's pure UX polish, not a checklist requirement. Phases 10 and 11 still bundle ECA-required items (queryCommands + log viewer in 10; queryContext in 11) with non-required items — splitting them further would fragment cohesive feature groupings, so they stay bundled.

## Per-phase notes

### Phase 6 / Tool-Call Diff Display

Extends Phase 5's expandable tool blocks. When a tool call modifies a file (`edit_file`, `write_file`, `apply_patch`), the expanded view should render a unified diff with ANSI red/green colouring. Server already provides before/after content via tool-call payloads.

Estimated effort: small-medium. One new renderer in `view/blocks.clj` (after Phase B's view split).

### Phase 7 / MCP Integration

Largest unplanned-feature item. Three sub-deliverables:

1. **Status indicator** — current MCPs (running / failed / pending) in the status bar.
2. **`/mcp` panel** — list MCPs with details (name, status, available tools, last error). New picker-like overlay.
3. **Server update notifications** — handle MCP status-change notifications from ECA and refresh the panel.

New ns: `mcp.clj` (status state, notification handler, `/mcp` command handler). Extends `view.clj` with an MCP panel renderer.

Estimated effort: medium-large. Aligns with ECA ecosystem expectations — sibling editors all have this.

### Phase 8 / Markdown Rendering

Pure UX polish (not on ECA checklist). Demoted behind the ECA-required phases. Scope unchanged from the original Phase 6 plan: markdown→ANSI converter for assistant + user text, library evaluation (commonmark-java vs flexmark-java vs purpose-built), `url` content type rendering as a side effect.

### Phase 10 (formerly 8) / Server-Driven Interaction

Fold in:
- **`chat/queryCommands`** — autocomplete pulls from server (extending Phase 4's local registry).
- **In-app stderr / log viewer** — new `/logs` panel reading the ECA stderr file.
- Existing scope: server-initiated Q&A (`chat/askQuestion`).

### Phase 11 (formerly 9) / Power Features

`chat/queryContext` add-context UI fits cleanly here alongside the already-planned context injection / jobs / rollback work.

## Recommendation

1. Land Phase B (structural) first — small, defensive, completes the refactor track.
2. Then resume roadmap track at Phase 6 (Tool-Call Diff Display) — first ECA-required item.
3. Continue through Phase 7 (MCP) before tackling the polish work in Phase 8 (Markdown).
4. Open a tracking issue mirroring development.md's checklist (per its instruction: *"Create an issue to help track the effort copying and pasting these check box to help track progress, [example](https://github.com/editor-code-assistant/eca/issues/5)"*) — gives ECA maintainers visibility into eca-bb's progress against the standard.

## Decisions

Open questions resolved:

**Renumber, no decimals.** 6/7/8/9 → 8/9/10/11. New Phase 6 (Tool-Call Diff Display) and new Phase 7 (MCP Integration) take the freed-up slots ahead of the existing roadmap items. All integer numbers — clean acceptance / stopping-criteria boundaries.

**ECA requirements promoted ahead of markdown.** The two unplanned ECA-checklist features (diff display, MCP) move ahead of markdown rendering. Markdown is UX polish, not a checklist requirement — it stays on the roadmap but at slot 8.

**MCP stays a single phase.** Status indicator alone is too thin to ship without the panel — users would see "something is broken" but couldn't drill in. Sibling editors (eca-emacs `eca-mcp.el` 341 LOC, eca-nvim integrated in sidebar) ship MCP as a unit. Following that pattern.

**Log viewer is not promoted.** The user's primary workflow is to tail the log file directly. The viewer's value is for contributors / users who don't know to do that — useful but not urgent. Stays in Phase 10 (Server-Driven Interaction).

These decisions are reflected in the updated [docs/roadmap.md](../roadmap.md).
