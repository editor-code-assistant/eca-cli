# Phase 1a Review: Gaps and Inconsistencies

---

## 1. `$/progress` — Wrong Mode in Task 1 (Architectural)

**Severity: High**

Task 1 says: *"In `view/view`: during `:connecting` mode, show active init tasks in the chat area."*

This is wrong. The drain loop only starts after `eca-initialized` is processed — which transitions the app to `:ready`. While in `:connecting`, the reader thread accumulates `$/progress` notifications on the queue but nothing drains them. By the time the drain loop first runs, the app is already in `:ready` mode.

The connecting-mode view change has no effect. Remove it. The correct approach is already described elsewhere in Task 1: the `⏳` indicator in the status bar (which is shown in `:ready` mode). Optionally, if you want to surface progress during the brief `:ready` phase before the first message, a small block at the top of the chat area (not gated on `:connecting`) would work.

**Fix:** Remove the `view/view` `:connecting`-mode paragraph from Task 1.

---

## 2. Task 5 Code Snippet Returns Wrong Type

**Severity: High**

The `handle-eca-tick` reduce accumulator is `[state cmd]`. The code snippet in Task 5 shows:

```clojure
(= :reader-error (:type m))
(-> s
    (assoc :mode :ready)
    (update :items conj {:type :system ...})
    (update :input ti/focus)
    rebuild-lines)
```

This returns just `state`, not `[state nil]`. The reduce function would then try to destructure a state map as `[[s cmd] m]` on the next iteration and crash. The description below the snippet correctly says *"Return `[new-state nil]`"* but the code doesn't match.

**Fix:** Wrap the snippet in a vector:

```clojure
(= :reader-error (:type m))
[(-> s
     (assoc :mode :ready)
     (update :items conj {:type :system :text (str "ECA disconnected: " (:error m))})
     (update :input ti/focus)
     rebuild-lines)
 nil]
```

---

## 3. Token Format Inconsistency Between Code and Test

**Severity: Medium**

The current `render-status-bar` in `view.clj` produces `"N tok"` (with a space):

```clojure
tokens (some-> (:usage state) :sessionTokens (str " tok"))
```

Task 4 proposes `(str "tok")` (no space), and the test asserts `"1234tok"` (no space). These three things — current impl, proposed impl, and test expectation — need to agree. The document should explicitly state that the space is being removed as part of Task 4 and why (or keep it and fix the test).

---

## 4. `with-redefs` on Private Var Won't Work

**Severity: Medium**

The `eca-login-action-test` "done action" case uses:

```clojure
(with-redefs [state/send-chat-prompt! (fn [& _] nil)]
  ...)
```

`send-chat-prompt!` is declared `defn-` (private). The existing test file accesses private vars via `(def ^:private fn-name #'state/fn-name)`. Referencing a private var directly in `with-redefs` without the `#'` reader macro may fail or silently redef the wrong thing.

**Fix:** Declare it at the top of the test file alongside the other private var aliases:

```clojure
(def ^:private send-chat-prompt! #'state/send-chat-prompt!)
```

Then use it in `with-redefs`:

```clojure
(with-redefs [send-chat-prompt! (fn [& _] nil)]
  ...)
```

---

## 5. Missing Test: `:system` Item Rendering

**Severity: Medium**

Task 2 adds a `:system` case to `view/render-item-lines`. The existing `render-item-lines-test` in `view_test.clj` tests `:user`, `:assistant-text`, `:streaming-text`, and `:tool-call` — but the document's new tests section does not add a `:system` case to this test. The `:system` type goes untested in view rendering.

**Fix:** Add to `view_test.clj`:

```clojure
(testing ":system item"
  (let [lines (view/render-item-lines {:type :system :text "Connection lost"} 80)]
    (is (= 1 (count lines)))
    (is (clojure.string/includes? (first lines) "Connection lost"))
    (is (clojure.string/includes? (first lines) "⚠"))))
```

---

## 6. Missing Test: `selectAgent nil` Clears Selection

**Severity: Low**

The `handle-config-updated-test` has a `"selectModel nil clears selection"` case but no equivalent for `selectAgent`. The `contains?` logic is symmetric — both should be tested.

**Fix:** Add:

```clojure
(testing "selectAgent nil clears selection"
  (let [s0    (assoc (base-state) :selected-agent {:id "some-agent"})
        [s _] (handle-eca-notification
                s0
                {:method "config/updated"
                 :params {:chat {:selectAgent nil}}})]
    (is (nil? (:selected-agent s)))))
```

---

## 7. `welcomeMessage` from `config/updated` Unmentioned

**Severity: Low**

The protocol's `config/updated` includes `welcomeMessage?: string` — "a message to show when starting a new chat." The document neither handles it nor explicitly excludes it. It should be addressed: either handle it by adding a `:system` item to chat when present, or explicitly defer it to a later phase.

---

## 8. `$/progress` Finish-Before-Start Edge Case

**Severity: Low**

If ECA sends a `finish` notification for a `taskId` that was never started, the implementation does:

```clojure
(assoc-in state [:init-tasks taskId :done?] true)
```

This creates `{taskId {:done? true}}` — an entry with no `:title`. The `any-tasks-running?` helper correctly returns false for this (since `(:done? %)` is true). However, if task display is ever rendered anywhere, it would show a nil title.

**Fix:** Guard the finish case:

```clojure
"finish" (if (contains? (:init-tasks state) taskId)
           (assoc-in state [:init-tasks taskId :done?] true)
           state)
```

---

## 9. Nested `let` in `eca-login-action-test`

**Severity: Low** (style)

The "nil action" test case has an unnecessary double-let:

```clojure
(let [s (state/update-state ...)]
  (let [[new-state _] s]
    (is ...)))
```

Should be a single destructuring let:

```clojure
(let [[new-state _] (state/update-state ...)]
  (is ...))
```

---

## Summary

| # | Issue | Severity | Where |
|---|-------|----------|-------|
| 1 | `$/progress` shown in `:connecting` mode — unreachable | High | Task 1 |
| 2 | Task 5 code snippet returns `state` not `[state nil]` | High | Task 5 |
| 3 | Token format: current `" tok"` vs proposed `"tok"` vs test `"1234tok"` | Medium | Task 4 + tests |
| 4 | `with-redefs` on private `send-chat-prompt!` will not work as written | Medium | Tests |
| 5 | No test for `:system` item in `render-item-lines` | Medium | Tests |
| 6 | No `selectAgent nil` test case | Low | Tests |
| 7 | `welcomeMessage` unmentioned — handle or explicitly defer | Low | Task 3 |
| 8 | `$/progress` finish-before-start creates titleless entry | Low | Task 1 |
| 9 | Nested `let` in login action test | Low | Tests |
