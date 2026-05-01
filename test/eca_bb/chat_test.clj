(ns eca-bb.chat-test
  "Tests for chat-domain notification handlers (chat/opened, chat/cleared,
  config/updated). Other chat fns (handle-content, content->item, etc.)
  are still exercised via state-test for now."
  (:require [clojure.test :refer [deftest is testing]]
            [charm.components.text-input :as ti]
            [eca-bb.chat :as chat]))

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

(deftest handle-config-updated-test
  (testing "stores models list"
    (let [models ["anthropic/claude-sonnet-4-6" "anthropic/claude-opus-4-7"]
          [s _]  (chat/handle-config-updated
                   (base-state)
                   {:method "config/updated"
                    :params {:chat {:models models}}})]
      (is (= models (:available-models s)))))

  (testing "stores agents list"
    (let [agents ["code" "plan"]
          [s _]  (chat/handle-config-updated
                   (base-state)
                   {:method "config/updated"
                    :params {:chat {:agents agents}}})]
      (is (= agents (:available-agents s)))))

  (testing "selectModel forces model selection"
    (let [[s _] (chat/handle-config-updated
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:selectModel "anthropic/claude-opus-4-7"}}})]
      (is (= "anthropic/claude-opus-4-7" (:selected-model s)))))

  (testing "selectModel nil clears selection"
    (let [s0    (assoc (base-state) :selected-model "anthropic/claude-sonnet-4-6")
          [s _] (chat/handle-config-updated
                  s0
                  {:method "config/updated"
                   :params {:chat {:selectModel nil}}})]
      (is (nil? (:selected-model s)))))

  (testing "selectAgent nil clears selection"
    (let [s0    (assoc (base-state) :selected-agent "code")
          [s _] (chat/handle-config-updated
                  s0
                  {:method "config/updated"
                   :params {:chat {:selectAgent nil}}})]
      (is (nil? (:selected-agent s)))))

  (testing "welcomeMessage adds assistant-text item"
    (let [[s _] (chat/handle-config-updated
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
          [s _] (chat/handle-config-updated
                  s0
                  {:method "config/updated"
                   :params {:chat {:models ["anthropic/claude-opus-4-7"]}}})]
      (is (= ["anthropic/claude-opus-4-7"] (:available-models s)))
      (is (= ["code"] (:available-agents s)))))

  (testing "nil chat field is a no-op"
    (let [base  (base-state)
          [s _] (chat/handle-config-updated base {:method "config/updated" :params {}})]
      (is (= base s)))))

(deftest handle-config-updated-variants-test
  (testing "variants list stored"
    (let [[s _] (chat/handle-config-updated
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:variants ["low" "medium" "high"]}}})]
      (is (= ["low" "medium" "high"] (:available-variants s)))))

  (testing "selectVariant sets selected-variant"
    (let [[s _] (chat/handle-config-updated
                  (base-state)
                  {:method "config/updated"
                   :params {:chat {:selectVariant "medium"}}})]
      (is (= "medium" (:selected-variant s)))))

  (testing "selectVariant null clears selected-variant"
    (let [s0    (assoc (base-state) :selected-variant "high")
          [s _] (chat/handle-config-updated
                  s0
                  {:method "config/updated"
                   :params {:chat {:selectVariant nil}}})]
      (is (nil? (:selected-variant s)))))

  (testing "absent variants field does not overwrite existing"
    (let [s0    (assoc (base-state) :available-variants ["low" "high"])
          [s _] (chat/handle-config-updated
                  s0
                  {:method "config/updated"
                   :params {:chat {:models ["anthropic/claude-opus-4-7"]}}})]
      (is (= ["low" "high"] (:available-variants s))))))

(deftest chat-opened-handler-test
  (testing "chat/opened stores chat-id and title"
    (let [[s _] (chat/handle-chat-opened
                  (base-state)
                  {:method "chat/opened"
                   :params {:chatId "new-chat-123" :title "My Project Chat"}})]
      (is (= "new-chat-123" (:chat-id s)))
      (is (= "My Project Chat" (:chat-title s)))))

  (testing "chat/opened with no title stores nil"
    (let [[s _] (chat/handle-chat-opened
                  (base-state)
                  {:method "chat/opened"
                   :params {:chatId "chat-no-title"}})]
      (is (= "chat-no-title" (:chat-id s)))
      (is (nil? (:chat-title s))))))

(deftest chat-cleared-handler-test
  (testing "chat/cleared with messages:true clears items and scroll"
    (let [s0 (assoc (base-state)
                    :items [{:type :user :text "hi"}]
                    :scroll-offset 5)
          [s _] (chat/handle-chat-cleared
                  s0
                  {:method "chat/cleared"
                   :params {:chatId "x" :messages true}})]
      (is (empty? (:items s)))
      (is (= 0 (:scroll-offset s)))))

  (testing "chat/cleared with messages:false leaves items intact"
    (let [s0 (assoc (base-state)
                    :items [{:type :user :text "hi"}])
          [s _] (chat/handle-chat-cleared
                  s0
                  {:method "chat/cleared"
                   :params {:chatId "x" :messages false}})]
      (is (= 1 (count (:items s)))))))
