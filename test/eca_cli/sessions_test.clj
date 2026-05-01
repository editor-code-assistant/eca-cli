(ns eca-cli.sessions-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.sessions :as sessions]))

(deftest load-chat-id-missing-file-test
  (testing "returns nil when file does not exist"
    (with-redefs [sessions/sessions-path (fn [] "/tmp/no-such-eca-sessions-file.edn")]
      (is (nil? (sessions/load-chat-id "/some/workspace"))))))

(deftest load-chat-id-found-test
  (testing "returns stored chat-id for workspace"
    (let [f (java.io.File/createTempFile "sessions" ".edn")]
      (.deleteOnExit f)
      (spit f (pr-str {"/workspace" "chat-abc"}))
      (with-redefs [sessions/sessions-path (fn [] (.getAbsolutePath f))]
        (is (= "chat-abc" (sessions/load-chat-id "/workspace")))))))

(deftest load-chat-id-missing-workspace-test
  (testing "returns nil when workspace not in file"
    (let [f (java.io.File/createTempFile "sessions" ".edn")]
      (.deleteOnExit f)
      (spit f (pr-str {"/other" "chat-xyz"}))
      (with-redefs [sessions/sessions-path (fn [] (.getAbsolutePath f))]
        (is (nil? (sessions/load-chat-id "/workspace")))))))

(deftest save-and-load-round-trip-test
  (testing "save then load returns same value"
    (let [f (java.io.File/createTempFile "sessions" ".edn")]
      (.deleteOnExit f)
      (with-redefs [sessions/sessions-path (fn [] (.getAbsolutePath f))]
        (sessions/save-chat-id! "/workspace" "chat-round-trip")
        (is (= "chat-round-trip" (sessions/load-chat-id "/workspace")))))))

(deftest save-nil-removes-entry-test
  (testing "save nil removes the workspace entry"
    (let [f (java.io.File/createTempFile "sessions" ".edn")]
      (.deleteOnExit f)
      (with-redefs [sessions/sessions-path (fn [] (.getAbsolutePath f))]
        (sessions/save-chat-id! "/workspace" "chat-to-remove")
        (sessions/save-chat-id! "/workspace" nil)
        (is (nil? (sessions/load-chat-id "/workspace")))))))
