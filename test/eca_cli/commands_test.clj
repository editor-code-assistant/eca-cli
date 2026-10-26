(ns eca-cli.commands-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.commands :as commands]
            [eca-cli.theme :as theme]))

(deftest help-test
  (let [[s _]  (commands/cmd-show-help {:items []})
        item   (peek (:items s))
        lines  (str/split-lines (theme/strip-ansi (:text item)))]
    (is (= :info (:level item)) "an informational note, not a warning")
    (is (= "Available commands:" (first lines)))
    (testing "descriptions aligned in one column"
      (is (= 1 (count (set (map #(count (re-find #"^\S+\s+" %)) (rest lines)))))))))

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
