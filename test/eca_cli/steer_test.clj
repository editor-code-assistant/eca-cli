(ns eca-cli.steer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.chat :as chat]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(def ^:private sent (atom []))

(defn- capture [t]
  (reset! sent [])
  (with-redefs [protocol/steer-prompt!        (fn [_ chat-id text] (swap! sent conj [:steer chat-id text]))
                protocol/steer-prompt-remove! (fn [_ chat-id] (swap! sent conj [:steer-remove chat-id]))
                protocol/stop-prompt!         (fn [_ chat-id] (swap! sent conj [:stop chat-id]))
                protocol/chat-prompt!         (fn [_ params _] (swap! sent conj [:prompt (:message params)]))
                protocol/update-chat!         (fn [_ chat-id fields] (swap! sent conj [:update chat-id fields]))]
    (t)))

(use-fixtures :each support/isolated-chats-file capture)

(defn- base []
  {:mode :chatting :turn-started-at 1 :chat-id "c1" :items [] :current-text "" :tool-calls {}
   :questions [] :approval-queue [] :input (ti/focus (ti/text-input)) :input-history []
   :width 100 :height 24 :chat-lines [] :scroll-offset 0 :opts {:workspace "/tmp/ws"}
   :mcps {} :init-tasks {} :trust false})

(defn- press [s & keys]
  (reduce (fn [s k] (first (state/update-state s (if (map? k) k (msg/key-press k))))) s keys))

(defn- typed [s text] (update s :input ti/set-value text))

(defn- server [s & msgs]
  (first (state/update-state s {:type :eca-tick :msgs (vec msgs)})))

(defn- content [role c] {:method "chat/contentReceived" :params {:chatId "c1" :role role :content c}})

;; As most terminals send it: ESC CR, read by charm as an Alt'ed "\r".
(def ^:private alt-enter (msg/key-press "\r" :alt true))

(deftest alt-enter-forms-test
  (is (chat/alt-enter? (msg/key-press "\r" :alt true)))
  (is (chat/alt-enter? (msg/key-press :enter :alt true)))
  (is (not (chat/alt-enter? (msg/key-press :enter)))))

(deftest steer-test
  (testing "Enter while the agent works steers the running prompt"
    (let [s (-> (base) (typed "use the v2 API") (press :enter))]
      (is (= [[:steer "c1" "use the v2 API"]] @sent))
      (is (= "use the v2 API" (:steered s)))
      (is (= "" (ti/value (:input s))))
      (is (= :chatting (:mode s)))
      (testing "steers add up, like on the server"
        (let [s2 (-> s (typed "and keep it short") (press :enter))]
          (is (= "use the v2 API\nand keep it short" (:steered s2)))
          (testing "the server echoing the injected message consumes them all"
            (let [s3 (server s2 (content "user" {:type "text" :text "use the v2 API\nand keep it short\n"}))]
              (is (nil? (:steered s3)))
              (is (= :user (:type (peek (:items s3))))))))))))

(deftest queue-test
  (testing "Alt+Enter queues a message for after the turn"
    (let [s (-> (base) (typed "then update the docs") (press alt-enter))]
      (is (= [] @sent))
      (is (= "then update the docs" (:queued s)))
      (testing "the turn ending sends what's queued (and an unconsumed steer) as the next prompt"
        (let [s2 (-> s (typed "late steer") (press :enter)
                     (typed "a draft") ; what the user is typing then stays put
                     (server (content "system" {:type "progress" :state "finished"})))]
          (is (= [:prompt "late steer\nthen update the docs"] (peek @sent)))
          (is (= :chatting (:mode s2)))
          (is (nil? (:queued s2)))
          (is (nil? (:steered s2)))
          (is (= "a draft" (ti/value (:input s2))))
          (is (= {:type :user :text "late steer\nthen update the docs"} (peek (:items s2)))))))))

(deftest take-back-test
  (testing "Esc stops the prompt and gives unsent text back to the input"
    (let [s (-> (base) (typed "steer me") (press :enter) (typed "queue me") (press alt-enter) (press :escape))]
      (is (some #{[:stop "c1"]} @sent))
      (is (some #{[:steer-remove "c1"]} @sent))
      (is (= :ready (:mode s)))
      (is (= "steer me\nqueue me" (ti/value (:input s))))
      (is (nil? (:steered s)))
      (testing "and the stopped turn's finish doesn't send it"
        (reset! sent [])
        (server s (content "system" {:type "progress" :state "finished"}))
        (is (= [] @sent)))))
  (testing "↑ on an empty input takes it back without stopping"
    (let [s (-> (base) (typed "queue me") (press alt-enter) (press :up))]
      (is (= :chatting (:mode s)))
      (is (= "queue me" (ti/value (:input s))))
      (is (nil? (:queued s))))))

(deftest commands-during-turn-test
  (testing "/trust runs right away while the agent works"
    (let [s (-> (base) (typed "/trust") (press :enter))]
      (is (true? (:trust s)))
      (is (= [[:update "c1" {:trust true}]] @sent))
      (is (= "" (ti/value (:input s))))))
  (testing "other slash text steers: the server decides what may run mid-turn"
    (reset! sent [])
    (-> (base) (typed "/compact") (press :enter))
    (is (= [[:steer "c1" "/compact"]] @sent))))

(deftest chatting-view-test
  (let [s   (-> (base) (typed "steer me") (press :enter) (typed "queue me") (press alt-enter) (typed "more"))
        out (theme/strip-ansi (view/view s))]
    (is (str/includes? out "Steering: steer me"))
    (is (str/includes? out "Queued: queue me"))
    (is (str/includes? out "> more"))
    (is (str/includes? out "enter to steer · alt+enter to queue · esc to interrupt"))
    (testing "the screen still fits: the chat gives up the lines"
      (is (= 24 (count (str/split-lines (view/view s))))))))
