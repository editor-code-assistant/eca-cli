(ns eca-cli.quit-test
  "Ctrl+C / Ctrl+D press twice to quit, as in Claude Code: the first press
  clears the input, stops the agent, or asks for a second press."
  (:require [charm.components.text-input :as ti]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.test-support :as support]
            [eca-cli.commands :as commands]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(use-fixtures :each support/isolated-chats-file)

(defn- base-state []
  {:mode             :ready
   :trust            false
   :chat-id          "chat1"
   :items            []
   :current-text     ""
   :tool-calls       {}
   :approval-queue   []
   :questions        []
   :pending-contexts []
   :init-tasks       {}
   :input            (ti/text-input)
   :input-history    []
   :chat-lines       []
   :scroll-offset    0
   :width            100
   :height           24
   :mcps             {}
   :server           nil
   :opts             {:workspace "/tmp/test"}})

(def ^:private ctrl-c (msg/key-press "c" :ctrl true))
(def ^:private ctrl-d (msg/key-press "d" :ctrl true))

(defn- press
  "Press `key` on `state`, quitting replaced by a marker: [state cmd]."
  [state key]
  (with-redefs [commands/cmd-quit (fn [s] [s ::quit])]
    (state/update-state state key)))

(defn- bottom-line [state]
  (theme/strip-ansi (peek (str/split-lines (view/view state)))))

(deftest first-press-asks-for-a-second-test
  (let [[s cmd] (press (base-state) ctrl-c)]
    (testing "nothing quits yet; the hint takes the status line's place"
      (is (not= ::quit cmd))
      (is (= :ready (:mode s)))
      (is (= "ctrl+c" (get-in s [:quit-armed :key])))
      (is (= "Press Ctrl+C again to exit" (bottom-line s))))
    (testing "the second press quits"
      (is (= ::quit (second (press s ctrl-c)))))
    (testing "the hint expires on its own"
      (with-redefs [commands/quit-window-ms 1]
        (let [[armed timer] (press (base-state) ctrl-c)
              expired       ((:fn timer))]
          (is (= {:type :quit-hint-expired :at (get-in armed [:quit-armed :at])} expired)
              "the timer names the press it's for")
          (is (nil? (:quit-armed (first (state/update-state armed expired)))))
          (is (not= "Press Ctrl+C again to exit" (bottom-line (first (state/update-state armed expired))))
              "the status line is back")
          (testing "an older press's timer doesn't clear a newer press"
            (let [newer (update-in armed [:quit-armed :at] inc)]
              (is (some? (:quit-armed (first (state/update-state newer expired))))))))))))

(deftest second-press-too-late-test
  (let [stale   (assoc (base-state) :quit-armed {:key "ctrl+c" :at (- (System/currentTimeMillis) 10000)})
        [s cmd] (press stale ctrl-c)]
    (is (not= ::quit cmd) "past the window, it asks again")
    (is (> (get-in s [:quit-armed :at]) (get-in stale [:quit-armed :at])))))

(deftest ctrl-c-clears-the-input-first-test
  (let [typed   (assoc (base-state) :input (ti/set-value (ti/text-input) "half a thought"))
        [s cmd] (press typed ctrl-c)]
    (is (nil? cmd))
    (is (= "" (ti/value (:input s))))
    (is (nil? (:quit-armed s)) "clearing isn't the first of two presses")
    (testing "even right after a first press"
      (let [[s' cmd'] (press (assoc typed :quit-armed {:key "ctrl+c" :at (System/currentTimeMillis)}) ctrl-c)]
        (is (not= ::quit cmd'))
        (is (= "" (ti/value (:input s'))))))))

(deftest ctrl-c-stops-the-agent-test
  (let [stopped (atom [])
        working (assoc (base-state) :mode :chatting :turn-started-at 1)]
    (with-redefs [protocol/stop-prompt! (fn [_srv chat-id] (swap! stopped conj chat-id))]
      (testing "with nothing typed, it stops the turn, like Esc"
        (let [[s cmd] (press working ctrl-c)]
          (is (not= ::quit cmd))
          (is (= ["chat1"] @stopped))
          (is (= :ready (:mode s)))
          (is (nil? (:quit-armed s)) "stopping closes nothing")
          (testing "then it takes two more to quit"
            (let [[s2 c2] (press s ctrl-c)]
              (is (not= ::quit c2))
              (is (= ::quit (second (press s2 ctrl-c))))))))
      (testing "with text typed, it clears that, the agent keeps going"
        (reset! stopped [])
        (let [[s _] (press (assoc working :input (ti/set-value (ti/text-input) "steer this")) ctrl-c)]
          (is (empty? @stopped))
          (is (= :chatting (:mode s)))
          (is (= "" (ti/value (:input s)))))))))

(deftest other-modes-ask-for-a-second-press-test
  (doseq [mode [:picking :approving :asking :login :connecting :disconnected]]
    (let [s0      (assoc (base-state) :mode mode)
          [s cmd] (press s0 ctrl-c)]
      (is (not= ::quit cmd) (str mode))
      (is (= mode (:mode s)) (str mode ": nothing else changes"))
      (is (= ::quit (second (press s ctrl-c))) (str mode)))))

(deftest ctrl-d-test
  (testing "on an empty input, Ctrl+D asks for a second Ctrl+D"
    (let [[s cmd] (press (base-state) ctrl-d)]
      (is (not= ::quit cmd))
      (is (= "Press Ctrl+D again to exit" (bottom-line s)))
      (is (= ::quit (second (press s ctrl-d))))
      (is (not= ::quit (second (press s ctrl-c))) "the other key starts over")))
  (testing "with text typed, Ctrl+D is the input's (delete forward)"
    (let [typed (assoc (base-state) :input (-> (ti/text-input) (ti/set-value "ab") (assoc :pos 0)))
          [s _] (press typed ctrl-d)]
      (is (nil? (:quit-armed s)))
      (is (not= "Press Ctrl+D again to exit" (bottom-line s))))))
