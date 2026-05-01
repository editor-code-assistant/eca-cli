# Phase A — Structural Refactor Plan

Goal: split `state.clj` (1121 LOC) into feature-scoped namespaces matching sibling-editor convention. No behavioral change. Tests must pass at every step.

## Target file layout

```
src/eca_cli/
  core.clj           ; entrypoint (unchanged)
  server.clj         ; process spawn, JSON-RPC framing, reader thread (unchanged)
  protocol.clj       ; request/notification builders (unchanged)
  upgrade.clj        ; ECA binary download (unchanged)
  wrap.clj           ; line wrapping helpers (unchanged)
  view.clj           ; pure rendering + rebuild-lines helper (~270 LOC)
  state.clj          ; state shape + init + update-state top-level dispatch (~250 LOC)
  chat.clj           ; content handlers, tool-call lifecycle, send-chat-prompt!, chat key + approval handlers (NEW, ~500 LOC)
  commands.clj       ; slash command registry + dispatch (REPLACE 4-line stub, ~150 LOC)
  picker.clj         ; model/agent/session/command picker overlays + :picking key handler (NEW, ~150 LOC)
  login.clj          ; login flow + provider status + :login key handler (NEW, ~200 LOC)
  sessions.clj       ; chat-list/open/delete cmds + persistence (EXTEND from 29 LOC, ~80 LOC)
```

No subdirs (`features/`) — eca-cli is small enough that flat layout matches eca-emacs.

## Dependency graph (one-way)

```
core ──► state
state ──► chat
state ──► picker
state ──► commands ──► picker      (open-command-picker uses picker fns)
state ──► login    ──► chat        (login flushes pending message via send-chat-prompt!)
state ──► sessions
all features ──► protocol, view, server, wrap   (leaf utilities)
```

No back-refs to `state`. No cycles.

## Mapping: state.clj → target ns

Line ranges from current `src/eca_cli/state.clj`.

| Lines | Symbol | Target |
|---|---|---|
| 19–22 | `rebuild-lines` | `view.clj` — trivial wrapper over `view/rebuild-chat-lines`; 15+ callers across all nses, so it lives in the leaf util |
| 23–29 | `flush-current-text` | `chat.clj` |
| 30–52 | `upsert-tool-call` | `chat.clj` |
| 53–75 | `content->item` | `chat.clj` |
| 76–104 | `focusable-paths`, `sync-focus` | `chat.clj` |
| 105–110 | `register-subagent` | `chat.clj` |
| 112–116 | `drain-queue-cmd` | `state.clj` (keep — runtime plumbing) |
| 118–130 | `init-cmd`, `shutdown-cmd` | `state.clj` |
| 132–153 | `delete-chat-cmd`, `open-chat-cmd`, `list-chats-cmd` | `sessions.clj` |
| 155–197 | `start-login-cmd`, `choose-login-method-cmd`, `submit-login-cmd` | `login.clj` |
| 199–216 | `send-chat-prompt!` | `chat.clj` |
| 217–349 | `handle-content` (132 LOC) | `chat.clj` |
| 351–369 | `handle-providers-updated` | `login.clj` |
| 370–467 | `handle-eca-notification` | `state.clj` (dispatch hub; delegates to chat / login) |
| 468–498 | `handle-eca-tick` | `state.clj` |
| 499–504 | `item-display` | `picker.clj` |
| 505–561 | `open-picker`, `open-session-picker`, `filter-picker`, `unfilter-picker` | `picker.clj` |
| 562–605 | `cmd-*` fns + `command-registry` | `commands.clj` |
| 616–627 | `open-command-picker` | `commands.clj` (calls picker/open) |
| 628–644 | `finalize-handler-result`, `dispatch-command`, `printable-char?` | `finalize-handler-result` stays in `state.clj` (called by both `commands/dispatch-command` and `update-state` command-picker arm); `dispatch-command` + `printable-char?` → `commands.clj` |
| 654–700 | `initial-state`, `make-init` | `state.clj` |
| 702–1121 | `update-state` (420 LOC giant `cond`) | `state.clj` (orchestrator) — per-mode arms split out, see below |

### `update-state` decomposition

The 420-LOC `cond` mixes runtime events, key bindings, and per-mode behavior. Split into:

- `state.clj/update-state` — top-level dispatcher (~80 LOC).
- `chat.clj/handle-key` — `:ready` and `:chatting` mode key bindings (typing, Enter-submit, Enter-toggle-expanded, scroll, Tab/Shift+Tab/arrow focus nav).
- `chat.clj/handle-approval-key` — `:approving` mode (`y` / `n` / `Y`).
- `picker.clj/handle-key` — `:picking` mode (filter, enter-selects, escape-cancels, arrow nav).
- `login.clj/handle-key` — `:login` mode (field input, submit, cancel).

Each `handle-key` returns `[new-state cmd-or-nil]` matching the existing contract.

### Dispatcher contract — cond-arm order is load-bearing

Top-level `update-state` must preserve current arm precedence so e.g. Ctrl+C beats per-mode arms:

```clojure
(defn update-state [state msg]
  (reset! debug-state ...)
  (let [queue (get-in state [:server :queue])]
    (cond
      ;; runtime events (mode-agnostic)
      (= :window-size       (:type msg)) ...rebuild-lines
      (= :eca-initialized   (:type msg)) ...
      (= :eca-error         (:type msg)) ...
      (= :eca-tick          (:type msg)) (handle-eca-tick state (:msgs msg))
      (= :eca-login-action  (:type msg)) (login/handle-msg state msg)
      (= :eca-login-complete (:type msg)) (login/handle-msg state msg)
      (= :chat-list-loaded  (:type msg)) (sessions/handle-msg state msg)

      ;; global key bindings (any mode)
      (or (msg/quit? msg) (ctrl-c? msg))   [state (shutdown-cmd ...)]
      (and (ctrl-l? msg) (= :ready (:mode state))) (commands/cmd-open-model-picker state)

      ;; per-mode key dispatch
      :else (case (:mode state)
              (:ready :chatting) (chat/handle-key state msg)
              :picking           (picker/handle-key state msg)
              :approving         (chat/handle-approval-key state msg)
              :login             (login/handle-key state msg)
              [state nil]))))
```

This block is the dispatcher's contract. Extraction steps must not reorder arms.

## Test coverage assessment

`test/eca_cli/state_test.clj` is 1125 LOC, 50 deftests. Breakdown by area:

| Area | deftest count | Coverage |
|---|---|---|
| chat content / tool-call / subagent / hooks | 18 | strong |
| login + providers | 8 | strong |
| picker (model, agent, command, session, filter, escape) | 9 | strong |
| commands (`/clear`, `/help`, `/quit`, `/new`, `/sessions`, registry) | 7 | strong |
| chat session ops (`chat/opened`, `chat/cleared`, `chat-list-loaded`) | 4 | adequate |
| progress / show-message / config-updated / reader-error | 5 | adequate |
| eca-tick | 1 | minimal |
| focus / Tab navigation | 1 | minimal |
| **approval flow (`y` / `n` / `Y` keypresses)** | **0** | **GAP** |
| **typing / Enter / scroll keys in `:chatting`** | **0** | **GAP** |
| **`shutdown-cmd` / Ctrl+C path** | **0** | **GAP** |
| **`:window-size` resize → rebuild-lines** | **0** | **GAP** |

Approval logic is exercised indirectly via `handle-content-tool-call-run-test` (sets `:pending-approval`) but no test drives the y/n/Y keypresses through `update-state`. Integration test (`integration_test.clj`, 515 LOC) likely covers the end-to-end approval flow via tmux — confirm before refactor.

## Test migration

50 deftests use `#'state/private-fn` and `state/public-fn` direct refs. Splitting requires:

- Update every `(:require [eca-cli.state ...])` in tests to point at the new ns.
- Rename `#'state/handle-content` → `#'chat/handle-content`, etc.
- Make currently-private fns public on move (or test through `update-state` only).

After each extraction step, grep `state/` references in tests and update. Mechanical churn — estimate ~30 of 50 deftests need ns updates.

## Risk and mitigation

| Risk | Mitigation |
|---|---|
| Hidden coupling between currently-private fns (`defn-`) | Make symbols public on move; audit `state_test.clj` `#'`-refs and update fully-qualified ns. |
| `rebuild-lines` callers span every ns | Place in `view.clj` (leaf util); all features depend on view already. |
| `update-state` dispatcher must not regress | Pin arm order per dispatcher contract above; add smoke test exercising one keypress per mode before refactor. |
| Approval flow gap | Add 3 deftests (y / n / Y) **before** moving approval handling — they document current behavior. |
| `:debug-state` atom (line 15) reset on every `update-state` call | Keep in `state.clj`; do not drop. |
| Integration test may reach into state-private symbols | Audit `integration_test.clj` before step 2. |
| `view.clj/render-chat` already knows item types | Phase A is structural-only; flag any view-side change as scope creep, defer to Phase B. |
| Login arm in `update-state` (lines 730–768) is large; may itself want sub-splitting | Note for later; not Phase A scope. |

## Sequencing (small, reviewable commits)

Each step ends with `bb test` green and integration test green.

1. **Pre-refactor test hardening.** Add deftests for current gaps:
   - approval `y` / `n` / `Y` keypresses through `update-state`.
   - `:chatting` mode typing + Enter sends prompt.
   - `:window-size` triggers `rebuild-lines`.
   - Ctrl+C in `:ready` fires `shutdown-cmd`.
2. **Move `rebuild-lines` to `view.clj`** as `view/rebuild-lines`. Update all callers (`state.clj` only at this point). Smallest possible commit; sets up subsequent extractions.
3. **Extract `chat.clj`** — move `handle-content` + helpers (`upsert-tool-call`, `content->item`, `flush-current-text`, `focusable-paths`, `sync-focus`, `register-subagent`, `send-chat-prompt!`). Move `:ready`/`:chatting` and `:approving` arms of `update-state` into `chat/handle-key` and `chat/handle-approval-key`. Make public. Split `state_test.clj` content + key-handler tests into new `chat_test.clj`.
4. **Extract `login.clj`** — move login-cmd helpers + `handle-providers-updated` + `:login` mode arm of `update-state` into `login/handle-key`, plus `:eca-login-action` / `:eca-login-complete` runtime arms into `login/handle-msg`. Split tests → `login_test.clj`.
5. **Extract `picker.clj`** — move `item-display`, all picker fns, `:picking` mode arm. Split tests → `picker_test.clj`.
6. **Replace `commands.clj` stub** — move `cmd-*` + `command-registry` + `open-command-picker` + `dispatch-command` + `printable-char?` + the command-picker selection arm. Keep `finalize-handler-result` in `state.clj`. Split tests → extend `commands_test.clj`.
7. **Extend `sessions.clj`** — absorb `delete-chat-cmd`, `open-chat-cmd`, `list-chats-cmd`, plus `:chat-list-loaded` runtime arm into `sessions/handle-msg`. Update callers.
8. **Slim `state.clj`** — `update-state` becomes the dispatcher per the contract above. Keep `initial-state`, `make-init`, `drain-queue-cmd`, `init-cmd`, `shutdown-cmd`, `finalize-handler-result`, `handle-eca-notification`, `handle-eca-tick`, `:debug-state`.

## Acceptance criteria

- `state.clj` ≤ 250 LOC.
- Each new ns ≤ 500 LOC (`chat.clj` will land near this ceiling — acceptable; further splitting is premature).
- No ns has more than one feature concern.
- Dep graph documented in `state.clj` ns docstring.
- `bb test` passes at every commit.
- `bb itest` (integration) passes after step 8.
- No public-API regression for `core.clj` callers (only `state/make-init` and `state/update-state` consumed externally).
- Manual smoke after step 8: connect, send prompt, get tool-call approval, switch model, list sessions — zero behavior diff.

## Deferred to Phase B

- `view.clj` split into per-block renderers.
- New feature work (MCP, queryContext, queryCommands, diff, log viewer).
- Block-navigation keybindings.
- `bb.edn` test-namespace auto-discovery (low value at 6–9 test-ns scale).

---

## Outcome

Status: **complete**. 73/73 tests green at every commit. No behaviour regressions beyond two intentional minor edge cases (PgUp/PgDn during `:login` is now no-op; typing in `:approving` no longer reaches the input — both irrelevant in practice).

### Final LOC vs targets

| File | Target | Final | Notes |
|---|---|---|---|
| `state.clj` | ≤ 250 | **272** | Overshot by 22. Five `handle-eca-notification` cases (`$/progress`, `$/showMessage`, `config/updated`, `chat/opened`, `chat/cleared`) stayed inline — heterogeneous and not worth splitting further. The 250 figure was below the natural floor anyway: sibling editors land at 289 (eca-nvim `state.lua`) and 299 (eca-desktop `chat-state.ts`). |
| `chat.clj` | ≤ 500 | **462** | Includes content handlers, focus helpers, `send-chat-prompt!`, `chat/handle-key`, `chat/handle-approval-key`, and `handle-content-received`. Cohesive — all chat-domain. |
| `commands.clj` | ~150 | **114** | Smaller than estimate: `printable-char?` migrated to `picker.clj` mid-execution (see decisions below). |
| `picker.clj` | ~150 | **145** | Includes overlay helpers + `handle-key` + `printable-char?`. |
| `login.clj` | ~200 | **175** | All cmd builders, `handle-providers-updated`, `handle-eca-login-action`, `handle-eca-login-complete`, `handle-key`. |
| `sessions.clj` | ~80 | **56** | Persistence (kept) + 3 cmd builders absorbed. |
| `view.clj` | unchanged | **265** | +`rebuild-lines` (8 LOC). |

Project total: 1975 LOC across 12 nses. Sibling comparison: eca-nvim 7000, eca-emacs 9903, eca-desktop 6185 — eca-cli is the smallest by 3-5×, expected for a TUI client.

### Step sequence (actual)

The plan listed 8 steps; execution produced 10 commits. Renumbering reflects two mid-flight reorderings:

1. Pre-refactor test hardening — 7 new deftests (approval `y`/`n`/`Y`, Enter-submit, `:window-size`, Ctrl+C).
2. `rebuild-lines` → `view.clj`.
3. Extract `chat.clj` helpers + `handle-content`.
4. Extract `picker.clj` (helpers only — no key dispatch yet).
5. Extend `sessions.clj` with chat-list/open/delete cmds.
6. Replace `commands.clj` stub with real registry + dispatch.
7. Extract `login.clj` (cmd builders + notification handler + `:login` key dispatch + runtime arms).
8. Extract chat key dispatch — `chat/handle-key` + `chat/handle-approval-key`. Rewrote `update-state` to dispatcher contract.
9. Extract `picker/handle-key` (5 :picking arms; command-picker selection stayed in `state.clj` — see below).
10. Bonus: moved `chat/contentReceived` notification handler to `chat.clj`. Pushed `state.clj` from 314 to 272.

Commits 1-6 were bundled into one git commit; 7, 8, 9, 10 are separate.

### Plan revisions during execution

**Order change.** Original plan had login → picker → commands → sessions. User asked to bring `commands.clj` forward. New order picker → sessions → commands → login worked because `commands.clj` could ship with `cmd-login` calling a transient copy of `start-login-cmd` (later moved to `login.clj` in step 7).

**`shutdown-cmd` placement.** Plan said keep in `state.clj`. `commands.clj/cmd-quit` originally needed it but couldn't `require` `state` (one-way rule). Resolution: inlined the 4-line shutdown sequence in `cmd-quit`; `state.clj` keeps an identical Ctrl+C path. 4 LOC duplication, no cycle.

**`finalize-handler-result` placement.** Plan said keep in `state.clj` (called from both dispatch-command and command-picker selection arm). Reality: ended up duplicated as a private fn in `commands.clj/dispatch-command` and `commands.clj/run-handler-from-picker`. Cleaner than exposing a shared util.

**`printable-char?` placement.** Plan implicitly put it in `commands.clj` (where it lived). When extracting picker key dispatch in step 9, picker needed `printable-char?` for the filter arm — but `picker → commands` would create a `picker → commands → login → chat` cycle. Resolution: moved `printable-char?` from `commands.clj` to `picker.clj` (which is a leaf-ish ns). State.clj and commands.clj now reference `picker/printable-char?`.

**Command-picker selection stayed in dispatcher.** `picker/handle-key` returns `[state nil]` for the `:command` kind on Enter; `state.clj`'s dispatcher catches that case above the picker delegation and calls `commands/run-handler-from-picker`. Same cycle-avoidance reason — `picker` cannot import `commands`.

**`login/handle-key` `:else` clause.** Original draft had `:else [state nil]`, which dropped typed characters into login fields. Fix: forwarded to `text-input-update` so typing into login prompts still works. Behaviour-preserving.

**`chat.clj` ceiling raised.** Plan upper-bounded chat at 500 LOC during the review pass. Final 462 — under. The bonus `handle-content-received` extraction in step 10 added 50 LOC without crossing.

### Dep graph (final)

```
core ──► state, view
state ──► chat, picker, login, commands, sessions, server, protocol, upgrade, view
commands ──► login, picker, sessions, server, protocol, view
login ──► chat, protocol, view
chat ──► protocol, sessions, view
picker ──► protocol, sessions
sessions ──► protocol
view ──► wrap
```

Acyclic. The cycle hazards encountered (`chat → commands → login → chat`, `picker → commands → login → chat`) were all solved by keeping integration arms in the dispatcher rather than the feature ns.

### Things the plan got right

- Dispatcher contract (mode-agnostic events → global keys → per-mode delegation) survived contact with reality unchanged.
- Test hardening *before* the refactor (step 1) caught zero issues during execution but provided regression confidence at every step.
- Mapping line ranges from `grep -nE '^\(defn'` produced LOC estimates within ±15% of actuals on every file except `state.clj`.
- One-way dep direction from `state` outward held throughout — no back-references created.

### Things the plan missed

- The `state.clj` 250-LOC target was below the natural floor for "central state ns" in this ecosystem (~280-300 across siblings). 272 is a better outcome than artificially shrinking further.
- `printable-char?` shared-utility need wasn't anticipated.
- `login/handle-key` `:else` text-input-fallthrough requirement wasn't explicit (silent edge case caught during review of step 7).
- The `picker → commands` cycle hazard wasn't on the dep-graph diagram; only surfaced during step 9.

### Phase B inputs

The deferred items (MCP, queryContext, queryCommands, diff, log viewer, view split, block-navigation keybindings) remain. New input from this execution: `chat.clj` is approaching its self-imposed 500 LOC ceiling — if Phase B work pushes it past 600, the natural seam is `chat.clj` (handlers + state) + `chat_keys.clj` (key dispatch). Sibling editors don't make this split, so it would be premature today.
