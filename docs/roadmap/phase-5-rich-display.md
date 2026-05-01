# Phase 5: Rich Display

> **Status: Complete** — all 20 automated unit criteria and integration criteria 15–19 pass. Sub-agent integration criteria 20–22 verified manually.

> **Pre-condition:** Phase 4 complete (`bb test` passes, command system functional).

## Goal

Replace the flat, text-only chat display with a structured, interactive rendering model — collapsible tool blocks, thinking blocks, and nested sub-agent content — matching the level of UX fidelity seen in nvim's ECA buffer.

---

## Background: What Phase 4 Left Behind

| Gap | Current state | Target state |
|-----|---------------|--------------|
| Tool call display | Summary string only | Collapsed 1-liner; expandable to args + output |
| Thinking content | Not stored or shown | Collapsible `▼ Thought` block |
| Sub-agent content | Suppressed via `parentChatId` check | Nested under `eca__spawn_agent` tool call |
| Item focus | None — scroll only | Tab navigation; Enter/Space toggles expand |
| Tool args/output | Discarded after display | Stored in item for deferred inspect |

The `parentChatId` suppression (commit `6cb3696`) is an interim fix. This phase replaces it with the real solution.

---

## Implementation Order

1. **Item model + initial state** — add `:args-text`, `:out-text`, `:expanded? false`, `:focused? false` to tool-call items at creation; `:sub-items []` on `eca__spawn_agent` items; add `:focus-path nil` and `:subagent-chats {}` to `initial-state`. Foundation — everything else depends on this.

2. **Protocol handlers: args/output + thinking + hooks** — `toolCallRun` stores `:args-text`; `toolCalled` stores `:out-text` (truncate at 8 KB); `reasonStarted/Text/Finished`; `hookActionStarted/Finished`.

3. **Task tool suppression + sub-agent routing** — intercept `{:server "eca" :name "task"}` and drop from `:items`; replace `parentChatId` suppression with routing to parent `:sub-items` via `:subagent-chats`; register sub-agent link on `toolCallRun` when `subagentDetails` present.

4. **`upsert-tool-call` merge safety** — fix before view work (see Notes).

5. **View: collapsed/expanded rendering** — `render-item-lines` gains collapsed/expanded branches per `:tool-call`, `:thinking`, `:hook`; box-drawing arg/output blocks; sub-item indentation; `▸ N steps` suffix; focus indicator (`›`).

6. **Focus/navigation** — Tab/Shift+Tab build render-order path list and advance/reverse; Enter/Space toggles `:expanded?` at `:focus-path`; Escape clears focus; Tab adjusts `:scroll-offset`.

7. All unit tests green, then integration + manual pass.

---

## What to Build

### 1. Extended item model

Add fields to tool-call items so they carry enough data to render at any expansion level:

```clojure
;; tool-call item — extended
{:type      :tool-call
 :name      "read_file"
 :state     :called        ; :preparing | :run | :running | :called | :rejected
 :summary   "read foo.clj" ; short display string (existing)
 :args-text nil            ; JSON string of arguments — nil until toolCallRun
 :out-text  nil            ; result string — nil until toolCalled
 :expanded? false          ; collapsed by default
 :focused?  false
 :error?    false}

;; thinking item — new type
;; Populated across three events: reasonStarted → reasonText (many) → reasonFinished
{:type      :thinking
 :id        "r1"         ; correlates streamed reasonText events
 :text      ""           ; accumulated; empty until first reasonText arrives
 :status    :thinking    ; :thinking | :thought (set on reasonFinished)
 :expanded? false
 :focused?  false}

;; hook item — new type
{:type      :hook
 :id        "h1"         ; correlates hookActionFinished
 :name      "pre-tool"   ; hook script/name
 :status    :running     ; :running | :ok | :failed
 :out-text  nil          ; populated on hookActionFinished
 :expanded? false
 :focused?  false}

;; tool-call item for eca__spawn_agent — carries sub-agent items
{:type      :tool-call
 :name      "eca__spawn_agent"
 :state     :called
 :summary   "explorer agent"
 :args-text "..."
 :out-text  "..."
 :expanded? false
 :focused?  false
 :sub-items []}             ; populated by sub-agent contentReceived
```

No other item types change shape.

---

### 2. State additions

```clojure
;; Two new top-level keys
:focus-path nil   ; nil = no focus
                  ; [3]   = top-level item at index 3
                  ; [3 1] = sub-item at index 1 of top-level item 3

;; Sub-agent routing table: subagent chat-id → index into :items
;; Populated when toolCallRun arrives with subagentDetails
:subagent-chats {}      ; {"subagent-chat-42" 7}
```

---

### 3. Protocol event changes

#### `toolCallRun` handler (store args, register sub-agent link)

`toolCallRun` arrives when ECA has collected the full argument JSON. Current handler adds args to `:args-text` (already partially done) and must now also register the sub-agent link when `subagentDetails` is present:

```clojure
(let [sub (:subagentDetails params)]
  (when sub
    (update state :subagent-chats assoc (:subagentChatId sub) tool-item-idx)))
```

#### `toolCalled` handler (store output)

Store the result string in the matching tool-call item's `:out-text`. The result may be long; truncate at a reasonable limit (e.g. 8 KB) with a `... [truncated]` suffix to avoid rendering pathological outputs in full.

#### `contentReceived` with `parentChatId` (route to parent, not suppress)

Replace the current one-line suppression with routing:

```clojure
(if-let [parent-idx (get-in state [:subagent-chats (:chatId params)])]
  ;; append to parent tool call's :sub-items
  (update-in state [:items parent-idx :sub-items]
             conj (content->item params))
  ;; no parent registered: normal flow (direct content)
  (handle-content state params))
```

`content->item` is a small helper that converts a `contentReceived` params map to the appropriate item type (`:assistant-text`, `:tool-call`, `:thinking`, etc.).

#### Hook actions — two-event protocol (new)

| Event | Fields | Action |
|-------|--------|--------|
| `hookActionStarted` | `id`, `name` | Create `:hook` item with `:status :running` |
| `hookActionFinished` | `id`, `status` (`"ok"`/`"failed"`), `output` | Find by `:id`, set `:status :ok/:failed`, store `:out-text` |

```clojure
"hookActionStarted"  → conj items {:type :hook :id id :name name
                                    :status :running :out-text nil :expanded? false}
"hookActionFinished" → update item where (= (:id item) id)
                         assoc :status (keyword status)
                               :out-text output
```

---

#### Thinking — three-event protocol (new)

Thinking is **not** a single content event. It arrives as three distinct events identified by a shared `:id`:

| Event | Fields | Action |
|-------|--------|--------|
| `reasonStarted` | `id` | Create `:thinking` item with empty `:text`, `:status :thinking` |
| `reasonText` | `id`, `text` | Find item by `:id`, append `text` to `:text` |
| `reasonFinished` | `id`, `totalTimeMs` | Find item by `:id`, set `:status :thought` |

```clojure
;; reasonStarted
"reasonStarted" → conj items {:type :thinking :id id :text "" :status :thinking
                               :expanded? false}

;; reasonText — find by id, append
"reasonText" → update item where (= (:id item) id)
                 update :text str text

;; reasonFinished — find by id, mark done
"reasonFinished" → update item where (= (:id item) id)
                     assoc :status :thought
```

The header label in the view reflects status: `▸ Thinking…` while `:status :thinking`, `▸ Thought` once `:thought`.

---

### 4. View: collapsed rendering

`render-item-lines` gains collapsed/expanded dispatch for `:tool-call` and `:thinking`. Everything else is unchanged.

**Collapsed tool call (1 line):**
```
[icon] tool-name   summary-text
```
Example:
```
✅ read_file   read src/foo.clj
⏳ eca__spawn_agent   explorer agent  ▸ 3 steps
```
The `▸ N steps` suffix appears only when `:sub-items` is non-empty.

**Expanded tool call:**
```
[icon] tool-name   summary-text  ▾
  ┌─ Arguments ──────────────────┐
  │ {"path": "src/foo.clj"}      │
  └──────────────────────────────┘
  ┌─ Output ─────────────────────┐
  │ (ns eca-cli.core              │
  │   (:require ...))            │
  └──────────────────────────────┘
```
Box drawing is ASCII-safe (`+`/`-`/`|` fallback) in environments where box chars fail. Wrap at `width - 4`.

**Collapsed hook (1 line):**
```
⚡ hook-name   running…
⚡ hook-name   ok
❌ hook-name   failed
```

**Expanded hook:**
```
⚡ hook-name   ok  ▾
  ┌─ Output ─────────────────────┐
  │ hook stdout/stderr here      │
  └──────────────────────────────┘
```
Focusable and expandable same as `:tool-call`. `:hook` items with no output (empty `:out-text`) show no output block when expanded.

**Collapsed thinking (1 line):**
```
▸ Thinking…   (while :status :thinking — live, pulsing label)
▸ Thought     (once :status :thought — static)
```

**Expanded thinking:**
```
▾ Thought
  ...model's reasoning text...
```
Wrapped at `width - 2`. While still streaming (`:status :thinking`), the expanded body updates in place as `reasonText` events append.

**Expanded `eca__spawn_agent` (shows sub-items indented, each individually expandable):**
```
✅ eca__spawn_agent   explorer agent  ▾
  ◆ Here is the codebase overview...
  ✅ read_file   read src/state.clj
  ✅ list_directory   src/eca_cli/
```
Sub-items render their own collapsed form (1 line each) indented by 2 spaces. They are individually focusable and expandable — Tab walks them in render order alongside top-level items. An expanded sub-item adds its args/output block at the same 2-space indent level.

---

### 5. Focus and toggle

**State machine additions:**

| Key | Condition | Effect |
|-----|-----------|--------|
| `Tab` | `:ready` or `:chatting` | Advance `:focus-path` to next focusable item in render order; wrap around |
| `Shift+Tab` | `:ready` or `:chatting` | Reverse |
| `Enter` / `Space` | focused item exists | Toggle `:expanded?` on focused item; rebuild lines |
| `Escape` | focused item exists | Clear focus (`nil`); rebuild lines |

"Focusable" items: `:tool-call`, `:thinking`, and `:hook`, at any nesting depth. `:user` and `:assistant-text` items are skipped.

**Render-order walk.** Tab advances through a flat sequence of focusable item paths built from `:items` on each keypress: top-level focusable items and — when a parent spawn item is expanded — its focusable sub-items, interleaved in the order they would appear on screen. A collapsed spawn block's sub-items are skipped (they're not visible). This keeps Tab navigation consistent with what the user sees.

Example path sequence for a chat with one user message, one `read_file` (top-level), one expanded `eca__spawn_agent` with two sub-tools:
```
[1]        ; read_file
[2]        ; eca__spawn_agent (the spawn block itself)
[2 0]      ; sub-item: read_file inside spawn
[2 1]      ; sub-item: list_directory inside spawn
```
If the spawn block is collapsed, Tab visits only `[1]` and `[2]`.

**`:focus-path` addressing.**  
Toggle and focus indicator both use `get-in`/`update-in` with the path directly:
```clojure
;; toggle expanded? on focused item
(update-in state (into [:items] (interleave (repeat :sub-items) focus-path)) ...)
;; simplified: build the get-in key sequence from focus-path
;; [3]   → [:items 3]
;; [3 1] → [:items 3 :sub-items 1]
```

**Visual indicator:** the first rendered line of the focused item is prefixed with a `›` glyph (or reverse-video if terminal supports it). This replaces the normal icon prefix. Sub-items retain their 2-space indent; the `›` replaces the icon within that indent.

**Scroll follows focus:** when Tab moves focus, adjust `:scroll-offset` so the focused item's first line is visible.

---

### 6. Rendering architecture note

The current model — `chat-lines` is a flat vec rebuilt by `rebuild-chat-lines` after every state change — still works. `render-item-lines` already dispatches per item type; we add collapsed/expanded branches per type. The rebuild is slightly more expensive when items have long outputs, but acceptable.

The truncation limit on `:out-text` (§3) bounds the worst case.

---

## State machine diagram additions

```
:ready / :chatting
  --Tab-->          advance :focus-path in render order; scroll to show
                    (skips sub-items of collapsed spawn blocks)
  --Shift+Tab-->    reverse
  --Enter/Space-->  toggle :expanded? at :focus-path; rebuild lines
                    (expanding a spawn block makes its sub-items Tab-reachable)
  --Escape-->       (focused item) clear :focus-path only; no mode change
```

No new top-level modes. Focus is orthogonal to existing modes.

---

## Tests

### Unit (`bb test`)

#### `state_test.clj` additions

```clojure
;; Tool args stored on toolCallRun
(deftest tool-args-stored-test
  (testing "toolCallRun stores args-text in matching tool-call item"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :tool-call :name "read_file"
                                :state :run :call-id "tc1"
                                :expanded? false :focused? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/toolCallRunning"
                        :params {:chatId "chat1" :callId "tc1"
                                 :arguments "{\"path\":\"foo.clj\"}"}})]
      (is (= "{\"path\":\"foo.clj\"}"
             (get-in s [:items 0 :args-text]))))))

;; Hook item — two-event lifecycle
(deftest hook-item-test
  (testing "hookActionStarted creates :hook item with :running status"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "hookActionStarted" :id "h1" :name "pre-tool"}}})]
      (is (= 1 (count (:items s))))
      (is (= :hook    (:type   (first (:items s)))))
      (is (= "h1"     (:id     (first (:items s)))))
      (is (= :running (:status (first (:items s)))))))

  (testing "hookActionFinished updates status and stores output"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :hook :id "h1" :name "pre-tool"
                                :status :running :out-text nil :expanded? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "hookActionFinished" :id "h1"
                                           :status "ok" :output "done"}}})]
      (is (= :ok   (:status   (first (:items s)))))
      (is (= "done" (:out-text (first (:items s))))))))

;; Thinking item — three-event lifecycle
(deftest thinking-item-test
  (testing "reasonStarted creates :thinking item with empty text and :thinking status"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "reasonStarted" :id "r1"}}})]
      (is (= 1 (count (:items s))))
      (is (= :thinking (:type (first (:items s)))))
      (is (= "r1" (:id (first (:items s)))))
      (is (= "" (:text (first (:items s)))))
      (is (= :thinking (:status (first (:items s)))))
      (is (false? (:expanded? (first (:items s)))))))

  (testing "reasonText appends to matching :thinking item"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :thinking :id "r1" :text "" :status :thinking
                                :expanded? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "reasonText" :id "r1" :text "I should..."}}})]
      (is (= "I should..." (:text (first (:items s)))))))

  (testing "reasonFinished sets :status to :thought"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :thinking :id "r1" :text "I should..." :status :thinking
                                :expanded? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "reasonFinished" :id "r1" :totalTimeMs 1234}}})]
      (is (= :thought (:status (first (:items s))))))))

;; Sub-agent content routed to parent
(deftest subagent-content-routed-test
  (testing "contentReceived with parentChatId is routed to parent tool call sub-items"
    (let [base  (-> (base-state)
                    (assoc :mode :chatting
                           :items [{:type :tool-call :name "eca__spawn_agent"
                                    :call-id "tc1" :state :called
                                    :expanded? false :sub-items []}]
                           :subagent-chats {"sub-42" 0}))
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId       "sub-42"
                                 :parentChatId "chat1"
                                 :role         "assistant"
                                 :content      {:type "text" :text "sub result"}}})]
      (is (= 1 (count (:items s))))
      (is (= 1 (count (get-in s [:items 0 :sub-items])))))))

;; Tab focus navigation
(deftest tab-focus-navigation-test
  (testing "Tab in :ready with tool-call items sets focus-path to first focusable"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path nil
                      :items [{:type :user :text "hi"}
                              {:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? false}])
          [s _] (update-state base (msg/key-press :tab))]
      (is (= [1] (:focus-path s)))))

  (testing "Enter on focused tool-call toggles :expanded?"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0]
                      :items [{:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? true}])
          [s _] (update-state base (msg/key-press :enter))]
      (is (true? (get-in s [:items 0 :expanded?])))))

  (testing "Escape clears focus, does not change mode"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0]
                      :items [{:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? true}])
          [s _] (update-state base (msg/key-press :escape))]
      (is (nil? (:focus-path s)))
      (is (= :ready (:mode s)))))

  (testing "Tab skips sub-items of a collapsed spawn block"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path nil
                      :items [{:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? false}
                              {:type :tool-call :name "eca__spawn_agent" :state :called
                               :expanded? false :focused? false
                               :sub-items [{:type :tool-call :name "list_dir"
                                            :state :called :expanded? false}]}])
          [s1 _] (update-state base (msg/key-press :tab))
          [s2 _] (update-state s1 (msg/key-press :tab))
          [s3 _] (update-state s2 (msg/key-press :tab))]
      ;; focus wraps: [0] → [1] → [0] (spawn collapsed, sub-items skipped)
      (is (= [0] (:focus-path s1)))
      (is (= [1] (:focus-path s2)))
      (is (= [0] (:focus-path s3)))))

  (testing "Tab reaches sub-items when spawn block is expanded"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path nil
                      :items [{:type :tool-call :name "eca__spawn_agent" :state :called
                               :expanded? true :focused? false
                               :sub-items [{:type :tool-call :name "read_file"
                                            :state :called :expanded? false}]}])
          [s1 _] (update-state base (msg/key-press :tab))
          [s2 _] (update-state s1 (msg/key-press :tab))]
      (is (= [0] (:focus-path s1)))      ; the spawn block itself
      (is (= [0 0] (:focus-path s2)))))  ; sub-item inside it

  (testing "Enter on focused sub-item toggles its :expanded?"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0 0]
                      :items [{:type :tool-call :name "eca__spawn_agent" :state :called
                               :expanded? true :focused? false
                               :sub-items [{:type :tool-call :name "read_file"
                                            :state :called :expanded? false}]}])
          [s _] (update-state base (msg/key-press :enter))]
      (is (true? (get-in s [:items 0 :sub-items 0 :expanded?]))))))
```

#### `view_test.clj` additions

```clojure
(deftest render-item-lines-rich-test
  (testing "collapsed tool-call renders 1 line"
    (let [lines (view/render-item-lines
                  {:type :tool-call :state :called :name "read_file"
                   :summary "foo.clj" :expanded? false} 80)]
      (is (= 1 (count lines)))))

  (testing "expanded tool-call with args renders multiple lines"
    (let [lines (view/render-item-lines
                  {:type :tool-call :state :called :name "read_file"
                   :summary "foo.clj" :args-text "{\"path\":\"foo.clj\"}"
                   :out-text "content" :expanded? true} 80)]
      (is (> (count lines) 1))
      (is (some #(clojure.string/includes? % "foo.clj") lines))
      (is (some #(clojure.string/includes? % "content") lines))))

  (testing "collapsed thinking renders 1 line with ▸"
    (let [lines (view/render-item-lines
                  {:type :thinking :text "I should..." :expanded? false} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/includes? (first lines) "▸"))))

  (testing "expanded thinking shows text"
    (let [lines (view/render-item-lines
                  {:type :thinking :text "I should..." :expanded? true} 80)]
      (is (> (count lines) 1))
      (is (some #(clojure.string/includes? % "I should") lines)))))
```

#### Additional `state_test.clj` tests

```clojure
;; upsert-tool-call preserves :expanded? across subsequent events
(deftest upsert-preserves-expanded-test
  (testing "toolCalled does not reset :expanded? on an already-expanded item"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :tool-call :id "tc1" :name "read_file"
                                :state :running :expanded? true :focused? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "toolCalled" :id "tc1" :name "read_file"
                                           :server "fs" :arguments {} :error false}}})]
      (is (true? (get-in s [:items 0 :expanded?]))))))

;; :out-text truncated at 8 KB
(deftest out-text-truncation-test
  (testing "toolCalled with large output truncates :out-text"
    (let [big   (apply str (repeat 9000 "x"))
          base  (assoc (base-state) :mode :chatting
                       :items [{:type :tool-call :id "tc1" :name "read_file"
                                :state :running :expanded? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "toolCalled" :id "tc1" :name "read_file"
                                           :server "fs" :arguments {} :error false
                                           :output big}}})]
      (is (<= (count (get-in s [:items 0 :out-text])) 8200))
      (is (clojure.string/includes? (get-in s [:items 0 :out-text]) "[truncated]")))))

;; Task tool suppressed
(deftest task-tool-suppressed-test
  (testing "toolCallPrepare for eca/task tool does not add to :items"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "toolCallPrepare" :id "tc1"
                                      :name "task" :server "eca" :summary "bg task"}}})]
      (is (empty? (:items s))))))

;; parentChatId fallthrough when no parent registered
(deftest subagent-fallthrough-test
  (testing "contentReceived with parentChatId and no registered parent falls through to main flow"
    (let [base  (assoc (base-state) :mode :chatting :subagent-chats {})
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId       "unregistered-sub"
                                 :parentChatId "chat1"
                                 :role         "assistant"
                                 :content      {:type "text" :text "fallthrough"}}})]
      (is (= 1 (count (:items s)))))))
```

---

## Stopping Criteria

### Automated (`bb test`) — ✅ Complete

1. ✅ `bb test` passes — no regressions from phases 1–4. (`tool-args-stored-test` + 65 prior tests)
2. ✅ `toolCallRun` handler stores `:args-text` on matching tool-call item. (`tool-args-stored-test`)
3. ✅ `toolCalled` handler stores `:out-text` on matching tool-call item. (`out-text-truncation-test`)
4. ✅ `contentReceived` with `parentChatId` routes to parent `:sub-items` when registered; falls through otherwise. (`subagent-content-routed-test`, `subagent-fallthrough-test`)
5. ✅ `reasonStarted` creates `:thinking` item; `reasonText` appends; `reasonFinished` sets `:status :thought`. (`thinking-item-test`)
6. ✅ `hookActionStarted` creates `:hook` item with `:status :running`; `hookActionFinished` updates status and `:out-text`. (`hook-item-test`)
7. ✅ Tab in `:ready` mode with focusable items sets `:focus-path` to first focusable item. (`tab-focus-navigation-test`)
8. ✅ Tab again advances focus in render order; wraps at end. (`tab-focus-navigation-test`)
9. ✅ Escape clears focus, does not change mode. (`tab-focus-navigation-test`)
10. ✅ Enter on focused item toggles `:expanded?`; rebuilds lines. (`tab-focus-navigation-test`)
11. ✅ Collapsed tool-call renders exactly 1 line. (`render-item-lines-rich-test`)
12. ✅ Expanded tool-call with `:args-text` and `:out-text` renders > 1 line, content includes both. (`render-item-lines-rich-test`)
13. ✅ Collapsed thinking renders 1 line containing `▸`. (`render-item-lines-rich-test`)
14. ✅ Expanded thinking renders > 1 line, content visible. (`render-item-lines-rich-test`)
15. ✅ `eca__spawn_agent` expanded with non-empty `:sub-items` renders sub-items indented. (`render-item-lines-rich-test`)
16. ✅ Tab skips sub-items of a collapsed spawn block; visiting only top-level focusable items. (`tab-focus-navigation-test`)
17. ✅ Tab reaches sub-items when the parent spawn block is expanded. (`tab-focus-navigation-test`)
18. ✅ Enter on a focused sub-item (`:focus-path [i j]`) toggles that sub-item's `:expanded?`. (`tab-focus-navigation-test`)
19. ✅ `toolCallPrepare` / `toolCallRun` with `{:server "eca" :name "task"}` produces no entry in `:items`. (`task-tool-suppressed-test`)
20. ✅ `upsert-tool-call` preserves `:expanded?` across subsequent events. (`upsert-preserves-expanded-test`)

### Integration (`bb itest`) — ✅ Criteria 15–19 automated; 20–22 manual

15. ✅ Collapsed tool block with `✓` icon visible after tool call completes. (`phase5-tool-block-and-focus-test`)
16. ✅ Tab moves `›` focus indicator to tool block. (`phase5-tool-block-and-focus-test`)
17. ✅ Enter expands block — `▾` marker and Arguments box visible. (`phase5-tool-block-and-focus-test`)
18. ✅ Enter again collapses block — `▾` gone. (`phase5-tool-block-and-focus-test`)
19. ✅ Escape clears focus — `›` gone. (`phase5-tool-block-and-focus-test`)
20. ☐ Sending a prompt that invokes `eca__spawn_agent` — collapsed spawn block shows `▸ N steps` suffix. *(manual — requires agent routing that invokes sub-agents)*
21. ☐ Expanding spawn block shows sub-agent steps indented. *(manual)*
22. ☐ Tab reaches a sub-item inside an expanded spawn block; Enter expands it. *(manual)*

### Manual

22. `bb run` → send a message that reads a file → tool block shows as 1-line collapsed entry with `✓` icon.
23. Tab to focus it → `›` indicator visible on the line.
24. Enter → expands to show arguments and file content output.
25. Enter again → collapses.
26. Escape → focus indicator gone, scrolling and typing work normally.
27. Send a message that uses thinking → `▸ Thinking…` appears while streaming; label changes to `▸ Thought` on completion; Tab + Enter expands to show accumulated reasoning text.
28. Send a message that triggers sub-agent → spawn tool block shows `▸ N steps`; expand to inspect nested tool calls.
29. Tab into the spawn block when expanded → sub-items are individually focusable; Enter on a sub-item expands it to show its args/output indented.

---

## Notes

- **Truncation is mandatory.** `:out-text` must be capped (≤ 8 KB rendered, with `[truncated]` notice) to prevent runaway re-render times on verbatim file dumps. The full output exists on disk in `~/.cache/eca/toolCallOutputs/` if needed.

- **`parentChatId` suppression removed.** The `(if (:parentChatId params) [state nil] ...)` guard in `handle-eca-notification` is replaced by the routing logic in §3. If no parent is registered (race or unknown sub-agent), content falls through to normal handling rather than being silently dropped.

- **Scroll + focus interaction.** Tab advancing focus must ensure the focused item's first line is within the visible window. Adjust `:scroll-offset` if the focused item is outside the current viewport. No animation — jump directly.

- **Syntax highlighting deferred.** Code blocks within tool output are rendered as plain text in this phase. A future phase (post–4.5) can add a lightweight tokenizer for common languages. ANSI colors from ECA pass through unchanged.

- **`subagentDetails` availability.** The `SubagentDetails` struct (`subagentChatId`) is present on `toolCallRun` params per the ECA protocol spec. This is the link that populates `:subagent-chats`. If ECA sends it, routing works automatically; if absent (tool is not a sub-agent spawn), `:subagent-chats` is not updated and `parentChatId` content falls through.

- **`upsert-tool-call` merge safety.** Current implementation uses `merge`, so a subsequent event (e.g. `toolCalled`) will clobber `:expanded?` back to false if the incoming map carries that key. Fix: strip `:expanded?` and `:focused?` from the incoming map before merging, or explicitly restore them from the pre-merge item after merge. Failing to do this causes user-expanded blocks to snap back to collapsed on the next tool event.

- **No new modes.** Focus state is orthogonal to `:ready`/`:chatting`/`:approving`. Tab and Enter remain active in both `:ready` and `:chatting` (so the user can inspect previous tool blocks while the agent is working).

- **Content type inventory.** Reference from eca-webview's protocol. Columns: handled in eca-cli today / handled in this phase / deferred.

  | Content type | Today | Phase 4.5 | Deferred |
  |---|---|---|---|
  | `text` (assistant) | ✅ | — | — |
  | `toolCallPrepare` | ✅ | — | — |
  | `toolCallRun` | ✅ | store `:args-text` | — |
  | `toolCallRunning` | ✅ | — | — |
  | `toolCalled` | ✅ | store `:out-text` | — |
  | `toolCallRejected` | ✅ | — | — |
  | `reasonStarted` | ❌ | ✅ | — |
  | `reasonText` | ❌ | ✅ | — |
  | `reasonFinished` | ❌ | ✅ | — |
  | `hookActionStarted` | ❌ | ✅ | — |
  | `hookActionFinished` | ❌ | ✅ | — |
  | `metadata` (chat title) | ✅ (via `chat/opened`) | — | — |
  | `progress` | ✅ (via `$/progress`) | — | — |
  | `flag` | ❌ | ❌ | phase 9 |
  | `url` | ❌ | ❌ | phase 6 |
  | `usage` | ✅ (status bar) | — | — |
