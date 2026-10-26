(ns eca-cli.wrap-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.wrap :as wrap]))

(deftest wrap-text-test
  (testing "word wrap"
    (is (= ["hello" "world"] (wrap/wrap-text "hello world" 7)))
    (is (= ["hi there"] (wrap/wrap-text "hi there" 80))))

  (testing "leading indentation of each paragraph is kept"
    (is (= ["  (str x)"] (wrap/wrap-text "  (str x)" 80)))
    (is (= ["(defn f [x]" "  (inc x))"] (wrap/wrap-text "(defn f [x]\n  (inc x))" 80)))
    (is (= ["    - nested"] (wrap/wrap-text "    - nested" 80))))

  (testing "a soft-wrapped line never starts with whitespace"
    (is (= ["  aaa" "bbb"] (wrap/wrap-text "  aaa bbb" 6))))

  (testing "indentation after a zero-width escape survives (diff context lines)"
    (let [[line] (wrap/wrap-text "\033[38;5;245m unchanged" 80)]
      (is (= " unchanged" (subs line (count "\033[38;5;245m"))))))

  (testing "indentation wider than the line is dropped rather than wrapped"
    (is (= ["x"] (wrap/wrap-text "      x" 4))))

  (testing "long words hard-break"
    (is (= ["abcd" "efgh" "ij"] (wrap/wrap-text "abcdefghij" 4))))

  (testing "nil, empty and zero width"
    (is (= [""] (wrap/wrap-text nil 10)))
    (is (= [""] (wrap/wrap-text "" 10)))
    (is (= ["abc"] (wrap/wrap-text "abc" 0)))))
