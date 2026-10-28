(ns eca-cli.view.blocks-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.theme :as theme]
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

(defn- plain [lines] (mapv theme/strip-ansi lines))

(deftest render-item-lines-test
  (testing ":user item — 3 lines, the message behind a coloured bar"
    (let [lines (blocks/render-item-lines {:type :user :text "hello"} 80)]
      (is (= ["" "┃ hello" ""] (plain lines)))
      (is (clojure.string/starts-with? (second lines) (theme/sgr :user-bar)))))

  (testing ":user item — every wrapped line carries the bar; markdown applies"
    (is (= ["" "┃ one two" "┃ three" ""]
           (plain (blocks/render-item-lines {:type :user :text "one **two** three"} 10)))))

  (testing ":assistant-text item — ◆ prefix on first line, indent on rest"
    (let [lines (plain (blocks/render-item-lines {:type :assistant-text :text "line1\nline2"} 80))]
      (is (= ["◆ line1" "  line2"] lines))))

  (testing ":assistant-text renders markdown"
    (is (= ["◆ • a" "  • b"]
           (plain (blocks/render-item-lines {:type :assistant-text :text "- a\n- b"} 80)))))

  (testing ":streaming-text item — ◆ prefix"
    (let [lines (plain (blocks/render-item-lines {:type :streaming-text :text "streaming"} 80))]
      (is (= ["◆ streaming"] lines))))

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

(deftest format-duration-test
  (is (nil? (blocks/format-duration 850)) "under a second is noise")
  (is (nil? (blocks/format-duration nil)))
  (is (= "12s" (blocks/format-duration 12400)))
  (is (= "2m" (blocks/format-duration 120000)))
  (is (= "2m 5s" (blocks/format-duration 125000))))

(deftest activity-details-test
  (testing "a finished tool call shows how long it took"
    (is (= ["✓ $ bb test  12s"]
           (plain (blocks/render-item-lines
                    {:type :tool-call :state :called :summary "$ bb test" :total-ms 12000} 80)))))
  (testing "a tool call awaiting approval has its summary in bold"
    (is (clojure.string/includes?
          (first (blocks/render-item-lines {:type :tool-call :state :run :summary "rm -rf"} 80))
          (str (theme/sgr {:bold true}) "rm -rf"))))
  (testing "thoughts show their duration"
    (is (= ["▸ Thought for 3s"]
           (plain (blocks/render-item-lines {:type :thinking :status :thought :total-ms 3000} 80))))
    (is (= ["▸ Thinking…"]
           (plain (blocks/render-item-lines {:type :thinking :status :thinking} 80)))))
  (testing "expanded tool call shows its output"
    (let [lines (plain (blocks/render-item-lines
                         {:type :tool-call :state :called :name "shell" :summary "ls"
                          :out-text "a.txt" :expanded? true} 40))]
      (is (some #(clojure.string/includes? % "Output") lines))
      (is (some #(clojure.string/includes? % "a.txt") lines)))))

(deftest url-item-test
  (is (= ["↗ ECA docs" "  https://eca.dev"]
         (plain (blocks/render-item-lines {:type :url :title "ECA docs" :url "https://eca.dev"} 80))))
  (is (= ["↗ https://eca.dev"]
         (plain (blocks/render-item-lines {:type :url :title "" :url "https://eca.dev"} 80)))))

(deftest system-item-test
  (testing "a ⚠ already in the text is not doubled"
    (is (= ["⚠ No models available"]
           (plain (blocks/render-item-lines {:type :system :text "⚠ No models available"} 80)))))
  (testing "multi-line system text keeps its indentation"
    (is (= ["⚠ Last lines:" "  │ boom"]
           (plain (blocks/render-item-lines {:type :system :text "Last lines:\n│ boom"} 80))))))

(deftest server-note-test
  (testing "the server's message keeps its lines (the /context grid), marked with • not ◆"
    (is (= ["• Context Usage"
            "  "
            "  🟦⬜  Instructions:"
            "  ⬜⬜    🟦 System prompt  648 tokens (0.1%)"]
           (plain (blocks/render-item-lines
                    {:type :server-note
                     :text "Context Usage\n\n🟦⬜  Instructions:\n⬜⬜    🟦 System prompt  648 tokens (0.1%)"}
                    80)))))
  (testing "markdown still applies, in the system colour"
    (let [[line] (blocks/render-item-lines {:type :server-note :text "**Skills**"} 80)]
      (is (= "• Skills" (theme/strip-ansi line)))
      (is (clojure.string/includes? line (theme/sgr :server-note))))))

(deftest focus-test
  (testing "the focused block's first line is highlighted as one plain run"
    (let [[line] (blocks/render-item-lines
                   {:type :tool-call :state :called :summary "read" :focused? true} 20)]
      (is (= (str (theme/sgr :focus) "✓ read" theme/reset) line)))))

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

  (testing "truncation boundary is exact: 500 lines untouched, 501 clipped"
    (let [d500 (clojure.string/join "\n" (map #(str "+" %) (range 500)))
          d501 (clojure.string/join "\n" (map #(str "+" %) (range 501)))
          l500 (blocks/render-diff {:diff d500} 80)
          l501 (blocks/render-diff {:diff d501} 80)]
      (is (= 500 (count l500)))
      (is (not-any? #(clojure.string/includes? % "[truncated]") l500))
      (is (= 501 (count l501)))
      (is (clojure.string/includes? (last l501) "[truncated]"))))

  (testing "total diff size is bounded before split — a huge multi-line diff stays cheap"
    ;; Guards the eager str/split-lines DoS: cost must not scale with total
    ;; diff size. A million-line diff renders the same 501 lines as any large diff.
    (let [huge  (clojure.string/join "\n" (repeat 1000000 "+x"))
          lines (blocks/render-diff {:diff huge} 80)]
      (is (= 501 (count lines)))
      (is (clojure.string/includes? (last lines) "[truncated]"))))

  (testing "empty / whitespace diff handled without throwing"
    (is (vector? (blocks/render-diff {:diff ""} 80)))
    (is (vector? (blocks/render-diff {:diff "   "} 80))))

  (testing "a pathological newline-free line is length-capped (no O(n²) wrap hang)"
    (let [big   (str "+" (apply str (repeat 1000000 "x")))
          lines (blocks/render-diff {:diff big} 80)]
      ;; 4 rows of 80 cols + elision → a handful of lines, not thousands
      (is (< (count lines) 10))
      (is (clojure.string/includes? (last lines) "…")))))

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
        (is (clojure.string/includes? (first lines) "−1"))))

    (testing "collapsed one-liner names the file, as eca-emacs: summary, file, +N −M"
      (let [line (theme/strip-ansi
                   (first (blocks/render-item-lines
                            {:type :tool-call :state :called :name "edit_file"
                             :summary "Editing" :edit edit :expanded? false :focused? false} 80)))]
        (is (= "✓ Editing foo.clj +1 −1" line))))

    (testing "a new file shows +N −0 with its name"
      (let [line (theme/strip-ansi
                   (first (blocks/render-item-lines
                            {:type :tool-call :state :called :name "write_file" :summary "Creating"
                             :edit {:path "/tmp/a/new.md" :diff "+x" :lines-added 1 :lines-removed 0}} 80)))]
        (is (= "✓ Creating new.md +1 −0" line))))

    (testing "expanded: the header names the file and the path tops the diff"
      (let [lines (plain (blocks/render-item-lines
                           {:type :tool-call :state :called :name "edit_file"
                            :summary "Editing" :edit edit :expanded? true} 80))]
        (is (clojure.string/includes? (first lines) "edit_file  Editing foo.clj +1 −1"))
        (is (= "  /tmp/foo.clj" (second lines)))))))

(def ^:private sh {:commands [{:command "git" :args ["status"] :approval-key "git status"}]})

(defn- shell-item [m]
  (merge {:type :tool-call :state :called :name "shell_command" :summary "$ git st..." :shell sh} m))

(deftest shell-result-test
  (is (= {:exit 0 :stderr nil :stdout "a\nb"} (blocks/shell-result "Exit code: 0\nStdout:\na\nb")))
  (is (= {:exit 1 :stderr "boom" :stdout "ok"} (blocks/shell-result "Exit code: 1\nStderr:\nboom\nStdout:\nok")))
  (is (= {:exit 0 :stderr nil :stdout nil} (blocks/shell-result "Exit code: 0")))
  (is (= {:stdout "Command timed out after 10 ms"} (blocks/shell-result "Command timed out after 10 ms")))
  (is (nil? (blocks/shell-result nil))))

(deftest shell-collapsed-test
  (testing "the whole command, not the server's cropped summary, then an output preview"
    (is (= ["✓ $ git status && git log --oneline -5  2s"
            "  ⎿ one"
            "    two"
            "    three"
            "    … +2 lines (alt+o to expand)"]
           (plain (blocks/render-item-lines
                    (shell-item {:arguments {:command "git status && git log --oneline -5"}
                                 :out-text "Exit code: 0\nStdout:\none\ntwo\nthree\nfour\nfive"
                                 :total-ms 2300})
                    80)))))
  (testing "a `cd <working dir> &&` prefix is dropped"
    (is (= "✓ $ ls"
           (first (plain (blocks/render-item-lines
                           (shell-item {:arguments {:command (str "cd " (System/getProperty "user.dir") " && ls")}
                                        :out-text "Exit code: 0\nStdout:\na"})
                           80))))))
  (testing "a failure says its exit code, its stderr in red"
    (let [lines (blocks/render-item-lines
                  (shell-item {:error? true :arguments {:command "bb test"}
                               :out-text "Exit code: 1\nStderr:\nboom\nStdout:\nok"})
                  80)]
      (is (= ["✗ $ bb test  exit 1" "  ⎿ boom" "    ok"] (plain lines)))
      (is (clojure.string/includes? (second lines) (str ansi-red "boom")))))
  (testing "no output, running, rejected"
    (is (= ["✓ $ touch a" "  ⎿ (no output)"]
           (plain (blocks/render-item-lines (shell-item {:arguments {:command "touch a"} :out-text "Exit code: 0"}) 80))))
    (is (= ["◌ $ sleep 5" "  ⎿ running…"]
           (plain (blocks/render-item-lines (shell-item {:state :running :arguments {:command "sleep 5"}}) 80))))
    (is (= ["✗ $ rm -rf /" "  ⎿ rejected"]
           (plain (blocks/render-item-lines (shell-item {:state :rejected :arguments {:command "rm -rf /"}}) 80)))))
  (testing "a long multi-line command keeps its lines, clipped after three rows"
    (is (= ["◌ $ cat <<'EOF' > x" "    line1" "    line2…  · background" "  ⎿ running…"]
           (plain (blocks/render-item-lines
                    (shell-item {:state :running :shell (assoc sh :background? true)
                                 :arguments {:command "cat <<'EOF' > x\nline1\nline2\nline3\nEOF"}})
                    40)))))
  (testing "long output lines are kept to one row"
    (let [lines (plain (blocks/render-item-lines
                         (shell-item {:arguments {:command "cat big"}
                                      :out-text (str "Exit code: 0\nStdout:\n" (apply str (repeat 200 "x")))})
                         40))]
      (is (= 2 (count lines)))
      (is (clojure.string/ends-with? (second lines) "…"))
      (is (<= (theme/width (second lines)) 40))))
  (testing "expanded: the output box drops the server's headers"
    (let [lines (plain (blocks/render-item-lines
                         (shell-item {:expanded? true :arguments {:command "bb test"}
                                      :out-text "Exit code: 1\nStderr:\nboom"})
                         60))]
      (is (some #(clojure.string/includes? % "Output · exit 1") lines))
      (is (some #(clojure.string/includes? % "boom") lines))
      (is (not-any? #(clojure.string/includes? % "Stderr:") lines)))))

(deftest display-path-test
  (let [cwd (System/getProperty "user.dir")]
    (is (= "src/a.clj" (blocks/display-path (str cwd "/src/a.clj"))))
    (is (= "/elsewhere/a.clj" (blocks/display-path "/elsewhere/a.clj")))))
