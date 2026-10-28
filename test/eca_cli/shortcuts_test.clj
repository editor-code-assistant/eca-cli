(ns eca-cli.shortcuts-test
  "The model / agent / variant / trust keys, the other shortcuts, and pickers
  opened while the agent works."
  (:require [charm.components.text-input :as ti]
            [charm.input.handler :as input]
            [charm.message :as msg]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.chat :as chat]
            [eca-cli.commands :as commands]
            [eca-cli.editor :as editor]
            [eca-cli.paths :as paths]
            [eca-cli.program :as program]
            [eca-cli.protocol :as protocol]
            [eca-cli.selection :as selection]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(use-fixtures :each support/isolated-chats-file)

(defn- base-state []
  {:mode               :ready
   :trust              false
   :chat-id            "chat1"
   :items              []
   :current-text       ""
   :tool-calls         {}
   :approval-queue     []
   :questions          []
   :pending-contexts   []
   :init-tasks         {}
   :available-models   ["m/a" "m/b" "m/c"]
   :available-agents   ["code" "plan"]
   :available-variants ["low" "medium" "high"]
   :selected-model     "m/a"
   :selected-agent     "code"
   :selected-variant   nil
   :recent-models      []
   :input              (ti/focus (ti/text-input))
   :input-history      []
   :chat-lines         []
   :scroll-offset      0
   :width              100
   :height             24
   :mcps               {}
   :server             nil
   :opts               {:workspace "/tmp/test"}})

(defn- chatting [s] (assoc s :mode :chatting :turn-started-at 1))

(def ^:private shift-tab (msg/key-press :tab :shift true))
(def ^:private alt-t     (msg/key-press "t" :alt true))
(def ^:private alt-p     (msg/key-press "p" :alt true))
(def ^:private ctrl-p    (msg/key-press "p" :ctrl true))
(def ^:private f2        (msg/key-press :f2))
(def ^:private shift-f2  (msg/key-press :f2 :shift true))
(def ^:private esc       (msg/key-press :escape))
(def ^:private enter     (msg/key-press :enter))

(defn- sent-to-server
  "[state notifications] after pressing `keys`: what reached the server."
  [state & keys]
  (let [sent (atom [])]
    (with-redefs [protocol/selected-model-changed! (fn [_ m] (swap! sent conj [:model m]))
                  protocol/selected-agent-changed! (fn [_ a] (swap! sent conj [:agent a]))
                  protocol/update-chat!            (fn [_ _ f] (swap! sent conj [:update f]))]
      [(reduce (fn [s k] (first (state/update-state s k))) state keys) @sent])))

(defn- press [state & keys] (first (apply sent-to-server state keys)))

(defn- typed [s text] (update s :input ti/set-value text))

;; --- Input ---

(deftest shift-tab-parses-test
  (testing "CSI Z is Shift+Tab, which charm alone doesn't know"
    (let [km (#'program/keymap nil)]
      (is (= {:type :tab :shift true} (input/parse-input 27 "[Z" km)))
      (is (= [:key-press :tab true]
             ((juxt :type :key :shift) (#'program/event->msg {:type :tab :shift true}))))))
  (testing "plain Tab still focuses blocks; Shift+Tab doesn't"
    (let [s (-> (base-state)
                (assoc :items [{:type :tool-call :id "t" :name "ls" :state :called}])
                (assoc-in [:opts :fullscreen] true)
                view/rebuild-lines)]
      (is (= [0] (:focus-path (press s (msg/key-press :tab)))))
      (is (nil? (:focus-path (press s shift-tab)))))))

;; --- New lines ---

(deftest newline-keys-parse-test
  (let [km (#'program/keymap nil)]
    (testing "Shift+Enter, in the terminals that tell it from Enter"
      (is (= {:type :enter :shift true} (input/parse-input 27 "[13;2u" km)))
      (is (= {:type :enter :shift true} (input/parse-input 27 "[27;2;13~" km))))))

(deftest newline-keys-test
  (let [ctrl-j      (msg/key-press "j" :ctrl true)
        shift-enter (msg/key-press :enter :shift true)]
    (doseq [k [ctrl-j shift-enter]]
      (testing (str k " inserts a newline at the cursor, sending nothing")
        (let [[s cmd] (state/update-state (typed (base-state) "first") k)]
          (is (nil? cmd))
          (is (= "first\n" (ti/value (:input s))))
          (is (= :ready (:mode s)))
          (is (empty? (:items s)))))
      (testing (str k " while the agent works: a newline, not a steer")
        (let [sent (atom [])
              s    (with-redefs [protocol/steer-prompt! (fn [& args] (swap! sent conj args))]
                     (press (chatting (typed (base-state) "wait")) k))]
          (is (= "wait\n" (ti/value (:input s))))
          (is (empty? @sent))
          (is (nil? (:steered s))))))
    (testing "in a free answer to a question too"
      (let [s (-> (base-state)
                  (assoc :mode :asking :questions [{:request-id 1 :question "?" :options [] :allow-freeform? true}])
                  (typed "line one"))]
        (is (= "line one\n" (ti/value (:input (press s ctrl-j))))))))
  (testing "`\\` then Enter: the backslash becomes a newline"
    (let [[s cmd] (state/update-state (typed (base-state) "first \\") enter)]
      (is (nil? cmd))
      (is (= "first \n" (ti/value (:input s))))
      (is (empty? (:items s)) "nothing sent"))
    (let [sent (atom [])
          s    (with-redefs [protocol/steer-prompt! (fn [& args] (swap! sent conj args))]
                 (press (chatting (typed (base-state) "more\\")) enter))]
      (is (= "more\n" (ti/value (:input s))))
      (is (empty? @sent) "no steer")))
  (testing "Enter still steers while the agent works"
    (let [sent (atom [])
          s    (with-redefs [protocol/steer-prompt! (fn [_ _ text] (swap! sent conj text))]
                 (press (chatting (typed (base-state) "go left")) enter))]
      (is (= ["go left"] @sent))
      (is (= "" (ti/value (:input s)))))))

;; --- Agent, variant, model ---

(deftest shift-tab-cycles-agents-test
  (let [[s sent] (sent-to-server (base-state) shift-tab)]
    (is (= "plan" (:selected-agent s)))
    (is (= "plan" (get-in s [:opts :agent])) "sent with the next prompts")
    (is (= [[:agent "plan"]] sent)))
  (testing "wraps around"
    (is (= "code" (:selected-agent (press (base-state) shift-tab shift-tab)))))
  (testing "while the agent works too, which keeps working"
    (let [s (press (chatting (typed (base-state) "draft")) shift-tab)]
      (is (= "plan" (:selected-agent s)))
      (is (= :chatting (:mode s)))
      (is (= "draft" (ti/value (:input s))) "the draft stays")))
  (testing "one agent: nothing to cycle"
    (let [[s sent] (sent-to-server (assoc (base-state) :available-agents ["code"]) shift-tab)]
      (is (= "code" (:selected-agent s)))
      (is (empty? sent))))
  (testing "not while approving: y/n/Y are the keys there"
    (is (= "code" (:selected-agent (press (assoc (base-state) :mode :approving) shift-tab))))))

(deftest alt-t-cycles-variants-test
  (let [steps (rest (reductions (fn [s _] (press s alt-t)) (base-state) (range 4)))]
    (is (= ["low" "medium" "high" nil] (map :selected-variant steps)) "in the server's order, then none")
    (is (= "low" (get-in (first steps) [:opts :variant]))))
  (testing "a model without variants says so"
    (let [s (press (assoc (base-state) :available-variants []) alt-t)]
      (is (nil? (:selected-variant s)))
      (is (str/includes? (:text (peek (:items s))) "no variants")))))

(deftest alt-p-opens-model-picker-test
  (doseq [k [alt-p (msg/key-press "l" :ctrl true)]]
    (testing (str k)
      (let [s (press (chatting (typed (base-state) "half a thought")) k)]
        (is (= :picking (:mode s)))
        (is (= :model (get-in s [:picker :kind])))
        (testing "picking returns to the running turn with the draft intact"
          (let [[after sent] (sent-to-server s (msg/key-press :down) enter)]
            (is (= :chatting (:mode after)))
            (is (= "m/b" (:selected-model after)))
            (is (= [[:model "m/b"]] sent))
            (is (= "half a thought" (ti/value (:input after))))))
        (testing "so does Esc"
          (let [after (press s esc)]
            (is (= :chatting (:mode after)))
            (is (= "m/a" (:selected-model after)))))))))

(deftest recent-models-test
  (testing "a picked model is remembered, first, with the one it replaced, and saved"
    (let [s (press (base-state) alt-p (msg/key-press :down) enter)]
      (is (= ["m/b" "m/a"] (:recent-models s)))
      (is (= ["m/b" "m/a"] (edn/read-string (slurp (paths/recent-models-file)))))
      (is (= ["m/b" "m/a"] (selection/load-recent-models)))
      (testing "so Ctrl+P goes back to it"
        (is (= "m/a" (:selected-model (press s ctrl-p)))))
      (testing "at most a few"
        (is (= selection/max-recent-models
               (count (:recent-models (reduce #(selection/remember-model %1 (str "x/" %2) nil)
                                              s (range 10)))))))))
  (testing "Ctrl+P / F2 cycle the recent models without reordering them: two toggle"
    (let [s0 (assoc (base-state) :recent-models ["m/a" "m/c"])
          [s1 sent] (sent-to-server s0 ctrl-p)]
      (is (= "m/c" (:selected-model s1)))
      (is (= [[:model "m/c"]] sent))
      (is (= "m/a" (:selected-model (press s1 f2))))
      (is (= ["m/a" "m/c"] (:recent-models (press s1 f2))))))
  (testing "Shift+F2 goes back"
    (let [s0 (assoc (base-state) :recent-models ["m/a" "m/b" "m/c"])]
      (is (= "m/c" (:selected-model (press s0 shift-f2))))))
  (testing "the model in use comes first when it isn't a recent one"
    (let [s0 (assoc (base-state) :selected-model "m/c" :recent-models ["m/a" "m/b"])]
      (is (= ["m/c" "m/a" "m/b"] (selection/model-cycle s0)))
      (is (= "m/a" (:selected-model (press s0 ctrl-p))))))
  (testing "models the server no longer offers are skipped"
    (is (= ["m/a" "m/b"] (selection/model-cycle (assoc (base-state) :recent-models ["gone/x" "m/b"])))))
  (testing "nothing recent: a hint"
    (let [s (press (base-state) ctrl-p)]
      (is (= "m/a" (:selected-model s)))
      (is (str/includes? (:text (peek (:items s))) "Alt+P"))))
  (testing "a new model drops the variant (the server says which applies)"
    (let [s (press (assoc (base-state) :selected-variant "high" :recent-models ["m/a" "m/b"]) ctrl-p)]
      (is (nil? (:selected-variant s))))))

;; --- Pickers while the agent works ---

(deftest picker-mid-turn-test
  (let [picking (press (chatting (base-state)) alt-p)]
    (testing "an approval waits for the picker: typing must not answer it"
      (let [s (chat/request-approval picking "chat1" "tool-1")]
        (is (= :picking (:mode s)))
        (is (= :approving (:mode (press s esc))))))
    (testing "a question waits for it too"
      (let [s (press (assoc picking :questions [{:request-id 1 :question "?" :options [] :allow-freeform? true}]) esc)]
        (is (= :asking (:mode s)))))
    (testing "the turn ending leaves it open; closing it then goes back to :ready"
      (let [s (chat/handle-content picking {:content {:type "progress" :state "finished"}})]
        (is (= :picking (:mode s)))
        (is (= :ready (:mode (press s esc))))))))

;; --- Trust, new chat, history, keys ---

(deftest ctrl-y-toggles-trust-test
  (let [[s sent] (sent-to-server (chatting (base-state)) (msg/key-press "y" :ctrl true))]
    (is (true? (:trust s)))
    (is (= :chatting (:mode s)))
    (is (= [[:update {:trust true}]] sent))
    (is (false? (:trust (press s (msg/key-press "y" :ctrl true)))))))

(deftest ctrl-n-new-chat-test
  (let [called (atom 0)]
    (with-redefs [commands/cmd-new-chat (fn [s] (swap! called inc) [(assoc s :chat-id nil) nil])]
      (is (nil? (:chat-id (press (base-state) (msg/key-press "n" :ctrl true)))))
      (testing "not while the agent works"
        (press (chatting (base-state)) (msg/key-press "n" :ctrl true))
        (is (= 1 @called))))))

(deftest ctrl-r-history-test
  (let [s0 (assoc (base-state) :input-history ["first prompt" "fix the\nbug" "first prompt"])
        s  (press s0 (msg/key-press "r" :ctrl true))]
    (is (= :picking (:mode s)))
    (is (= ["first prompt" "fix the\nbug"] (get-in s [:picker :all])) "newest first, once each")
    (testing "typing filters; Enter puts the prompt back in the input"
      (let [after (press s (msg/key-press "b") (msg/key-press "u") enter)]
        (is (= :ready (:mode after)))
        (is (= "fix the\nbug" (ti/value (:input after))))))
    (testing "no history: nothing opens"
      (is (= :ready (:mode (press (base-state) (msg/key-press "r" :ctrl true))))))))

(deftest esc-esc-rolls-back-test
  (let [s0  (assoc (base-state) :items [{:type :user :text "hello" :content-id "c1"}])
        one (press s0 esc)]
    (testing "the first Esc asks for a second"
      (is (some? (:esc-armed one)))
      (is (= "Press Esc again to roll back"
             (theme/strip-ansi (peek (str/split-lines (view/view one)))))))
    (testing "the second opens /rollback"
      (let [two (press one esc)]
        (is (= :rollback (get-in two [:picker :kind])))
        (is (nil? (:esc-armed two)))))
    (testing "another key in between disarms it"
      (is (nil? (:esc-armed (press one (msg/key-press "x"))))))
    (testing "the hint expires"
      (is (nil? (:esc-armed (first (state/update-state one {:type :esc-hint-expired :at (:esc-armed one)})))))))
  (testing "not with text typed"
    (is (nil? (:esc-armed (press (typed (base-state) "abc") esc))))))

(deftest question-mark-shows-keys-test
  (let [s    (press (base-state) (msg/key-press "?"))
        text (theme/strip-ansi (:text (peek (:items s))))]
    (is (str/starts-with? text "Keyboard shortcuts:"))
    (doseq [k ["Shift+Tab" "Alt+T" "Alt+P" "Ctrl+P" "Ctrl+Y" "Ctrl+G" "Ctrl+R"]]
      (is (str/includes? text k))))
  (testing "mid-sentence it's just a character"
    (is (= "why?" (ti/value (:input (press (typed (base-state) "why") (msg/key-press "?")))))))
  (testing "/keys too"
    (is (contains? commands/command-registry "/keys"))))

;; --- Ctrl+G ---

(deftest ctrl-g-editor-test
  (testing "Ctrl+G hands the draft to the editor through the program loop"
    (let [[_ cmd] (state/update-state (typed (base-state) "draft") (msg/key-press "g" :ctrl true))
          m       ((:fn cmd))]
      (is (= ::program/external (:type m)))
      (with-redefs [editor/edit! (fn [text] {:text (str text " edited\n")})]
        (is (= {:type :editor-done :text "draft edited\n"} ((:done m) ((:run m))))))))
  (testing "what was saved replaces the prompt, without the trailing newline"
    (let [s (first (state/update-state (typed (base-state) "draft") {:type :editor-done :text "new text\n\n"}))]
      (is (= "new text" (ti/value (:input s))))
      (is (= 8 (ti/position (:input s))))))
  (testing "an editor failing leaves the prompt"
    (let [s (first (state/update-state (typed (base-state) "draft") {:type :editor-done :error "vi exited with 1"}))]
      (is (= "draft" (ti/value (:input s))))
      (is (str/includes? (:text (peek (:items s))) "exited"))))
  (testing "the editor command"
    (with-redefs [paths/getenv {"VISUAL" "" "EDITOR" "nano -w"}]
      (is (= "nano -w" (editor/editor-command))))
    (with-redefs [paths/getenv (constantly nil)]
      (is (#{"vi" "nano"} (editor/editor-command)))))
  (testing "edit! runs it on a file holding the draft"
    (with-redefs [paths/getenv {"EDITOR" "sed -i s/draft/edited/"}]
      (is (= {:text "my edited"} (editor/edit! "my draft"))))
    (with-redefs [paths/getenv {"EDITOR" "false"}]
      (is (:error (editor/edit! "x"))))))
