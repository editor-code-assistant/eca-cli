(ns eca-cli.view-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
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
        (is (re-find #"ECA server is not running\. Press Ctrl\+C to exit\." out))))))

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

(deftest pad-to-height-test
  (testing "shorter than height — pads at top with empty strings"
    (let [result (pad-to-height ["a" "b"] 5)]
      (is (= 5 (count result)))
      (is (= ["" "" "" "a" "b"] result))))

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
            visible  (clojure.string/split-lines rendered)]
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
