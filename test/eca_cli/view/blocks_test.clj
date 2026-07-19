(ns eca-cli.view.blocks-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.view.blocks :as blocks]))

(deftest render-tool-icon-test
  (is (clojure.string/includes? (blocks/render-tool-icon {:state :preparing})   "◌"))
  (is (clojure.string/includes? (blocks/render-tool-icon {:state :run})         "▸"))
  (is (clojure.string/includes? (blocks/render-tool-icon {:state :running})     "◌"))
  (is (clojure.string/includes? (blocks/render-tool-icon {:state :called})      "✓"))
  (is (clojure.string/includes? (blocks/render-tool-icon {:state :called :error? true}) "✗"))
  (is (clojure.string/includes? (blocks/render-tool-icon {:state :rejected})    "✗"))
  (is (clojure.string/includes? (blocks/render-tool-icon {:state :unknown})     "◌"))
  ;; All icons must be ANSI-wrapped (no bare emoji → consistent 1-col width for JLine diff)
  (is (clojure.string/starts-with? (blocks/render-tool-icon {:state :called}) "\033[")))

(deftest render-item-lines-test
  (testing ":user item — 3 lines with symbol and reverse-video highlight"
    (let [lines (blocks/render-item-lines {:type :user :text "hello"} 80)]
      (is (= 3 (count lines)))
      (is (= "" (first lines)))
      (is (clojure.string/includes? (second lines) "❯"))
      (is (clojure.string/includes? (second lines) "hello"))
      (is (= "" (last lines)))))

  (testing ":assistant-text item — ◆ prefix on first line, indent on rest"
    (let [lines (blocks/render-item-lines {:type :assistant-text :text "line1\nline2"} 80)]
      (is (= 2 (count lines)))
      (is (clojure.string/starts-with? (first lines) "◆ "))
      (is (clojure.string/includes? (first lines) "line1"))
      (is (clojure.string/starts-with? (second lines) "  "))
      (is (clojure.string/includes? (second lines) "line2"))))

  (testing ":streaming-text item — ◆ prefix"
    (let [lines (blocks/render-item-lines {:type :streaming-text :text "streaming"} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/starts-with? (first lines) "◆ "))))

  (testing ":tool-call with summary"
    (let [lines (blocks/render-item-lines {:type :tool-call :state :called :summary "read foo.clj"} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/includes? (first lines) "read foo.clj"))))

  (testing ":tool-call falls back to name"
    (let [lines (blocks/render-item-lines {:type :tool-call :state :called :name "read_file"} 80)]
      (is (clojure.string/includes? (first lines) "read_file"))))

  (testing ":system item"
    (let [lines (blocks/render-item-lines {:type :system :text "Connection lost"} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/includes? (first lines) "Connection lost"))
      (is (clojure.string/includes? (first lines) "⚠"))))

  (testing "unknown type"
    (is (= [] (blocks/render-item-lines {:type :unknown} 80)))))

(deftest render-item-lines-rich-test
  (testing "collapsed tool-call renders 1 line"
    (let [lines (blocks/render-item-lines
                  {:type :tool-call :state :called :name "read_file"
                   :summary "foo.clj" :expanded? false :focused? false} 80)]
      (is (= 1 (count lines)))))

  (testing "expanded tool-call with args and output renders multiple lines with content"
    (let [lines (blocks/render-item-lines
                  {:type :tool-call :state :called :name "read_file"
                   :summary "foo.clj" :args-text "{\"path\":\"foo.clj\"}"
                   :out-text "content here" :expanded? true :focused? false} 80)]
      (is (> (count lines) 1))
      (is (some #(clojure.string/includes? % "foo.clj") lines))
      (is (some #(clojure.string/includes? % "content") lines))))

  (testing "collapsed thinking renders 1 line with ▸"
    (let [lines (blocks/render-item-lines
                  {:type :thinking :id "r1" :text "I should..."
                   :status :thought :expanded? false :focused? false} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/includes? (first lines) "▸"))))

  (testing "expanded thinking renders > 1 line with text content"
    (let [lines (blocks/render-item-lines
                  {:type :thinking :id "r1" :text "I should think carefully."
                   :status :thought :expanded? true :focused? false} 80)]
      (is (> (count lines) 1))
      (is (some #(clojure.string/includes? % "I should") lines))))

  (testing "collapsed hook renders 1 line with status"
    (let [lines (blocks/render-item-lines
                  {:type :hook :id "h1" :name "pre-tool"
                   :status :ok :expanded? false :focused? false} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/includes? (first lines) "pre-tool"))))

  (testing "eca__spawn_agent collapsed with sub-items shows step count"
    (let [lines (blocks/render-item-lines
                  {:type :tool-call :state :called :name "eca__spawn_agent"
                   :summary "explorer" :expanded? false :focused? false
                   :sub-items [{:type :tool-call :name "read_file" :state :called
                                :expanded? false :focused? false}]} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/includes? (first lines) "▸"))))

  (testing "eca__spawn_agent expanded renders sub-items indented"
    (let [lines (blocks/render-item-lines
                  {:type :tool-call :state :called :name "eca__spawn_agent"
                   :summary "explorer" :expanded? true :focused? false
                   :sub-items [{:type :tool-call :name "read_file" :state :called
                                :summary "foo.clj" :expanded? false :focused? false}]} 80)]
      (is (> (count lines) 1))
      (is (some #(clojure.string/starts-with? % "  ") (rest lines))))))

(def ^:private ansi-red   "\033[31m")
(def ^:private ansi-green "\033[32m")
(def ^:private ansi-grey  "\033[38;5;245m")
(def ^:private ansi-dim   "\033[2;38;5;245m")

(deftest render-diff-test
  (testing "each line coloured by leading character"
    (let [lines (blocks/render-diff
                  {:diff "@@ -1,2 +1,2 @@\n-removed\n+added\n unchanged"} 80)]
      (is (= 4 (count lines)))
      (is (clojure.string/starts-with? (nth lines 0) ansi-dim))   ; hunk header
      (is (clojure.string/starts-with? (nth lines 1) ansi-red))   ; removed
      (is (clojure.string/starts-with? (nth lines 2) ansi-green)) ; added
      (is (clojure.string/starts-with? (nth lines 3) ansi-grey))  ; context
      (is (clojure.string/includes? (nth lines 1) "removed"))
      (is (clojure.string/includes? (nth lines 2) "added"))))

  (testing ">500 rendered lines truncate with a [truncated] footer"
    (let [big   (clojure.string/join "\n" (repeat 600 "+x"))
          lines (blocks/render-diff {:diff big} 80)]
      (is (= 501 (count lines)))
      (is (clojure.string/includes? (last lines) "[truncated]"))))

  (testing "empty / whitespace diff handled without throwing"
    (is (vector? (blocks/render-diff {:diff ""} 80)))
    (is (vector? (blocks/render-diff {:diff "   "} 80)))))

(deftest render-item-lines-diff-test
  (let [edit {:path "/tmp/foo.clj" :diff "@@ -1,1 +1,1 @@\n-a\n+b"
              :lines-added 1 :lines-removed 1}]
    (testing "expanded :tool-call with :edit renders the diff (delegates to render-diff)"
      (let [lines (blocks/render-item-lines
                    {:type :tool-call :state :called :name "edit_file"
                     :summary "foo.clj" :edit edit :expanded? true :focused? false} 80)]
        (is (some #(clojure.string/includes? % "@@") lines))
        (is (some #(clojure.string/starts-with? % ansi-red) lines))
        (is (some #(clojure.string/starts-with? % ansi-green) lines))
        ;; fallback boxes must NOT appear
        (is (not-any? #(clojure.string/includes? % "┌─ Arguments") lines))))

    (testing "expanded :tool-call without :edit still renders arguments/output boxes"
      (let [lines (blocks/render-item-lines
                    {:type :tool-call :state :called :name "read_file"
                     :summary "foo.clj" :args-text "{\"path\":\"foo.clj\"}"
                     :out-text "content here" :expanded? true :focused? false} 80)]
        (is (some #(clojure.string/includes? % "Arguments") lines))
        (is (some #(clojure.string/includes? % "content") lines))))

    (testing "collapsed one-liner shows +N −M badge"
      (let [lines (blocks/render-item-lines
                    {:type :tool-call :state :called :name "edit_file"
                     :summary "foo.clj" :edit edit :expanded? false :focused? false} 80)]
        (is (= 1 (count lines)))
        (is (clojure.string/includes? (first lines) "+1"))
        (is (clojure.string/includes? (first lines) "−1"))))))
