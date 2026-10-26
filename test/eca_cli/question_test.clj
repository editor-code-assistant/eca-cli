(ns eca-cli.question-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]
            [eca-cli.view.blocks :as blocks]))

(def ^:private responses (atom []))

(defn- capture-responses [t]
  (reset! responses [])
  (with-redefs [protocol/send-response!       (fn [_ id result] (swap! responses conj [id result]))
                protocol/send-error-response! (fn [_ id code _] (swap! responses conj [id {:error code}]))
                protocol/approve-tool!        (fn [& _])]
    (t)))

(use-fixtures :each support/isolated-chats-file capture-responses)

(defn- base []
  {:mode :chatting :turn-started-at 1 :chat-id "c1" :items [] :current-text "" :tool-calls {}
   :questions [] :approval-queue [] :input (ti/blur (ti/text-input)) :width 80 :height 24
   :chat-lines [] :scroll-offset 0 :opts {:workspace "/tmp/ws"} :mcps {} :init-tasks {}})

(defn- ask [s id & {:as params}]
  (first (state/update-state s {:type :eca-tick
                                :msgs [{:jsonrpc "2.0" :id id :method "chat/askQuestion"
                                        :params (merge {:chatId "c1" :question "Which DB?"
                                                        :options [{:label "Postgres" :description "relational"}
                                                                  {:label "SQLite"}]
                                                        :allowFreeform true}
                                                       params)}]})))

(defn- press [s & keys]
  (reduce (fn [s k] (first (state/update-state s (msg/key-press k)))) s keys))

(defn- type-text [s text] (apply press s (map str text)))

(deftest asking-test
  (testing "the question takes over the input and joins the chat"
    (let [s (ask (base) 7)]
      (is (= :asking (:mode s)))
      (is (= 7 (get-in s [:questions 0 :request-id])))
      (is (= {:type :question :request-id 7 :question "Which DB?"} (peek (:items s))))
      (is (:focused (:input s)))))

  (testing "Enter picks the highlighted option; the turn carries on"
    (let [s (press (ask (base) 7) :enter)]
      (is (= [[7 {:answer "Postgres" :cancelled false}]] @responses))
      (is (= :chatting (:mode s)))
      (is (= "Postgres" (:answer (peek (:items s)))))))

  (testing "↓ then Enter picks the next one"
    (reset! responses [])
    (press (ask (base) 7) :down :enter)
    (is (= [[7 {:answer "SQLite" :cancelled false}]] @responses)))

  (testing "a typed answer wins over the options"
    (reset! responses [])
    (-> (ask (base) 7) (type-text "MySQL") (press :enter))
    (is (= [[7 {:answer "MySQL" :cancelled false}]] @responses)))

  (testing "Esc cancels"
    (reset! responses [])
    (let [s (press (ask (base) 7) :escape)]
      (is (= [[7 {:answer nil :cancelled true}]] @responses))
      (is (:cancelled? (peek (:items s))))))

  (testing "without free answers, digits pick and typing does nothing"
    (reset! responses [])
    (let [s (ask (base) 7 :allowFreeform false)]
      (is (= "" (ti/value (:input (type-text s "x")))))
      (press s "2")
      (is (= [[7 {:answer "SQLite" :cancelled false}]] @responses)))))

(deftest ask-user-tool-call-test
  (testing "asked by a tool call in the chat: the call shows the answer, no extra item"
    (let [s0 (assoc (base) :items [{:type :tool-call :id "tc9" :name "ask_user" :server "eca"
                                    :summary "Q: Which DB?" :state :running}])
          s  (press (ask s0 7 :toolCallId "tc9") :enter)]
      (is (= 1 (count (:items s))))
      (is (= "Postgres" (:answer (first (:items s)))))
      (is (= "✓ Q: Which DB?  → Postgres"
             (theme/strip-ansi (first (blocks/render-item-lines (assoc (first (:items s)) :state :called) 80))))))))

(deftest question-queue-test
  (testing "questions asked at once are answered in turn"
    (let [s  (-> (base) (ask 1) (ask 2 :question "Port?" :options []))
          s1 (press s :enter)]
      (is (= :asking (:mode s1)))
      (is (= "Port?" (get-in s1 [:questions 0 :question])))
      (let [s2 (-> s1 (type-text "5432") (press :enter))]
        (is (= [[1 {:answer "Postgres" :cancelled false}] [2 {:answer "5432" :cancelled false}]] @responses))
        (is (= :chatting (:mode s2)))))))

(deftest question-and-approval-test
  (testing "a question arriving during an approval waits for it"
    (let [s0 (assoc (base) :mode :approving :pending-approval {:chat-id "c1" :tool-call-id "t1"}
                    :tool-calls {"t1" {:id "t1" :name "shell"}})
          s  (ask s0 7)]
      (is (= :approving (:mode s)))
      (is (= :asking (:mode (press s "y"))))))
  (testing "an approval arriving during a question doesn't interrupt it"
    (let [s  (first (state/update-state (ask (base) 7)
                                        {:type :eca-tick
                                         :msgs [{:method "chat/contentReceived"
                                                 :params {:chatId "c1" :role "assistant"
                                                          :content {:type "toolCallRun" :id "t2" :name "shell"
                                                                    :server "eca" :manualApproval true}}}]}))]
      (is (= :asking (:mode s)))
      (is (= :approving (:mode (press s :enter)))))))

(deftest question-outlives-turn-test
  (testing "the turn finishing keeps the question; answering it then goes back to :ready"
    (let [s (first (state/update-state (ask (base) 7)
                                       {:type :eca-tick
                                        :msgs [{:method "chat/contentReceived"
                                                :params {:chatId "c1" :role "system"
                                                         :content {:type "progress" :state "finished"}}}]}))]
      (is (= :asking (:mode s)))
      (is (= :ready (:mode (press s :enter)))))))

(deftest draft-kept-test
  (testing "what the user was typing comes back after the answer"
    (let [s0 (-> (base) (assoc :mode :ready :turn-started-at nil) (update :input #(-> % ti/focus (ti/set-value "half a thought"))))
          s  (press (ask s0 7) :enter)]
      (is (= "half a thought" (ti/value (:input s)))))))

(deftest unsupported-request-test
  (testing "a server request eca-cli doesn't support gets an error, not silence"
    (state/update-state (base) {:type :eca-tick
                                :msgs [{:jsonrpc "2.0" :id 9 :method "editor/getDiagnostics" :params {}}]})
    (is (= [[9 {:error protocol/method-not-found}]] @responses))))

(deftest capability-test
  (is (true? (get-in (protocol/initialize-params "/tmp/x")
                     [:capabilities :codeAssistant :chatCapabilities :askQuestion]))))

(deftest render-question-test
  (let [s   (press (ask (base) 7 :question "Which **DB**?") :down)
        out (theme/strip-ansi (view/render-question s))]
    (is (str/includes? out "? Which DB?"))
    (is (str/includes? out "  1. Postgres  relational"))
    (is (str/includes? out "❯ 2. SQLite"))
    (is (str/includes? out "↑↓ Enter to pick, or type an answer · Esc cancels"))
    (is (str/includes? out "> ") "a prompt for a free answer")
    (testing "lines never exceed the width"
      (is (every? #(<= (theme/width %) 80) (str/split-lines (view/render-question s))))))
  (testing "the chat keeps the question and its answer"
    (let [lines #(mapv theme/strip-ansi (blocks/render-item-lines % 60))]
      (is (= ["? Which DB?" "  → Postgres"] (lines {:type :question :question "Which DB?" :answer "Postgres"})))
      (is (= ["? Which DB?" "  ✗ cancelled"] (lines {:type :question :question "Which DB?" :cancelled? true})))
      (is (= "  waiting for your answer…" (peek (lines {:type :question :question "Which DB?"})))))))
