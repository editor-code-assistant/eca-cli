(ns eca-cli.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.markdown :as md]
            [eca-cli.theme :as theme]
            [nextjournal.markdown]))

(defn- plain [lines] (mapv theme/strip-ansi lines))

(defn- render-plain [text width] (plain (md/render text width)))

(def ^:private sample (slurp "test/eca_cli/fixtures/markdown-sample.md"))

(deftest paragraphs-test
  (testing "wraps at word boundaries"
    (is (= ["one two" "three"] (render-plain "one two three" 8))))
  (testing "soft line breaks are kept"
    (is (= ["first line" "second line"] (render-plain "first line\nsecond line" 80))))
  (testing "paragraphs are separated by one blank line"
    (is (= ["a" "" "b"] (render-plain "a\n\nb" 80))))
  (testing "empty text renders nothing"
    (is (= [] (md/render "" 80)))))

(deftest inline-styles-test
  (let [[line] (md/render "**b** *i* ~~s~~ `c`" 80)]
    (is (= "b i s c" (theme/strip-ansi line)) "markers are gone")
    (is (str/includes? line (str (theme/sgr {:bold true}) "b")))
    (is (str/includes? line (str (theme/sgr {:italic true}) "i")))
    (is (str/includes? line (str (theme/sgr {:strike true}) "s")))
    (is (str/includes? line (str (theme/sgr :md-code) "c")))))

(deftest styles-never-span-lines-test
  (testing "a bold phrase wrapped over two lines is bold on both, each line resets"
    (let [lines (md/render "**alpha beta gamma**" 11)]
      (is (= ["alpha beta" "gamma"] (plain lines)))
      (is (every? #(str/starts-with? % (theme/sgr {:bold true})) lines))
      (is (every? #(str/ends-with? % theme/reset) lines))))
  (testing "a word mixing styles is not split at the style boundary"
    (is (= ["xx" "boldness"] (render-plain "xx **bold**ness" 9)))))

(deftest links-test
  (testing "text, then the destination in muted"
    (let [[line] (md/render "see [docs](https://eca.dev)" 80)]
      (is (= "see docs (https://eca.dev)" (theme/strip-ansi line)))
      (is (str/includes? line (str (theme/sgr :md-link) "docs")))
      (is (str/includes? line (str (theme/sgr :md-url) " (https://eca.dev)")))))
  (testing "autolinks and mailto do not repeat themselves"
    (is (= ["go https://eca.dev now"] (render-plain "go https://eca.dev now" 80)))
    (is (= ["mail a@b.co"] (render-plain "mail <a@b.co>" 80))
        "an email autolink shows the address once, not its mailto: link")))

(deftest headings-test
  (let [lines (md/render "# Top\n\n## Second\n\n#### Deep" 80)]
    (is (= ["Top" "" "Second" "" "Deep"] (plain lines)) "no # markers")
    (is (str/starts-with? (first lines) (theme/sgr :md-h1)))
    (is (str/starts-with? (nth lines 2) (theme/sgr :md-h2)))))

(deftest lists-test
  (testing "bullets by depth, nested indentation"
    (is (= ["• a" "  ◦ nested" "• b"] (render-plain "- a\n  - nested\n- b" 80))))
  (testing "hanging indent for wrapped items"
    (is (= ["• one two" "  three"] (render-plain "- one two three" 10))))
  (testing "ordered lists right-align their numbers"
    (let [lines (render-plain (str/join "\n" (map #(str % ". item") (range 1 11))) 80)]
      (is (= " 1. item" (first lines)))
      (is (= "10. item" (last lines)))))
  (testing "task lists"
    (is (= ["[x] done" "[ ] todo"] (render-plain "- [x] done\n- [ ] todo" 80))))
  (testing "loose lists keep a blank line between items"
    (is (= ["• a" "" "• b"] (render-plain "- a\n\n- b" 80)))))

(deftest blockquote-test
  (is (= ["│ quoted text" "│ continues"] (render-plain "> quoted text\n> continues" 80)))
  (testing "quote text is italic"
    (is (str/includes? (first (md/render "> q" 80)) (theme/sgr :md-quote)))))

(deftest code-blocks-test
  (testing "fences stay, with the language; indentation is preserved"
    (is (= ["```clojure" "(defn f [x]" "  (inc x))" "```"]
           (render-plain "```clojure\n(defn f [x]\n  (inc x))\n```" 80))))
  (testing "blank lines inside code are kept"
    (is (= ["```" "a" "" "b" "```"] (render-plain "```\na\n\nb\n```" 80))))
  (testing "long lines hard-wrap instead of being cut off"
    (is (= ["```" "abcde" "fghij" "```"] (render-plain "```\nabcdefghij\n```" 5))))
  (testing "known languages are highlighted"
    (is (str/includes? (second (md/render "```clojure\n(defn f [])\n```" 80))
                       (str (theme/sgr :syn-keyword) "defn"))))
  (testing "an unclosed fence (still streaming) renders as code"
    (is (= ["intro" "" "```js" "let x" "```"] (render-plain "intro\n\n```js\nlet x" 80)))))

(deftest tables-test
  (let [table "| A | Right |\n|---|------:|\n| x | 1 |\n| y | 22 |"]
    (testing "box drawing with aligned columns"
      (is (= ["┌───┬───────┐"
              "│ A │ Right │"
              "├───┼───────┤"
              "│ x │     1 │"
              "│ y │    22 │"
              "└───┴───────┘"]
             (render-plain table 80))))
    (testing "header cells are bold"
      (is (str/includes? (second (md/render table 80)) (str (theme/sgr :md-th) "A")))))
  (testing "cells wrap to fit the width, every line exactly as wide"
    (let [lines (render-plain "| Name | Description |\n|---|---|\n| a | a fairly long description that must wrap |" 30)]
      (is (every? #(= 30 (theme/width %)) lines))
      (is (> (count lines) 5) "the long row spans several lines")))
  (testing "too narrow for columns: header: value records"
    (is (= ["A: x" "B: y"] (render-plain "| A | B |\n|---|---|\n| x | y |" 6)))))

(deftest rule-and-misc-test
  (is (= [(apply str (repeat 12 "─"))] (render-plain "---" 12)))
  (is (= ["[image: logo]"] (render-plain "![logo](x.png)" 80)))
  (is (= ["some **bold"] (render-plain "some **bold" 80)) "unclosed emphasis stays literal"))

(deftest at-refs-test
  (let [[line] (md/render "check @src/foo.clj and a@b" 80 {:at-refs? true})]
    (is (str/includes? line (str (theme/sgr :at-ref) "@src/foo.clj")))
    (is (not (str/includes? line (str (theme/sgr :at-ref) "@b"))) "mid-word @ is not a reference"))
  (is (not (str/includes? (first (md/render "@x" 80)) (theme/sgr :at-ref))) "off by default"))

(deftest fits-width-test
  (doseq [w [20 33 50 80]]
    (testing (str "no line exceeds " w " columns")
      (is (every? #(<= (theme/width %) w) (md/render sample w))))))

(deftest fallback-test
  (with-redefs [nextjournal.markdown/parse (fn [_] (throw (ex-info "boom" {})))]
    (is (= ["**raw**" "text"] (md/render "**raw** text" 8)) "plain wrap on renderer failure")))
