(ns eca-cli.view-test
  (:require [clojure.test :refer [deftest is testing]]
            [charm.components.text-input :as ti]
            [eca-cli.view :as view]))

(def ^:private pad-to-height     #'view/pad-to-height)
(def ^:private render-status-bar #'view/render-status-bar)
(def ^:private render-login      #'view/render-login)

(deftest divider-test
  (is (= "" (view/divider 0)))
  (is (= "──────────" (view/divider 10)))
  (is (= 40 (count (view/divider 40)))))

(deftest rebuild-chat-lines-test
  (testing "empty"
    (is (= [] (view/rebuild-chat-lines [] "" 80))))

  (testing "single user item — 3 lines (empty, content, empty)"
    (let [lines (view/rebuild-chat-lines [{:type :user :text "hi"}] "" 80)]
      (is (= 3 (count lines)))
      (is (clojure.string/includes? (second lines) "hi"))))

  (testing "multiple items — user (3 lines) + assistant (1 line) + blank (1 line)"
    (let [lines (view/rebuild-chat-lines
                  [{:type :user :text "hi"}
                   {:type :assistant-text :text "hello"}]
                  "" 80)]
      (is (= 5 (count lines)))
      (is (clojure.string/includes? (second lines) "hi"))
      (is (= "" (last lines)))
      (is (clojure.string/starts-with? (nth lines 3) "◆ "))))

  (testing "with current-text appended as streaming (+ blank)"
    (let [lines (view/rebuild-chat-lines [{:type :user :text "hi"}] "typing..." 80)]
      (is (= 5 (count lines)))
      (is (= "" (last lines)))
      (is (clojure.string/starts-with? (nth lines 3) "◆ "))))

  (testing "empty current-text not appended"
    (let [lines (view/rebuild-chat-lines [{:type :user :text "hi"}] "" 80)]
      (is (= 3 (count lines))))))

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

    (testing "with usage — shows tokens and cost"
      (let [bar (render-status-bar
                  (assoc base :usage {:sessionTokens 1234 :sessionCost "$0.002"}))]
        (is (clojure.string/includes? bar "1234tok"))
        (is (clojure.string/includes? bar "$0.002"))))

    (testing "with context limit — shows ctx percentage"
      (let [bar (render-status-bar
                  (assoc base :usage {:sessionTokens 500
                                      :sessionCost   "$0.001"
                                      :limit         {:context 1000 :output 4096}}))]
        (is (clojure.string/includes? bar "50%"))))

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

    (testing "long title truncated to 24 chars with ellipsis"
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
                       :list    (charm.components.list/item-list
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

