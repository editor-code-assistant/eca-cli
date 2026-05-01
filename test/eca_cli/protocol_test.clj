(ns eca-cli.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.protocol :as protocol]))

(deftest initialize-params-test
  (let [p (protocol/initialize-params "/home/user/myproject")]
    (is (nil? (:processId p)))
    (is (= "eca-cli" (get-in p [:clientInfo :name])))
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
    (is (= "tc42" (:toolCallId p)))))

(deftest tool-reject-params-test
  (let [p (protocol/tool-reject-params "chat1" "tc42")]
    (is (= "chat1" (:chatId p)))
    (is (= "tc42" (:toolCallId p)))))

(deftest prompt-stop-params-test
  (let [p (protocol/prompt-stop-params "chat1")]
    (is (= "chat1" (:chatId p)))))
