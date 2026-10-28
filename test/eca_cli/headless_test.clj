(ns eca-cli.headless-test
  "Print mode and the lists, end to end against a scripted server
  (fixtures/headless_eca.clj), plus the pure parts."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.headless :as headless]
            [eca-cli.paths :as paths]
            [eca-cli.test-support :as support]))

(use-fixtures :each support/isolated-chats-file)

;; --- Harness ---

(def ^:private fixture
  (str (fs/canonicalize "test/eca_cli/fixtures/headless_eca.clj")))

(defn- fake-eca
  "An executable running the scripted server, and the file where it
  records what it gets."
  []
  (let [dir (fs/create-temp-dir {:prefix "eca-cli-headless-"})
        bin (str (fs/path dir "eca"))
        log (str (fs/path dir "received.jsonl"))]
    (spit bin (str "#!/bin/sh\nFAKE_ECA_LOG='" log "' exec bb '" fixture "' \"$@\"\n"))
    (fs/set-posix-file-permissions bin "rwxr-xr-x")
    {:bin bin :log log :dir (str dir)}))

(defn- received
  "What the fake server got: its arguments, then the messages."
  [log]
  (if (fs/exists? log)
    (mapv #(json/parse-string % true) (str/split-lines (slurp log)))
    []))

(defn- sent [log method]
  (filter #(= method (:method %)) (received log)))

(defn- run
  "Run `f` (print!, list-models!…) with `opts` against a fresh fake
  server: {:exit :out :err :log}."
  ([opts] (run headless/print! opts))
  ([f opts]
   (let [{:keys [bin log dir]} (fake-eca)
         err (java.io.StringWriter.)
         ws  (str (fs/create-dirs (fs/path dir "ws")))]
     (with-redefs [paths/log-file           (constantly (fs/file dir "server.log"))
                   headless/ready-quiet-ms 50]
       (let [exit (volatile! nil)
             out  (with-out-str
                    (binding [*err* err]
                      (vreset! exit (f (merge {:workspace ws :eca bin} opts)))))]
         {:exit @exit :out out :err (str err) :log log :workspace ws})))))

;; --- Print mode ---

(deftest print-text-test
  (let [{:keys [exit out err log]} (run {:prompt "hello"})]
    (testing "the answer, alone on stdout"
      (is (= 0 exit))
      (is (= "Hi there!\n" out))
      (is (= "" err)))
    (testing "a new chat, prompted without asking questions"
      (let [init   (first (sent log "initialize"))
            prompt (:params (first (sent log "chat/prompt")))]
        (is (false? (get-in init [:params :capabilities :codeAssistant :chatCapabilities :askQuestion])))
        (is (= "hello" (:message prompt)))
        (is (string? (:chatId prompt)))
        (is (not (contains? prompt :trust)) "the server's default trust applies")))))

(deftest print-json-test
  (let [{:keys [exit out]} (run {:prompt "hello" :output-format "json" :agent "plan" :variant "high"})
        result (json/parse-string out true)]
    (is (= 0 exit))
    (is (= 1 (count (str/split-lines out))) "one object")
    (is (= {:type "result" :result "Hi there!" :isError false :model "fake/model-a"
            :agent "plan" :variant "high" :usage {:sessionTokens 120 :sessionCost "0.01"}}
           (dissoc result :chatId :durationMs)))
    (is (string? (:chatId result)))
    (is (nat-int? (:durationMs result)))))

(deftest print-stream-json-test
  (let [{:keys [exit out]} (run {:prompt "hello" :output-format "stream-json"})
        events (mapv #(json/parse-string % true) (str/split-lines out))]
    (is (= 0 exit))
    (is (= "init" (:type (first events))))
    (is (= "result" (:type (peek events))))
    (is (= (:chatId (first events)) (:chatId (peek events))))
    (testing "in between, the server's chat events as they are"
      (let [contents (map :params (filter #(= "chat/contentReceived" (:type %)) events))]
        (is (= ["Hi " "there!"] (keep #(when (= "assistant" (:role %)) (get-in % [:content :text])) contents)))
        (is (some #(= "finished" (get-in % [:content :state])) contents))))))

(deftest print-options-test
  (let [file (str (fs/create-temp-file {:prefix "ctx-" :suffix ".clj"}))
        {:keys [exit out log]} (run {:prompt "check it" :trust true :model "fake/model-b"
                                     :files [{:type "file" :path file}]
                                     :stdin "piped text"})
        prompt (:params (first (sent log "chat/prompt")))]
    (is (= 0 exit))
    (is (str/starts-with? out "You said: check it"))
    (is (= {:message "check it" :trust true :model "fake/model-b"
            :contexts [{:type "file" :path file}
                       {:type "text" :label "stdin" :content "piped text"}]}
           (dissoc prompt :chatId)))))

(deftest print-tool-approval-test
  (testing "a tool call needing approval is rejected, and said on stderr"
    (let [{:keys [exit out err log]} (run {:prompt "tool" :output-format "json"})
          result (json/parse-string out true)]
      (is (= 0 exit))
      (is (= 1 (count (sent log "chat/toolCallReject"))))
      (is (empty? (sent log "chat/toolCallApprove")) "never approved by eca-cli")
      (is (str/includes? err "rejected shell_command (Running rm -rf build): it needs approval"))
      (is (= "The command was rejected." (:result result)) "the answer after the tool call")
      (is (= [{:id "t1" :name "shell_command" :server "eca"}] (:rejectedToolCalls result))))))

(deftest print-error-test
  (let [{:keys [exit out err]} (run {:prompt "fail"})]
    (is (= 1 exit))
    (is (= "" out) "the error isn't the answer")
    (is (str/includes? err "boom")))
  (let [{:keys [exit out]} (run {:prompt "fail" :output-format "json"})
        result (json/parse-string out true)]
    (is (= 1 exit))
    (is (true? (:isError result)))
    (is (= {:message "boom" :type "network"} (:error result)))))

(deftest print-login-test
  (let [{:keys [exit out err]} (run {:prompt "need login"})]
    (is (= 1 exit))
    (is (= "" out))
    (is (str/includes? err "needs a login"))))

(deftest print-server-gone-test
  (let [{:keys [exit out err]} (run {:prompt "die"})]
    (is (= 1 exit))
    (is (= "" out))
    (is (str/includes? err "ECA server exited unexpectedly (exit code 3)"))))

(deftest print-command-test
  (testing "a server command's output is the answer"
    (let [{:keys [exit out]} (run {:prompt "/costs"})]
      (is (= 0 exit))
      (is (= "Total cost: $0.01\n" out)))))

(deftest print-continue-title-ephemeral-test
  (testing "--continue prompts the latest chat (not an inline one)"
    (let [{:keys [exit log]} (run {:prompt "hello" :continue true})]
      (is (= 0 exit))
      (is (= "latest" (get-in (first (sent log "chat/prompt")) [:params :chatId])))))
  (testing "--title names the chat once the turn is over"
    (let [{:keys [log]} (run {:prompt "hello" :title "Nightly review"})
          msgs   (received log)
          prompt (first (keep-indexed #(when (= "chat/prompt" (:method %2)) %1) msgs))
          update (first (keep-indexed #(when (= "chat/update" (:method %2)) %1) msgs))]
      (is (some? update))
      (is (< prompt update))
      (is (= "Nightly review" (get-in msgs [update :params :title])))))
  (testing "--ephemeral deletes the chat"
    (let [{:keys [log]} (run {:prompt "hello" :ephemeral true})
          chat-id (get-in (first (sent log "chat/prompt")) [:params :chatId])]
      (is (= [chat-id] (map #(get-in % [:params :chatId]) (sent log "chat/delete")))))))

(deftest print-output-last-message-test
  (let [f (str (fs/create-temp-file {:prefix "answer-" :suffix ".txt"}))
        {:keys [exit out]} (run {:prompt "hello" :output-format "json" :output-last-message f})]
    (is (= 0 exit))
    (is (str/includes? out "\"result\""))
    (is (= "Hi there!\n" (slurp f)))))

(deftest server-args-test
  (let [{:keys [log]} (run {:prompt "hello" :server-args ["--log-level" "debug" "--verbose"]})]
    (is (= ["server" "--log-level" "debug" "--verbose"] (:args (first (received log)))))))

;; --- Lists ---

(deftest list-models-test
  (let [{:keys [exit out]} (run headless/list-models! {})]
    (is (= 0 exit))
    (is (= "fake/model-a\nfake/model-b\n" out)))
  (let [{:keys [exit out]} (run headless/list-models! {:json true})]
    (is (= 0 exit))
    (is (= {:models ["fake/model-a" "fake/model-b"] :agents ["code" "plan"]
            :defaultModel "fake/model-a" :defaultAgent "code"}
           (json/parse-string out true)))))

(deftest list-chats-test
  (let [{:keys [exit out]} (run headless/list-chats! {})
        lines (str/split-lines out)]
    (is (= 0 exit))
    (is (= 2 (count lines)) "inline chats left out")
    (is (str/starts-with? (first lines) "latest  "))
    (is (str/ends-with? (first lines) "6 msgs  Latest work"))
    (is (str/ends-with? (second lines) "(untitled)")))
  (let [{:keys [out]} (run headless/list-chats! {:json true})]
    (is (= ["latest" "older"] (map :id (json/parse-string out true))))))

;; --- Pure parts ---

(deftest final-text-test
  (is (= "after" (headless/final-text {:segment " after " :last-text "before" :notes ["n"]})))
  (is (= "before" (headless/final-text {:segment "" :last-text "before"})))
  (is (= "a\n\nb" (headless/final-text {:segment "" :notes ["a" "b"]})))
  (is (= "" (headless/final-text {:segment "" :notes ["Error: x"] :error {:message "x"}}))))

(deftest prompt-params-test
  (let [ws  (str (fs/create-temp-dir))
        src (str (fs/create-file (fs/path ws "core.clj")))]
    (testing "`@path`s in the prompt: contexts, spelled out in the message, as the TUI sends them"
      (let [p (headless/prompt-params {:prompt "explain @core.clj" :workspace ws} "c1")]
        (is (= (str "explain @" (fs/canonicalize src)) (:message p)))
        (is (= [{:type "file" :path (str (fs/canonicalize src))}] (:contexts p)))))
    (testing "no contexts, no key"
      (is (not (contains? (headless/prompt-params {:prompt "hi" :workspace ws} "c1") :contexts))))))
