# Phase 2: Model & Agent Identity

> **Pre-condition:** Phase 1b complete (`bb test` passes, manual login criteria met).

## Goal

The user always knows what model, agent, and variant they're running, and can change any of them without restarting. Model and agent selection is keyboard-driven and takes effect on the next sent message.

---

## What Phase 1 Already Did

Phase 1a stored `available-models`, `available-agents`, `selected-model`, and `selected-agent` in state and wired `config/updated` to populate them. The status bar already shows `selected-model`. This phase builds the interactive layer on top.

---

## What to Build

### 1. Variant handling in `config/updated`

`config/updated` has two fields not yet handled:

- `variants` — available variant names for the currently selected model (e.g. `["low" "medium" "high"]`). Empty array = model has no variants.
- `selectVariant` — ECA's instruction to forcefully select a variant; `null` means clear (e.g. new model has no variants).

Store both in state. When `chat/selectedModelChanged` is sent, ECA will respond with a `config/updated` containing these fields for the new model.

**New state fields:**
```clojure
:available-variants []   ; string[] from config/updated :variants
:selected-variant   nil  ; string | nil — current variant
```

### 2. Protocol: selection notifications

Two new notification functions in `protocol.clj`. Both are fire-and-forget (no response expected):

```clojure
(defn selected-model-changed! [srv model]
  (send-notification! srv "chat/selectedModelChanged" {:model model}))

(defn selected-agent-changed! [srv agent]
  (send-notification! srv "chat/selectedAgentChanged" {:agent agent}))
```

Note: `Model = string` and `ChatAgent = string` per the ECA protocol — these are plain strings like `"anthropic/claude-sonnet-4-6"` and `"code"`.

### 3. Model and agent picker (`:picking` mode)

A new state mode that captures the input area for interactive selection.

**Trigger:**
- `Ctrl+L` in `:ready` mode → model picker
- `/model` as the entire input text + Enter in `:ready` → model picker
- `/agent` as the entire input text + Enter in `:ready` → agent picker

**Picker state** (stored as `:picker` in app state):
```clojure
{:kind     :model       ; or :agent
 :list     <charm-list> ; charm.components.list — handles up/down/pgup/end
 :all      ["a" "b"]    ; unfiltered items (for rebuilding after backspace)
 :query    ""}          ; current filter string
```

**Keys in `:picking` mode:**

| Key | Action |
|-----|--------|
| Up / Down | Move cursor (passed to `list-update`) |
| Printable char | Append to `:query`, re-filter list |
| Backspace | Remove last char from `:query`, re-filter |
| Enter | Select highlighted item, send notification, return to `:ready` |
| Escape | Cancel, return to `:ready`, selection unchanged |

**Filtering:** case-insensitive substring match against `:all`. On every filter change, call `cl/set-items` to rebuild the list with only matching items and reset cursor to 0.

**On selection:** update `selected-model` (or `selected-agent`), clear `selected-variant` (ECA will send a new `config/updated` with the correct variant for the new model), send the notification, dissoc `:picker`, return to `:ready`.

**Using `charm.components.list`:** Confirmed available in charm 0.2.69. Create with `(cl/item-list items :height 8)`. Navigate with `cl/list-update`. Render with `cl/list-view`. Access selection with `cl/selected-item`.

### 4. View: render-picker

New private function in `view.clj`:

```clojure
(defn- render-picker [state]
  (let [{:keys [kind query list]} (:picker state)
        label (if (= kind :model) "model" "agent")]
    (str "Select " label " (type to filter): " query "\n"
         (divider (:width state)) "\n"
         (cl/list-view list))))
```

Rendered in the `view` fn's `input-area` when `(= :picking (:mode state))`. Replaces the text input. Chat area and status bar render normally.

### 5. Status bar: model / agent / variant

Update `render-status-bar` to show agent (when selected) and variant (when selected):

```
myproject  claude-sonnet-4-6  code  medium  1234tok  $0.002  SAFE
```

- Model: already shown
- Agent: show when `selected-agent` is non-nil
- Variant: show when `selected-variant` is non-nil, formatted as a separate field (not appended to model name, since model names are already long)

---

## State machine additions

```
:ready  --Ctrl+L / /model / /agent-->  :picking
:picking  --Enter-->  :ready  (+ notification cmd)
:picking  --Esc-->    :ready  (no change)
```

`:picking` is only reachable from `:ready`. If the user wants to change model while a prompt is running, they must wait or stop it first.

---

## Tests

All tests are pure unit tests — no real ECA process needed.

### New private vars to expose

```clojure
(def ^:private handle-eca-notification #'state/handle-eca-notification)  ; already exposed
```

No new private fns needed — all behaviour is reachable through `update-state` or `handle-eca-notification`.

Add `[charm.components.list :as cl]` to test requires (needed to inspect picker list state).

---

### Test: `config/updated` variants

```clojure
(deftest handle-config-updated-variants-test
  (testing "variants list stored"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:variants ["low" "medium" "high"]}}})]
      (is (= ["low" "medium" "high"] (:available-variants s)))))

  (testing "selectVariant sets selected-variant"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:selectVariant "medium"}}})]
      (is (= "medium" (:selected-variant s)))))

  (testing "selectVariant null clears selected-variant"
    (let [s0    (assoc (base-state) :selected-variant "high")
          [s _] (handle-eca-notification
                  s0
                  {:method "config/updated"
                   :params {:chat {:selectVariant nil}}})]
      (is (nil? (:selected-variant s)))))

  (testing "absent variants field does not overwrite existing"
    (let [s0    (assoc (base-state) :available-variants ["low" "high"])
          [s _] (handle-eca-notification
                  s0
                  {:method "config/updated"
                   :params {:chat {:models ["anthropic/claude-opus-4-7"]}}})]
      (is (= ["low" "high"] (:available-variants s))))))
```

### Test: Ctrl+L opens model picker

```clojure
(deftest ctrl-l-opens-model-picker-test
  (testing "Ctrl+L in :ready enters :picking with kind :model"
    (let [s0 (assoc (base-state)
                    :mode :ready
                    :available-models ["anthropic/claude-opus-4-7"
                                       "anthropic/claude-sonnet-4-6"])
          [s _] (state/update-state s0 (msg/key-press "l" :ctrl true))]
      (is (= :picking (:mode s)))
      (is (= :model (get-in s [:picker :kind])))
      (is (= 2 (cl/item-count (get-in s [:picker :list]))))
      (is (= "" (get-in s [:picker :query])))))

  (testing "Ctrl+L in :chatting is a no-op"
    (let [s0    (assoc (base-state) :mode :chatting)
          [s _] (state/update-state s0 (msg/key-press "l" :ctrl true))]
      (is (= :chatting (:mode s)))
      (is (nil? (:picker s)))))

  (testing "Ctrl+L with empty available-models is a no-op"
    (let [s0    (assoc (base-state) :mode :ready :available-models [])
          [s _] (state/update-state s0 (msg/key-press "l" :ctrl true))]
      (is (= :ready (:mode s))))))
```

### Test: `/model` and `/agent` commands

```clojure
(deftest slash-model-opens-picker-test
  (testing "/model as input + Enter opens model picker"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :available-models ["anthropic/claude-opus-4-7"])
                 (assoc :input (ti/set-value (ti/text-input) "/model")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (= :picking (:mode s)))
      (is (= :model (get-in s [:picker :kind])))))

  (testing "/agent as input + Enter opens agent picker"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :available-agents ["code" "plan"])
                 (assoc :input (ti/set-value (ti/text-input) "/agent")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (= :picking (:mode s)))
      (is (= :agent (get-in s [:picker :kind]))))))
```

### Test: picker filter

```clojure
(deftest picker-filter-test
  (testing "typing narrows list by case-insensitive substring"
    (let [s0 (assoc (base-state)
                    :mode :ready
                    :available-models ["anthropic/claude-opus-4-7"
                                       "anthropic/claude-sonnet-4-6"
                                       "openai/gpt-4o"])
          [s0-pick _] (state/update-state s0 (msg/key-press "l" :ctrl true))
          ;; type 'o' — should match opus and openai
          [s1 _] (state/update-state s0-pick (msg/key-press "o"))
          ;; type 'p' — should match opus only
          [s2 _] (state/update-state s1 (msg/key-press "p"))]
      (is (= "o" (get-in s1 [:picker :query])))
      (is (= 2 (cl/item-count (get-in s1 [:picker :list]))))
      (is (= "op" (get-in s2 [:picker :query])))
      (is (= 1 (cl/item-count (get-in s2 [:picker :list]))))))

  (testing "backspace removes last filter char"
    (let [s0 (assoc (base-state)
                    :mode :ready
                    :available-models ["anthropic/claude-opus-4-7"
                                       "openai/gpt-4o"])
          [s-pick _] (state/update-state s0 (msg/key-press "l" :ctrl true))
          [s1 _]     (state/update-state s-pick (msg/key-press "o"))
          [s2 _]     (state/update-state s1 (msg/key-press "p"))
          [s3 _]     (state/update-state s2 (msg/key-press :backspace))]
      (is (= "o" (get-in s3 [:picker :query])))
      (is (= 2 (cl/item-count (get-in s3 [:picker :list])))))))
```

### Test: picker Enter selects

```clojure
(deftest picker-enter-selects-model-test
  (testing "Enter in :picking :model updates selected-model, returns :ready, non-nil cmd"
    (with-redefs [protocol/selected-model-changed! (fn [& _] nil)]
      (let [s0 (assoc (base-state)
                      :mode :ready
                      :available-models ["anthropic/claude-opus-4-7"
                                         "anthropic/claude-sonnet-4-6"])
            [s-pick _] (state/update-state s0 (msg/key-press "l" :ctrl true))
            [s cmd]    (state/update-state s-pick (msg/key-press :enter))]
        (is (= :ready (:mode s)))
        (is (nil? (:picker s)))
        (is (= "anthropic/claude-opus-4-7" (:selected-model s)))
        (is (nil? (:selected-variant s)))))))

(deftest picker-enter-selects-agent-test
  (testing "Enter in :picking :agent updates selected-agent, returns :ready"
    (with-redefs [protocol/selected-agent-changed! (fn [& _] nil)]
      (let [s0 (-> (base-state)
                   (assoc :mode :ready :available-agents ["code" "plan"])
                   (assoc :input (ti/set-value (ti/text-input) "/agent")))
            [s-pick _] (state/update-state s0 (msg/key-press :enter))
            [s _]      (state/update-state s-pick (msg/key-press :enter))]
        (is (= :ready (:mode s)))
        (is (= "code" (:selected-agent s)))))))
```

### Test: picker Escape cancels

```clojure
(deftest picker-escape-cancels-test
  (testing "Escape in :picking returns to :ready, selection unchanged"
    (let [s0 (assoc (base-state)
                    :mode :ready
                    :selected-model "anthropic/claude-sonnet-4-6"
                    :available-models ["anthropic/claude-opus-4-7"
                                       "anthropic/claude-sonnet-4-6"])
          [s-pick _] (state/update-state s0 (msg/key-press "l" :ctrl true))
          [s _]      (state/update-state s-pick (msg/key-press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))
      (is (= "anthropic/claude-sonnet-4-6" (:selected-model s))))))
```

### Test: status bar agent and variant

```clojure
;; Add to the existing render-status-bar-test deftest in view_test.clj

(testing "selected-agent shown when non-nil"
  (let [bar (render-status-bar (assoc base :selected-agent "plan"))]
    (is (clojure.string/includes? bar "plan"))))

(testing "selected-variant shown when non-nil"
  (let [bar (render-status-bar (assoc base :selected-variant "medium"))]
    (is (clojure.string/includes? bar "medium"))))

(testing "no agent or variant when nil"
  (let [bar (render-status-bar base)]
    (is (not (clojure.string/includes? bar "plan")))
    (is (not (clojure.string/includes? bar "medium")))))
```

---

## Stopping Criteria

### Automated (`bb test`)

1. `bb test` passes — no Phase 1 regressions.
2. `config/updated` `variants` and `selectVariant` fields stored and cleared correctly (4 cases).
3. Ctrl+L in `:ready` opens model picker; no-op in `:chatting`; no-op with empty model list.
4. `/model` input + Enter opens model picker; `/agent` input + Enter opens agent picker.
5. Typing in picker filters by case-insensitive substring; backspace removes last filter char.
6. Enter in model picker updates `selected-model`, clears `selected-variant`, returns to `:ready`, cmd non-nil.
7. Enter in agent picker updates `selected-agent`, returns to `:ready`.
8. Escape in picker returns to `:ready` with selection unchanged.
9. Status bar shows `selected-agent` when non-nil; shows `selected-variant` when non-nil.

### Manual (run `bb run`)

10. Press Ctrl+L — picker appears, showing available models from `config/updated`.
11. Type to filter — list narrows immediately.
12. Select a model — status bar updates; next message uses the selected model (visible in log or ECA response).
13. After model change, `config/updated` arrives with `variants` — if non-empty, variant appears in status bar.
14. Type `/agent` + Enter — agent picker appears with available agents.
15. Select an agent — status bar updates; next message uses the selected agent.
16. Escape at any point in the picker — returns cleanly to `:ready`, prior selection unchanged.
17. Ctrl+L with only one available model — picker opens (or is a no-op if only the current model is listed — document the chosen behaviour).

---

## Notes

- **`/model` and `/agent` are not full slash commands yet** — they're special-cased in the Enter handler in `:ready` mode. The `/model` and `/agent` checks must come **before** the general "send to ECA" branch in the `cond`. If text is `/model` or `/agent`, enter picker mode and clear the input; do not send to ECA. Phase 4 (command system) will migrate them into the command registry.
- **Variant field uses `(contains? chat :variants)` not `(:variants chat)`** — ECA explicitly sends `[]` to mean "model has no variants". Truthy check `(:variants chat)` would silently ignore `[]`. Use `(contains? chat :variants)` so an explicit empty array correctly clears `available-variants`, same as `selectModel`/`selectAgent` use `(contains? chat :selectModel)`.
- **Variant selection is not interactive in this phase** — `selected-variant` is display-only, set by ECA via `config/updated`. A `/variant` command is a natural Phase 4 extension.
- **Ctrl+L key format:** use `(msg/key-press "l" :ctrl true)` — confirmed from charm.message API.
- **Protocol lesson from Phase 1:** `Model` and `ChatAgent` are plain strings per the protocol (`type Model = string`, `type ChatAgent = string`). Do not assume they are maps.
