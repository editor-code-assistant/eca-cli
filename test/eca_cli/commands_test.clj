(ns eca-cli.commands-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.test-support :as support]
            [eca-cli.commands :as commands]
            [eca-cli.picker :as picker]
            [eca-cli.protocol :as protocol]
            [eca-cli.theme :as theme]))

(use-fixtures :each support/isolated-chats-file)

(deftest help-test
  (let [[s _]  (commands/cmd-show-help {:items []})
        item   (peek (:items s))
        lines  (str/split-lines (theme/strip-ansi (:text item)))]
    (is (= :info (:level item)) "an informational note, not a warning")
    (is (= "Available commands:" (first lines)))
    (testing "descriptions aligned in one column"
      (is (= 1 (count (set (map #(count (re-find #"^\S+\s+" %)) (filter #(str/starts-with? % "/") lines)))))))
    (testing "points at the server's commands"
      (is (str/includes? (last lines) "type / to browse")))))

(deftest picker-ranking-test
  (let [items [["/compact" "Summarize to reduce context."] ["/context" "Context window usage"]
               ["/costs" "Total costs"]]]
    (testing "a name match ranks above a description match"
      (is (= ["/context" "/compact"] (map first (picker/filter-items :command items "context")))))
    (testing "ties keep their order; no query keeps everything"
      (is (= ["/compact" "/context" "/costs"] (map first (picker/filter-items :command items "co"))))
      (is (= 3 (count (picker/filter-items :command items "")))))))

(deftest toggle-trust-test
  (let [sent (atom [])]
    (with-redefs [protocol/update-chat! (fn [_ chat-id fields] (swap! sent conj [chat-id fields]))]
      (testing "on: shown, sent with the next prompts, and applied to the chat right away"
        (let [[s _] (commands/cmd-toggle-trust {:trust false :chat-id "c1" :opts {} :items []})]
          (is (true? (:trust s)))
          (is (true? (get-in s [:opts :trust])))
          (is (= [["c1" {:trust true}]] @sent))
          (is (= :info (:level (peek (:items s)))))))
      (testing "off"
        (reset! sent [])
        (let [[s _] (commands/cmd-toggle-trust {:trust true :chat-id "c1" :opts {:trust true} :items []})]
          (is (false? (:trust s)))
          (is (false? (get-in s [:opts :trust])))
          (is (= [["c1" {:trust false}]] @sent))))
      (testing "no chat yet: nothing to update, the first prompt carries it"
        (reset! sent [])
        (commands/cmd-toggle-trust {:trust false :chat-id nil :opts {} :items []})
        (is (= [] @sent))))))

(deftest registry-completeness-test
  (testing "all expected commands present"
    (doseq [cmd ["/model" "/agent" "/new" "/chats"
                 "/clear" "/help" "/quit" "/login"]]
      (is (contains? commands/command-registry cmd)
          (str cmd " missing from registry"))))

  (testing "each entry has non-empty :doc string"
    (doseq [[name {:keys [doc]}] commands/command-registry]
      (is (string? doc) (str name " :doc must be a string"))
      (is (seq doc)     (str name " :doc must be non-empty"))))

  (testing "each entry has :handler fn"
    (doseq [[name {:keys [handler]}] commands/command-registry]
      (is (fn? handler) (str name " :handler must be a fn")))))
