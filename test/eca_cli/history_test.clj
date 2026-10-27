(ns eca-cli.history-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]
            [eca-cli.view.blocks :as blocks]))

(def ^:private sent (atom []))

(defn- capture [t]
  (reset! sent [])
  (with-redefs [protocol/rollback-chat! (fn [_ chat-id id include] (swap! sent conj [:rollback chat-id id include]))
                protocol/add-flag!      (fn [_ chat-id id text] (swap! sent conj [:add-flag chat-id id text]))
                protocol/remove-flag!   (fn [_ chat-id id] (swap! sent conj [:remove-flag chat-id id]))
                protocol/fork-chat!     (fn [_ chat-id id] (swap! sent conj [:fork chat-id id]))
                protocol/chat-prompt!   (fn [& _])]
    (t)))

(use-fixtures :each support/isolated-chats-file capture)

(defn- base [& {:as extra}]
  (view/rebuild-lines
    (merge {:mode :ready :chat-id "c1" :current-text "" :tool-calls {} :questions [] :approval-queue []
            :input (ti/focus (ti/text-input)) :input-history [] :width 80 :height 12
            :scroll-offset 0 :opts {:workspace "/tmp/ws"} :mcps {} :init-tasks {} :server-commands []
            :items [{:type :user :text "first question" :content-id "u1"}
                    {:type :assistant-text :text "first answer"}
                    {:type :tool-call :id "t1" :name "read_file" :summary "Reading" :state :called}
                    {:type :user :text "second question" :content-id "u2"}
                    {:type :assistant-text :text "second answer"}]}
           extra)))

(defn- press [s & keys]
  (reduce (fn [s k] (first (state/update-state s (if (map? k) k (msg/key-press k))))) s keys))

(defn- run [s text]
  (first (state/update-state (update s :input ti/set-value text) (msg/key-press :enter))))

(defn- receive [s params]
  (first (state/update-state s {:type :eca-tick :msgs [{:method "chat/contentReceived" :params params}]})))

(deftest content-ids-test
  (testing "the echo of our own message gives it its id"
    (let [s (-> (base :items [] :mode :chatting :echo-pending true)
                (update :items conj {:type :user :text "hi"})
                (receive {:chatId "c1" :role "user" :content {:type "text" :text "hi\n" :contentId "u9"}}))]
      (is (= "u9" (:content-id (peek (:items s)))))))
  (testing "replayed messages keep theirs"
    (let [s (receive (base :items []) {:chatId "c1" :role "user" :content {:type "text" :text "old" :contentId "u7"}})]
      (is (= {:type :user :text "old" :content-id "u7"} (peek (:items s))))))
  (testing "flags arrive as flag items"
    (let [s (receive (base :items []) {:chatId "c1" :role "system" :content {:type "flag" :text "before refactor" :contentId "f1"}})]
      (is (= {:type :flag :text "before refactor" :content-id "f1"} (peek (:items s))))
      (is (= "── ⚑ before refactor ──" (subs (theme/strip-ansi (first (blocks/render-item-lines (peek (:items s)) 80))) 0 23))))))

(deftest rollback-test
  (testing "nothing to roll back to"
    (let [s (run (base :items []) "/rollback")]
      (is (= :ready (:mode s)))
      (is (= :info (:level (peek (:items s)))))))
  (testing "pick a message (newest first), then what to drop"
    (let [s (run (base) "/rollback")]
      (is (= :rollback (get-in s [:picker :kind])))
      (is (= ["second question" "first question"] (map first (get-in s [:picker :all]))))
      (let [s2 (press s :down :enter)]
        (is (= :rollback-type (get-in s2 [:picker :kind])))
        (testing "dropping the messages: rolled back, and the message comes back to the input"
          (let [s3 (press s2 :down :enter)]
            (is (= [[:rollback "c1" "u1" ["messages"]]] @sent))
            (is (= :ready (:mode s3)))
            (is (= "first question" (ti/value (:input s3))))))
        (testing "only the tools' changes: the input stays as it was"
          (reset! sent [])
          (let [s3 (press s2 :down :down :enter)]
            (is (= [[:rollback "c1" "u1" ["tools"]]] @sent))
            (is (= "" (ti/value (:input s3))))))))))

(deftest flags-test
  (testing "/flag <text> flags the latest message or content with an id"
    (run (base) "/flag before refactor")
    (is (= [[:add-flag "c1" "u2" "before refactor"]] @sent)))
  (testing "a tool call counts too"
    (reset! sent [])
    (run (base :items [{:type :user :text "q" :content-id "u1"} {:type :tool-call :id "t5" :name "x"}]) "/flag here")
    (is (= [[:add-flag "c1" "t5" "here"]] @sent)))
  (testing "/flag without text explains itself"
    (reset! sent [])
    (let [s (run (base) "/flag")]
      (is (= [] @sent))
      (is (re-find #"Usage: /flag" (:text (peek (:items s)))))))
  (let [s0 (base :items [{:type :user :text "q" :content-id "u1"}
                         {:type :flag :text "checkpoint" :content-id "f1"}
                         {:type :assistant-text :text (apply str (repeat 40 "long answer "))}])]
    (testing "/flags: fork a new chat from one"
      (reset! sent [])
      (let [s (-> (run s0 "/flags") (press :enter) (press :down :enter))]
        (is (= [[:fork "c1" "f1"]] @sent))
        (is (= :ready (:mode s)))))
    (testing "/flags: remove one"
      (reset! sent [])
      (let [s (-> (run s0 "/flags") (press :enter) (press :down :down :enter))]
        (is (= [[:remove-flag "c1" "f1"]] @sent))
        (is (not-any? #(= :flag (:type %)) (:items s)))))
    (testing "/flags: jump to one scrolls it to the top"
      (let [s     (-> (run s0 "/flags") (press :enter) (press :enter))
            [start] (get (:chat-line-spans s) 1)]
        (is (pos? (:scroll-offset s)))
        (is (= start (- (count (:chat-lines s)) (:scroll-offset s) 7)))))
    (testing "/timeline lists your messages and flags, newest first"
      (let [s (run s0 "/timeline")]
        (is (= ["⚑ checkpoint" "┃ q"] (map first (get-in s [:picker :all]))))))))
