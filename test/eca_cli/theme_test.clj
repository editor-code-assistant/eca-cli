(ns eca-cli.theme-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.theme :as theme]))

(deftest sgr-test
  (testing "ANSI 16 colours map to 30-37 / 90-97 so they follow the terminal palette"
    (is (= "\033[31m" (theme/sgr {:fg 1})))
    (is (= "\033[92m" (theme/sgr {:fg 10})))
    (is (= "\033[44m" (theme/sgr {:bg 4}))))
  (testing "256-colour indices and hex colours"
    (is (= "\033[38;5;245m" (theme/sgr {:fg 245})))
    (is (= "\033[38;2;97;175;239m" (theme/sgr {:fg "#61afef"})))
    (is (= "\033[48;2;97;175;239m" (theme/sgr {:bg "61afef"}))))
  (testing "attributes come first, then colours"
    (is (= "\033[2;38;5;245m" (theme/sgr {:fg 245 :dim true})))
    (is (= "\033[1;3;4;7;9;36m" (theme/sgr {:bold true :italic true :underline true
                                            :reverse true :strike true :fg 6}))))
  (testing "roles resolve through the palette"
    (is (= (theme/sgr (:error theme/palette)) (theme/sgr :error))))
  (testing "nothing to set -> empty string; invalid colours are ignored"
    (is (= "" (theme/sgr {})))
    (is (= "" (theme/sgr nil)))
    (is (= "" (theme/sgr {:fg "not-a-colour"})))))

(deftest no-color-test
  (binding [theme/*color?* false]
    (is (= "" (theme/sgr {:fg 1 :bg 2})) "colours dropped")
    (is (= "\033[1m" (theme/sgr {:fg 1 :bold true})) "attributes kept")))

(deftest paint-test
  (is (= "\033[31mx\033[0m" (theme/paint {:fg 1} "x")))
  (is (= "x" (theme/paint {} "x")) "no style, no escapes")
  (is (= "" (theme/paint :error "")) "nothing to paint")
  (is (= "42" (theme/strip-ansi (theme/paint :accent 42)))))

(deftest width-and-pad-test
  (is (= 2 (theme/width (theme/paint :accent "ab"))) "escapes have no width")
  (is (= 4 (theme/width "你好")) "wide characters count double")
  (is (= "ab  " (theme/pad-right "ab" 4)))
  (is (= "abcdef" (theme/pad-right "abcdef" 4)) "never truncates"))

(deftest merge-styles-test
  (is (= {:fg 1 :bold true} (theme/merge-styles {:fg 2 :bold true} {:fg 1})))
  (is (= (assoc (:md-h2 theme/palette) :underline true)
         (theme/merge-styles :md-h2 {:underline true}))))

(deftest agent-color-test
  (is (= 6 (theme/agent-color "code")))
  (is (= 5 (theme/agent-color "plan")))
  (is (= (theme/agent-color "reviewer") (theme/agent-color "reviewer")) "stable")
  (is (int? (theme/agent-color nil))))

(deftest hex->rgb-test
  (is (= [255 0 16] (theme/hex->rgb "#ff0010")))
  (is (nil? (theme/hex->rgb "#fff")))
  (is (nil? (theme/hex->rgb nil))))
