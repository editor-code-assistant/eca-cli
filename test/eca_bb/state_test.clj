(ns eca-bb.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-bb.chat :as chat]
            [eca-bb.login :as login]
            [eca-bb.protocol :as protocol]
            [eca-bb.sessions :as sessions]
            [eca-bb.state :as state]))

(def ^:private flush-current-text       chat/flush-current-text)
(def ^:private upsert-tool-call         chat/upsert-tool-call)
(def ^:private handle-content           chat/handle-content)
(def ^:private handle-eca-tick          #'state/handle-eca-tick)
(def ^:private handle-eca-notification  #'state/handle-eca-notification)
(def ^:private handle-providers-updated login/handle-providers-updated)

(defn- base-state []
  {:mode                  :chatting
   :trust                 false
   :chat-id               "chat1"
   :chat-title            nil
   :items                 []
   :current-text          ""
   :tool-calls            {}
   :pending-approval      nil
   :pending-message       nil
   :session-trusted-tools #{}
   :init-tasks            {}
   :available-models      []
   :available-agents      []
   :available-variants    []
   :selected-model        nil
   :selected-agent        nil
   :selected-variant      nil
   :input                 (ti/text-input)
   :input-history         []
   :history-idx           nil
   :focus-path            nil
   :subagent-chats        {}
   :chat-lines            []
   :scroll-offset         0
   :width                 80
   :height                24
   :model                 nil
   :usage                 nil
   :server                nil
   :opts                  {:workspace "/tmp/test"}})

(deftest flush-current-text-test
  (testing "non-empty text appended and cleared"
    (let [s (flush-current-text (assoc (base-state) :current-text "hello"))]
      (is (= "" (:current-text s)))
      (is (= 1 (count (:items s))))
      (is (= {:type :assistant-text :text "hello"} (first (:items s))))))

  (testing "empty text is a no-op"
    (let [s (base-state)]
      (is (= s (flush-current-text s))))))

(deftest upsert-tool-call-test
  (testing "new tool call inserted"
    (let [s (upsert-tool-call (base-state) {:id "tc1" :name "read_file" :state :preparing})]
      (is (= :preparing (get-in s [:tool-calls "tc1" :state])))
      (is (some #(= "tc1" (:id %)) (:items s)))))

  (testing "existing tool call merged"
    (let [s0 (upsert-tool-call (base-state) {:id "tc1" :name "read_file" :state :preparing})
          s1 (upsert-tool-call s0 {:id "tc1" :state :called :error? false})]
      (is (= :called (get-in s1 [:tool-calls "tc1" :state])))
      (is (= "read_file" (get-in s1 [:tool-calls "tc1" :name])))
      (is (= 1 (count (filter #(= "tc1" (:id %)) (:items s1))))))))

(deftest handle-content-text-test
  (let [s (handle-content (base-state) {:content {:type "text" :text "hello"}})]
    (is (= "hello" (:current-text s))))

  (testing "appends to existing current-text"
    (let [s (-> (base-state)
                (assoc :current-text "he")
                (handle-content {:content {:type "text" :text "llo"}}))]
      (is (= "hello" (:current-text s))))))

(deftest handle-content-progress-test
  (testing "finished — flushes text, mode :ready"
    (let [s (handle-content
              (assoc (base-state) :current-text "streamed")
              {:content {:type "progress" :state "finished"}})]
      (is (= :ready (:mode s)))
      (is (= "" (:current-text s)))
      (is (some #(= "streamed" (:text %)) (:items s)))))

  (testing "non-finished — state unchanged"
    (let [base (assoc (base-state) :mode :chatting)
          s    (handle-content base {:content {:type "progress" :state "running"}})]
      (is (= :chatting (:mode s)))))

  (testing "finished with echo-buf not matching echo-pending — flushed as content, mode :ready"
    (let [s (handle-content
              (assoc (base-state) :current-text "something else")
              {:content {:type "progress" :state "finished"}})]
      (is (= :ready (:mode s)))
      (is (some #(= "something else" (:text %)) (:items s))))))

(deftest handle-content-tool-call-prepare-test
  (let [s (handle-content (base-state)
                           {:content {:type    "toolCallPrepare"
                                      :id      "tc1"
                                      :name    "read_file"
                                      :server  "local"
                                      :summary "read src/foo.clj"}})]
    (is (= :preparing (get-in s [:tool-calls "tc1" :state])))
    (is (some #(= "tc1" (:id %)) (:items s)))))

(deftest handle-content-tool-call-run-test
  (testing "trust=true — auto-approves, no mode change"
    (with-redefs [protocol/approve-tool! (fn [& _] nil)]
      (let [s (handle-content
                (assoc (base-state) :trust true)
                {:content {:type "toolCallRun" :id "tc1" :name "read_file"
                           :server "local" :manualApproval true}})]
        (is (not= :approving (:mode s)))
        (is (nil? (:pending-approval s))))))

  (testing "manualApproval=true trust=false — enters approving mode"
    (let [s (handle-content
              (base-state)
              {:content {:type "toolCallRun" :id "tc1" :name "read_file"
                         :server "local" :manualApproval true}})]
      (is (= :approving (:mode s)))
      (is (= {:chat-id "chat1" :tool-call-id "tc1"} (:pending-approval s)))))

  (testing "manualApproval=false — auto-approves regardless of trust"
    (with-redefs [protocol/approve-tool! (fn [& _] nil)]
      (let [s (handle-content
                (base-state)
                {:content {:type "toolCallRun" :id "tc1" :name "read_file"
                           :server "local" :manualApproval false}})]
        (is (not= :approving (:mode s)))))))

(deftest handle-content-tool-called-test
  (let [s (handle-content (base-state)
                           {:content {:type "toolCalled" :id "tc1" :name "read_file"
                                      :server "local" :error false}})]
    (is (= :called (get-in s [:tool-calls "tc1" :state])))))

(deftest handle-content-tool-call-rejected-test
  (let [s (handle-content (base-state)
                           {:content {:type "toolCallRejected" :id "tc1" :name "read_file"
                                      :server "local"}})]
    (is (= :rejected (get-in s [:tool-calls "tc1" :state])))))

;; --- handle-eca-tick (return type fixed: now [state cmd]) ---

(deftest handle-eca-tick-test
  (testing "always returns [state cmd] tuple"
    (let [result (handle-eca-tick (base-state) [])]
      (is (vector? result))
      (is (= 2 (count result)))))

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

  (testing "non-login status — cmd is nil"
    (let [[_ cmd] (handle-eca-tick (base-state)
                                   [{:type :eca-prompt-response
                                     :chat-id "c1" :model "m1" :status "prompting"}])]
      (is (nil? cmd))))

  (testing "login status — cmd is non-nil"
    (let [[_ cmd] (handle-eca-tick (assoc (base-state) :pending-message "hi")
                                   [{:type :eca-prompt-response
                                     :chat-id "c1" :model "m1" :status "login"}])]
      (is (some? cmd))))

  (testing "unknown messages pass through unchanged"
    (let [base  (base-state)
          [s _] (handle-eca-tick base [{:type :unknown :data "x"}])]
      (is (= (dissoc base :input) (dissoc s :input))))))

;; --- $/progress ---

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

  (testing "finish for unknown taskId is a no-op"
    (let [base  (base-state)
          [s _] (handle-eca-notification base {:method "$/progress"
                                               :params {:type "finish" :taskId "unknown" :title "x"}})]
      (is (= base s))))

  (testing "unknown progress type is a no-op"
    (let [base  (base-state)
          [s _] (handle-eca-notification base {:method "$/progress"
                                               :params {:type "other" :taskId "x" :title "x"}})]
      (is (= base s)))))

;; --- $/showMessage ---

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

;; --- subagent routing ---

(deftest subagent-content-suppressed-test
  (testing "contentReceived with parentChatId and no registered parent falls through (text → current-text)"
    (let [base (assoc (base-state) :mode :chatting)
          [s _] (handle-eca-notification
                  base
                  {:method "chat/contentReceived"
                   :params {:chatId       "subagent-chat-42"
                            :parentChatId "chat1"
                            :role         "assistant"
                            :content      {:type "text" :text "verbatim file contents..."}}})]
      (is (= "verbatim file contents..." (:current-text s)))
      (is (empty? (:items s)))))

  (testing "contentReceived without parentChatId is processed normally"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId  "chat1"
                            :role    "assistant"
                            :content {:type "text" :text "hello"}}})]
      (is (= "hello" (:current-text s))))))

(deftest handle-reader-error-test
  (testing "reader error adds system message and returns to :ready"
    (let [[s _] (handle-eca-tick
                  (base-state)
                  [{:type :reader-error :error "Broken pipe"}])]
      (is (= :ready (:mode s)))
      (is (= 1 (count (:items s))))
      (is (= :system (:type (first (:items s)))))
      (is (clojure.string/includes? (:text (first (:items s))) "Broken pipe")))))

;; --- Login state machine ---

(deftest login-status-triggers-cmd-test
  (testing "status=login returns a non-nil cmd"
    (let [[_ cmd] (handle-eca-tick
                    (assoc (base-state) :pending-message "hi")
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
                     :action   {:action "device-code" :url "https://example.com"
                                :code "ABCD" :message "Enter code"}
                     :field-idx 0 :collected {} :pending-message "original question"}]

    (testing "auth success in :login mode returns :chatting and non-nil cmd"
      (let [s0      (assoc (base-state)
                           :mode :login
                           :login login-state
                           :pending-message "original question"
                           :server {:queue (java.util.concurrent.LinkedBlockingQueue.)})
            [s cmd] (handle-providers-updated s0 {:id "anthropic" :auth {:status "authenticated"}})]
        (is (= :chatting (:mode s)))
        (is (nil? (:login s)))
        (is (some? cmd))))

    (testing "expiring auth status also triggers success"
      (let [s0      (assoc (base-state)
                           :mode :login
                           :login login-state
                           :pending-message "original question"
                           :server {:queue (java.util.concurrent.LinkedBlockingQueue.)})
            [s _] (handle-providers-updated s0 {:id "anthropic" :auth {:status "expiring"}})]
        (is (= :chatting (:mode s)))))

    (testing "auth success for wrong provider — no-op"
      (let [s0    (assoc (base-state) :mode :login :login login-state)
            [s _] (handle-providers-updated s0 {:id "openai" :auth {:status "authenticated"}})]
        (is (= :login (:mode s)))))

    (testing "auth update when not in :login mode — no-op"
      (let [s0    (base-state)
            [s _] (handle-providers-updated s0 {:id "anthropic" :auth {:status "authenticated"}})]
        (is (= :chatting (:mode s)))))))

(deftest eca-login-action-test
  (testing "nil action → :ready with error message"
    (let [[new-state _] (state/update-state
                          (assoc (base-state) :mode :chatting)
                          {:type :eca-login-action :provider "anthropic"
                           :action nil :pending-message "hi"})]
      (is (= :ready (:mode new-state)))
      (is (some #(= :system (:type %)) (:items new-state)))))

  (testing "done action with no pending → :chatting"
    (let [[new-state _] (state/update-state
                          (assoc (base-state) :mode :chatting)
                          {:type :eca-login-action :provider "anthropic"
                           :action {:action "done"} :pending-message nil})]
      (is (= :chatting (:mode new-state)))))

  (testing "input action → :login mode with correct provider and pending"
    (let [[new-state _] (state/update-state
                          (assoc (base-state) :mode :chatting)
                          {:type :eca-login-action :provider "anthropic"
                           :action {:action "input"
                                    :fields [{:key "api-key" :label "API Key" :type "secret"}]}
                           :pending-message "hi"})]
      (is (= :login (:mode new-state)))
      (is (= "anthropic" (get-in new-state [:login :provider])))
      (is (= "hi" (get-in new-state [:login :pending-message])))))

  (testing "device-code action → :login mode, input blurred"
    (let [[new-state _] (state/update-state
                          (assoc (base-state) :mode :chatting)
                          {:type :eca-login-action :provider "github"
                           :action {:action "device-code"
                                    :url "https://github.com/login/device"
                                    :code "ABCD-1234"
                                    :message "Enter code"}
                           :pending-message "hi"})]
      (is (= :login (:mode new-state)))
      (is (= "github" (get-in new-state [:login :provider]))))))

;; --- Phase 1b: login hardening ---

(deftest login-timeout-test
  (testing "nil action → :ready, error item contains 'timed out', :login cleared"
    (let [[s _] (state/update-state
                  (assoc (base-state) :mode :chatting)
                  {:type :eca-login-action
                   :provider "anthropic"
                   :action nil
                   :pending-message "original"})]
      (is (= :ready (:mode s)))
      (is (nil? (:login s)))
      (is (some #(and (= :system (:type %))
                      (clojure.string/includes? (:text %) "timed out"))
               (:items s))))))

(deftest login-cancel-cleans-state-test
  (testing "Escape in :login mode → :ready, :login nil, :pending-message nil"
    (let [login-state {:provider "anthropic"
                       :action {:action "device-code"
                                :url "https://example.com"
                                :code "ABCD"
                                :message "Enter code"}
                       :field-idx 0 :collected {}
                       :pending-message "original question"}
          s0 (assoc (base-state) :mode :login :login login-state
                                 :pending-message "original question")
          [s _] (state/update-state s0 (msg/key-press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:login s)))
      (is (nil? (:pending-message s))))))

(deftest login-re-trigger-test
  (testing "second login status on established session starts fresh login cmd"
    (let [s0       (assoc (base-state)
                          :chat-id "existing-chat"
                          :pending-message "second question")
          [s cmd]  (handle-eca-tick s0 [{:type :eca-prompt-response
                                         :chat-id "existing-chat"
                                         :model "claude-sonnet-4-6"
                                         :status "login"}])]
      (is (= "existing-chat" (:chat-id s)))
      (is (some? cmd)))))

(deftest providers-updated-wrong-mode-test
  (testing "providers/updated in :chatting mode is a no-op — state identical"
    (let [s0    (assoc (base-state) :mode :chatting)
          [s _] (handle-eca-notification
                  s0
                  {:method "providers/updated"
                   :params {:id "anthropic" :auth {:status "authenticated"}}})]
      (is (= :chatting (:mode s)))
      (is (= s0 s)))))

(deftest submit-login-multi-field-test
  (testing "multi-field input: first enter advances field-idx and collects value"
    (let [login-state {:provider "anthropic"
                       :action {:action "input"
                                :fields [{:key "api-key" :label "API Key" :type "secret"}
                                         {:key "org-id"  :label "Org ID"  :type "text"}]}
                       :field-idx 0 :collected {}
                       :pending-message "hi"}
          s0 (-> (base-state)
                 (assoc :mode :login :login login-state)
                 (assoc :input (ti/set-value (ti/text-input) "sk-abc123")))
          [s1 cmd1] (state/update-state s0 (msg/key-press :enter))]
      (is (nil? cmd1))
      (is (= 1 (get-in s1 [:login :field-idx])))
      (is (= "sk-abc123" (get-in s1 [:login :collected "api-key"])))

      (testing "second enter submits and returns non-nil cmd"
        (let [s1' (assoc s1 :input (ti/set-value (:input s1) "my-org"))
              [_ cmd2] (state/update-state s1' (msg/key-press :enter))]
          (is (some? cmd2))))))

;; --- Phase 2: model & agent identity ---

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

  (testing "Ctrl+L with empty available-models shows error"
    (let [s0    (assoc (base-state) :mode :ready :available-models [])
          [s _] (state/update-state s0 (msg/key-press "l" :ctrl true))]
      (is (= :ready (:mode s)))
      (is (some #(and (= :system (:type %))
                      (clojure.string/includes? (:text %) "No models"))
                (:items s))))))

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

(deftest picker-filter-test
  (testing "typing narrows list by case-insensitive substring"
    ;; "opus-small" and "cohere-cmd" both contain 'o'; "llama-3" does not
    ;; Only "opus-small" contains "op"
    (let [s0 (assoc (base-state)
                    :mode :ready
                    :available-models ["opus-small" "cohere-cmd" "llama-3"])
          [s0-pick _] (state/update-state s0 (msg/key-press "l" :ctrl true))
          [s1 _]      (state/update-state s0-pick (msg/key-press "o"))
          [s2 _]      (state/update-state s1 (msg/key-press "p"))]
      (is (= "o" (get-in s1 [:picker :query])))
      (is (= 2 (cl/item-count (get-in s1 [:picker :list]))))
      (is (= "op" (get-in s2 [:picker :query])))
      (is (= 1 (cl/item-count (get-in s2 [:picker :list]))))))

  (testing "backspace removes last filter char"
    (let [s0      (assoc (base-state)
                         :mode :ready
                         :available-models ["opus-small" "cohere-cmd"])
          [s-pick _] (state/update-state s0 (msg/key-press "l" :ctrl true))
          [s1 _]     (state/update-state s-pick (msg/key-press "o"))
          [s2 _]     (state/update-state s1 (msg/key-press "p"))
          [s3 _]     (state/update-state s2 (msg/key-press :backspace))]
      (is (= "o" (get-in s3 [:picker :query])))
      (is (= 2 (cl/item-count (get-in s3 [:picker :list])))))))

(deftest picker-enter-selects-model-test
  (testing "Enter in :picking :model updates selected-model, clears variant, returns :ready"
    (with-redefs [protocol/selected-model-changed! (fn [& _] nil)]
      (let [s0 (assoc (base-state)
                      :mode :ready
                      :selected-variant "medium"
                      :available-models ["anthropic/claude-opus-4-7"
                                         "anthropic/claude-sonnet-4-6"])
            [s-pick _] (state/update-state s0 (msg/key-press "l" :ctrl true))
            [s _]      (state/update-state s-pick (msg/key-press :enter))]
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

;; --- Phase 3: Session Continuity ---

(deftest slash-new-clears-state-test
  (testing "/new with no chat-id is a no-op (already fresh)"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready :chat-id nil)
                 (assoc :input (ti/set-value (ti/text-input) "/new")))
          [s cmd] (state/update-state s0 (msg/key-press :enter))]
      (is (= :ready (:mode s)))
      (is (nil? cmd))))

  (testing "/new with chat-id clears state and returns delete cmd"
    (with-redefs [sessions/save-chat-id! (fn [& _] nil)]
      (let [s0 (-> (base-state)
                   (assoc :mode :ready
                          :chat-id "old-chat"
                          :items [{:type :user :text "hello"}])
                   (assoc :input (ti/set-value (ti/text-input) "/new")))
            [s cmd] (state/update-state s0 (msg/key-press :enter))]
        (is (= :ready (:mode s)))
        (is (nil? (:chat-id s)))
        (is (nil? (:chat-title s)))
        (is (empty? (:items s)))
        (is (some? cmd))))))

(deftest slash-sessions-fires-list-cmd-test
  (testing "/sessions in :ready fires chat/list cmd"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (assoc :input (ti/set-value (ti/text-input) "/sessions")))
          [s cmd] (state/update-state s0 (msg/key-press :enter))]
      (is (= :ready (:mode s)))
      (is (some? cmd)))))

(deftest chat-list-loaded-enters-picking-test
  (testing ":chat-list-loaded with results enters :picking :session"
    (let [s0    (assoc (base-state) :mode :ready)
          chats [{:id "chat-abc" :title "Project A" :messageCount 10}
                 {:id "chat-def" :title "Project B" :messageCount 5}]
          [s _] (state/update-state s0 {:type :chat-list-loaded :chats chats})]
      (is (= :picking (:mode s)))
      (is (= :session (get-in s [:picker :kind])))
      (is (= 2 (count (get-in s [:picker :all]))))))

  (testing ":chat-list-loaded with empty list still enters :picking"
    (let [s0    (assoc (base-state) :mode :ready)
          [s _] (state/update-state s0 {:type :chat-list-loaded :chats []})]
      (is (= :picking (:mode s)))
      (is (= :session (get-in s [:picker :kind])))
      (is (empty? (get-in s [:picker :all]))))))

(deftest session-picker-enter-fires-open-cmd-test
  (testing "Enter in session picker returns :ready and fires open-chat cmd"
    (with-redefs [sessions/save-chat-id! (fn [& _] nil)]
      (let [s0    (assoc (base-state) :mode :ready)
            chats [{:id "chat-abc" :title "My Chat" :messageCount 3}]
            [s-pick _] (state/update-state s0 {:type :chat-list-loaded :chats chats})
            [s cmd]    (state/update-state s-pick (msg/key-press :enter))]
        (is (= :ready (:mode s)))
        (is (nil? (:picker s)))
        (is (some? cmd))))))

(deftest session-picker-escape-test
  (testing "Esc in session picker returns to :ready, no change"
    (let [s0    (assoc (base-state) :mode :ready)
          chats [{:id "chat-abc" :title "My Chat" :messageCount 3}]
          [s-pick _] (state/update-state s0 {:type :chat-list-loaded :chats chats})
          [s _]      (state/update-state s-pick (msg/key-press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s))))))

;; ---------------------------------------------------------------------------
;; Phase 4 — command system
;; ---------------------------------------------------------------------------

(deftest slash-opens-command-picker-test
  (testing "typing '/' in empty :ready input enters :picking :command"
    (let [s0 (assoc (base-state) :mode :ready)
          [s _] (state/update-state s0 (msg/key-press "/"))]
      (is (= :picking (:mode s)))
      (is (= :command (get-in s [:picker :kind])))
      (is (= "" (get-in s [:picker :query])))
      (is (= 8 (cl/item-count (get-in s [:picker :list]))))))

  (testing "typing non-'/' in :ready does not open picker"
    (let [s0 (assoc (base-state) :mode :ready)
          [s _] (state/update-state s0 (msg/key-press "h"))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))))

  (testing "typing '/' in non-empty input does not open picker"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (update :input #(ti/set-value % "hello")))
          [s _] (state/update-state s0 (msg/key-press "/"))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s))))))

(deftest command-picker-filter-test
  (testing "typing narrows list; backspace on non-empty query removes char"
    (let [s0 (assoc (base-state) :mode :ready)
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          total      (cl/item-count (get-in s-pick [:picker :list]))
          [s1 _]     (state/update-state s-pick (msg/key-press "m"))]
      (is (= "m" (get-in s1 [:picker :query])))
      (is (< (cl/item-count (get-in s1 [:picker :list])) total))
      (is (pos? (cl/item-count (get-in s1 [:picker :list]))))
      (let [[s2 _] (state/update-state s1 (msg/key-press :backspace))]
        (is (= "" (get-in s2 [:picker :query])))
        (is (= :picking (:mode s2))))))

  (testing "backspace on empty query returns to :ready"
    (let [s0 (assoc (base-state) :mode :ready)
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          [s1 _]     (state/update-state s-pick (msg/key-press :backspace))]
      (is (= :ready (:mode s1)))
      (is (nil? (:picker s1))))))

(deftest command-picker-escape-test
  (testing "Escape returns to :ready with cleared picker"
    (let [s0 (assoc (base-state) :mode :ready)
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          [s _]      (state/update-state s-pick (msg/key-press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s))))))

(deftest slash-clear-test
  (testing "/clear entered directly clears items and scroll"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :items [{:type :user :text "hi"}]
                        :scroll-offset 5)
                 (update :input #(ti/set-value % "/clear")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (empty? (:items s)))
      (is (= 0 (:scroll-offset s)))))

  (testing "/clear via command picker clears items"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :items [{:type :user :text "hi"}]))
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          [s1 _]     (state/update-state s-pick (msg/key-press "c"))
          [s2 _]     (state/update-state s1 (msg/key-press "l"))
          [s3 _]     (state/update-state s2 (msg/key-press "e"))
          [s4 _]     (state/update-state s3 (msg/key-press :enter))]
      (is (= :ready (:mode s4)))
      (is (nil? (:picker s4)))
      (is (empty? (:items s4))))))

(deftest slash-help-test
  (testing "/help appends system item containing all command names"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (update :input #(ti/set-value % "/help")))
          [s _] (state/update-state s0 (msg/key-press :enter))
          sys   (last (filter #(= :system (:type %)) (:items s)))]
      (is (some? sys))
      (doseq [cmd ["/model" "/agent" "/new" "/sessions"
                   "/clear" "/help" "/quit" "/login"]]
        (is (clojure.string/includes? (:text sys) cmd)
            (str cmd " missing from /help output"))))))

(deftest slash-quit-test
  (testing "/quit returns non-nil shutdown cmd"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (update :input #(ti/set-value % "/quit")))
          [_ cmd] (state/update-state s0 (msg/key-press :enter))]
      (is (some? cmd)))))

(deftest unknown-command-test
  (testing "unrecognised /cmd appends system error containing command text"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (update :input #(ti/set-value % "/foobarxyzzy")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (some #(and (= :system (:type %))
                      (clojure.string/includes? (:text %) "/foobarxyzzy"))
                (:items s))))))

(deftest slash-model-via-registry-test
  (testing "/model via direct Enter still opens model picker"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :available-models ["anthropic/claude-sonnet-4-6"])
                 (update :input #(ti/set-value % "/model")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (= :picking (:mode s)))
      (is (= :model (get-in s [:picker :kind])))))

  (testing "/model via command picker opens model picker"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :available-models ["anthropic/claude-sonnet-4-6"]))
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          ;; filter to 'mo' to isolate /model
          [s1 _]     (state/update-state s-pick (msg/key-press "m"))
          [s2 _]     (state/update-state s1 (msg/key-press "o"))
          [s3 _]     (state/update-state s2 (msg/key-press :enter))]
      (is (= :picking (:mode s3)))
      (is (= :model (get-in s3 [:picker :kind])))))

  (testing "/model with no available-models shows error (direct Enter)"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready :available-models [])
                 (update :input #(ti/set-value % "/model")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (= :ready (:mode s)))
      (is (some #(and (= :system (:type %))
                      (clojure.string/includes? (:text %) "No models"))
                (:items s))))))

;; --- Phase 5: Rich Display ---

(deftest tool-args-stored-test
  (testing "toolCallRun stores :args-text on the tool-call item"
    (with-redefs [protocol/approve-tool! (fn [& _] nil)]
      (let [[s _] (handle-eca-notification
                    (base-state)
                    {:method "chat/contentReceived"
                     :params {:chatId "chat1" :role "assistant"
                              :content {:type "toolCallRun" :id "tc1"
                                        :name "read_file" :server "fs"
                                        :summary "read foo.clj"
                                        :arguments {"path" "foo.clj"}
                                        :manualApproval false}}})]
        (is (some? (get-in s [:items 0 :args-text])))
        (is (clojure.string/includes? (get-in s [:items 0 :args-text]) "foo.clj"))))))

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
      (is (<= (count (get-in s [:items 0 :out-text])) 8210))
      (is (clojure.string/includes? (get-in s [:items 0 :out-text]) "[truncated]")))))

(deftest task-tool-suppressed-test
  (testing "toolCallPrepare for eca/task tool does not add to :items"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "toolCallPrepare" :id "tc1"
                                      :name "task" :server "eca" :summary "bg task"}}})]
      (is (empty? (:items s)))))

  (testing "toolCallRun for eca/task tool does not add to :items"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "toolCallRun" :id "tc1"
                                      :name "task" :server "eca"
                                      :summary "bg task" :arguments {}}}})]
      (is (empty? (:items s))))))

(deftest subagent-fallthrough-test
  (testing "contentReceived with parentChatId and no registered parent falls through to main flow"
    (let [base  (assoc (base-state) :mode :chatting :subagent-chats {})
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId       "unregistered-sub"
                                 :parentChatId "chat1"
                                 :role         "assistant"
                                 :content      {:type "text" :text "fallthrough"}}})]
      ;; text content goes to :current-text (not :items) via normal handle-content
      (is (= "fallthrough" (:current-text s))))))

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
      (is (= :ok    (:status   (first (:items s)))))
      (is (= "done" (:out-text (first (:items s))))))))

(deftest thinking-item-test
  (testing "reasonStarted creates :thinking item"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "reasonStarted" :id "r1"}}})]
      (is (= 1 (count (:items s))))
      (is (= :thinking (:type   (first (:items s)))))
      (is (= "r1"      (:id     (first (:items s)))))
      (is (= ""        (:text   (first (:items s)))))
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

(deftest subagent-content-routed-test
  (testing "contentReceived with parentChatId is routed to parent tool call sub-items"
    (let [base  (-> (base-state)
                    (assoc :mode :chatting
                           :items [{:type :tool-call :name "eca__spawn_agent"
                                    :id "tc1" :state :called
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

(deftest tab-focus-navigation-test
  (testing "Tab in :ready with tool-call items sets focus-path to first focusable"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path nil
                      :items [{:type :user :text "hi"}
                              {:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? false}])
          [s _] (state/update-state base (msg/key-press :tab))]
      (is (= [1] (:focus-path s)))))

  (testing "Enter on focused tool-call toggles :expanded?"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0]
                      :items [{:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? true}])
          [s _] (state/update-state base (msg/key-press :enter))]
      (is (true? (get-in s [:items 0 :expanded?])))))

  (testing "Escape clears focus, does not change mode"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0]
                      :items [{:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? true}])
          [s _] (state/update-state base (msg/key-press :escape))]
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
          [s1 _] (state/update-state base (msg/key-press :tab))
          [s2 _] (state/update-state s1   (msg/key-press :tab))
          [s3 _] (state/update-state s2   (msg/key-press :tab))]
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
          [s1 _] (state/update-state base (msg/key-press :tab))
          [s2 _] (state/update-state s1   (msg/key-press :tab))]
      (is (= [0]   (:focus-path s1)))
      (is (= [0 0] (:focus-path s2)))))

  (testing "Enter on focused sub-item toggles its :expanded?"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0 0]
                      :items [{:type :tool-call :name "eca__spawn_agent" :state :called
                               :expanded? true :focused? false
                               :sub-items [{:type :tool-call :name "read_file"
                                            :state :called :expanded? false}]}])
          [s _] (state/update-state base (msg/key-press :enter))]
      (is (true? (get-in s [:items 0 :sub-items 0 :expanded?])))))))

;; --- Pre-refactor coverage hardening (Phase A step 1) ---
;; These tests pin down behaviors that will be split across nses in later steps.
;; They drive update-state directly so they continue to pass through extraction.

(deftest approving-y-key-approves-and-returns-to-chatting-test
  (testing "y in :approving calls protocol/approve-tool! and clears pending-approval"
    (let [calls (atom [])]
      (with-redefs [protocol/approve-tool! (fn [srv chat-id tc-id]
                                             (swap! calls conj [srv chat-id tc-id]))]
        (let [s0 (-> (base-state)
                     (assoc :mode :approving
                            :pending-approval {:chat-id "chat1" :tool-call-id "tc1"}
                            :tool-calls {"tc1" {:id "tc1" :name "read_file"}}))
              [s _] (state/update-state s0 (msg/key-press "y"))]
          (is (= :chatting (:mode s)))
          (is (nil? (:pending-approval s)))
          (is (= 1 (count @calls)))
          (is (= "tc1" (nth (first @calls) 2)))
          (is (= #{} (:session-trusted-tools s))))))))

(deftest approving-Y-key-approves-and-trusts-tool-test
  (testing "Y in :approving approves AND adds tool name to session-trusted-tools"
    (with-redefs [protocol/approve-tool! (fn [& _] nil)]
      (let [s0 (-> (base-state)
                   (assoc :mode :approving
                          :pending-approval {:chat-id "chat1" :tool-call-id "tc1"}
                          :tool-calls {"tc1" {:id "tc1" :name "read_file"}}))
            [s _] (state/update-state s0 (msg/key-press "Y"))]
        (is (= :chatting (:mode s)))
        (is (nil? (:pending-approval s)))
        (is (contains? (:session-trusted-tools s) "read_file"))))))

(deftest approving-n-key-rejects-and-returns-to-chatting-test
  (testing "n in :approving calls protocol/reject-tool! and clears pending-approval"
    (let [calls (atom [])]
      (with-redefs [protocol/reject-tool! (fn [srv chat-id tc-id]
                                            (swap! calls conj [srv chat-id tc-id]))]
        (let [s0 (-> (base-state)
                     (assoc :mode :approving
                            :pending-approval {:chat-id "chat1" :tool-call-id "tc1"}
                            :tool-calls {"tc1" {:id "tc1" :name "read_file"}}))
              [s _] (state/update-state s0 (msg/key-press "n"))]
          (is (= :chatting (:mode s)))
          (is (nil? (:pending-approval s)))
          (is (= 1 (count @calls)))
          (is (= "tc1" (nth (first @calls) 2))))))))

(deftest ready-enter-sends-chat-prompt-test
  (testing "Enter in :ready with non-slash text sends prompt and enters :chatting"
    (let [prompts (atom [])]
      (with-redefs [protocol/chat-prompt! (fn [srv params _cb]
                                            (swap! prompts conj params))]
        (let [s0 (-> (base-state)
                     (assoc :mode :ready
                            :input (ti/set-value (ti/text-input) "hello world")))
              [s _] (state/update-state s0 (msg/key-press :enter))]
          (is (= :chatting (:mode s)))
          (is (= "hello world" (:pending-message s)))
          (is (some #(and (= :user (:type %)) (= "hello world" (:text %))) (:items s)))
          (is (= 1 (count @prompts)))
          (is (= "hello world" (:message (first @prompts))))
          (is (= "chat1" (:chat-id (first @prompts)))))))))

(deftest ready-enter-empty-input-noop-test
  (testing "Enter in :ready with empty input is a no-op (no prompt sent, mode unchanged)"
    (let [prompts (atom [])]
      (with-redefs [protocol/chat-prompt! (fn [& _] (swap! prompts conj :sent))]
        (let [s0 (assoc (base-state) :mode :ready)
              [s _] (state/update-state s0 (msg/key-press :enter))]
          (is (= :ready (:mode s)))
          (is (empty? @prompts)))))))

(deftest window-size-rebuilds-chat-lines-test
  (testing ":window-size message updates width/height and rebuilds chat lines"
    (let [s0 (-> (base-state)
                 (assoc :width 80 :height 24
                        :items [{:type :assistant-text :text "hello"}]
                        :chat-lines []))
          [s cmd] (state/update-state s0 {:type :window-size :width 120 :height 40})]
      (is (= 120 (:width s)))
      (is (= 40 (:height s)))
      (is (seq (:chat-lines s)) "chat-lines should be rebuilt from :items")
      (is (nil? cmd)))))

(deftest ctrl-c-fires-shutdown-cmd-test
  (testing "Ctrl+C returns a non-nil shutdown cmd without changing state"
    (let [s0 (assoc (base-state) :mode :ready)
          [s cmd] (state/update-state s0 (msg/key-press "c" :ctrl true))]
      (is (= s0 s) "state should be unchanged")
      (is (some? cmd) "shutdown cmd should be returned")
      (is (= :sequence (:type cmd)) "shutdown cmd should be a charm batch sequence"))))
