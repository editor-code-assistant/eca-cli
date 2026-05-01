(ns eca-cli.chat-test
  "Tests for chat-domain notification handlers (chat/opened, chat/cleared,
  config/updated). Other chat fns (handle-content, content->item, etc.)
  are still exercised via state-test for now."
  (:require [clojure.test :refer [deftest is testing]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.chat :as chat]
            [eca-cli.view :as view]))

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
                       (repeat 30 {:type :assistant-text :text "old"})
                       [{:type :tool-call :name "read_file" :state :called
                         :expanded? false :focused? false}]))
          s0 (-> (base-state)
                 (assoc :items items :mode :ready :height 10 :scroll-offset 25)
                 view/rebuild-lines)
          [s _] (chat/handle-key s0 (msg/key-press :tab))]
      (is (= [30] (:focus-path s)))
      ;; Last item's span ends at total; offset must drop so end-of-item is visible.
      (is (zero? (:scroll-offset s)))))

  (testing "Tab to item already inside window leaves scroll-offset unchanged"
    (let [items [{:type :tool-call :name "a" :state :called :expanded? false :focused? false}
                 {:type :tool-call :name "b" :state :called :expanded? false :focused? false}]
          s0 (-> (base-state)
                 (assoc :items items :mode :ready :height 24 :scroll-offset 0)
                 view/rebuild-lines)
          [s _] (chat/handle-key s0 (msg/key-press :tab))]
      (is (= [0] (:focus-path s)))
      (is (zero? (:scroll-offset s))))))

(deftest block-nav-noop-on-empty-items-test
  (testing "Alt+↓ on empty items returns state unchanged"
    (let [s0 (assoc (base-state) :items [])
          [s _] (chat/handle-key s0 (msg/key-press :down :alt true))]
      (is (nil? (:focus-path s))))))
