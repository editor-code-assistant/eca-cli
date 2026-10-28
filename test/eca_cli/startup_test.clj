(ns eca-cli.startup-test
  "What the command line asks of the TUI: a prompt, files, -c, --title,
  --model / --agent / --variant."
  (:require [babashka.fs :as fs]
            [charm.components.text-input :as ti]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.chat :as chat]
            [eca-cli.protocol :as protocol]
            [eca-cli.selection :as selection]
            [eca-cli.startup :as startup]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]))

(use-fixtures :each support/isolated-chats-file)

(defn- base-state [opts]
  (merge {:mode               :ready
          :trust              false
          :chat-id            nil
          :items              []
          :current-text       ""
          :tool-calls         {}
          :approval-queue     []
          :questions          []
          :pending-contexts   []
          :init-tasks         {}
          :available-models   ["m/a" "m/b"]
          :available-agents   ["code" "plan"]
          :available-variants []
          :input              (ti/focus (ti/text-input))
          :input-history      []
          :chat-lines         []
          :scroll-offset      0
          :width              100
          :height             24
          :mcps               {}
          :server             nil
          :opts               (merge {:workspace "/tmp/ws"} opts)}
         (startup/init opts)))

(defn- ready
  "Initialized a while ago, the startup tasks done."
  [s]
  (assoc s :ready-at 0 :init-tasks {"models" {:done? true}} :init-tasks-at 0))

(defn- with-sent
  "[result sent]: `f`'s result, and the prompts / notifications it sent."
  [f]
  (let [sent (atom [])]
    (with-redefs [protocol/chat-prompt!            (fn [_ params _] (swap! sent conj [:prompt params]))
                  protocol/selected-model-changed! (fn [_ m] (swap! sent conj [:model m]))
                  protocol/selected-agent-changed! (fn [_ a] (swap! sent conj [:agent a]))
                  protocol/update-chat!            (fn [_ id f] (swap! sent conj [:update id f]))
                  protocol/open-chat!              (fn [_ id _] (swap! sent conj [:open id]))]
      [(f) @sent])))

;; --- --model, --agent, --variant ---

(deftest pins-test
  (let [s (base-state {:model "m/b" :agent "plan" :variant "high"})]
    (testing "shown from the start"
      (is (= ["m/b" "plan" "high"] ((juxt :selected-model :selected-agent :selected-variant) s)))
      (is (= #{:model :agent :variant} (:pins s))))
    (testing "the server's defaults don't replace them"
      (let [[s' _] (chat/handle-config-updated s {:params {:chat {:selectModel "m/a" :selectAgent "code"
                                                                  :selectVariant "low" :variants ["low" "high"]}}})]
        (is (= ["m/b" "plan" "high"] ((juxt :selected-model :selected-agent :selected-variant) s')))
        (is (= "high" (get-in s' [:opts :variant])) "still sent with the prompts")
        (is (= ["low" "high"] (:available-variants s')) "the variants list does update")))
    (testing "until the user picks another"
      (let [s' (with-redefs [protocol/selected-model-changed! (fn [& _])]
                 (selection/choose s :model "m/a"))
            [s'' _] (chat/handle-config-updated s' {:params {:chat {:selectVariant "low"}}})]
        (is (= #{:agent} (:pins s'')) "a new model drops the variant's pin too")
        (is (= "low" (:selected-variant s'')))))
    (testing "without flags, nothing is pinned"
      (let [[s' _] (chat/handle-config-updated (base-state {}) {:params {:chat {:selectModel "m/a"}}})]
        (is (= "m/a" (:selected-model s'))))))
  (testing "the server hears of the model and agent once initialized, the agent first"
    (let [[[_ cmd] sent] (with-sent #(startup/on-ready (base-state {:model "m/b" :agent "plan"})))]
      (is (= [[:agent "plan"] [:model "m/b"]] sent))
      (is (nil? cmd) "nothing to continue"))))

;; --- The prompt ---

(deftest startup-prompt-test
  (let [s (base-state {:prompt "explain this project"})]
    (testing "not before the server is ready"
      (doseq [[why not-ready] [["no models yet"        (assoc (ready s) :available-models [])]
                               ["a startup task runs"  (assoc (ready s) :init-tasks {"mcp" {:done? false}})]
                               ["a task just ended"    (assoc (ready s) :init-tasks-at (System/currentTimeMillis))]
                               ["not initialized"      (dissoc (ready s) :ready-at)]
                               ["not :ready"           (assoc (ready s) :mode :connecting)]]]
        (let [[[s' _] sent] (with-sent #(startup/tick not-ready (System/currentTimeMillis)))]
          (is (empty? sent) why)
          (is (some? (:startup s')) why))))
    (testing "then sent, once"
      (let [[[s' _] sent] (with-sent #(startup/tick (ready s) (System/currentTimeMillis)))]
        (is (= "explain this project" (get-in (first sent) [1 :message])))
        (is (= :chatting (:mode s')))
        (is (nil? (:startup s')))
        (is (= [{:type :user :text "explain this project"}] (:items s')))
        (let [[_ again] (with-sent #(startup/tick s' (System/currentTimeMillis)))]
          (is (empty? again)))))
    (testing "every tick checks: an idle one too"
      (let [[[s' _] sent] (with-sent #(#'state/handle-eca-tick (ready s) []))]
        (is (= 1 (count sent)))
        (is (= :chatting (:mode s')))))))

(deftest startup-files-test
  (let [ws  (str (fs/canonicalize (fs/create-temp-dir)))
        src (str (fs/create-file (fs/path ws "core.clj")))
        dir (str (fs/create-dirs (fs/path ws "docs")))
        files [{:type "file" :path src} {:type "directory" :path dir}]]
    (testing "with a prompt: `@path`s ahead of it, their contexts sent"
      (let [s (base-state {:workspace ws :prompt "explain" :files files})
            [_ sent] (with-sent #(startup/tick (ready s) (System/currentTimeMillis)))
            params (second (first sent))]
        (is (= (str "@" src " @" dir " explain") (:message params)) "spelled out, as the `@` picker's")
        (is (= files (:contexts params)))))
    (testing "alone: in the input, ready for the prompt"
      (let [s (base-state {:workspace ws :files files})
            [[s' _] sent] (with-sent #(startup/tick (ready s) (System/currentTimeMillis)))]
        (is (empty? sent))
        (is (= "@core.clj @docs/ " (ti/value (:input s'))))
        (is (= files (:pending-contexts s')))))))

;; --- -c ---

(deftest continue-test
  (let [s (base-state {:continue true :prompt "and add tests"})]
    (testing "once initialized, the latest chat is looked up"
      (let [[[_ cmd] _] (with-sent #(startup/on-ready s))]
        (is (some? cmd))))
    (testing "the prompt waits for it"
      (let [[_ sent] (with-sent #(startup/tick (ready s) (System/currentTimeMillis)))]
        (is (empty? sent))))
    (testing "found: opened (its replay follows), then the prompt goes to it"
      (let [[[s' cmd] _] (with-sent #(startup/handle-continue (ready s) "chat-7"))]
        (is (= "chat-7" (:chat-id s')))
        (is (some? cmd))
        (let [[_ opened] (with-sent #((:fn cmd)))]
          (is (= [[:open "chat-7"]] opened)))
        (let [s''          (startup/handle-continued s')
              [[_ _] sent] (with-sent #(startup/tick s'' (System/currentTimeMillis)))]
          (is (= {:message "and add tests" :chat-id "chat-7"}
                 (select-keys (second (first sent)) [:message :chat-id]))))))
    (testing "none: a new chat, said so"
      (let [[[s' cmd] _] (with-sent #(startup/handle-continue (ready s) nil))]
        (is (nil? cmd))
        (is (nil? (:chat-id s')))
        (is (re-find #"No chat to continue" (:text (peek (:items s')))))
        (let [[_ sent] (with-sent #(startup/tick s' (System/currentTimeMillis)))]
          (is (= 1 (count sent))))))))

;; --- --title ---

(deftest title-test
  (testing "a new chat is named once its first turn is over"
    (let [s (assoc (base-state {:title "Release notes"}) :chat-id "c1" :turn-started-at 1)]
      (let [[s' sent] (with-sent #(startup/after-turn s))]
        (is (empty? sent) "not while the turn runs")
        (is (= "Release notes" (get-in s' [:opts :title]))))
      (let [done      (assoc s :turn-started-at nil :last-turn-ms 900)
            [s' sent] (with-sent #(startup/after-turn done))]
        (is (= [[:update "c1" {:title "Release notes"}]] sent))
        (is (nil? (get-in s' [:opts :title])) "once"))))
  (testing "a continued chat without a prompt: right away"
    (let [s (assoc (base-state {:title "Old work" :continue true}) :chat-id "c9")
          [s' sent] (with-sent #(startup/handle-continued s))]
      (is (= [[:update "c9" {:title "Old work"}]] sent))
      (is (nil? (get-in s' [:opts :title]))))))
