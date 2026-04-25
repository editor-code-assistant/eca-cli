# Phase 1a: Reliable Core

> **No credentials required.** All tasks in this part are verifiable with ECA running but without a working provider login. Phase 1b covers login hardening and requires real credentials.

## Goal

Every part of the fundamental chat loop works correctly and visibly. ECA's full initialization lifecycle is handled. The user always knows what is happening and can always recover. No silent failures, no permanent stuck states.

---

## Context: What Is Currently Broken

Before any new work, several things are already wrong and must be fixed as part of this phase.

**`handle-eca-tick` returns `[state cmd]` but tests expect `state`.**  
The return type was changed to support login commands but the test suite was not updated. All `handle-eca-tick` tests currently fail. This is the first thing to fix.

**`status: 'login'` was silently dropped.**  
`send-chat-prompt!` did not forward `:status` in the queue message. This is fixed in code but untested.

**`$/progress`, `$/showMessage`, `config/updated` are unhandled.**  
These arrive via the queue and fall through to `:else` in `handle-eca-notification`, returning `[state nil]` with a wrong return signature (it was returning just `state` before, now `[state nil]` — but the notification handler was just changed to return tuples and existing callers may not match).

**No usage or model shown in status bar.**  
`:usage` is stored in state but the status bar shows `"…"` for the model until a `chat/prompt` completes. Token and cost fields are never displayed.

**`:reader-error` from the reader thread is unhandled.**  
If the ECA process dies or the pipe breaks, the reader thread puts `{:type :reader-error :error "..."}` on the queue. `handle-eca-tick` falls through the cond without handling it — the user sees nothing and the app appears frozen.

---

## State Changes

Add to `initial-state`:

```clojure
:init-tasks      {}   ; taskId -> {:title str :done? bool} for $/progress
:available-models []  ; vec of model maps from config/updated
:available-agents []  ; vec of agent maps from config/updated
:selected-model  nil  ; forced model from config/updated selectModel
:selected-agent  nil  ; forced agent from config/updated selectAgent
```

The `:usage` key already exists. Its shape (from protocol):

```
{:type          "usage"
 :sessionTokens number          ; total tokens this session
 :lastMessageCost string|nil    ; cost of last message e.g. "$0.003"
 :sessionCost    string|nil     ; total session cost e.g. "$0.012"
 :limit          {:context number :output number} | nil}
```

New item type `:system` for `$/showMessage` entries — rendered distinctly in the chat area.

---

## Implementation Tasks

Tasks are ordered by dependency. Each is a discrete, testable unit.

---

### Task 0: Fix broken `handle-eca-tick` tests

**What:** `handle-eca-tick` now returns `[state cmd]`. Update every test that calls it to destructure the result.

**Change:**
```clojure
;; Before
(let [s (handle-eca-tick (base-state) msgs)]
  (is (= "hi there" (:current-text s))))

;; After
(let [[s _] (handle-eca-tick (base-state) msgs)]
  (is (= "hi there" (:current-text s))))
```

Apply to all `handle-eca-tick-test` cases. Also update `base-state` in `state_test.clj` to include the new state keys: `:init-tasks {}`, `:available-models []`, `:available-agents []`, `:selected-model nil`, `:selected-agent nil`, `:pending-message nil`.

**Stopping check:** `bb test` passes with no failures.

---

### Task 1: Handle `$/progress` — startup task tracking

**Protocol:** `{:method "$/progress" :params {:type "start"|"finish" :taskId "..." :title "..."}}`

**In `handle-eca-notification`:**

```clojure
"$/progress"
(let [{:keys [type taskId title]} (:params notification)]
  [(case type
     "start"  (assoc-in state [:init-tasks taskId] {:title title :done? false})
     "finish" (if (contains? (:init-tasks state) taskId)
                (assoc-in state [:init-tasks taskId :done?] true)
                state)
     state)
   nil])
```

**In `view/render-status-bar`:** add a loading indicator when any init task is running:

```clojure
(defn- any-tasks-running? [init-tasks]
  (some #(not (:done? %)) (vals init-tasks)))
```

Show `"⏳"` in the status bar when `(any-tasks-running? (:init-tasks state))` is true. Remove it once all tasks complete.

> **Note:** `$/progress` notifications arrive in `:ready` mode, not `:connecting`. The drain loop only starts after `eca-initialized` is processed. There is no change needed to the `:connecting` view — the `⏳` indicator in the status bar (visible in `:ready` mode) is sufficient.

---

### Task 2: Handle `$/showMessage` — server messages

**Protocol:** `{:method "$/showMessage" :params {:message "..." :code number}}`  
The params shape follows the JSON-RPC `Error` type — it always has at least a `:message` field.

**In `handle-eca-notification`:**

```clojure
"$/showMessage"
(let [text (or (get-in notification [:params :message]) "Server message")]
  [(-> state
       (update :items conj {:type :system :text text})
       rebuild-lines)
   nil])
```

**In `view/render-item-lines`:** add a `:system` case:

```clojure
:system
[(str "⚠ " (:text item))]
```

---

### Task 3: Handle `config/updated` — model and agent list

**Protocol:** `{:method "config/updated" :params {:chat {:models [...] :agents [...] :selectModel ... :selectAgent ... :variants [...] :selectVariant ...}}}`

Fields are optional. A nil/absent field means no change since last update — ignore it, keep existing state.

**In `handle-eca-notification`:**

```clojure
"config/updated"
(let [chat (get-in notification [:params :chat])
      s'   (cond-> state
             (:models chat)                (assoc :available-models (:models chat))
             (:agents chat)                (assoc :available-agents (:agents chat))
             (contains? chat :selectModel) (assoc :selected-model (:selectModel chat))
             (contains? chat :selectAgent) (assoc :selected-agent (:selectAgent chat))
             (:welcomeMessage chat)        (update :items conj {:type :system
                                                                :text (:welcomeMessage chat)}))]
  [(if (:welcomeMessage chat) (rebuild-lines s') s') nil])
```

Note: use `contains?` (not `(:key map)`) for `selectModel`/`selectAgent` because a `nil` value is meaningful — it means "deselect". `welcomeMessage` uses truthiness (nil/absent = no message to show).

**In `view/render-status-bar`:** prefer `:selected-model` (from `config/updated`) over `:model` (from `chat/prompt` response) for the model name display, falling back gracefully:

```clojure
(or (get-in state [:selected-model :id])
    (:model state)
    "…")
```

---

### Task 4: Real usage display in status bar

**Current status bar** renders: `workspace  model  N tok  SAFE/TRUST` (note: current impl has a space before `tok`)

**New status bar** renders: `workspace  model  Ntok  $X.XX  ctx%  SAFE/TRUST` (space removed — aligns with test expectations)

Extract from `:usage` in state:

```clojure
(let [usage    (:usage state)
      tokens   (some-> usage :sessionTokens (str "tok"))  ; no space — was " tok" before
      cost     (some-> usage :sessionCost)
      ctx-pct  (when-let [l (:limit usage)]
                 (when (pos? (:context l))
                   (str (int (* 100 (/ (:sessionTokens usage) (:context l)))) "%")))]
  (str/join "  " (remove nil? [workspace model tokens cost ctx-pct trust])))
```

Show `nil` for all usage fields when `:usage` is nil (pre-first-message).

---

### Task 5: Handle `:reader-error`

**What:** The reader thread puts `{:type :reader-error :error "..."}` on the queue if the ECA pipe breaks. Currently falls through `handle-eca-tick` unhandled — the user sees nothing, the drain loop keeps running, the app appears frozen.

**In `handle-eca-tick`:**

```clojure
(= :reader-error (:type m))
[(-> s
     (assoc :mode :ready)
     (update :items conj {:type :system
                           :text (str "ECA disconnected: " (:error m))})
     (update :input ti/focus)
     rebuild-lines)
 nil]
```

The tuple return `[new-state nil]` is required — the reduce accumulator is `[state cmd]`. No cmd is issued; the drain loop continues (it will keep getting empty ticks).

---

## Tests

### `state_test.clj` — fixes and additions

```clojure
;; Add to private var declarations at top of file
(def ^:private handle-eca-notification  #'state/handle-eca-notification)
(def ^:private handle-providers-updated #'state/handle-providers-updated)
(def ^:private send-chat-prompt!        #'state/send-chat-prompt!)

;; Updated base-state — include all new fields
(defn- base-state []
  {:mode                  :chatting
   :trust                 false
   :chat-id               "chat1"
   :items                 []
   :current-text          ""
   :tool-calls            {}
   :pending-approval      nil
   :pending-message       nil
   :session-trusted-tools #{}
   :init-tasks            {}
   :available-models      []
   :available-agents      []
   :selected-model        nil
   :selected-agent        nil
   :input                 (ti/text-input)
   :chat-lines            []
   :scroll-offset         0
   :width                 80
   :height                24
   :model                 nil
   :usage                 nil
   :server                nil
   :opts                  {:workspace "/tmp/test"}})
```

#### Fix: `handle-eca-tick` return type

```clojure
(deftest handle-eca-tick-returns-tuple-test
  (testing "always returns [state cmd]"
    (let [result (handle-eca-tick (base-state) [])]
      (is (vector? result))
      (is (= 2 (count result)))))

  (testing "no login status — cmd is nil"
    (let [[_ cmd] (handle-eca-tick (base-state)
                                   [{:type :eca-prompt-response
                                     :chat-id "c1" :model "m1" :status "prompting"}])]
      (is (nil? cmd))))

  (testing "login status — cmd is non-nil"
    (let [[_ cmd] (handle-eca-tick (assoc (base-state) :pending-message "hi")
                                   [{:type :eca-prompt-response
                                     :chat-id "c1" :model "m1" :status "login"}])]
      (is (some? cmd)))))

;; Fix all existing handle-eca-tick-test assertions to destructure [s _]
(deftest handle-eca-tick-test
  (testing "reduces content notifications"
    (let [msgs [{:method "chat/contentReceived"
                 :params {:content {:type "text" :text "hi"}}}
                {:method "chat/contentReceived"
                 :params {:content {:type "text" :text " there"}}}]
          [s _] (handle-eca-tick (base-state) msgs)]
      (is (= "hi there" (:current-text s)))))

  (testing "prompt response sets chat-id and model"
    (let [[s _] (handle-eca-tick (base-state)
                                 [{:type :eca-prompt-response
                                   :chat-id "new-chat" :model "claude-opus-4-7"
                                   :status "prompting"}])]
      (is (= "new-chat" (:chat-id s)))
      (is (= "claude-opus-4-7" (:model s)))))

  (testing "unknown messages pass through unchanged"
    (let [base  (base-state)
          [s _] (handle-eca-tick base [{:type :unknown :data "x"}])]
      (is (= (dissoc base :input) (dissoc s :input))))))
```

#### New: `$/progress` tests

```clojure
(deftest handle-progress-test
  (testing "start notification adds running task"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "$/progress"
                   :params {:type "start" :taskId "models" :title "Loading models"}})]
      (is (= {:title "Loading models" :done? false}
             (get-in s [:init-tasks "models"])))))

  (testing "finish notification marks task done"
    (let [s0    (assoc-in (base-state) [:init-tasks "models"] {:title "Loading models" :done? false})
          [s _] (handle-eca-notification s0 {:method "$/progress"
                                              :params {:type "finish" :taskId "models" :title "Loading models"}})]
      (is (true? (get-in s [:init-tasks "models" :done?])))))

  (testing "unknown type is a no-op"
    (let [base  (base-state)
          [s _] (handle-eca-notification base {:method "$/progress"
                                               :params {:type "unknown" :taskId "x" :title "x"}})]
      (is (= base s)))))
```

#### New: `$/showMessage` tests

```clojure
(deftest handle-show-message-test
  (testing "adds :system item to items"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "$/showMessage"
                   :params {:message "Something went wrong" :code 1}})]
      (is (= 1 (count (:items s))))
      (is (= :system (:type (first (:items s)))))
      (is (= "Something went wrong" (:text (first (:items s)))))))

  (testing "handles missing message field gracefully"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "$/showMessage" :params {}})]
      (is (= 1 (count (:items s))))
      (is (= :system (:type (first (:items s))))))))
```

#### New: `config/updated` tests

```clojure
(deftest handle-config-updated-test
  (testing "stores models list"
    (let [models [{:id "anthropic/claude-sonnet-4-6"}]
          [s _]  (handle-eca-notification
                   (base-state)
                   {:method "config/updated"
                    :params {:chat {:models models}}})]
      (is (= models (:available-models s)))))

  (testing "stores agents list"
    (let [agents [{:id "my-agent" :name "My Agent"}]
          [s _]  (handle-eca-notification
                   (base-state)
                   {:method "config/updated"
                    :params {:chat {:agents agents}}})]
      (is (= agents (:available-agents s)))))

  (testing "selectModel forces model selection"
    (let [model {:id "anthropic/claude-opus-4-7"}
          [s _] (handle-eca-notification
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:selectModel model}}})]
      (is (= model (:selected-model s)))))

  (testing "selectModel nil clears selection"
    (let [s0    (assoc (base-state) :selected-model {:id "some-model"})
          [s _] (handle-eca-notification
                  s0
                  {:method "config/updated"
                   :params {:chat {:selectModel nil}}})]
      (is (nil? (:selected-model s)))))

  (testing "selectAgent nil clears selection"
    (let [s0    (assoc (base-state) :selected-agent {:id "some-agent"})
          [s _] (handle-eca-notification
                  s0
                  {:method "config/updated"
                   :params {:chat {:selectAgent nil}}})]
      (is (nil? (:selected-agent s)))))

  (testing "welcomeMessage adds system item"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:welcomeMessage "Welcome! How can I help?"}}})]​
      (is (= 1 (count (:items s))))
      (is (= :system (:type (first (:items s)))))
      (is (= "Welcome! How can I help?" (:text (first (:items s)))))))

  (testing "absent fields do not overwrite existing state"
    (let [s0    (assoc (base-state)
                       :available-models [{:id "existing"}]
                       :available-agents [{:id "existing-agent"}])
          [s _] (handle-eca-notification
                  s0
                  {:method "config/updated"
                   :params {:chat {:models [{:id "new-model"}]}}})]
      (is (= [{:id "new-model"}] (:available-models s)))
      (is (= [{:id "existing-agent"}] (:available-agents s)))))

  (testing "nil chat field is a no-op"
    (let [base  (base-state)
          [s _] (handle-eca-notification base {:method "config/updated" :params {}})]
      (is (= base s)))))
```

#### New: `:reader-error` test

```clojure
(deftest handle-reader-error-test
  (testing "reader error adds system message and returns to :ready"
    (let [[s _] (handle-eca-tick
                  (base-state)
                  [{:type :reader-error :error "Broken pipe"}])]
      (is (= :ready (:mode s)))
      (is (= 1 (count (:items s))))
      (is (= :system (:type (first (:items s)))))
      (is (clojure.string/includes? (:text (first (:items s))) "Broken pipe")))))
```

#### New: login flow tests

```clojure
(deftest login-status-triggers-cmd-test
  (testing "status=login returns a non-nil cmd"
    (let [[_ cmd] (handle-eca-tick
                    (assoc (base-state) :pending-message "original question")
                    [{:type :eca-prompt-response
                      :chat-id "c1" :model "m1" :status "login"}])]
      (is (some? cmd))))

  (testing "status=prompting returns nil cmd"
    (let [[_ cmd] (handle-eca-tick
                    (base-state)
                    [{:type :eca-prompt-response
                      :chat-id "c1" :model "m1" :status "prompting"}])]
      (is (nil? cmd)))))

(deftest handle-providers-updated-test
  (let [login-state {:provider "anthropic"
                     :action   {:action "device-code" :url "https://example.com" :code "ABCD" :message "Enter code"}
                     :field-idx 0 :collected {} :pending-message "original question"}]

    (testing "auth success in :login mode re-sends pending — returns non-nil cmd"
      (let [s0    (assoc (base-state)
                         :mode :login
                         :login login-state
                         :pending-message "original question"
                         :server {:queue (java.util.concurrent.LinkedBlockingQueue.)})
            [s cmd] (handle-providers-updated s0 {:id "anthropic" :auth {:status "authenticated"}})]
        (is (= :chatting (:mode s)))
        (is (nil? (:login s)))
        (is (some? cmd))))

    (testing "auth success for wrong provider — no-op"
      (let [s0    (assoc (base-state) :mode :login :login login-state)
            [s _] (handle-providers-updated s0 {:id "openai" :auth {:status "authenticated"}})]
        (is (= :login (:mode s)))))

    (testing "auth update when not in :login mode — no-op"
      (let [s0    (base-state) ; mode is :chatting
            [s _] (handle-providers-updated s0 {:id "anthropic" :auth {:status "authenticated"}})]
        (is (= :chatting (:mode s)))))))

(deftest eca-login-action-test
  (testing "nil action → :ready with error message"
    (let [[new-state _] (state/update-state
                          (assoc (base-state) :mode :chatting)
                          {:type :eca-login-action :provider "anthropic" :action nil :pending-message "hi"})]
      (is (= :ready (:mode new-state)))
      (is (some #(= :system (:type %)) (:items new-state)))))

  (testing "done action → :chatting"
    (with-redefs [send-chat-prompt! (fn [& _] nil)]
      (let [[new-state _] (state/update-state
                            (assoc (base-state) :mode :chatting :server {:queue (java.util.concurrent.LinkedBlockingQueue.)})
                            {:type :eca-login-action :provider "anthropic"
                             :action {:action "done"} :pending-message "hi"})]
        (is (= :chatting (:mode new-state))))))

  (testing "input action → :login mode with input focused"
    (let [[new-state _] (state/update-state
                          (assoc (base-state) :mode :chatting)
                          {:type :eca-login-action :provider "anthropic"
                           :action {:action "input"
                                    :fields [{:key "api-key" :label "API Key" :type "secret"}]}
                           :pending-message "hi"})]
      (is (= :login (:mode new-state)))
      (is (= "anthropic" (get-in new-state [:login :provider])))
      (is (= "hi" (get-in new-state [:login :pending-message]))))))
```

### `view_test.clj` — additions

```clojure
;; Add to existing render-item-lines-test
(testing ":system item"
  (let [lines (view/render-item-lines {:type :system :text "Connection lost"} 80)]
    (is (= 1 (count lines)))
    (is (clojure.string/includes? (first lines) "Connection lost"))
    (is (clojure.string/includes? (first lines) "⚠"))))
```

(deftest render-status-bar-test
  (let [base {:opts {:workspace "/home/user/myproject"}
              :model "claude-sonnet-4-6"
              :selected-model nil
              :trust false
              :usage nil
              :init-tasks {}}]

    (testing "no usage — tokens and cost absent"
      (let [bar (view/render-status-bar base)]
        (is (clojure.string/includes? bar "myproject"))
        (is (clojure.string/includes? bar "SAFE"))
        (is (not (clojure.string/includes? bar "tok")))))

    (testing "with usage — shows tokens"
      (let [bar (view/render-status-bar
                  (assoc base :usage {:sessionTokens 1234 :sessionCost "$0.002"}))]
        (is (clojure.string/includes? bar "1234tok"))
        (is (clojure.string/includes? bar "$0.002"))))

    (testing "selected-model takes precedence over :model"
      (let [bar (view/render-status-bar
                  (assoc base :selected-model {:id "anthropic/claude-opus-4-7"}))]
        (is (clojure.string/includes? bar "anthropic/claude-opus-4-7"))
        (is (not (clojure.string/includes? bar "claude-sonnet-4-6")))))

    (testing "init tasks running — shows loading indicator"
      (let [bar (view/render-status-bar
                  (assoc base :init-tasks {"models" {:title "Loading models" :done? false}}))]
        (is (clojure.string/includes? bar "⏳"))))

    (testing "all init tasks done — no loading indicator"
      (let [bar (view/render-status-bar
                  (assoc base :init-tasks {"models" {:title "Loading models" :done? true}}))]
        (is (not (clojure.string/includes? bar "⏳")))))))

(deftest render-login-test
  (let [base-login-state {:opts {:workspace "/tmp"} :trust false :model nil
                          :selected-model nil :usage nil :init-tasks {}
                          :items [] :current-text "" :chat-lines []
                          :scroll-offset 0 :width 80 :height 24
                          :input (ti/text-input)}]

    (testing "input action shows provider name and field label"
      (let [s   (assoc base-login-state
                        :mode :login
                        :login {:provider "anthropic"
                                :action {:action "input"
                                         :fields [{:key "api-key" :label "API Key" :type "secret"}]}
                                :field-idx 0 :collected {} :pending-message "hi"})
            txt (view/render-login s)]
        (is (clojure.string/includes? txt "anthropic"))
        (is (clojure.string/includes? txt "API Key"))))

    (testing "choose-method action lists methods with numbers"
      (let [s   (assoc base-login-state
                        :mode :login
                        :login {:provider "openai"
                                :action {:action "choose-method"
                                         :methods [{:key "api-key" :label "API Key"}
                                                   {:key "oauth" :label "OAuth"}]}
                                :field-idx 0 :collected {} :pending-message "hi"})
            txt (view/render-login s)]
        (is (clojure.string/includes? txt "[1]"))
        (is (clojure.string/includes? txt "[2]"))
        (is (clojure.string/includes? txt "API Key"))
        (is (clojure.string/includes? txt "OAuth"))))

    (testing "device-code action shows code and url"
      (let [s   (assoc base-login-state
                        :mode :login
                        :login {:provider "github"
                                :action {:action "device-code"
                                         :url "https://github.com/login/device"
                                         :code "ABCD-1234"
                                         :message "Enter code at URL"}
                                :field-idx 0 :collected {} :pending-message "hi"})
            txt (view/render-login s)]
        (is (clojure.string/includes? txt "ABCD-1234"))
        (is (clojure.string/includes? txt "https://github.com/login/device"))))

    (testing "authorize action shows url and message"
      (let [s   (assoc base-login-state
                        :mode :login
                        :login {:provider "google"
                                :action {:action "authorize"
                                         :url "https://accounts.google.com/o/oauth2/auth?..."
                                         :message "Open URL in browser to authorize"}
                                :field-idx 0 :collected {} :pending-message "hi"})
            txt (view/render-login s)]
        (is (clojure.string/includes? txt "https://accounts.google.com"))
        (is (clojure.string/includes? txt "browser"))))))
```

---

## Stopping Criteria

Phase 1a is complete when **all** of the following are true.

### Automated (verified by `bb test`)

1. `bb test` passes with zero failures and zero errors.
2. `handle-eca-tick` return type tests pass — the function returns `[state cmd]`.
3. `$/progress` tests pass — start/finish transitions update `:init-tasks` correctly.
4. `$/showMessage` tests pass — server messages become `:system` items.
5. `config/updated` tests pass — models/agents stored; `selectModel nil` correctly clears selection; absent fields do not overwrite existing state.
6. `:reader-error` test passes — reader disconnection surfaces as a system message and returns to `:ready`.
7. Login state machine unit tests pass — nil action → error+`:ready`, done action → `:chatting`, input action → `:login` mode with correct pending message.
8. `handle-providers-updated` unit tests pass — scoped to correct mode/provider; triggers cmd only when appropriate; no-op for wrong provider or wrong mode.
9. Status bar tests pass — tokens, cost, loading indicator, and `selected-model` precedence all render correctly.
10. Login view tests pass — all four action types (`input`, `choose-method`, `device-code`, `authorize`) render expected content.

### Manual (verified by running the app, no credentials required)

11. On `bb run --nrepl 7888`, the status bar shows `⏳` while ECA initialises and the indicator disappears once all init tasks complete.
12. A `$/showMessage` notification (triggered by a misconfigured or unauthenticated provider on startup) appears in the chat area with the `⚠` prefix.
13. When a model is forced via `config/updated`, the status bar reflects the forced model name.
14. Pressing Escape in `:chatting` mode — regardless of whether `chat-id` is set — always returns to `:ready`.
15. Pressing Escape during any login action type (`input`, `device-code`, `authorize`, `choose-method`) immediately returns to `:ready` with the input focused.
16. If the ECA process is killed while the app is running, the app shows a disconnection message in the chat area rather than hanging silently.

---

> **Next:** [Phase 1b — Login Hardening](phase-1b-login-hardening.md) covers end-to-end login verification and requires working credentials.
