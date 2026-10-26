(ns eca-cli.server-test
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.server :as server])
  (:import [java.io BufferedInputStream BufferedWriter OutputStreamWriter ByteArrayOutputStream]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; --- format-exit-report ---

(deftest format-exit-report-test
  (testing "exit code + log tail (e.g. a glibc binary on NixOS)"
    (is (= (str "ECA server exited unexpectedly (exit code 127).\n"
                "Last lines of /logs/eca-cli.log:\n"
                "│ Could not start dynamically linked executable: /x/eca\n"
                "│ NixOS cannot run dynamically linked executables")
           (server/format-exit-report
             {:exit-code 127
              :log-file  "/logs/eca-cli.log"
              :log-lines ["Could not start dynamically linked executable: /x/eca"
                          "NixOS cannot run dynamically linked executables"]}))))

  (testing "still running (stream error), nothing logged"
    (is (= (str "Lost connection to the ECA server: java.io.IOException: boom\n"
                "It wrote nothing to /logs/eca-cli.log.")
           (server/format-exit-report {:error     "java.io.IOException: boom"
                                       :log-file  "/logs/eca-cli.log"
                                       :log-lines []}))))

  (testing "stderr not captured in a file"
    (is (= "Lost connection to the ECA server."
           (server/format-exit-report {})))))

(deftest log-tail-test
  (let [f (fs/create-temp-file {:prefix "eca-cli-log-"})]
    (try
      (spit (fs/file f) "one\n\ntwo\nthree\n  \nfour\n")
      (is (= ["three" "four"] (server/log-tail (fs/file f) 2)) "blank lines skipped")
      (is (= ["one" "two" "three" "four"] (server/log-tail (fs/file f) 10)))
      (finally (fs/delete f))))
  (is (= [] (server/log-tail "/definitely/not/here.log" 5))))

;; --- exit report from a real process ---

(defn- fake-server
  "A server map around `sh -c script`, stderr to a temp log — like spawn!."
  [script]
  (let [log (fs/file (fs/create-temp-file {:prefix "eca-cli-srv-" :suffix ".log"}))
        p   (proc/process ["sh" "-c" script] {:err log})]
    {:process          p
     :reader           (BufferedInputStream. (:out p))
     :writer           (BufferedWriter. (OutputStreamWriter. (:in p) "UTF-8"))
     :queue            (LinkedBlockingQueue.)
     :alive?           (atom true)
     :pending-requests (atom {})
     :log-file         log}))

(deftest exit-report-test
  (let [srv (fake-server "echo 'Could not start dynamically linked executable' >&2; exit 127")]
    (try
      (let [report (server/exit-report srv nil)]
        (is (str/includes? report "exit code 127"))
        (is (str/includes? report "Could not start dynamically linked executable"))
        (is (str/includes? report (str (:log-file srv)))))
      (finally (fs/delete (:log-file srv))))))

(deftest reader-reports-server-death-test
  (testing "server dies before answering: the reader queues a :reader-error carrying the report"
    (let [srv (fake-server "echo 'fatal: bad config' >&2; exit 3")]
      (try
        (server/start-reader! srv)
        (let [m (.poll ^LinkedBlockingQueue (:queue srv) 10 TimeUnit/SECONDS)]
          (is (= :reader-error (:type m)))
          (is (str/includes? (:error m) "exit code 3"))
          (is (str/includes? (:error m) "fatal: bad config")))
        (finally (fs/delete (:log-file srv)))))))

(deftest reader-survives-failing-callback-test
  (testing "an exception in a response callback doesn't take the connection down"
    (let [body (fn [m] (let [s (json/generate-string m)]
                         (str "Content-Length: " (count (.getBytes s "UTF-8")) "\r\n\r\n" s)))
          srv  (fake-server (str "printf '%s' '" (body {:jsonrpc "2.0" :id 1 :result {}}) "';"
                                 "printf '%s' '" (body {:jsonrpc "2.0" :method "note" :params {}}) "';"
                                 "sleep 5"))]
      (try
        (swap! (:pending-requests srv) assoc 1 (fn [_] (throw (ex-info "callback bug" {}))))
        (server/start-reader! srv)
        (let [m (.poll ^LinkedBlockingQueue (:queue srv) 10 TimeUnit/SECONDS)]
          (is (= "note" (:method m)) "the next message still arrives"))
        (finally
          (server/shutdown! srv)
          (fs/delete (:log-file srv)))))))

;; --- write-message! ---

(deftest write-message-test
  (testing "frames the message"
    (let [out (ByteArrayOutputStream.)
          w   (BufferedWriter. (OutputStreamWriter. out "UTF-8"))]
      (is (true? (server/write-message! w {:jsonrpc "2.0" :method "ping"})))
      (is (= "Content-Length: 33\r\n\r\n{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"
             (.toString out "UTF-8")))))

  (testing "no server (failed to spawn): false, no exception"
    (is (false? (server/write-message! nil {:method "x"}))))

  (testing "server's stdin closed (it died): false, no exception"
    (let [w (BufferedWriter. (OutputStreamWriter. (ByteArrayOutputStream.) "UTF-8"))]
      (.close w)
      (is (false? (server/write-message! w {:method "x"}))))))

;; --- shutdown! ---

(deftest shutdown-nil-safe-test
  (is (nil? (server/shutdown! nil)) "no server to stop when spawning failed"))
