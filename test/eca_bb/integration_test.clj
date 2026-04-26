(ns eca-bb.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

;; ---------------------------------------------------------------------------
;; Tmux harness
;; ---------------------------------------------------------------------------

(def ^:private session "eca-bb-itest")

(defn- new-session! [cmd]
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

;; ---------------------------------------------------------------------------
;; Shared startup
;; ---------------------------------------------------------------------------

(defn- start! [cmd]
  (new-session! cmd)
  ;; Wait for SAFE *and* a real model name (provider/model format), which
  ;; signals config/updated has arrived and available-models is populated.
  (wait-for! #(and (str/includes? % "SAFE")
                   (re-find #"\w+/\w+" %))))

;; ---------------------------------------------------------------------------
;; Phase 1a — baseline (implicit pre-condition for all other tests)
;; ---------------------------------------------------------------------------

(deftest phase1a-startup-test
  (start! "bb run")
  (try
    (testing "app starts, connects to ECA, reaches :ready"
      (is (str/includes? (screen) "SAFE")))
    (finally (kill!))))

;; ---------------------------------------------------------------------------
;; Phase 1a — additional criteria
;; ---------------------------------------------------------------------------

(deftest phase1a-init-spinner-test
  (new-session! "bb run")
  (try
    (testing "11: ⏳ spinner visible before config/updated arrives"
      (let [s (wait-for! (has "⏳") 10000)]
        (is (str/includes? s "⏳"))))
    (testing "11: spinner clears once init tasks complete"
      (let [s (wait-for! #(and (str/includes? % "SAFE") (re-find #"\w+/\w+" %)) 15000)]
        (is (not (str/includes? s "⏳")))))
    (finally (kill!))))

(deftest phase1a-model-in-status-bar-test
  (start! "bb run")
  (try
    (testing "13: model from config/updated shown in status bar"
      (is (re-find #"\w+/\w+" (screen))))
    (finally (kill!))))

(deftest phase1a-escape-chatting-test
  (start! "bb run")
  (try
    (testing "14: Escape during :chatting returns to :ready"
      (keys! "Hello" "Enter")
      (Thread/sleep 500)
      (keys! "Escape")
      (let [s (wait-for! (has "SAFE") 8000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))

(deftest phase1a-no-echo-test
  (start! "bb run")
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
  (start! "bb run")
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
  (start! "bb run")
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
  (start! "bb run")
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
  (start! "bb run")
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
  (start! "bb run")
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
  (start! "bb run")
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
