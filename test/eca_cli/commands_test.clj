(ns eca-cli.commands-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.commands :as commands]))

(deftest registry-completeness-test
  (testing "all expected commands present"
    (doseq [cmd ["/model" "/agent" "/new" "/sessions"
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
