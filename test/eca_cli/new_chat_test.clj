(ns eca-cli.new-chat-test
  "A new chat keeps the one left on the server (the other ECA clients do the
  same), and a chat the server deletes (`chat/deleted`) gives way to a fresh
  one instead of staying on screen."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]))

(def ^:private sent (atom []))

(defn- capture [t]
  (reset! sent [])
  (with-redefs [protocol/delete-chat!          (fn [_ chat-id _] (swap! sent conj [:delete chat-id]))
                protocol/chat-prompt!          (fn [_ params _] (swap! sent conj [:prompt (:chat-id params) (:message params)]))
                protocol/chat-query-commands!  (fn [& _])]
    (t)))

(use-fixtures :each support/isolated-chats-file capture)

(defn- base []
  {:mode :ready :chat-id "c1" :chat-title "Fix the login test"
   :items [{:type :user :text "fix it" :content-id "u1"} {:type :assistant-text :text "Done."}]
   :recent-chats [{:id "c2" :title "Dark theme" :ago "1d ago"}]
   :current-text "" :tool-calls {} :questions [] :approval-queue []
   :input (ti/focus (ti/text-input)) :input-history [] :width 100 :height 30
   :chat-lines [] :scroll-offset 0 :opts {:workspace "/tmp/ws"} :mcps {} :init-tasks {}
   :server-commands []})

(defn- press [s & keys]
  (reduce (fn [s k] (first (state/update-state s (if (map? k) k (msg/key-press k))))) s keys))

(defn- server [s & msgs]
  (first (state/update-state s {:type :eca-tick :msgs (vec msgs)})))

(defn- shown
  "The whole chat as rendered, what's gone to the terminal's history included."
  [s]
  (str/join "\n" (map theme/strip-ansi (:chat-lines s))))

(defn- left-chat-kept? [s]
  (and (= [] @sent)
       (nil? (:chat-id s))
       (= [:welcome] (mapv :type (:items s)))
       (= [{:id "c1" :title "Fix the login test" :ago "just now"}
           {:id "c2" :title "Dark theme" :ago "1d ago"}]
          (:recent-chats s))
       (str/includes? (shown s) "Fix the login test")))

(deftest ctrl-n-keeps-the-chat-left-test
  (testing "Ctrl+N starts a new chat without deleting the one on screen"
    (is (left-chat-kept? (press (base) (msg/key-press "n" :ctrl true))))))

(deftest slash-new-keeps-the-chat-left-test
  (testing "/new, picked from the / picker, doesn't delete it either"
    (is (left-chat-kept? (press (base) "/" "n" "e" "w" :enter)))))

(deftest a-new-chat-gets-a-new-id-test
  (testing "the next message starts a chat of its own"
    (let [s (-> (base) (press (msg/key-press "n" :ctrl true)))
          s (-> s (update :input ti/set-value "hello again") (press :enter))
          [[_ chat-id message]] @sent]
      (is (= "hello again" message))
      (is (string? chat-id))
      (is (not= "c1" chat-id))
      (is (= chat-id (:chat-id s))))))

(deftest chat-deleted-test
  (testing "the chat on screen deleted (the server's /delete-chat, another client): a fresh one starts"
    (let [s (server (base) {:method "chat/deleted" :params {:chatId "c1"}})]
      (is (nil? (:chat-id s)))
      (is (nil? (:chat-title s)))
      (is (= [:welcome :system] (mapv :type (:items s))))
      (is (str/includes? (shown s) "This chat was deleted"))
      (is (= ["c2"] (mapv :id (:recent-chats s))))
      (testing "and the next message gets a new id, not the deleted one"
        (let [s (-> s (update :input ti/set-value "start over") (press :enter))
              [[_ chat-id]] @sent]
          (is (string? chat-id))
          (is (not= "c1" chat-id))
          (is (= chat-id (:chat-id s)))))))
  (testing "another chat deleted: it leaves the recent ones, the chat on screen stays"
    (let [s (server (base) {:method "chat/deleted" :params {:chatId "c2"}})]
      (is (= "c1" (:chat-id s)))
      (is (= [:user :assistant-text] (mapv :type (:items s))))
      (is (= [] (:recent-chats s)))))
  (testing "deleted mid-turn: what was running goes too"
    (let [s (server (assoc (base) :mode :chatting :turn-started-at 1 :steered "use v2")
                    {:method "chat/deleted" :params {:chatId "c1"}})]
      (is (= :ready (:mode s)))
      (is (nil? (:turn-started-at s)))
      (is (nil? (:steered s))))))
