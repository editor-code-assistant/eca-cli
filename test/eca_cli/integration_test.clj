(ns eca-cli.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

;; ---------------------------------------------------------------------------
;; Tmux harness
;; ---------------------------------------------------------------------------

(def ^:private session "eca-cli-itest")

(defn- new-session! [cmd]
  (sh "mkdir" "-p" "/tmp/eca-cli-itest")
  (sh "tmux" "kill-session" "-t" session)
  (Thread/sleep 200)
  (let [r (sh "tmux" "new-session" "-d" "-s" session "-x" "200" "-y" "50")]
    (when (pos? (:exit r))
      (throw (ex-info "tmux new-session failed" r))))
  (sh "tmux" "send-keys" "-t" session cmd "Enter"))

(defn- kill! []
  (sh "tmux" "kill-session" "-t" session))

(defn- keys!
  "Send one or more tmux key sequences. Literal text, special keys (Enter,
   Escape, BSpace, C-l, etc.) are all valid — tmux interprets each arg."
  [& ks]
  (apply sh "tmux" "send-keys" "-t" session ks))

(defn- screen []
  (:out (sh "tmux" "capture-pane" "-p" "-t" session)))

(defn- wait-for!
  "Poll screen until pred returns truthy or timeout elapses.
   Returns the matching screen content. Throws ex-info on timeout
   (shows as ERROR in test output with last screen content attached)."
  ([pred] (wait-for! pred 15000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [content (screen)]
         (cond
           (pred content) content
           (> (System/currentTimeMillis) deadline)
           (throw (ex-info "Timeout waiting for condition"
                           {:last-screen content}))
           :else (do (Thread/sleep 200) (recur))))))))

(defn- has [text] #(str/includes? % text))
(defn- lacks [text] #(not (str/includes? % text)))

(defn- wait-for-ready!
  "Wait for app to be in :ready mode — detected by the prompt '>' appearing on its own
   line between two dividers. tmux trims trailing spaces so '> ' becomes '>', which sits
   between \\n (divider) and \\n (next divider). Sleeps briefly first to let any
   in-progress transition away from :ready complete."
  ([] (wait-for-ready! 60000))
  ([timeout-ms]
   (Thread/sleep 300)
   (wait-for! #(str/includes? % "\n>\n") timeout-ms)))

;; ---------------------------------------------------------------------------
;; Shared startup
;; ---------------------------------------------------------------------------

(def ^:private itest-workspace "/tmp/eca-cli-itest")
(def ^:private itest-cmd (str "bb run --workspace " itest-workspace))

(def ^:private sessions-file
  (str (System/getProperty "user.home") "/.cache/eca/eca-cli-sessions.edn"))

(defn- start!
  "Start a fresh eca-cli session, clearing any persisted chat-ids first so tests
   don't inherit stale sessions (and their accumulated token context).
   Kills any running session BEFORE rm to avoid a race where eca-cli writes
   sessions.edn during JVM shutdown after rm has already run."
  [cmd]
  (sh "tmux" "kill-session" "-t" session)
  (Thread/sleep 500)
  (sh "rm" "-f" sessions-file)
  (new-session! cmd)
  ;; Wait for SAFE *and* a real model name (provider/model format), which
  ;; signals config/updated has arrived and available-models is populated.
  ;; Use a generous 60s timeout — eca-cli startup + ECA init can be slow.
  (wait-for! #(and (str/includes? % "SAFE")
                   (re-find #"\w+/\w+" %))
             60000))

;; ---------------------------------------------------------------------------
;; Phase 1a — baseline (implicit pre-condition for all other tests)
;; ---------------------------------------------------------------------------

(deftest phase1a-startup-test
  (start! itest-cmd)
  (try
    (testing "app starts, connects to ECA, reaches :ready"
      (is (str/includes? (screen) "SAFE")))
    (finally (kill!))))

;; ---------------------------------------------------------------------------
;; Phase 1a — additional criteria
;; ---------------------------------------------------------------------------

(deftest phase1a-init-spinner-test
  (new-session! itest-cmd)
  (try
    (testing "11: ⏳ spinner visible before config/updated arrives"
      (let [s (wait-for! (has "⏳") 10000)]
        (is (str/includes? s "⏳"))))
    (testing "11: spinner clears once init tasks complete"
      (let [s (wait-for! #(and (str/includes? % "SAFE") (re-find #"\w+/\w+" %)) 15000)]
        (is (not (str/includes? s "⏳")))))
    (finally (kill!))))

(deftest phase1a-model-in-status-bar-test
  (start! itest-cmd)
  (try
    (testing "13: model from config/updated shown in status bar"
      (is (re-find #"\w+/\w+" (screen))))
    (finally (kill!))))

(deftest phase1a-escape-chatting-test
  (start! itest-cmd)
  (try
    (testing "14: Escape during :chatting returns to :ready"
      (keys! "Hello" "Enter")
      (Thread/sleep 500)
      (keys! "Escape")
      (let [s (wait-for! (has "SAFE") 8000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))

(deftest phase1a-no-echo-test
  (start! itest-cmd)
  (try
    (testing "user message not echoed as AI output after response completes"
      (let [msg "ping-echo-xyzzy"]
        (keys! msg "Enter")
        ;; Sleep to ensure :chatting has begun before polling for SAFE return
        (Thread/sleep 1500)
        ;; Wait for response to complete — app returns to :ready
        (let [s (wait-for! (has "SAFE") 30000)]
          ;; Message text must NOT appear as AI output (◆ prefix = echoed)
          (is (not (str/includes? s (str "◆ " msg))))
          ;; Text appears exactly once in the visible screen (user item only)
          (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote msg)) s)))))))
    (finally (kill!))))

(deftest phase1a-reader-error-test
  (start! itest-cmd)
  (try
    (testing "16: ECA process killed → disconnect message appears in chat"
      ;; Kill the eca subprocess — reader thread detects broken pipe and emits :reader-error
      (sh "pkill" "-x" "eca")
      (let [s (wait-for! (has "⚠") 10000)]
        (is (str/includes? s "⚠"))))
    (finally (kill!))))

;; Phase 1b login criteria (7, 8, 9, 10, 11) remain manual.
;; The providers/login flow requires ECA to return status:"login" on a
;; chat/prompt, which only fires when a provider is in a specific
;; unauthenticated state — not reproducible with a blank API key.
;; See docs/roadmap/phase-1b-test-scenarios.md for the manual runbook.

;; ---------------------------------------------------------------------------
;; Phase 2 — model & agent identity (criteria 10–17)
;; ---------------------------------------------------------------------------

(deftest phase2-model-picker-test
  (start! itest-cmd)
  (try
    (testing "10: Ctrl+L opens model picker"
      (keys! "C-l")
      (let [s (wait-for! (has "Select model") 5000)]
        (is (str/includes? s "Select model"))))

    (testing "11: typing filters list"
      ;; "zzz" matches no model name — list empties, query appears in header
      (keys! "zzz")
      (Thread/sleep 300)
      (is (str/includes? (screen) "zzz")))

    (testing "11: backspace restores filter"
      (keys! "BSpace" "BSpace" "BSpace")
      (Thread/sleep 300)
      (is (not (str/includes? (screen) "zzz"))))

    (testing "12: Enter selects highlighted model, picker closes, :ready restored"
      (keys! "Enter")
      (let [s (wait-for! (lacks "Select model") 5000)]
        (is (str/includes? s "SAFE"))
        (is (re-find #"\w+/\w+" s))))

    (finally (kill!))))

(deftest phase2-selected-model-in-status-bar-test
  (start! itest-cmd)
  (try
    (testing "12: model selected in picker appears in status bar"
      (keys! "C-l")
      (let [ps (wait-for! (has "Select model") 5000)
            ;; Highlighted item rendered with "> " prefix by charm list component
            selected (second (re-find #"> (\S+)" ps))]
        (keys! "Enter")
        (let [s (wait-for! (lacks "Select model") 5000)]
          (is (some? selected) "picker must have a highlighted item")
          (is (str/includes? s selected)))))
    (finally (kill!))))

(deftest phase2-agent-picker-test
  (start! itest-cmd)
  (try
    (testing "14: /agent + Enter opens agent picker"
      (keys! "/agent" "Enter")
      (let [s (wait-for! (has "Select agent") 5000)]
        (is (str/includes? s "Select agent"))))

    (testing "15: Enter selects agent, returns to :ready"
      (keys! "Enter")
      (let [s (wait-for! (lacks "Select agent") 5000)]
        (is (str/includes? s "SAFE"))))

    (finally (kill!))))

(deftest phase2-selected-agent-in-status-bar-test
  (start! itest-cmd)
  (try
    (testing "15: agent selected in picker appears in status bar"
      (keys! "/agent" "Enter")
      (let [ps (wait-for! (has "Select agent") 5000)
            selected (second (re-find #"> (\S+)" ps))]
        (keys! "Enter")
        (let [s (wait-for! (lacks "Select agent") 5000)]
          (is (some? selected) "picker must have a highlighted item")
          (is (str/includes? s selected)))))
    (finally (kill!))))

(deftest phase2-escape-picker-test
  (start! itest-cmd)
  (try
    (testing "16: Escape from picker returns to :ready without changing selection"
      (keys! "C-l")
      (wait-for! (has "Select model") 5000)
      (let [before (screen)
            ;; capture current model from status bar before picker changes it
            before-model (second (re-find #"SAFE|TRUST" before))]
        (keys! "Escape")
        (let [s (wait-for! (lacks "Select model") 5000)]
          (is (str/includes? s "SAFE")))))

    (finally (kill!))))

;; Phase 2 criterion 17 (single-model picker) is not automated —
;; requires controlling ECA's returned model list to a single entry,
;; which cannot be done via CLI flags alone.

;; ---------------------------------------------------------------------------
;; Phase 3 — input history and scroll
;; ---------------------------------------------------------------------------

(deftest phase3-history-recalls-last-test
  (start! itest-cmd)
  (try
    (testing "up arrow in :ready recalls most recently sent message into input"
      (let [msg "hist-last-xyzzy-001"]
        (keys! msg "Enter")
        (wait-for-ready!)
        (keys! "Up" "Enter")
        (let [s (wait-for-ready!)]
          (is (<= 2 (count (re-seq (re-pattern (java.util.regex.Pattern/quote msg)) s)))))))
    (finally (kill!))))

(deftest phase3-history-multi-navigate-test
  (start! itest-cmd)
  (try
    (testing "up+up reaches second-to-last message"
      (keys! "hist-alpha-xyzzy" "Enter")
      (wait-for-ready!)
      (keys! "hist-beta-xyzzy" "Enter")
      (wait-for-ready!)
      (keys! "Up" "Up" "Enter")
      (let [s (wait-for-ready!)]
        (is (<= 2 (count (re-seq #"hist-alpha-xyzzy" s))))
        (is (<= 1 (count (re-seq #"hist-beta-xyzzy" s))))))
    (finally (kill!))))

(deftest phase3-history-down-clears-test
  (start! itest-cmd)
  (try
    (testing "down after up clears input back to empty"
      (keys! "hist-base-xyzzy" "Enter")
      (wait-for-ready!)
      (keys! "Up" "Down" "hist-fresh-xyzzy" "Enter")
      (let [s (wait-for-ready!)]
        (is (str/includes? s "hist-fresh-xyzzy"))
        (is (not (str/includes? s "hist-base-xyzzyhist-fresh-xyzzy")))))
    (finally (kill!))))

(deftest phase3-scroll-pgup-pgdn-test
  (start! itest-cmd)
  (try
    (testing "PgUp and PgDn scroll within the viewport without breaking the app"
      (keys! "scroll-test-ping" "Enter")
      (wait-for! (has "SAFE") 30000)
      (keys! "PPage")
      (Thread/sleep 300)
      (keys! "NPage")
      (let [s (wait-for! (has "SAFE") 3000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))

;; ---------------------------------------------------------------------------
;; Phase 3 — session continuity
;; ---------------------------------------------------------------------------

(deftest phase3-resume-test
  ;; Startup is always a fresh session. /sessions is the explicit resume path.
  ;; Sends a message (persists chat-id), kills, restarts — verifies the new session
  ;; is clean, then resumes the old chat via /sessions and checks messages replay.
  (start! itest-cmd)
  (try
    (keys! "resume-seed-xyzzy" "Enter")
    (wait-for-ready!)
    (kill!)
    (Thread/sleep 500)
    ;; Restart WITHOUT clearing sessions — preserves the persisted chat-id for /sessions
    (new-session! itest-cmd)
    (wait-for! #(and (str/includes? % "SAFE") (re-find #"\w+/\w+" %)) 60000)
    (testing "fresh restart shows no old messages"
      (is (not (str/includes? (screen) "resume-seed-xyzzy"))))
    (testing "previous chat appears in /sessions"
      (keys! "/sessions" "Enter")
      (wait-for! (has "Select chat") 15000))
    (testing "selecting session replays old messages"
      (keys! "Enter")
      (let [s (wait-for! (has "resume-seed-xyzzy") 15000)]
        (is (str/includes? s "resume-seed-xyzzy"))))
    (finally (kill!))))

(deftest phase3-new-command-test
  (start! itest-cmd)
  (try
    (let [old-msg "new-cmd-before-xyzzy"
          new-msg "new-cmd-after-xyzzy"]
      (keys! old-msg "Enter")
      (wait-for-ready!)
      (keys! "/new" "Enter")
      (Thread/sleep 500)
      (testing "/new clears old message from UI"
        (is (not (str/includes? (screen) old-msg))))
      (keys! new-msg "Enter")
      (let [s (wait-for-ready!)]
        (testing "new message works after /new"
          (is (str/includes? s new-msg)))))
    (finally (kill!))))

(deftest phase3-sessions-picker-test
  (start! itest-cmd)
  (try
    (keys! "sessions-seed-xyzzy" "Enter")
    (wait-for-ready!)
    (testing "/sessions opens session picker"
      (keys! "/sessions" "Enter")
      (let [s (wait-for! (has "Select chat") 15000)]
        (is (str/includes? s "Select chat"))))
    (testing "Escape from sessions picker returns to :ready"
      (keys! "Escape")
      (let [s (wait-for! (lacks "Select chat") 5000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))

;; ---------------------------------------------------------------------------
;; Phase 4 — command system
;; ---------------------------------------------------------------------------

(deftest phase4-command-picker-opens-test
  (start! itest-cmd)
  (try
    (testing "typing '/' opens command picker"
      (keys! "/")
      (let [s (wait-for! (has "Select command") 3000)]
        (is (str/includes? s "Select command"))))
    (testing "typing 'm' filters list — model visible, quit not visible"
      (keys! "m")
      (Thread/sleep 200)
      (is (str/includes? (screen) "model"))
      (is (not (str/includes? (screen) "/quit"))))
    (testing "Escape closes picker, returns to SAFE"
      (keys! "Escape")
      (let [s (wait-for! (lacks "Select command") 3000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))

(deftest phase4-backspace-exits-picker-test
  (start! itest-cmd)
  (try
    (testing "Backspace on empty query in command picker returns to :ready"
      (keys! "/")
      (wait-for! (has "Select command") 3000)
      (keys! "BSpace")
      (let [s (wait-for! (lacks "Select command") 3000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))

(deftest phase4-clear-command-test
  (start! itest-cmd)
  (try
    (testing "/clear removes previous chat content from display"
      (keys! "hello-clear-xyzzy" "Enter")
      (wait-for-ready!)
      (keys! "/clear" "Enter")
      (Thread/sleep 300)
      (is (not (str/includes? (screen) "hello-clear-xyzzy"))))
    (finally (kill!))))

(deftest phase4-help-command-test
  (start! itest-cmd)
  (try
    (testing "/help shows command listing in chat"
      (keys! "/help" "Enter")
      (Thread/sleep 300)
      (let [s (screen)]
        (is (str/includes? s "/model"))
        (is (str/includes? s "/quit"))))
    (finally (kill!))))

(deftest phase4-unknown-command-test
  (start! itest-cmd)
  (try
    (testing "unknown /cmd shows error containing command text"
      (keys! "/notacommandxyzzy" "Enter")
      (Thread/sleep 300)
      (is (str/includes? (screen) "notacommandxyzzy")))
    (finally (kill!))))

(deftest phase4-command-picker-executes-test
  (start! itest-cmd)
  (try
    (testing "selecting /new from command picker clears chat"
      (keys! "picker-exec-seed-xyzzy" "Enter")
      (wait-for-ready!)
      (keys! "/")
      (wait-for! (has "Select command") 3000)
      (keys! "new" "Enter")
      (Thread/sleep 500)
      (is (not (str/includes? (screen) "picker-exec-seed-xyzzy"))))
    (finally (kill!))))

;; ---------------------------------------------------------------------------
;; Phase 5 — Rich Display (criteria 15–19)
;; ---------------------------------------------------------------------------

(deftest phase5-tool-block-and-focus-test
  ;; Sends a prompt that reliably invokes the read_file tool. After the agent
  ;; finishes, checks collapsed tool block then Tab/Enter/Escape interactions.
  ;; Note: the agent may generate thinking blocks before the tool call, so
  ;; criterion 17 loops Tab+Enter until Arguments box appears (tool call expanded).
  (start! itest-cmd)
  (try
    (sh "touch" "/tmp/eca-cli-itest/phase5-sentinel.txt")
    (keys! "Read the file /tmp/eca-cli-itest/phase5-sentinel.txt" "Enter")

    (testing "15: collapsed tool block with ✓ visible after agent fully finishes"
      ;; Wait for :ready (\n>\n) AND ✓ — not just SAFE (which is always present)
      (wait-for! #(and (str/includes? % "\n>\n") (str/includes? % "✓")) 60000)
      (is (str/includes? (screen) "✓")))

    (testing "16: Tab moves focus — › indicator visible on first focusable item"
      (keys! "Tab")
      (Thread/sleep 300)
      (is (str/includes? (screen) "›")))

    (testing "17: Tab to tool call + Enter expands — ▾ and Arguments box visible"
      ;; The agent may have thinking blocks before the tool call in render order.
      ;; Tab+Enter each item; if Arguments does not appear (thinking block, no args),
      ;; collapse it and advance to the next until we hit the tool call.
      (loop [n 0]
        (when (< n 6)
          (keys! "Tab")
          (Thread/sleep 200)
          (keys! "Enter")
          (Thread/sleep 300)
          (when-not (str/includes? (screen) "Arguments")
            (keys! "Enter") ; collapse — not the tool call
            (Thread/sleep 100)
            (recur (inc n)))))
      (let [s (screen)]
        (is (str/includes? s "▾"))
        (is (str/includes? s "Arguments"))))

    (testing "18: Enter collapses block — ▾ gone"
      (keys! "Enter")
      (Thread/sleep 300)
      (is (not (str/includes? (screen) "▾"))))

    (testing "19: Escape clears focus — › gone"
      (keys! "Tab")
      (Thread/sleep 200)
      (keys! "Escape")
      (Thread/sleep 200)
      (is (not (str/includes? (screen) "›"))))

    (finally
      (sh "rm" "-f" "/tmp/eca-cli-itest/phase5-sentinel.txt")
      (kill!))))

;; Phase 5 criteria 20–22 (sub-agent spawn block, Tab into sub-items) are manual.
;; Requires sending a prompt that triggers eca__spawn_agent, which depends on
;; the selected agent and LLM routing — not deterministic from CLI alone.
;; Manual runbook:
;;   20. Send a prompt to an agent that uses sub-agents (e.g. orchestrator).
;;       After completion, collapsed spawn block shows "▸ N steps" suffix.
;;   21. Tab to spawn block, Enter to expand — sub-agent steps appear indented.
;;   22. Tab again reaches a sub-item inside the expanded block;
;;       Enter expands it to show its args/output indented.
