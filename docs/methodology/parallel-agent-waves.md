# Parallel-Agent Waves

A reproducible methodology for delivering multiple roadmap tracks concurrently using a fleet of coding subagents.

Distilled from Wave 1 (2026-05-15) — 4 tracks shipped in parallel as PRs [#1](https://github.com/editor-code-assistant/eca-cli/pull/1), [#2](https://github.com/editor-code-assistant/eca-cli/pull/2), [#3](https://github.com/editor-code-assistant/eca-cli/pull/3), [#4](https://github.com/editor-code-assistant/eca-cli/pull/4): XDG paths, MCP integration, jobs panel, `@`-file references.

## When to use this

A wave is worth running when you have **3+ independent roadmap tracks** that:

- Touch overlapping but non-identical file sets (so true parallelism saves wall-clock time)
- Are well-scoped (you can write a 1-page brief without hand-waving)
- Don't have a sequential dependency (no "B builds on A")

If only 1–2 tracks fit, a single agent or direct implementation is cheaper. If tracks are sequential, parallelism just creates rebase pain.

## Stage 1 — Identify and lock cross-track decisions

Before any agent runs, the human picks tracks and freezes anything that crosses them.

In Wave 1 the cross-track surfaces were:

- **Status-bar slot order** — MCP and jobs each owned a new slot; their adjacency had to be specified up front
- **Picker model** — `:at-file`, `:mcp`, `:jobs` all share the existing `:picker` mode with a `:kind` discriminator. Only one open at a time.
- **Notification dispatch table** — all 3 picker tracks add an entry; agreed to insert alphabetically, no reorder of existing entries
- **`bb.edn` test list** — all 4 tracks add a test ns; conflict expected but trivial to resolve

Every brief quotes the locked decisions verbatim so an agent can't drift.

## Stage 2 — Set up worktrees

One git worktree per track. They share `.git` but have independent working trees and branches.

```bash
mkdir -p ../.worktrees
git worktree add ../.worktrees/eca-cli-<track-slug> -b <branch-name>
```

Naming convention used in Wave 1:

| Worktree | Branch |
|----------|--------|
| `eca-cli-xdg-paths` | `maint-xdg-paths` |
| `eca-cli-mcp` | `phase-7-mcp` |
| `eca-cli-jobs` | `phase-11b-jobs` |
| `eca-cli-at-files` | `phase-11a-at-refs` |

Worktrees go in `../.worktrees/`, kept out of the main repo path.

## Stage 3 — brepl hooks prerequisite

**Load-bearing.** Skipping this killed every agent in Wave 1's first attempt.

```bash
# In main repo:
brepl hooks install

# In every worktree:
for wt in ../.worktrees/eca-cli-*; do (cd "$wt" && brepl hooks install); done
```

What this fixes: without hooks, agents tried to start nREPL manually with `bb nrepl-server PORT &`. The `&` backgrounding in the Bash tool doesn't fully detach the process; the tool waits for all file descriptors to close, hangs forever, and the 600s no-progress watchdog kills the agent. With brepl hooks installed, agents call `brepl <<'EOF' ... EOF` and brepl manages the nREPL lifecycle itself.

Sanity check: `brepl ':ok'` should return `:ok` from any worktree.

## Stage 4 — Two-phase research

For each track, run **two** research passes before writing code:

### Phase A: own-code + protocol survey

A single Explore-class agent reads:

- The relevant protocol doc(s) for exact JSON shapes (cite line numbers)
- Existing namespaces that will be the canonical pattern reference (in eca-cli: `login.clj`, `picker.clj`, `sessions.clj`)
- Existing state shape and dispatch table
- Existing test conventions

Output: implementation-ready notes with file:line citations, state-shape additions, key bindings to add, and **a list of cross-track risks**.

### Phase B: ecosystem survey

A second Explore-class agent surveys how sibling clients (in eca-cli's case: eca-emacs, eca-nvim, eca-desktop, eca-web, eca-bb, eca-server) handle the same feature.

This is the part that's easy to skip and shouldn't be. Wave 1's first research pass missed emacs entirely. The ecosystem surveys then surfaced:

- emacs's status-emoji palette for MCP rows (reused verbatim)
- emacs's `d` + `y/n` kill confirm pattern for jobs (chosen over Enter-kill)
- emacs's inline `@filename` tokens for `@`-refs (revised the original "separate chip block" plan)
- The server's own `XDG_CACHE_HOME` resolver as canonical XDG pattern

Phase B agents should cite file:line in each surveyed repo and produce a **decision recommendation per open question**, plus steal-worthy UX touches.

## Stage 5 — Synthesise + lock the rest

Take the Phase A and Phase B reports. Resolve every open question. Lock:

- Protocol shapes (quoted JSON)
- File-level scope (in/out)
- Acceptance criteria (numbered, testable from REPL state)
- Test plan (which test cases live in which file)
- Cross-track conflict points
- Status-bar slot order, picker mode contract, etc.

If you can't lock a decision yet, do not start coding. Ambiguity at this stage propagates into every brief and shows up as either contradictory PRs or scope creep.

## Stage 6 — Write a brief per track

One `.agent-plan.md` per worktree. **Filename matters** — use `.agent-plan.md`, not `PLAN.md`, because most projects already have a `PLAN.md` at the root that propagates to worktrees and your Write will fail with "file not read yet".

Brief sections in order:

1. **Worktree + branch** — absolute paths, base commit, branch name
2. **Goal** — one paragraph
3. **Scope IN / OUT** — explicit. OUT is as important as IN.
4. **Locked decisions** — quoted verbatim from Stage 5
5. **Protocol shapes** — with `path:line` citations
6. **Files to create/modify** — table with `file:line` references from Phase A
7. **Acceptance criteria** — numbered, testable from REPL state (not requiring a TUI run)
8. **Tests** — required test cases, conventions to mirror (e.g. "follow `test/eca_cli/sessions_test.clj`"), `bb.edn` task-list update
9. **Conventions** — REPL workflow (with hard rule against `bb nrepl-server` manual start), code style, lint requirements
10. **Skill invocations** — mandatory list (see Stage 7)
11. **Anti-patterns** — explicit forbidden list (no scope creep, no new abstractions, no `git push`, no editing other worktrees)
12. **Reporting format** — exact shape of the report (AC checklist, test output tail, lint output, brepl smoke, commit hash, blockers)

## Stage 7 — Skill-invocation policy

Subagents inherit:

- User-level skills at `~/.claude/skills/`
- Project-level skills at `.claude/skills/` of the worktree

For Clojure coding agents, mandate **all** relevant skills. Conditional ones simply won't fire if their trigger never appears.

| Skill | When agent invokes |
|-------|--------------------|
| `clojure-craft` | Start of work — patterns, naming, data modelling |
| `brepl` | Before first brepl call — heredoc pattern, MANDATORY per skill description |
| `clj-debug` | On test failure or unexpected behaviour — REPL inspection, NOT println adds |
| `clj-discover` | First Java interop or unfamiliar macro — explore before guessing API |
| `clj-refactor` | After first-pass green, BEFORE commit — mechanism/policy review pass |
| `clj-replace` | Every structural code change (renames, body swaps) — over raw Edit |

Wave 1 mandated only `clojure-craft` and `brepl`. The others were "invoke when relevant"; only `clj-refactor` fired in 1 of 4 agents. Bumping the conditional ones to mandatory is the suggested default — dial back if the refactor passes start churning more than they help.

## Stage 8 — Spawn agents

One `general-purpose` agent per worktree, in parallel, all in the background:

```
Agent(
  description: "<phase> code",
  subagent_type: "general-purpose",
  run_in_background: true,
  prompt: """
You are the coding agent for the <track> track.

Worktree: /absolute/path/to/worktree
Branch: <branch>

CRITICAL: Read your full brief first at:
/absolute/path/to/worktree/.agent-plan.md

Follow the brief precisely. Do not deviate from scope.

KEY RULES:
- DO NOT run `bb nrepl-server` — backgrounding hangs the Bash tool.
- brepl hooks pre-installed. Use `brepl <<'EOF' ... EOF` directly.
- Invoke `brepl` skill BEFORE first brepl call.
- Fall back to `bb test` only if brepl misbehaves.

Project CLAUDE.md: /path/to/repo/CLAUDE.md
Global Clojure conventions: ~/.claude/CLAUDE.md

Skills (all mandatory per brief): clojure-craft, brepl, clj-debug,
clj-discover, clj-refactor, clj-replace.

Work entirely inside your worktree. Do NOT touch other worktrees or
the main repo.

When done, commit on branch (do NOT push), return the report format
specified in the brief. Document blockers precisely. No clarifying
questions; make reasonable calls and document them.
"""
)
```

The brief is the source of truth. The spawn prompt just points at it and reinforces the no-`bb nrepl-server` rule.

## Stage 9 — Per-agent verification

When an agent reports back, audit four things:

1. **`bb test` green** — quoted in the report
2. **AC checklist** — every numbered item marked ✓ with brief justification
3. **clj-kondo clean** — 0 new warnings on changed files (pre-existing warnings noted)
4. **brepl smoke** — at least one concrete REPL output showing the entry-point fn produces expected output for a sample input

If an agent missed an AC, ask for a follow-up before merging. If it skipped tests or invented behaviour, reject and re-spawn with a tightened brief.

## Stage 10 — Push, PR, merge

Lowest-overlap branch first to minimise rebase chains.

```bash
git push -u origin <branch>:<branch>
gh pr create --base main --head <branch> --title "..." --body "..."
```

PR body should mirror the brief's report: summary, decisions worth flagging, scope OUT, test plan checklist, brepl smoke.

Wave 1 merge order: `maint-xdg-paths` (no overlap) → `phase-7-mcp` (claims shared seams: state.clj, view.clj, commands.clj, protocol.clj, bb.edn) → `phase-11b-jobs` (rebase on MCP — overlaps on every file MCP touched plus `protocol.clj` for new senders) → `phase-11a-at-refs` (rebase last — touches state.clj, view.clj, protocol.clj plus `picker.clj`, `chat.clj`, `view/blocks.clj` not in MCP/jobs).

## Common failure modes

- **Watchdog kills agent mid-Bash-call** — root cause is almost always `bb nrepl-server &` hang from missing brepl hooks. Install hooks, re-spawn.
- **Agent stops early, asks a clarifying question** — brief was ambiguous OR didn't include "make reasonable calls and document them". Either tighten the spec or add the autonomy clause.
- **Agent invents an abstraction not in brief** — anti-patterns section was missing or weak. Add explicit "no new abstractions beyond AC".
- **Two agents conflict on the same line of `state.clj`** — cross-track lock wasn't tight enough. Specify line ranges or alphabetical insertion rules.
- **Agent skips tests** — make `bb test` 0-failures an explicit hard gate in the brief, separate from AC. Reject the report if absent.
- **`Unknown skill: brepl` errors at the start** — race between `brepl hooks install` and subagent skill registry refresh. Retry succeeds. Not a hard blocker; observed in all 4 Wave 1 agents and self-resolved within seconds.

## Worked example — Wave 1 timeline

| Step | Detail |
|------|--------|
| Identify tracks | Picked 4 from `docs/roadmap.md`: 7 MCP, 11a `@`-refs, 11b jobs, XDG maint |
| Worktrees | `git worktree add` × 4 (clean main, all branched from `613daae`) |
| Phase A research | 4 Explore agents in parallel (~3 min each) |
| Cross-track sync | Resolved status-bar slot order, picker `:kind` model |
| Phase B research | 2 more Explore agents (one per major feature area) surveyed emacs/nvim/desktop/web/bb/server |
| Open-question resolution | 4 toggle decisions resolved using ecosystem data (single-select, inline tag, output view in, `d`+confirm kill) |
| Brief drafting | 4 `.agent-plan.md` files, ~1500 words each, written to each worktree |
| brepl-hooks install | Forgotten in first attempt; all 4 agents stalled. Installed in main + 4 worktrees, re-spawned. |
| Coding agents | 4 `general-purpose` agents, parallel background |
| Reports + PRs | All 4 ACs ✓, all `bb test` green, 4 PRs pushed in lowest-overlap order |

Wall-clock from coding-agent spawn to last PR: roughly the duration of the slowest single track. Sequential implementation would have been ~4× that.
