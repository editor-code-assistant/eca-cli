(ns eca-cli.slash-typing-test
  "Slash commands typed key by key, as the terminal sends them: `/` opens
  the picker to find a command, a space goes on with its arguments in the
  input, Enter runs it. (Setting the input and pressing Enter skips the
  picker, which is how `/rename <title>` went untested and unusable.)"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(def ^:private sent (atom []))

(defn- capture [t]
  (reset! sent [])
  (with-redefs [protocol/update-chat! (fn [_ chat-id fields] (swap! sent conj [:update chat-id fields]))
                protocol/add-flag!    (fn [_ chat-id content-id text] (swap! sent conj [:flag chat-id content-id text]))
                protocol/chat-prompt! (fn [_ params _] (swap! sent conj [:prompt (:message params)]))]
    (t)))

(use-fixtures :each support/isolated-chats-file capture)

(def ^:private server-commands
  [{:name "compact" :description "Summarize the chat to free context" :type "native"
    :arguments [{:name "additional-input"}]}
   {:name "btw" :description "Ask a side question" :type "native"
    :arguments [{:name "prompt" :required true}]}])

(defn- base []
  {:mode :ready :chat-id "c1" :items [{:type :user :text "fix it" :content-id "u1"}]
   :current-text "" :tool-calls {} :questions [] :approval-queue []
   :input (ti/focus (ti/text-input)) :input-history [] :width 100 :height 30
   :chat-lines [] :scroll-offset 0 :opts {:workspace "/tmp/ws"} :mcps {} :init-tasks {}
   :available-models ["anthropic/claude-sonnet-4-6" "openai/gpt-5.2"]
   :server-commands server-commands})

(defn- press [s & keys]
  (reduce (fn [s k] (first (state/update-state s (if (map? k) k (msg/key-press k))))) s keys))

(defn- type-keys [s text] (apply press s (map str text)))

(defn- draft [s] (ti/value (:input s)))

(deftest local-command-with-arguments-test
  (testing "/rename My first chat, typed: the picker finds the name, the title follows"
    (let [s (type-keys (base) "/rename ")]
      (is (= :ready (:mode s)) "the space left the picker")
      (is (nil? (:picker s)))
      (is (= "/rename " (draft s)))
      (let [s (-> s (type-keys "My first chat") (press :enter))]
        (is (= [[:update "c1" {:title "My first chat"}]] @sent))
        (is (= "" (draft s))))))
  (testing "/flag before refactor, typed"
    (reset! sent [])
    (-> (base) (type-keys "/flag before refactor") (press :enter))
    (is (= [[:flag "c1" "u1" "before refactor"]] @sent))))

(deftest server-command-with-arguments-test
  (testing "/compact focus on the tests, typed, goes to the server whole"
    (let [s (type-keys (base) "/compact ")]
      (is (str/includes? (theme/strip-ansi (view/view s)) "/compact [additional-input]")
          "the command's signature shows above the prompt")
      (press (type-keys s "focus on the tests") :enter)
      (is (= [[:prompt "/compact focus on the tests"]] @sent))))
  (testing "/btw with its question"
    (reset! sent [])
    (-> (base) (type-keys "/btw is the cache warm?") (press :enter))
    (is (= [[:prompt "/btw is the cache warm?"]] @sent))))

(deftest picking-still-works-test
  (testing "/ then part of a name and Enter runs the command picked"
    (let [s (-> (base) (type-keys "/mod") (press :enter))]
      (is (= :picking (:mode s)))
      (is (= :model (get-in s [:picker :kind])))))
  (testing "picking a command that takes arguments puts it in the input"
    (let [s (-> (base) (type-keys "/compac") (press :enter))]
      (is (= :ready (:mode s)))
      (is (= "/compact " (draft s)))))
  (testing "Esc closes the picker, nothing typed left"
    (let [s (-> (base) (type-keys "/ren") (press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))
      (is (= "" (draft s))))))

(deftest nothing-matches-test
  (testing "a message starting with a path is a message"
    (-> (base) (type-keys "/tmp/x is empty") (press :enter))
    (is (= [[:prompt "/tmp/x is empty"]] @sent)))
  (testing "Enter on a name nothing matches runs it as typed: the server may know it"
    (reset! sent [])
    (-> (base) (assoc :server-commands []) (type-keys "/compac") (press :enter))
    (is (= [[:prompt "/compac"]] @sent)))
  (testing "one neither knows, once the server's are in, says so"
    (reset! sent [])
    (let [s (-> (base) (type-keys "/zzz") (press :enter))]
      (is (= [] @sent))
      (is (= :ready (:mode s)))
      (is (str/includes? (:text (peek (:items s))) "Unknown command: /zzz")))))
