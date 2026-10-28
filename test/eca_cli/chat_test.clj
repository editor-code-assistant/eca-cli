(ns eca-cli.chat-test
  "Tests for chat-domain notification handlers (chat/opened, chat/cleared,
  config/updated). Other chat fns (handle-content, content->item, etc.)
  are still exercised via state-test for now."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.test-support :as support]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.chat :as chat]
            [eca-cli.chats :as chats]
            [eca-cli.protocol :as protocol]
            [eca-cli.view :as view]))

(use-fixtures :each support/isolated-chats-file)

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
   :approval-queue        []
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
                  (assoc (base-state) :chat-id nil)
                  {:method "chat/opened"
                   :params {:chatId "new-chat-123" :title "My Project Chat"}})]
      (is (= "new-chat-123" (:chat-id s)))
      (is (= "My Project Chat" (:chat-title s)))))

  (testing "chat/opened with no title stores nil"
    (let [[s _] (chat/handle-chat-opened
                  (assoc (base-state) :chat-id nil)
                  {:method "chat/opened"
                   :params {:chatId "chat-no-title"}})]
      (is (= "chat-no-title" (:chat-id s)))
      (is (nil? (:chat-title s))))))

(deftest chat-opened-switch-test
  (testing "a chat the server opened off this one (/fork, /btw) is shown from now on"
    (let [saved (atom nil)]
      (with-redefs [chats/save-chat-id! (fn [_ id] (reset! saved id))]
        (let [s0    (assoc (base-state) :mode :chatting :turn-started-at 1
                           :items [{:type :user :text "/fork"}]
                           :pending-approval {:chat-id "chat1" :tool-call-id "t"})
              [s _] (chat/handle-chat-opened s0 {:method "chat/opened"
                                                 :params {:chatId "forked" :title "Fork of x"}})]
          (is (= "forked" (:chat-id s)))
          (is (= "Fork of x" (:chat-title s)))
          (is (= [] (:items s)) "its own messages follow")
          (is (= :ready (:mode s)) "the turn that forked ends in the other chat")
          (is (nil? (:pending-approval s)))
          (is (= "forked" @saved) "remembered for the workspace")))))
  (testing "the same chat: just its title"
    (let [[s _] (chat/handle-chat-opened (assoc (base-state) :items [{:type :user :text "hi"}])
                                         {:method "chat/opened" :params {:chatId "chat1" :title "T"}})]
      (is (= 1 (count (:items s))))
      (is (= "T" (:chat-title s))))))

(deftest content-routing-test
  (let [receive (fn [s params] (first (chat/handle-content-received s {:method "chat/contentReceived"
                                                                       :params params})))]
    (testing "another chat's content stays out of this one"
      (let [s (receive (base-state) {:chatId "other" :role "assistant" :content {:type "text" :text "x"}})]
        (is (= "" (:current-text s)))))
    (testing "an unannounced sub-agent of this chat is adopted by the latest spawn call"
      (let [s0 (assoc (base-state) :items [{:type :tool-call :id "sp" :name "spawn_agent" :server "eca"
                                            :state :running :sub-items []}])
            s  (receive s0 {:chatId "sub-9" :parentChatId "chat1" :role "assistant"
                            :content {:type "text" :text "found"}})]
        (is (= {"sub-9" 0} (:subagent-chats s)))
        (is (= "found" (get-in s [:items 0 :sub-items 0 :text])))))))

(deftest chat-cleared-handler-test
  (testing "chat/cleared with messages:true clears items and scroll"
    (let [s0 (assoc (base-state)
                    :items [{:type :user :text "hi"}]
                    :scroll-offset 5)
          [s _] (chat/handle-chat-cleared
                  s0
                  {:method "chat/cleared"
                   :params {:chatId "chat1" :messages true}})]
      (is (empty? (:items s)))
      (is (= 0 (:scroll-offset s)))))

  (testing "another chat being cleared leaves this one alone"
    (let [s0    (assoc (base-state) :items [{:type :user :text "hi"}])
          [s _] (chat/handle-chat-cleared s0 {:method "chat/cleared"
                                              :params {:chatId "other" :messages true}})]
      (is (= 1 (count (:items s))))))

  (testing "chat/cleared with messages:false leaves items intact"
    (let [s0 (assoc (base-state)
                    :items [{:type :user :text "hi"}])
          [s _] (chat/handle-chat-cleared
                  s0
                  {:method "chat/cleared"
                   :params {:chatId "x" :messages false}})]
      (is (= 1 (count (:items s)))))))

;; --- Block navigation keybindings (Phase B step 5) ---

(defn- with-tools []
  (assoc (base-state)
         :mode :ready
         :focus-path nil
         :items [{:type :user :text "hi"}
                 {:type :tool-call :name "read_file" :state :called
                  :expanded? true :focused? false
                  :sub-items [{:type :tool-call :name "ls" :state :called
                               :expanded? false :focused? false}]}
                 {:type :assistant-text :text "answer"}
                 {:type :thinking :id "r1" :text "..." :status :thought
                  :expanded? false :focused? false}
                 {:type :tool-call :name "write_file" :state :called
                  :expanded? false :focused? false}]))

(deftest alt-down-jumps-to-next-top-level-test
  (testing "from no focus, Alt+↓ focuses first top-level focusable block"
    (let [[s _] (chat/handle-key (with-tools) (msg/key-press :down :alt true))]
      (is (= [1] (:focus-path s)))))

  (testing "Alt+↓ from sub-item focus jumps to next top-level (not next sub-item)"
    (let [s0    (chat/sync-focus (assoc (with-tools) :focus-path [1 0]))
          [s _] (chat/handle-key s0 (msg/key-press :down :alt true))]
      (is (= [3] (:focus-path s)))))

  (testing "Alt+↓ wraps from last to first"
    (let [s0    (chat/sync-focus (assoc (with-tools) :focus-path [4]))
          [s _] (chat/handle-key s0 (msg/key-press :down :alt true))]
      (is (= [1] (:focus-path s))))))

(deftest alt-up-jumps-to-prev-top-level-test
  (testing "Alt+↑ from no focus picks last top-level focusable"
    (let [[s _] (chat/handle-key (with-tools) (msg/key-press :up :alt true))]
      (is (= [4] (:focus-path s)))))

  (testing "Alt+↑ skips sub-items even when focused on a sub-item"
    (let [s0    (chat/sync-focus (assoc (with-tools) :focus-path [1 0]))
          [s _] (chat/handle-key s0 (msg/key-press :up :alt true))]
      ;; cur-top = [1]; previous top-level is [4] (wraps)
      (is (= [4] (:focus-path s))))))

(deftest alt-g-focuses-first-block-test
  (testing "Alt+g focuses first focusable block regardless of current focus"
    (let [s0    (chat/sync-focus (assoc (with-tools) :focus-path [4]))
          [s _] (chat/handle-key s0 (msg/key-press "g" :alt true))]
      (is (= [1] (:focus-path s))))))

(deftest alt-shift-g-focuses-last-block-test
  (testing "Alt+G focuses last focusable block (sub-item if final top-level is expanded)"
    (let [[s _] (chat/handle-key (with-tools) (msg/key-press "G" :alt true))]
      ;; with-tools expands index 1 with one sub-item; last block is [4]
      (is (= [4] (:focus-path s))))))

(deftest alt-c-collapses-all-test
  (testing "Alt+c sets :expanded? false on every focusable item and sub-item"
    (let [[s _] (chat/handle-key (with-tools) (msg/key-press "c" :alt true))]
      (is (every? #(or (not (#{:tool-call :thinking :hook} (:type %)))
                       (false? (:expanded? %)))
                  (:items s))))))

(deftest alt-o-expands-all-test
  (testing "Alt+o sets :expanded? true on every focusable item and sub-item"
    (let [[s _] (chat/handle-key (with-tools) (msg/key-press "o" :alt true))]
      (is (every? #(or (not (#{:tool-call :thinking :hook} (:type %)))
                       (true? (:expanded? %)))
                  (:items s))))))

(deftest tab-scrolls-focused-item-into-view-test
  (testing "Tab past end of visible window pulls scroll-offset toward target"
    (let [items (vec (concat
                       (repeat 30 {:type :system :text "old"})
                       [{:type :tool-call :name "read_file" :state :called
                         :expanded? false :focused? false}]))
          s0 (-> (base-state)
                 (assoc :items items :mode :ready :transcript? true :height 10 :scroll-offset 25)
                 view/rebuild-lines)
          [s _] (chat/handle-key s0 (msg/key-press :tab))]
      (is (= [30] (:focus-path s)))
      ;; Last item's span ends at total; offset must drop so end-of-item is visible.
      (is (zero? (:scroll-offset s)))))

  (testing "Tab to item already inside window leaves scroll-offset unchanged"
    (let [items [{:type :tool-call :name "a" :state :called :expanded? false :focused? false}
                 {:type :tool-call :name "b" :state :called :expanded? false :focused? false}]
          s0 (-> (base-state)
                 (assoc :items items :mode :ready :transcript? true :height 24 :scroll-offset 0)
                 view/rebuild-lines)
          [s _] (chat/handle-key s0 (msg/key-press :tab))]
      (is (= [0] (:focus-path s)))
      (is (zero? (:scroll-offset s))))))

(deftest block-nav-noop-on-empty-items-test
  (testing "Alt+↓ on empty items returns state unchanged"
    (let [s0 (assoc (base-state) :items [])
          [s _] (chat/handle-key s0 (msg/key-press :down :alt true))]
      (is (nil? (:focus-path s))))))

(deftest content->edit-test
  (testing "fileChange detail normalizes to an :edit map"
    (is (= {:path "/tmp/foo.clj" :diff "@@ -1 +1 @@\n-a\n+b"
            :lines-added 12 :lines-removed 3}
           (chat/content->edit
             {:type "toolCallRun" :id "1" :name "edit_file"
              :details {:type "fileChange" :path "/tmp/foo.clj"
                        :linesAdded 12 :linesRemoved 3
                        :diff "@@ -1 +1 @@\n-a\n+b"}}))))

  (testing "non-fileChange detail yields nil (no :edit key)"
    (is (nil? (chat/content->edit
                {:type "toolCallRun" :id "2" :name "shell"
                 :details {:type "shellCommand"}}))))

  (testing "missing details yields nil"
    (is (nil? (chat/content->edit {:type "toolCallRun" :id "3" :name "read_file"})))))

(deftest content->shell-test
  (testing "a shellCommand breakdown, normalized"
    (is (= {:commands    [{:command "git" :args ["checkout" "x"] :approval-key "git checkout" :remembered? true}
                          {:command "sudo" :args ["ls"] :approval-key nil :remembered? false}]
            :background? false}
           (chat/content->shell {:details {:type "shellCommand"
                                           :commands [{:command "git" :args ["checkout" "x"]
                                                       :approvalKey "git checkout" :remembered true}
                                                      {:command "sudo" :args ["ls"]}]}}))))
  (testing "other details -> nil"
    (is (nil? (chat/content->shell {:details {:type "fileChange"}})))
    (is (nil? (chat/content->shell {})))))

(deftest subagent-chat-id-test
  (is (= "sub-1" (chat/subagent-chat-id {:details {:type "subagent" :subagentChatId "sub-1"}})))
  (is (nil? (chat/subagent-chat-id {:details {:type "task"}}))))

(deftest send-chat-prompt-trust-test
  (let [sent (atom nil)]
    (with-redefs [protocol/chat-prompt! (fn [_ params _] (reset! sent params))]
      (testing "trust on: sent with the prompt"
        (chat/send-chat-prompt! nil "c1" "hi" {:trust true})
        (is (true? (:trust @sent))))
      (testing "trust turned off: sent as false, so a new chat doesn't fall back to
                the user's chat.defaultTrust"
        (chat/send-chat-prompt! nil "c1" "hi" {:trust false})
        (is (false? (:trust @sent))))
      (testing "never set: left out, the server default applies"
        (chat/send-chat-prompt! nil "c1" "hi" {})
        (is (not (contains? @sent :trust)))))))

(deftest tool-output-text-test
  (testing "the protocol's outputs parts"
    (is (= "a\nb" (chat/tool-output-text {:outputs [{:type "text" :text "a"} {:type "text" :text "b"}]}))))
  (testing "a plain string outputs, or the legacy single output"
    (is (= "s" (chat/tool-output-text {:outputs "s"})))
    (is (= "old" (chat/tool-output-text {:output "old"}))))
  (testing "empty -> nil; long output capped"
    (is (nil? (chat/tool-output-text {:outputs []})))
    (is (str/ends-with? (chat/tool-output-text {:outputs [{:text (apply str (repeat 9000 "x"))}]})
                        "\n[truncated]"))))

(defn- content [type & {:as kvs}]
  {:chatId "chat1" :role "assistant" :content (merge {:type type} kvs)})

(deftest handle-content-details-test
  (testing "toolCalled keeps the output text and duration"
    (let [s    (chat/handle-content (base-state)
                                    (content "toolCalled" :id "t1" :name "shell" :summary "ls"
                                             :error false :totalTimeMs 2500
                                             :outputs [{:type "text" :text "a.txt"}]))
          item (first (:items s))]
      (is (= "a.txt" (:out-text item)))
      (is (= 2500 (:total-ms item)))))
  (testing "reasonFinished keeps the thought's duration"
    (let [s0 (assoc (base-state) :items [{:type :thinking :id "r1" :text "" :status :thinking}])
          s  (chat/handle-content s0 (content "reasonFinished" :id "r1" :totalTimeMs 3000))]
      (is (= {:status :thought :total-ms 3000} (select-keys (first (:items s)) [:status :total-ms])))))
  (testing "url content becomes a link item, after any streamed text"
    (let [s0 (assoc (base-state) :current-text "see:")
          s  (chat/handle-content s0 (content "url" :title "ECA" :url "https://eca.dev"))]
      (is (= [{:type :assistant-text :text "see:"} {:type :url :title "ECA" :url "https://eca.dev"}]
             (:items s)))
      (is (= "" (:current-text s))))))

(deftest add-sub-content-test
  (let [fold (fn [events] (reduce chat/add-sub-content [] events))]
    (testing "streamed text chunks extend one item"
      (is (= [{:type :assistant-text :role "assistant" :text "Hello world"}]
             (fold [(content "text" :text "Hello") (content "text" :text " world")]))))
    (testing "text from another role starts a new item"
      (is (= 2 (count (fold [(assoc (content "text" :text "task") :role "user")
                             (content "text" :text "answer")])))))
    (testing "a tool call is one item updated through its lifecycle"
      (let [subs (fold [(content "toolCallPrepare" :id "t" :name "read_file" :summary "Reading x")
                        (content "toolCallRunning" :id "t" :name "read_file")
                        (content "toolCalled" :id "t" :name "read_file" :error false :totalTimeMs 1200
                                 :outputs [{:type "text" :text "body"}])])]
        (is (= 1 (count subs)))
        (is (= {:state :called :summary "Reading x" :out-text "body" :total-ms 1200}
               (select-keys (first subs) [:state :summary :out-text :total-ms])))))
    (testing "thoughts accumulate text and finish"
      (let [[t] (fold [(content "reasonStarted" :id "r")
                       (content "reasonText" :id "r" :text "a")
                       (content "reasonText" :id "r" :text "b")
                       (content "reasonFinished" :id "r" :totalTimeMs 2000)])]
        (is (= {:text "ab" :status :thought :total-ms 2000} (select-keys t [:text :status :total-ms])))))))
