(ns eca-cli.chats-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.paths :as paths]
            [eca-cli.chats :as chats]))

(defn- tmp-file []
  (let [f (java.io.File/createTempFile "chats" ".edn")]
    (.deleteOnExit f)
    f))

(defn- absent-file []
  (let [f (java.io.File/createTempFile "absent" ".edn")]
    (.deleteOnExit f)
    (.delete f)
    f))

(deftest load-chat-id-missing-file-test
  (testing "returns nil when neither current nor legacy files exist"
    (with-redefs [paths/chats-file           (constantly (absent-file))
                  paths/legacy-chats-file    (constantly (absent-file))
                  paths/legacy-sessions-file (constantly (absent-file))]
      (is (nil? (chats/load-chat-id "/some/workspace"))))))

(deftest load-chat-id-found-test
  (testing "returns stored chat-id for workspace"
    (let [f (tmp-file)]
      (spit f (pr-str {"/workspace" "chat-abc"}))
      (with-redefs [paths/chats-file           (constantly f)
                    paths/legacy-chats-file    (constantly (absent-file))
                    paths/legacy-sessions-file (constantly (absent-file))]
        (is (= "chat-abc" (chats/load-chat-id "/workspace")))))))

(deftest load-chat-id-missing-workspace-test
  (testing "returns nil when workspace not in file"
    (let [f (tmp-file)]
      (spit f (pr-str {"/other" "chat-xyz"}))
      (with-redefs [paths/chats-file           (constantly f)
                    paths/legacy-chats-file    (constantly (absent-file))
                    paths/legacy-sessions-file (constantly (absent-file))]
        (is (nil? (chats/load-chat-id "/workspace")))))))

(deftest load-chat-id-legacy-chats-fallback-test
  (testing "falls back to the legacy cache chats file when state file absent"
    (let [legacy (tmp-file)]
      (spit legacy (pr-str {"/workspace" "legacy-chat"}))
      (with-redefs [paths/chats-file           (constantly (absent-file))
                    paths/legacy-chats-file    (constantly legacy)
                    paths/legacy-sessions-file (constantly (absent-file))]
        (is (= "legacy-chat" (chats/load-chat-id "/workspace")))))))

(deftest load-chat-id-legacy-sessions-fallback-test
  (testing "falls back to the original sessions file when both chats files absent"
    (let [legacy (tmp-file)]
      (spit legacy (pr-str {"/workspace" "legacy-session-chat"}))
      (with-redefs [paths/chats-file           (constantly (absent-file))
                    paths/legacy-chats-file    (constantly (absent-file))
                    paths/legacy-sessions-file (constantly legacy)]
        (is (= "legacy-session-chat" (chats/load-chat-id "/workspace")))))))

(deftest save-and-load-round-trip-test
  (testing "save then load returns same value"
    (let [f (tmp-file)]
      (with-redefs [paths/chats-file           (constantly f)
                    paths/legacy-chats-file    (constantly (absent-file))
                    paths/legacy-sessions-file (constantly (absent-file))]
        (chats/save-chat-id! "/workspace" "chat-round-trip")
        (is (= "chat-round-trip" (chats/load-chat-id "/workspace")))))))

(deftest save-nil-removes-entry-test
  (testing "save nil removes the workspace entry"
    (let [f (tmp-file)]
      (with-redefs [paths/chats-file           (constantly f)
                    paths/legacy-chats-file    (constantly (absent-file))
                    paths/legacy-sessions-file (constantly (absent-file))]
        (chats/save-chat-id! "/workspace" "chat-to-remove")
        (chats/save-chat-id! "/workspace" nil)
        (is (nil? (chats/load-chat-id "/workspace")))))))
