(ns eca-cli.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.protocol :as protocol]
            [eca-cli.version :as version]))

(deftest initialize-params-test
  (let [p (protocol/initialize-params "/home/user/myproject")]
    (is (= (.pid (java.lang.ProcessHandle/current)) (:processId p))
        "server monitors us and exits if we die")
    (is (= "eca-cli" (get-in p [:clientInfo :name])))
    (is (= version/version (get-in p [:clientInfo :version])))
    (is (= 1 (count (:workspaceFolders p))))
    (is (= "file:///home/user/myproject" (get-in p [:workspaceFolders 0 :uri])))
    (is (= "myproject" (get-in p [:workspaceFolders 0 :name])))))

(deftest chat-prompt-params-test
  (testing "bare message"
    (let [p (protocol/chat-prompt-params {:message "hello"})]
      (is (= "hello" (:message p)))
      (is (nil? (:chatId p)))
      (is (nil? (:model p)))))

  (testing "with chat-id"
    (let [p (protocol/chat-prompt-params {:message "hi" :chat-id "abc"})]
      (is (= "abc" (:chatId p)))))

  (testing "with model"
    (let [p (protocol/chat-prompt-params {:message "hi" :model "claude-opus-4-7"})]
      (is (= "claude-opus-4-7" (:model p)))))

  (testing "with agent"
    (let [p (protocol/chat-prompt-params {:message "hi" :agent "my-agent"})]
      (is (= "my-agent" (:agent p)))))

  (testing "all options"
    (let [p (protocol/chat-prompt-params {:message "hi" :chat-id "c1" :model "m1" :agent "a1" :trust true})]
      (is (= "c1" (:chatId p)))
      (is (= "m1" (:model p)))
      (is (= "a1" (:agent p)))
      (is (true? (:trust p))))))

(deftest tool-approve-params-test
  (let [p (protocol/tool-approve-params "chat1" "tc42")]
    (is (= "chat1" (:chatId p)))
    (is (= "tc42" (:toolCallId p)))
    (is (not (contains? p :save))))
  (testing "approve & remember asks the server to save it for the session"
    (is (= {:chatId "chat1" :toolCallId "tc42" :save "session"}
           (protocol/tool-approve-params "chat1" "tc42" "session")))))

(deftest tool-reject-params-test
  (let [p (protocol/tool-reject-params "chat1" "tc42")]
    (is (= "chat1" (:chatId p)))
    (is (= "tc42" (:toolCallId p)))))

(deftest prompt-stop-params-test
  (let [p (protocol/prompt-stop-params "chat1")]
    (is (= "chat1" (:chatId p)))))

(deftest requests-to-a-gone-server-test
  (testing "send-request! returns nil and leaves no dangling callback"
    (let [before @protocol/pending-requests]
      (is (nil? (protocol/send-request! {:writer nil} "chat/list" {} (fn [_]))))
      (is (= before @protocol/pending-requests))))

  (testing "shutdown! doesn't wait for an answer that can't come"
    (let [t0 (System/currentTimeMillis)]
      (protocol/shutdown! {:writer nil})
      (is (< (- (System/currentTimeMillis) t0) 1000))))

  (testing "initialize! is async: registers the callback and returns the request id"
    (let [out (java.io.ByteArrayOutputStream.)
          w   (java.io.BufferedWriter. (java.io.OutputStreamWriter. out "UTF-8"))
          cb  (fn [_])
          id  (protocol/initialize! {:writer w} "/tmp/ws" cb)]
      (is (number? id))
      (is (= cb (get @protocol/pending-requests id)))
      (is (re-find #"\"method\":\"initialize\"" (.toString out "UTF-8")))
      (swap! protocol/pending-requests dissoc id))))
