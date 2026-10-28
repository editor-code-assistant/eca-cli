(ns eca-cli.approvals-test
  "Approvals never take keys meant as text: one arriving while the user types
  waits for a pause, keys that don't answer it go to the message, and y / n /
  Y answer only once it's been on screen a moment. Driven key by key through
  `state/update-state`, as the terminal sends them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.chat :as chat]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(def ^:private sent (atom []))

(defn- capture [t]
  (reset! sent [])
  (with-redefs [protocol/approve-tool!        (fn [_ chat-id id & [save]] (swap! sent conj [:approve chat-id id save]))
                protocol/reject-tool!         (fn [_ chat-id id] (swap! sent conj [:reject chat-id id]))
                protocol/steer-prompt!        (fn [_ chat-id text] (swap! sent conj [:steer chat-id text]))
                protocol/steer-prompt-remove! (fn [_ chat-id] (swap! sent conj [:steer-remove chat-id]))
                protocol/stop-prompt!         (fn [_ chat-id] (swap! sent conj [:stop chat-id]))
                protocol/chat-prompt!         (fn [_ params _] (swap! sent conj [:prompt (:message params)]))]
    (t)))

(use-fixtures :each support/isolated-chats-file capture)

(defn- base []
  {:mode :chatting :turn-started-at 1 :chat-id "c1" :items [] :current-text "" :tool-calls {}
   :questions [] :approval-queue [] :input (ti/focus (ti/text-input)) :input-history []
   :width 100 :height 30 :chat-lines [] :scroll-offset 0 :opts {:workspace "/tmp/ws"}
   :mcps {} :init-tasks {} :trust false})

(defn- press [s & keys]
  (reduce (fn [s k] (first (state/update-state s (if (map? k) k (msg/key-press k))))) s keys))

(defn- type-keys
  "`text` typed one key at a time, as the terminal sends it."
  [s text]
  (apply press s (map str text)))

(defn- server [s & msgs]
  (first (state/update-state s {:type :eca-tick :msgs (vec msgs)})))

(defn- approval-run [id]
  {:method "chat/contentReceived"
   :params {:chatId "c1" :role "assistant"
            :content {:type "toolCallRun" :id id :name "shell_command" :server "eca"
                      :summary "Running rm" :manualApproval true
                      :arguments {:command "rm -rf build/"}}}})

(defn- later
  "`s` as it is `ms` later: what the user typed and the approval on screen
  are that much older. A tick then lets the state see the time pass."
  [s ms]
  (cond-> s
    (:typed-at s)          (update :typed-at - ms)
    (:approval-shown-at s) (update :approval-shown-at - ms)))

(defn- draft [s] (ti/value (:input s)))

(defn- screen [s] (theme/strip-ansi (view/view s)))

(deftest typing-holds-an-approval-test
  (testing "an approval arriving mid-word waits: the rest of the message is text, nothing is answered"
    (let [s (-> (base) (type-keys "wait, I meant j") (server (approval-run "ta")))]
      (is (= :chatting (:mode s)) "the prompt stays")
      (is (true? (:approval-held? s)))
      (is (str/includes? (screen s) "Waiting for your approval · shows when you stop typing"))
      (is (str/includes? (screen s) "> wait, I meant j") "the message stays on screen")
      (let [s (type-keys s "ust the cache, it is a really big dir")]
        (is (= [] @sent) "no y or n in the message answered the approval")
        (is (= "wait, I meant just the cache, it is a really big dir" (draft s)) "no key was lost")
        (is (= :chatting (:mode s)))
        (testing "sending the message steers, and the approval takes over at once"
          (let [s (press s :enter)]
            (is (= [[:steer "c1" "wait, I meant just the cache, it is a really big dir"]] @sent))
            (is (= :approving (:mode s)))
            (is (str/includes? (screen s) "[y] approve"))))))))

(deftest a-pause-shows-the-approval-test
  (let [held (-> (base) (type-keys "keep the") (server (approval-run "ta")))]
    (testing "still typing: it waits"
      (is (= :chatting (:mode (server (later held 500))))))
    (testing "after a pause it takes over the prompt, the message kept for later"
      (let [shown (server (later held chat/approval-quiet-ms))]
        (is (= :approving (:mode shown)))
        (is (nil? (:approval-held? shown)))
        (is (number? (:approval-shown-at shown)))
        (is (= "keep the" (draft shown)))
        (testing "a y pressed as it appears is the message going on, not an answer"
          (let [s (press shown "y")]
            (is (= [] @sent))
            (is (= "keep they" (draft s)))
            (is (= :chatting (:mode s)) "the approval steps aside")
            (is (true? (:approval-held? s)))))
        (testing "any other letter, or Backspace, goes to the message too"
          (is (= "keep thex" (draft (press (later shown 1000) "x"))))
          (is (= "keep th" (draft (press (later shown 1000) :backspace))))
          (is (= :chatting (:mode (press (later shown 1000) "x")))))
        (testing "once it's been on screen a moment, y approves and the message comes back"
          (let [s (press (later shown chat/approval-arm-ms) "y")]
            (is (= [[:approve "c1" "ta" nil]] @sent))
            (is (= :chatting (:mode s)))
            (is (= "keep the" (draft s)))))))))

(deftest deliberate-answers-test
  (testing "not typing, an approval takes over at once and its keys answer"
    (doseq [[k expected] [["y" [:approve "c1" "ta" nil]]
                          ["Y" [:approve "c1" "ta" "session"]]
                          ["n" [:reject "c1" "ta"]]]]
      (reset! sent [])
      (let [s (server (base) (approval-run "ta"))]
        (is (= :approving (:mode s)))
        (press (later s chat/approval-arm-ms) k)
        (is (= [expected] @sent) k))))
  (testing "an old draft, not being typed, doesn't hold it back"
    (reset! sent [])
    (let [s (-> (base) (type-keys "for later") (later 60000) (server (approval-run "ta")))]
      (is (= :approving (:mode s)))
      (press (later s chat/approval-arm-ms) "n")
      (is (= [[:reject "c1" "ta"]] @sent))))
  (testing "a key already on its way as the approval appears is text"
    (reset! sent [])
    (let [s (press (server (base) (approval-run "ta")) "n")]
      (is (= [] @sent))
      (is (= "n" (draft s))))))

(deftest a-held-key-does-not-answer-the-queue-test
  (testing "y pressed twice (or held down) answers one approval, not the next too"
    (let [s (-> (base) (server (approval-run "a") (approval-run "b")))
          s (press (later s chat/approval-arm-ms) "y")]
      (is (= [[:approve "c1" "a" nil]] @sent))
      (is (= "b" (get-in s [:pending-approval :tool-call-id])))
      (is (= :approving (:mode s)) "the next one shows")
      (let [s (press s "y")]
        (is (= [[:approve "c1" "a" nil]] @sent) "the repeated y didn't approve b")
        (is (= "y" (draft s)))
        (testing "b is answered once looked at"
          (press (later (server (later s chat/approval-quiet-ms)) chat/approval-arm-ms) "n")
          (is (= [[:approve "c1" "a" nil] [:reject "c1" "b"]] @sent)))))))

(deftest pasted-text-never-answers-test
  (testing "a paste over an approval is a message, whatever its letters"
    (let [s (-> (base) (server (approval-run "ta")) (later 5000))
          s (press s :paste-start "y" "e" "s" " " "n" "o" :enter "Y" :paste-end)]
      (is (= [] @sent))
      (is (= "yes no\nY" (draft s)))
      (is (= :chatting (:mode s)))
      (is (true? (:approval-held? s))))))

(deftest clearing-or-stopping-test
  (testing "clearing the message (Ctrl+C) brings the approval up"
    (let [s (-> (base) (type-keys "hmm") (server (approval-run "ta")) (press (msg/key-press "c" :ctrl true)))]
      (is (= :approving (:mode s)))
      (is (= "" (draft s)))))
  (testing "stopping the agent (Esc) drops the held approval: the server rejects it"
    (let [s (-> (base) (type-keys "hmm") (server (approval-run "ta")) (press :escape))]
      (is (= [[:stop "c1"]] @sent))
      (is (= :ready (:mode s)))
      (is (nil? (:pending-approval s)))
      (is (nil? (:approval-held? s)))
      (is (= :ready (:mode (server (later s chat/approval-quiet-ms)))) "nothing comes back"))))

(deftest transcript-reader-keys-test
  (testing "with a transcript block focused, a letter that isn't an answer is ignored, as before"
    (let [s (-> (base)
                (assoc :items [{:type :tool-call :id "ta" :name "shell_command" :summary "Running rm"
                                :state :run :expanded? false}])
                (server (approval-run "ta"))
                (later 5000)
                (press (msg/key-press "o" :ctrl true) :tab))
          after (press s "x")]
      (is (= :approving (:mode after)))
      (is (= "" (draft after)))
      (is (= [] @sent)))))
