(ns eca-bb.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-bb.protocol :as protocol]
            [eca-bb.state :as state]))

(def ^:private flush-current-text   #'state/flush-current-text)
(def ^:private upsert-tool-call     #'state/upsert-tool-call)
(def ^:private handle-content       #'state/handle-content)
(def ^:private handle-eca-tick      #'state/handle-eca-tick)
(def ^:private handle-eca-notification  #'state/handle-eca-notification)
(def ^:private handle-providers-updated #'state/handle-providers-updated)

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
   :available-variants    []
   :selected-model        nil
   :selected-agent        nil
   :selected-variant      nil
   :input                 (ti/text-input)
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
      (is (= :chatting (:mode s))))))

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

;; --- config/updated ---

(deftest handle-config-updated-test
  (testing "stores models list"
    (let [models ["anthropic/claude-sonnet-4-6" "anthropic/claude-opus-4-7"]
          [s _]  (handle-eca-notification
                   (base-state)
                   {:method "config/updated"
                    :params {:chat {:models models}}})]
      (is (= models (:available-models s)))))

  (testing "stores agents list"
    (let [agents ["code" "plan"]
          [s _]  (handle-eca-notification
                   (base-state)
                   {:method "config/updated"
                    :params {:chat {:agents agents}}})]
      (is (= agents (:available-agents s)))))

  (testing "selectModel forces model selection"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:selectModel "anthropic/claude-opus-4-7"}}})]
      (is (= "anthropic/claude-opus-4-7" (:selected-model s)))))

  (testing "selectModel nil clears selection"
    (let [s0    (assoc (base-state) :selected-model "anthropic/claude-sonnet-4-6")
          [s _] (handle-eca-notification
                  s0
                  {:method "config/updated"
                   :params {:chat {:selectModel nil}}})]
      (is (nil? (:selected-model s)))))

  (testing "selectAgent nil clears selection"
    (let [s0    (assoc (base-state) :selected-agent "code")
          [s _] (handle-eca-notification
                  s0
                  {:method "config/updated"
                   :params {:chat {:selectAgent nil}}})]
      (is (nil? (:selected-agent s)))))

  (testing "welcomeMessage adds assistant-text item"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:welcomeMessage "Welcome! How can I help?"}}})]
      (is (= 1 (count (:items s))))
      (is (= :assistant-text (:type (first (:items s)))))
      (is (= "Welcome! How can I help?" (:text (first (:items s)))))))

  (testing "absent fields do not overwrite existing state"
    (let [s0    (assoc (base-state)
                       :available-models ["anthropic/claude-sonnet-4-6"]
                       :available-agents ["code"])
          [s _] (handle-eca-notification
                  s0
                  {:method "config/updated"
                   :params {:chat {:models ["anthropic/claude-opus-4-7"]}}})]
      (is (= ["anthropic/claude-opus-4-7"] (:available-models s)))
      (is (= ["code"] (:available-agents s)))))

  (testing "nil chat field is a no-op"
    (let [base  (base-state)
          [s _] (handle-eca-notification base {:method "config/updated" :params {}})]
      (is (= base s)))))

;; --- :reader-error ---

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
          (is (some? cmd2)))))))
