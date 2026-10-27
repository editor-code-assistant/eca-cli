(ns eca-cli.view-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [eca-cli.render :as render]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(def ^:private pad-to-height     #'view/pad-to-height)
(def ^:private render-login      #'view/render-login)

(defn- render-status-bar
  "The status line as plain text."
  [state]
  (theme/strip-ansi (view/render-status-bar state)))

(deftest divider-test
  (is (= "" (view/divider 0)))
  (is (= "──────────" (theme/strip-ansi (view/divider 10))))
  (is (= 40 (theme/width (view/divider 40))))
  (testing "coloured by role, dim border by default"
    (is (str/starts-with? (view/divider 5) (theme/sgr :border)))
    (is (str/starts-with? (view/divider 5 :warning) (theme/sgr :warning)))))

(deftest render-connecting-test
  (let [home  (System/getProperty "user.home")
        state {:connecting-since 1000000
               :server           {:log-file (java.io.File. (str home "/.local/state/eca/eca-cli.log"))}}]
    (testing "first seconds: just the spinner line"
      (let [s (theme/strip-ansi (view/render-connecting state 1001000))]
        (is (re-find #"Connecting to ECA server…$" s))))
    (testing "after a few seconds: elapsed time"
      (is (re-find #"Connecting to ECA server… 5s$"
                   (theme/strip-ansi (view/render-connecting state 1005000)))))
    (testing "when it drags on: where to look, home-relative"
      (is (re-find #"… 20s  \(taking long\? see ~/\.local/state/eca/eca-cli\.log\)$"
                   (theme/strip-ansi (view/render-connecting state 1020000)))))))

(deftest render-working-test
  (let [now 1000000]
    (testing "spinner, activity, elapsed time and how to stop"
      (is (re-find #"^. Working… 14s  esc to interrupt$"
                   (theme/strip-ansi (view/render-working {:turn-started-at (- now 14000) :items []} now)))))
    (testing "the activity follows the tail of the chat"
      (is (str/includes? (theme/strip-ansi (view/render-working {:current-text "hi" :items []} now)) "Writing…"))
      (is (str/includes? (theme/strip-ansi (view/render-working {:items [{:type :thinking :status :thinking}]} now))
                         "Thinking…"))
      (is (str/includes? (theme/strip-ansi (view/render-working {:items [{:type :tool-call :state :running}]} now))
                         "Running tools…")))))

(deftest render-list-test
  (let [lst (cl/item-list ["/agent  —  Open agent picker" "/model  —  Open model picker"
                                              "/mcp  —  View MCP server status"]
                                             :height 8)
        out (view/render-list :command (cl/select lst 1) 80)]
    (testing "selected row marked; descriptions aligned in a muted column"
      (is (= ["  /agent  Open agent picker"
              "❯ /model  Open model picker"
              "  /mcp    View MCP server status"]
             (str/split-lines (theme/strip-ansi out))))
      (is (str/includes? out (str (theme/sgr :accent-bold) "❯ "))))
    (testing "rows never overflow; the description is cut first"
      (is (every? #(<= (theme/width %) 20)
                  (str/split-lines (view/render-list :command lst 20)))))
    (testing "empty list"
      (is (str/includes? (theme/strip-ansi (view/render-list :model (cl/item-list []) 80))
                         "nothing matches")))))

(deftest view-connection-modes-test
  (let [base {:width 60 :height 12 :chat-lines [] :scroll-offset 0 :items []
              :input (ti/text-input) :opts {:workspace "/tmp/ws"} :init-tasks {} :mcps {}}]
    (testing ":connecting shows the connecting indicator"
      (is (re-find #"Connecting to ECA server"
                   (view/view (assoc base :mode :connecting
                                     :connecting-since (System/currentTimeMillis))))))
    (testing ":disconnected replaces the input with a hint"
      (let [out (view/view (assoc base :mode :disconnected))]
        (is (re-find #"ECA server is not running\. Press Ctrl\+C twice to exit\." out))))))

(deftest rebuild-chat-lines-test
  (testing "empty"
    (is (= {:lines [] :spans {}} (view/rebuild-chat-lines [] "" 80))))

  (testing "single user item — 3 lines (empty, content, empty)"
    (let [{:keys [lines spans]} (view/rebuild-chat-lines [{:type :user :text "hi"}] "" 80)]
      (is (= 3 (count lines)))
      (is (clojure.string/includes? (second lines) "hi"))
      (is (= {0 [0 3]} spans))))

  (testing "multiple items — user (3 lines) + assistant (1 line) + blank (1 line)"
    (let [{:keys [lines spans]} (view/rebuild-chat-lines
                                  [{:type :user :text "hi"}
                                   {:type :assistant-text :text "hello"}]
                                  "" 80)]
      (is (= 5 (count lines)))
      (is (clojure.string/includes? (second lines) "hi"))
      (is (= "" (last lines)))
      (is (str/starts-with? (theme/strip-ansi (nth lines 3)) "◆ "))
      (is (= {0 [0 3] 1 [3 5]} spans))))

  (testing "with current-text appended as streaming (+ blank); streaming has no span entry"
    (let [{:keys [lines spans]} (view/rebuild-chat-lines [{:type :user :text "hi"}] "typing..." 80)]
      (is (= 5 (count lines)))
      (is (= "" (last lines)))
      (is (str/starts-with? (theme/strip-ansi (nth lines 3)) "◆ "))
      (is (= {0 [0 3]} spans))))

  (testing "empty current-text not appended"
    (let [{:keys [lines]} (view/rebuild-chat-lines [{:type :user :text "hi"}] "" 80)]
      (is (= 3 (count lines)))))

  (testing "consecutive collapsed tool calls stack without blank lines; text after them is spaced"
    (let [{:keys [lines spans]} (view/rebuild-chat-lines
                                  [{:type :tool-call :id "a" :state :called :summary "one"}
                                   {:type :tool-call :id "b" :state :called :summary "two"}
                                   {:type :assistant-text :text "done"}]
                                  "" 80)]
      (is (= ["✓ one" "✓ two" "" "◆ done" ""] (mapv theme/strip-ansi lines)))
      (is (= {0 [0 1] 1 [1 3] 2 [3 5]} spans))))

  (testing "an expanded block keeps its blank line"
    (let [{:keys [lines]} (view/rebuild-chat-lines
                            [{:type :thinking :id "r" :status :thought :text "hmm" :expanded? true}
                             {:type :tool-call :id "b" :state :called :summary "two"}]
                            "" 80)]
      (is (= "" (nth lines 2))))))

(deftest rebuild-lines-deferred-test
  (let [state {:items [{:type :assistant-text :text "hi"}] :current-text "" :width 40 :chat-lines []}]
    (testing "while deferred, rebuild-lines only flags the batch"
      (let [pending (volatile! false)
            s       (binding [view/*rebuild-deferred* pending] (view/rebuild-lines state))]
        (is (true? @pending))
        (is (= [] (:chat-lines s)))))
    (testing "otherwise it rebuilds"
      (is (= ["◆ hi" ""] (mapv theme/strip-ansi (:chat-lines (view/rebuild-lines state))))))))

;; --- Inline: the terminal's history and the live region ---

(def ^:private mixed-items
  "Every spacing rule: user messages, stacked compact rows, an expanded one,
  text, notes."
  [{:type :user :text "hi"}
   {:type :thinking :id "r1" :status :thought :text "hmm"}
   {:type :tool-call :id "a" :state :called :summary "one"}
   {:type :tool-call :id "b" :state :called :summary "two"}
   {:type :assistant-text :text "done"}
   {:type :tool-call :id "c" :state :called :summary "three" :expanded? true :args-text "{}"}
   {:type :hook :id "h" :name "lint" :status :ok}
   {:type :system :text "note"}
   {:type :user :text "again"}
   {:type :assistant-text :text "sure"}])

(defn- history+tail
  "The history lines of all `items`, with the live region's blank line."
  [items width]
  (into (view/history-lines items 0 (count items) width)
        (when (not= :user (:type (peek items))) [""])))

(deftest history-lines-test
  (testing "the history reads like the full-screen chat, whatever is printed when"
    (is (= (:lines (view/rebuild-chat-lines mixed-items "" 80)) (history+tail mixed-items 80)))
    (is (= (:lines (view/rebuild-chat-lines (pop mixed-items) "" 80)) (history+tail (pop mixed-items) 80))
        "ending on a user message")
    (testing "printed in several goes, the same lines"
      (let [n (count mixed-items)]
        (doseq [cut (range 1 n)]
          (is (= (view/history-lines mixed-items 0 n 80)
                 (into (view/history-lines mixed-items 0 cut 80) (view/history-lines mixed-items cut n 80)))
              (str "cut at " cut))))))
  (testing "no focus highlight in the history"
    (let [focused (assoc mixed-items 2 (assoc (nth mixed-items 2) :focused? true))]
      (is (= (view/history-lines mixed-items 0 4 80) (view/history-lines focused 0 4 80)))
      (is (not= (:lines (view/rebuild-chat-lines mixed-items "" 80))
                (:lines (view/rebuild-chat-lines focused "" 80)))
          "the full-screen chat does show it"))))

(defn- inline-state [& {:as extra}]
  (merge {:mode :ready :items [] :current-text "" :committed 0 :epoch 1 :tool-calls {} :questions []
          :input (ti/focus (ti/text-input)) :width 80 :height 24 :chat-lines [] :scroll-offset 0
          :opts {:workspace "/tmp/ws"} :mcps {} :init-tasks {}}
         extra))

(deftest inline-view-test
  (let [items [{:type :user :text "first question"} {:type :assistant-text :text "first answer"}
               {:type :user :text "second question"}
               {:type :tool-call :id "t" :state :running :summary "Reading a.txt"}]
        s     (inline-state :mode :chatting :turn-started-at 1 :items items :committed 3
                            :current-text "streaming reply")
        out   (theme/strip-ansi (view/view s))
        lines (str/split-lines out)]
    (testing "only what isn't in the terminal's history yet: no padding, no scrolling"
      (is (not (str/includes? out "first question")))
      (is (not (str/includes? out "second question")))
      (is (str/includes? out "Reading a.txt"))
      (is (str/includes? out "streaming reply"))
      (is (< (count lines) 24)))
    (testing "the prompt and the status line close it"
      (is (some #(str/starts-with? % "> ") lines))
      (is (= 4 (count (drop-while #(not (re-find #"^─+$" %)) lines)))
          "rule, input, rule, status: the last lines"))
    (testing "nothing left to show: just the prompt"
      (let [idle (theme/strip-ansi (view/view (inline-state :items (subvec items 0 2) :committed 2)))]
        (is (not (str/includes? idle "first answer")))
        (is (= ["" "ctrl+o for transcript" (apply str (repeat 80 "─"))]
               (take 3 (str/split-lines idle)))
            "the blank after the chat, the transcript hint, the rule")))
    (testing "full screen (--fullscreen, or the transcript): the whole chat, the whole screen"
      (doseq [fs [(assoc-in s [:opts :fullscreen] true) (assoc s :transcript? true)]]
        (let [fs  (view/rebuild-lines fs)
              out (theme/strip-ansi (view/view fs))]
          (is (str/includes? out "first question"))
          (is (= 24 (count (str/split-lines (view/view fs))))))))))

(defn- visible-tail [state]
  ;; Exercise the view's output under the terminal's tail-clipping constraint,
  ;; without depending on renderer internals or starting a terminal process.
  (str/join "\n" (take-last (:height state) (str/split (view/view state) #"\n" -1))))

(deftest short-terminal-keeps-prompt-cursor-visible-test
  (let [draft (str/join "\n" (map #(str % "-draft row") "ABCDEFGHIJKL"))]
    (doseq [mode [:ready :chatting :login :asking]
            height [5 7 10]
            layout [:inline :transcript :fullscreen]
            marker ["A" "F" "L"]]
      (testing (str mode " height " height " " layout " cursor " marker)
        (let [s (-> (inline-state :mode mode :height height :turn-started-at 1
                                  :input (-> (ti/text-input) (ti/set-value draft) ti/focus
                                             (assoc :pos (str/index-of draft (str marker "-"))))
                                  :tasks {:tasks (mapv #(hash-map :subject (str "task " %) :status "pending") (range 10))}
                                  :tasks-expanded? true :steered "steering" :queued "queued"
                                  :login {:provider "test" :field-idx 0
                                          :action {:action "input" :fields [{:key "user" :label "User" :type "text"}]}}
                                  :questions [{:question "A long question to answer" :allow-freeform? true
                                               :options [{:label "First"} {:label "Second"}]}])
                    (cond-> (= :transcript layout) (assoc :transcript? true)
                            (= :fullscreen layout) (assoc-in [:opts :fullscreen] true)))
              out (visible-tail s)]
          (is (str/includes? out (theme/paint {:reverse true} marker))
              "the cursor cell must survive tail clipping, including at the start of a long draft")
          (is (= draft (ti/value (:input s)))))))))

(deftest short-terminal-login-secret-remains-masked-test
  (let [s (inline-state :mode :login :height 5 :width 20
                        :input (-> (ti/text-input) (ti/set-value "secret-value-that-wraps-over-many-rows") ti/focus (assoc :pos 0))
                        :login {:provider "test" :field-idx 0
                                :action {:action "input" :fields [{:key "token" :label "Token" :type "secret"}]}})
        out (visible-tail s)]
    (is (str/includes? out (theme/paint {:reverse true} "•")))
    (is (not (str/includes? (view/view s) "secret-value")))))

(deftest short-terminal-picker-keeps-selection-visible-test
  (doseq [height [5 7 9]
          selected [0 5 11]
          layout [:inline :transcript :fullscreen]]
    (testing (str "height " height ", selected " selected ", " layout)
      (let [labels (mapv #(format "choice-%02d" %) (range 12))
            ;; Deliberately stale offsets exercise a resize after selection.
            lst (-> (cl/item-list labels :height 8) (cl/select selected)
                    (assoc :offset (if (zero? selected) 4 0)))
            s (cond-> (inline-state :mode :picking :height height
                                    :picker {:kind :model :query "" :list lst})
                (= :transcript layout) (assoc :transcript? true)
                (= :fullscreen layout) (assoc-in [:opts :fullscreen] true))
            out (theme/strip-ansi (visible-tail s))
            all (theme/strip-ansi (view/view s))]
        (is (str/includes? out (str "❯ " (nth labels selected))))
        (is (<= (count (re-seq #"choice-\d+" all)) (max 1 (- height 6))))
        (is (= lst (get-in s [:picker :list])) "rendering must not resize or scroll the model list")
        (is (= 8 (get-in s [:picker :list :height])))))))

(defn- question-layout-state [height cursor layout]
  (cond-> (inline-state :mode :asking :height height :question-cursor cursor
                        :questions [{:question "Which option?" :allow-freeform? false
                                     :options (mapv #(hash-map :label (format "Option-%02d" %)
                                                              :description (str "Detail " %))
                                                    (range 12))}])
    (= :transcript layout) (assoc :transcript? true)
    (= :fullscreen layout) (assoc-in [:opts :fullscreen] true)))

(defn- clipped-frame-content [state]
  (str/join "\n" (render/content-lines (:content (view/frame state)) (:height state))))

(deftest question-option-window-survives-frame-clipping-test
  (doseq [height [8 10 12]
          selected [0 5 11]
          layout [:inline :transcript :fullscreen]
          freeform? [false true]
          question ["Which option?" (str/join "\n\n" (repeat 20 "A long question paragraph."))]]
    (testing (str "height " height ", selected " selected ", " layout ", freeform " freeform?)
      (let [s (-> (question-layout-state height selected layout)
                  (assoc-in [:questions 0 :question] question)
                  (assoc-in [:questions 0 :allow-freeform?] freeform?))
            out (theme/strip-ansi (clipped-frame-content s))
            area (str/split-lines (view/render-question s))]
        (is (str/includes? out (str "❯ " (inc selected) ". " (format "Option-%02d" selected))))
        (is (= 1 (count (re-seq #"❯ " out))))
        (is (str/includes? out "↑↓ Enter"))
        (is (<= (count area) (- height 4)))
        (is (every? #(<= (theme/width %) (:width s)) area))))))

(deftest question-option-and-free-answer-cursor-stay-visible-test
  (let [draft (str/join "\n" (map #(str % "-draft row") "ABCDEFGHIJKL"))]
    (doseq [height [8 10 12]
            selected [0 5 11]
            layout [:inline :transcript :fullscreen]
            marker ["A" "F" "L"]]
      (let [s (-> (question-layout-state height selected layout)
                  (assoc-in [:questions 0 :allow-freeform?] true)
                  (assoc-in [:questions 0 :question] (str/join "\n\n" (repeat 20 "Long question.")))
                  (assoc :input (-> (ti/text-input) (ti/set-value draft) ti/focus
                                    (assoc :pos (str/index-of draft (str marker "-"))))))
            out (clipped-frame-content s)]
        (is (str/includes? (theme/strip-ansi out)
                           (str "❯ " (inc selected) ". " (format "Option-%02d" selected))))
        (is (str/includes? out (theme/paint {:reverse true} marker))
            (str "cursor " marker " at height " height))
        (is (<= (count (str/split-lines (view/render-question s))) (- height 4)))
        (is (= draft (ti/value (:input s))))))))

(deftest question-layout-keeps-all-details-when-they-fit-test
  (doseq [layout [:inline :transcript :fullscreen]]
    (let [s (-> (question-layout-state 12 1 layout)
                (assoc :questions [{:question "First paragraph.\n\nSecond paragraph."
                                   :allow-freeform? true
                                   :options [{:label "First" :description "First detail"}
                                             {:label "Second" :description "Second detail"}]}]
                       :input (-> (ti/text-input) (ti/set-value "First draft line\nSecond draft line") ti/focus)))
          out (theme/strip-ansi (clipped-frame-content s))]
      (doseq [text ["? First paragraph." "Second paragraph." "1. First" "First detail"
                    "❯ 2. Second" "Second detail" "↑↓ Enter to pick, or type an answer · Esc cancels"
                    "First draft line" "Second draft line"]]
        (is (str/includes? out text) text))
      (is (not (str/includes? (theme/strip-ansi (view/render-question s)) "…"))))))

(deftest question-window-does-not-truncate-history-or-model-test
  (let [question (str (str/join "\n\n" (repeat 15 "A long question paragraph."))
                      "\n\nQuestion tail preserved.")
        item {:type :question :question question :answer "Full selected option text"}
        s (-> (question-layout-state 8 0 :inline)
              (assoc-in [:questions 0 :question] question)
              (assoc :items [item] :committed 1 :history-removals [1 0])
              view/rebuild-lines)
        frame (view/frame s)
        history (theme/strip-ansi (str/join "\n" ((get-in frame [:history :lines]) 0 1)))
        short (theme/strip-ansi (clipped-frame-content s))
        enlarged (theme/strip-ansi (clipped-frame-content (assoc s :height 80)))]
    (is (not (str/includes? short "Question tail preserved.")))
    (is (str/includes? history "Question tail preserved."))
    (is (str/includes? history "Full selected option text"))
    (is (str/includes? (theme/strip-ansi (str/join "\n" (:chat-lines s))) "Question tail preserved."))
    (is (str/includes? enlarged "Question tail preserved."))
    (doseq [i (range 12)]
      (is (str/includes? enlarged (format "Option-%02d" i)))
      (is (str/includes? enlarged (str "Detail " i))))
    (is (= question (get-in s [:questions 0 :question])))
    (is (= [1 0] (get-in frame [:history :removals])))))

(deftest inline-focus-highlight-test
  (let [items [{:type :tool-call :id "live" :name "read_file" :state :running
                :summary "Reading file" :focused? true}]
        s (inline-state :items items :committed 0)
        live-row (first (str/split-lines (:content (view/frame s))))
        full-row (first (:lines (view/rebuild-chat-lines items "" 80)))
        printed-row (first (view/history-lines items 0 1 80))]
    (is (= full-row live-row) "inline selection is as visible as full-screen selection")
    (is (not= printed-row live-row) "only permanent history strips the focus highlight")
    (is (= (view/history-lines (mapv #(dissoc % :focused?) items) 0 1 80)
           (view/history-lines items 0 1 80)))))

(deftest frame-test
  (let [s (inline-state :items [{:type :user :text "hi"} {:type :assistant-text :text "hello"}]
                        :committed 1 :epoch 7 :current-text "more")
        {:keys [fullscreen? content history]} (view/frame s)]
    (is (false? fullscreen?))
    (is (= (view/view s) content))
    (is (= {:epoch 7 :committed 1 :total 3 :removals []} (dissoc history :lines)) "the reply streaming in counts in the total")
    (is (= (view/history-lines (conj (:items s) {:type :streaming-text :text "more"}) 1 3 80)
           ((:lines history) 1 3)))
    (testing "committed never past the items"
      (is (= 2 (:committed (:history (view/frame (assoc s :committed 9)))))))
    (testing "full screen"
      (is (:fullscreen? (view/frame (assoc s :transcript? true))))
      (is (:fullscreen? (view/frame (assoc-in s [:opts :fullscreen] true)))))))

(deftest pad-to-height-test
  (testing "shorter than height — the chat starts at the top, blank lines below"
    (let [result (pad-to-height ["a" "b"] 5)]
      (is (= 5 (count result)))
      (is (= ["a" "b" "" "" ""] result))))

  (testing "exact height — no change"
    (is (= ["a" "b" "c"] (pad-to-height ["a" "b" "c"] 3))))

  (testing "longer than height — returned as-is"
    (let [lines ["a" "b" "c" "d" "e"]]
      (is (= lines (pad-to-height lines 3))))))

(deftest render-chat-test
  (let [base-state {:chat-lines ["line1" "line2" "line3" "line4" "line5"]
                    :height     9
                    :scroll-offset 0}]

    (testing "offset 0 — last N visible lines"
      (let [rendered (view/render-chat base-state)
            visible  (clojure.string/split-lines rendered)]
        (is (= 4 (count visible)))
        (is (clojure.string/includes? rendered "line5"))))

    (testing "mid-scroll — offset shifts window up"
      (let [rendered (view/render-chat (assoc base-state :scroll-offset 2))
            visible  (str/split rendered #"\n" -1)]
        (is (= 4 (count visible)))
        (is (clojure.string/includes? rendered "line3"))
        (is (not (clojure.string/includes? rendered "line5")))))

    (testing "empty chat lines — pads to visible height"
      (let [rendered (view/render-chat (assoc base-state :chat-lines []))]
        (is (= 3 (count (re-seq #"\n" rendered))))))))

;; --- Status bar ---

(deftest render-status-bar-test
  (let [base {:opts           {:workspace "/home/user/myproject"}
              :model          "claude-sonnet-4-6"
              :selected-model nil
              :trust          false
              :usage          nil
              :init-tasks     {}}]

    (testing "no usage — tokens and cost absent"
      (let [bar (render-status-bar base)]
        (is (clojure.string/includes? bar "myproject"))
        (is (clojure.string/includes? bar "SAFE"))
        (is (not (clojure.string/includes? bar "tok")))))

    (testing "with usage — shows friendly tokens over the context limit, and cost"
      (let [bar (render-status-bar
                  (assoc base :usage {:sessionTokens 1234 :sessionCost "0.002"
                                      :limit {:context 200000 :output 4096}}))]
        (is (clojure.string/includes? bar "1.2K/200K"))
        (is (clojure.string/includes? bar "$0.002"))))

    (testing "context percentage comes from the server's breakdown, not session tokens"
      (let [bar (render-status-bar
                  (assoc base :usage {:sessionTokens 5000
                                      :limit         {:context 1000 :output 4096}
                                      :contextBreakdown {:usedTokens 500 :contextLimit 1000
                                                         :categories [{:name "Conversation" :tokens 500
                                                                       :color "#98c379"}]}}))]
        (is (clojure.string/includes? bar "50%"))
        (is (not (clojure.string/includes? bar "500%")))))

    (testing "selected-model overrides :model field"
      (let [bar (render-status-bar
                  (assoc base :selected-model "anthropic/claude-opus-4-7"))]
        (is (clojure.string/includes? bar "anthropic/claude-opus-4-7"))
        (is (not (clojure.string/includes? bar "claude-sonnet-4-6")))))

    (testing "init tasks running — shows loading indicator"
      (let [bar (render-status-bar
                  (assoc base :init-tasks {"models" {:title "Loading models" :done? false}}))]
        (is (clojure.string/includes? bar "⏳"))))

    (testing "all init tasks done — no loading indicator"
      (let [bar (render-status-bar
                  (assoc base :init-tasks {"models" {:title "Loading models" :done? true}}))]
        (is (not (clojure.string/includes? bar "⏳")))))

    (testing "trust mode shows TRUST"
      (let [bar (render-status-bar (assoc base :trust true))]
        (is (clojure.string/includes? bar "TRUST"))))

    (testing "selected-agent shown when non-nil"
      (let [bar (render-status-bar (assoc base :selected-agent "plan"))]
        (is (clojure.string/includes? bar "plan"))))

    (testing "selected-variant shown when non-nil"
      (let [bar (render-status-bar (assoc base :selected-variant "medium"))]
        (is (clojure.string/includes? bar "medium"))))

    (testing "no agent or variant when nil"
      (let [bar (render-status-bar base)]
        (is (not (clojure.string/includes? bar "plan")))
        (is (not (clojure.string/includes? bar "medium")))))

    (testing "chat title shown when :chat-title set"
      (let [bar (render-status-bar (assoc base :chat-title "My Project Chat"))]
        (is (clojure.string/includes? bar "My Project Chat"))))

    (testing "long title truncated with an ellipsis"
      (let [bar (render-status-bar (assoc base :chat-title "A very long chat title that exceeds limits"))]
        (is (clojure.string/includes? bar "…"))
        (is (not (clojure.string/includes? bar "A very long chat title that exceeds")))))

    (testing "no title shown when :chat-title nil"
      (let [bar (render-status-bar base)]
        (is (not (clojure.string/includes? bar "My Project")))))))

;; --- Command picker render ---

(deftest render-picker-command-test
  (testing "command picker renders 'Select command' label"
    (let [s {:mode    :picking
             :width   80
             :height  24
             :opts    {:workspace "/tmp"}
             :trust   false
             :model   nil
             :selected-model nil
             :selected-agent nil
             :selected-variant nil
             :init-tasks {}
             :usage   nil
             :chat-title nil
             :items   []
             :chat-lines []
             :scroll-offset 0
             :input   (ti/text-input)
             :picker  {:kind    :command
                       :query   "m"
                       :list    (cl/item-list
                                  ["/model  —  Open model picker"] :height 8)}}
          rendered (view/view s)]
      (is (clojure.string/includes? rendered "Select command")))))

;; --- Login render ---

(deftest render-login-test
  (let [base-login-state {:opts           {:workspace "/tmp"}
                          :trust          false
                          :model          nil
                          :selected-model nil
                          :usage          nil
                          :init-tasks     {}
                          :items          []
                          :current-text   ""
                          :chat-lines     []
                          :scroll-offset  0
                          :width          80
                          :height         24
                          :input          (ti/text-input)}]

    (testing "input action shows provider name and field label"
      (let [s   (assoc base-login-state
                       :mode :login
                       :login {:provider  "anthropic"
                               :action    {:action "input"
                                           :fields [{:key "api-key" :label "API Key" :type "secret"}]}
                               :field-idx 0 :collected {} :pending-message "hi"})
            txt (render-login s)]
        (is (clojure.string/includes? txt "anthropic"))
        (is (clojure.string/includes? txt "API Key"))))

    (testing "choose-method action lists methods with numbers"
      (let [s   (assoc base-login-state
                       :mode :login
                       :login {:provider  "openai"
                               :action    {:action  "choose-method"
                                           :methods [{:key "api-key" :label "API Key"}
                                                     {:key "oauth"   :label "OAuth"}]}
                               :field-idx 0 :collected {} :pending-message "hi"})
            txt (render-login s)]
        (is (clojure.string/includes? txt "[1]"))
        (is (clojure.string/includes? txt "[2]"))
        (is (clojure.string/includes? txt "API Key"))
        (is (clojure.string/includes? txt "OAuth"))))

    (testing "device-code action shows code and url"
      (let [s   (assoc base-login-state
                       :mode :login
                       :login {:provider  "github"
                               :action    {:action  "device-code"
                                           :url     "https://github.com/login/device"
                                           :code    "ABCD-1234"
                                           :message "Enter code at URL"}
                               :field-idx 0 :collected {} :pending-message "hi"})
            txt (render-login s)]
        (is (clojure.string/includes? txt "ABCD-1234"))
        (is (clojure.string/includes? txt "https://github.com/login/device"))))

    (testing "authorize action shows url and message"
      (let [s   (assoc base-login-state
                       :mode :login
                       :login {:provider  "google"
                               :action    {:action  "authorize"
                                           :url     "https://accounts.google.com/o/oauth2/auth"
                                           :message "Open URL in browser to authorize"}
                               :field-idx 0 :collected {} :pending-message "hi"})
            txt (render-login s)]
        (is (clojure.string/includes? txt "https://accounts.google.com"))
        (is (clojure.string/includes? txt "browser"))))))


(deftest login-secret-masked-test
  (let [state {:mode :login :width 80 :height 12 :chat-lines [] :scroll-offset 0 :items []
               :opts {:workspace "/tmp"} :init-tasks {} :mcps {} :trust false
               :input (ti/set-value (ti/text-input) "sk-123")
               :login {:provider "openai"
                       :action {:action "input"
                                :fields [{:key "api-key" :label "API Key" :type "secret"}]}
                       :field-idx 0}}
        out   (theme/strip-ansi (view/view state))]
    (is (str/includes? out "API Key"))
    (is (str/includes? out "••••••"))
    (is (not (str/includes? out "sk-123")) "an API key is never echoed"))
  (testing "plain text fields stay visible"
    (let [state {:mode :login :width 80 :height 12 :chat-lines [] :scroll-offset 0 :items []
                 :opts {:workspace "/tmp"} :init-tasks {} :mcps {} :trust false
                 :input (ti/set-value (ti/text-input) "me@x.y")
                 :login {:provider "p" :field-idx 0
                         :action {:action "input" :fields [{:key "user" :label "User" :type "text"}]}}}]
      (is (str/includes? (theme/strip-ansi (view/view state)) "me@x.y")))))

(deftest render-approval-test
  (testing "approval renders the full tool-call block with a diff, then the y/n/Y prompt"
    (let [edit  {:path "/tmp/foo.clj" :diff "@@ -1,2 +1,2 @@\n-old\n+new\n ctx"
                 :lines-added 1 :lines-removed 1}
          state {:mode :approving :width 80
                 :pending-approval {:tool-call-id "t1"}
                 :tool-calls {"t1" {:id "t1" :name "edit_file" :summary "foo.clj"
                                    :state :run :edit edit}}}
          out   (theme/strip-ansi (view/render-approval state))]
      (is (string? out))
      (is (clojure.string/includes? out "edit_file"))
      (is (clojure.string/includes? out "@@"))      ; diff present
      (is (clojure.string/includes? out "old"))
      (is (clojure.string/includes? out "new"))
      (is (clojure.string/includes? out "[y] approve  [n] reject  [Y] allow for this chat"))
      ;; prompt sits on its own line, below the diff
      (is (< (.indexOf out "@@") (.indexOf out "[y] approve")))))

  (testing "non-edit tool call still renders block + prompt (fallback intact)"
    (let [state {:mode :approving :width 80
                 :pending-approval {:tool-call-id "t2"}
                 :tool-calls {"t2" {:id "t2" :name "shell" :summary "ls"
                                    :state :run :args-text "{\"cmd\":\"ls\"}"}}}
          out   (theme/strip-ansi (view/render-approval state))]
      (is (clojure.string/includes? out "shell"))
      (is (clojure.string/includes? out "[y] approve"))))

  (testing "a shell call shows its command and what Y would remember"
    (let [shell {:commands [{:command "git" :args ["checkout" "main"] :approval-key "git checkout"}
                            {:command "sudo" :args ["rm" "x"] :approval-key nil}
                            {:command "rg" :args ["foo"] :approval-key "rg" :remembered? true}]}
          state {:mode :approving :width 80
                 :pending-approval {:tool-call-id "t3"}
                 :tool-calls {"t3" {:id "t3" :name "shell_command" :summary "Running git" :state :run
                                    :arguments {:command "git checkout main && sudo rm x && rg foo"}
                                    :shell shell}}}
          out   (theme/strip-ansi (view/render-approval state))]
      (is (str/includes? out "$ git checkout main && sudo rm x && rg foo"))
      (is (str/includes? out "↳ git checkout main"))
      (is (str/includes? out "↳ sudo rm x  (always asks)"))
      (is (str/includes? out "↳ rg foo  (remembered)"))
      (is (str/includes? out "[Y] remember git checkout"))
      (is (not (str/includes? out "{\"command\"")) "no JSON arguments box for shell calls")))

  (testing "nothing left to remember: no Y"
    (let [state {:mode :approving :width 80
                 :pending-approval {:tool-call-id "t4"}
                 :tool-calls {"t4" {:id "t4" :name "shell_command" :summary "sudo" :state :run
                                    :arguments {:command "sudo ls"}
                                    :shell {:commands [{:command "sudo" :args ["ls"] :approval-key nil}]}}}}
          out   (theme/strip-ansi (view/render-approval state))]
      (is (str/includes? out "[y] approve  [n] reject"))
      (is (not (str/includes? out "[Y]")))))

  (testing "the gutter says when a sub-agent asks and how many more wait"
    (let [state {:chat-id "chat1" :pending-approval {:chat-id "sub-1" :tool-call-id "t"}
                 :approval-queue [{:chat-id "chat1" :tool-call-id "u"}]}]
      (is (= "▲ Waiting for your approval · sub-agent · 1 more"
             (theme/strip-ansi (view/approval-gutter state)))))))
