# eca-bb Design

## Vision

Terminal-first AI development driver. Not a chat window in a terminal — a development loop where the LLM has real agency (reads, edits, runs commands) and the human steers and approves. Inspired by how Claude Code drives development, adapted to the ECA ecosystem and protocol.

The key distinction: the LLM *pulls* context via ECA's built-in tools (filesystem, grep, git, shell) rather than the user pushing it via mentions. The user states a goal; eca-bb handles the loop.

## Trust Model

Safe by default. Approval required for tool calls unless trust is explicitly enabled.

Trust can be enabled via:
- `--trust` CLI flag at startup *(implemented)*
- `trust: true` in ECA config (persists across sessions) *(MVP-1)*

When trusted: tool calls execute without interruption. Status still shown — the user can see what ran.

## Visual Language

Inherits from existing ECA editor plugins for consistency across the ecosystem.

**Tool call states:**
- `⏳` preparing / in-progress
- `🚧` pending approval
- `✅` called successfully
- `❌` error

**Expandable tool blocks** *(MVP-1)*:
- `⏵` / `⏷` toggle open/close
- Auto-expand when approval needed
- Auto-collapse after completion

**Prompt prefix reflects state:**
- `> ` ready
- `⏳ ` LLM active
- `🚧 ` waiting for approval

**Status bar** (bottom): workspace, model, usage, trust indicator *(implemented)*; elapsed time *(MVP-1)*.

**Separator** between chat history and input: `---`

## UX Principles

**Show what's happening.** No silent long operations. Stream partial tool arguments during preparation so it never looks frozen.

**Keyboard-first.** No mouse dependency. Every action reachable from keys.

**Render budget.** Cap at ~20fps, differential redraw. Prevents flicker on slow terminals.

**Semantic checkpoints over granular approval.** Tool calls in a coherent sequence shouldn't each demand a keypress. Trust mode handles this; approval mode should feel minimal not exhausting.

**Hard stop always works.** Esc sends `chat/promptStop` regardless of state.

## Approval Flow

In approval mode (`🚧`):
- `y` — approve tool call
- `n` — reject tool call
- `Y` — approve and remember (auto-approve this tool for session)

## Layout (sketch)

```
┌─────────────────────────────────────────┐
│ chat history                            │
│                                         │
│  ⏵ read_file src/foo.clj       ✅       │
│  ⏵ grep "defn handler"         ✅       │
│  ⏵ write_file src/foo.clj      🚧       │
│    [y] approve  [Y] remember  [n] reject│
│                                         │
│ streamed LLM text appears here...       │
│                                         │
│ ---                                     │
│ > _                                     │
├─────────────────────────────────────────┤
│ workspace  model  00:42  1.2k tok  SAFE │
└─────────────────────────────────────────┘
```

## Out of Scope (MVP-0)

- Model / agent pickers
- Reasoning block display
- File context (`--file`, `/file`)
- Usage breakdown
- Inline diffs
- Multi-session

## References

- ECA protocol: `eca/docs/protocol.md`
- Plugin visual reference: `eca-emacs/eca-chat.el`, `eca-emacs/eca-chat-expandable.el`
- Implementation plan: `PLAN.md`
