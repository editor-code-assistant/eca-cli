(ns eca-cli.markdown.highlight-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.markdown.highlight :as hl]))

(defn- styled
  "Line segments as [role text] pairs, plain text as strings."
  [info code]
  (mapv (fn [segs] (mapv (fn [{:keys [text style]}] (if style [style text] text)) segs))
        (hl/highlight info (str/split-lines code))))

(deftest families-test
  (is (some? (hl/family-of "clojure")))
  (is (some? (hl/family-of "TS title=\"x\"")) "first word, case-insensitive")
  (is (nil? (hl/family-of "brainfuck")))
  (is (nil? (hl/family-of nil))))

(deftest unknown-language-test
  (is (= [["plain"]] (styled "nope" "plain"))))

(deftest clojure-test
  (is (= [["(" [:syn-keyword "defn"] " f [x] " [:syn-string "\"s\""] " " [:syn-constant ":k"]
           " " [:syn-number "1"] " " [:syn-comment "; c"]]]
         (styled "clojure" "(defn f [x] \"s\" :k 1 ; c"))))

(deftest multi-line-state-test
  (testing "block comments carry over"
    (is (= [["a " [:syn-comment "/* x"]]
            [[:syn-comment "y */"] " b"]]
           (styled "js" "a /* x\ny */ b"))))
  (testing "triple-quoted strings carry over"
    (is (= [[[:syn-string "\"\"\"doc"]] [[:syn-string "end\"\"\""]]]
           (styled "python" "\"\"\"doc\nend\"\"\""))))
  (testing "escaped quotes do not end a string"
    (is (= [[[:syn-string "\"a\\\"b\""]]] (styled "js" "\"a\\\"b\"")))))

(deftest comment-detection-test
  (is (= [["a#b"]] (styled "bash" "a#b")) "# mid-word is not a comment")
  (is (= [["x " [:syn-comment "# c"]]] (styled "bash" "x # c"))))

(deftest config-keys-test
  (is (= [["- " [:syn-type "my-key"] ": " [:syn-constant "true"]]] (styled "yaml" "- my-key: true")))
  (is (= [[[:syn-type "title"] " = " [:syn-string "\"x\""]]] (styled "toml" "title = \"x\""))))

(deftest diff-test
  (is (= [[[:diff-hunk "@@ -1 +1 @@"]] [[:diff-del "-a"]] [[:diff-add "+b"]] [" c"]]
         (styled "diff" "@@ -1 +1 @@\n-a\n+b\n c"))))
