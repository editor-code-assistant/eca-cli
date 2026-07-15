(ns eca-cli.chats-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.chats :as chats]))

(deftest load-chat-id-missing-file-test
  (testing "returns nil when file does not exist"
    (with-redefs [chats/chats-path        (fn [] "/tmp/no-such-eca-chats-file.edn")
                  chats/legacy-chats-path (fn [] "/tmp/no-such-eca-sessions-file.edn")]
      (is (nil? (chats/load-chat-id "/some/workspace"))))))

(deftest load-chat-id-found-test
  (testing "returns stored chat-id for workspace"
    (let [f (java.io.File/createTempFile "chats" ".edn")]
      (.deleteOnExit f)
      (spit f (pr-str {"/workspace" "chat-abc"}))
      (with-redefs [chats/chats-path (fn [] (.getAbsolutePath f))]
        (is (= "chat-abc" (chats/load-chat-id "/workspace")))))))

(deftest load-chat-id-missing-workspace-test
  (testing "returns nil when workspace not in file"
    (let [f (java.io.File/createTempFile "chats" ".edn")]
      (.deleteOnExit f)
      (spit f (pr-str {"/other" "chat-xyz"}))
      (with-redefs [chats/chats-path (fn [] (.getAbsolutePath f))]
        (is (nil? (chats/load-chat-id "/workspace")))))))

(deftest load-chat-id-legacy-fallback-test
  (testing "falls back to legacy eca-cli-sessions.edn when new file absent"
    (let [legacy (java.io.File/createTempFile "legacy-sessions" ".edn")]
      (.deleteOnExit legacy)
      (spit legacy (pr-str {"/workspace" "legacy-chat"}))
      (with-redefs [chats/chats-path        (fn [] "/tmp/no-such-eca-chats-file.edn")
                    chats/legacy-chats-path (fn [] (.getAbsolutePath legacy))]
        (is (= "legacy-chat" (chats/load-chat-id "/workspace")))))))

(deftest save-and-load-round-trip-test
  (testing "save then load returns same value"
    (let [f (java.io.File/createTempFile "chats" ".edn")]
      (.deleteOnExit f)
      (with-redefs [chats/chats-path (fn [] (.getAbsolutePath f))]
        (chats/save-chat-id! "/workspace" "chat-round-trip")
        (is (= "chat-round-trip" (chats/load-chat-id "/workspace")))))))

(deftest save-nil-removes-entry-test
  (testing "save nil removes the workspace entry"
    (let [f (java.io.File/createTempFile "chats" ".edn")]
      (.deleteOnExit f)
      (with-redefs [chats/chats-path (fn [] (.getAbsolutePath f))]
        (chats/save-chat-id! "/workspace" "chat-to-remove")
        (chats/save-chat-id! "/workspace" nil)
        (is (nil? (chats/load-chat-id "/workspace")))))))
