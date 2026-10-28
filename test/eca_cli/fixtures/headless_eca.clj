#!/usr/bin/env bb
;; A scripted ECA server for eca-cli's headless tests (headless_test.clj).
;; It records what it receives (its arguments, then every message) as JSON
;; lines in $FAKE_ECA_LOG, and answers prompts by their message:
;;
;;   "hello"        a short answer, with usage
;;   "tool"         a tool call needing approval, then says what became of it
;;   "fail"         a provider error
;;   "/costs"       a server command: only a system note
;;   "need login"   the prompt's answer says a login is needed
;;   "die"          exits mid-turn
;;   "slow"         takes 20s (to try Ctrl+C by hand)
;;   anything else  echoes the message and its contexts
(ns headless-eca
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private lock (Object.))

(defn- record! [m]
  (when-let [f (System/getenv "FAKE_ECA_LOG")]
    (locking lock (spit f (str (json/generate-string m) "\n") :append true))))

(defn- send! [msg]
  (let [body (.getBytes ^String (json/generate-string (assoc msg :jsonrpc "2.0")) "UTF-8")]
    (locking lock
      (.write System/out (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "UTF-8"))
      (.write System/out body)
      (.flush System/out))))

(defn- respond! [id result] (send! {:id id :result result}))
(defn- notify! [method params] (send! {:method method :params params}))

(defn- header-line [^java.io.InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.length sb)) (str sb))
          (= 10 b) (str/trimr (str sb))
          :else    (do (.append sb (char b)) (recur)))))))

(defn- read-message [^java.io.InputStream in]
  (loop [len nil]
    (when-let [line (header-line in)]
      (cond
        (and (str/blank? line) len)
        (let [buf (byte-array len)]
          (loop [off 0]
            (when (< off len)
              (let [n (.read in buf off (- len off))]
                (when (pos? n) (recur (+ off n))))))
          (json/parse-string (String. buf "UTF-8") true))
        (str/blank? line) (recur nil)
        :else (recur (or (some-> (re-find #"(?i)^content-length:\s*(\d+)" line) second parse-long) len))))))

(def ^:private model "fake/model-a")
(def ^:private approvals (atom {}))

(defn- content! [chat-id role content]
  (notify! "chat/contentReceived" {:chatId chat-id :role role :content content}))

(defn- finished! [chat-id & [error]]
  (content! chat-id "system" (cond-> {:type "progress" :state "finished"} error (assoc :error error))))

(defn- answer! [chat-id {:keys [message contexts]}]
  (content! chat-id "user" {:type "text" :text message})
  (content! chat-id "system" {:type "progress" :state "running" :text "Generating"})
  (case message
    "hello"
    (do (content! chat-id "assistant" {:type "text" :text "Hi "})
        (content! chat-id "assistant" {:type "text" :text "there!"})
        (content! chat-id "system" {:type "usage" :sessionTokens 120 :sessionCost "0.01"})
        (finished! chat-id))

    "tool"
    (let [decision (promise)]
      (swap! approvals assoc "t1" decision)
      (content! chat-id "assistant" {:type "text" :text "Let me look."})
      (content! chat-id "assistant" {:type "toolCallRun" :id "t1" :name "shell_command" :server "eca"
                                     :summary "Running rm -rf build" :manualApproval true :arguments {}})
      (let [d (deref decision 5000 :timeout)]
        (content! chat-id "assistant" {:type (if (= :approve d) "toolCalled" "toolCallRejected")
                                       :id "t1" :name "shell_command" :server "eca" :arguments {}})
        (content! chat-id "assistant" {:type "text" :text (str "The command was "
                                                               (case d :approve "approved" :reject "rejected" "left waiting")
                                                               ".")})
        (finished! chat-id)))

    "fail"
    (do (content! chat-id "system" {:type "text" :text "\n\nError: boom"})
        (finished! chat-id {:message "boom" :type "network"}))

    "/costs"
    (do (content! chat-id "system" {:type "text" :text "Total cost: $0.01"})
        (finished! chat-id))

    "die"
    (do (Thread/sleep 100) (System/exit 3))

    "slow"
    (do (content! chat-id "assistant" {:type "text" :text "Working on it..."})
        (Thread/sleep 20000)
        (finished! chat-id))

    (do (content! chat-id "assistant" {:type "text" :text (str "You said: " message
                                                               " | contexts: " (json/generate-string contexts))})
        (finished! chat-id))))

(defn- handle! [{:keys [id method params] :as msg}]
  (record! (select-keys msg [:id :method :params]))
  (case method
    "initialize"  (respond! id {})
    "initialized" (do (notify! "$/progress" {:type "start" :taskId "init" :title "Starting"})
                      (notify! "config/updated" {:chat {:models [model "fake/model-b"] :agents ["code" "plan"]
                                                        :selectModel model :selectAgent "code"}})
                      (notify! "$/progress" {:type "finish" :taskId "init"}))
    "chat/list"   (respond! id {:chats [{:id "inline-1" :title "inline" :status "idle" :messageCount 2
                                         :updatedAt 3000 :kind "inline"}
                                        {:id "latest" :title "Latest work" :status "idle" :messageCount 6
                                         :updatedAt 2000}
                                        {:id "older" :status "idle" :messageCount 1 :updatedAt 1000}]})
    "chat/prompt" (let [chat-id (:chatId params)]
                    (if (= "need login" (:message params))
                      (respond! id {:chatId chat-id :model model :status "login"})
                      (do (respond! id {:chatId chat-id :model model :status "prompting"})
                          (future (answer! chat-id params)))))
    "chat/update" (respond! id {})
    "chat/delete" (respond! id {})
    "chat/toolCallApprove" (some-> (get @approvals (:toolCallId params)) (deliver :approve))
    "chat/toolCallReject"  (some-> (get @approvals (:toolCallId params)) (deliver :reject))
    "shutdown"    (respond! id nil)
    "exit"        (System/exit 0)
    (when id (respond! id {}))))

(defn -main [& args]
  (record! {:args (vec args)})
  (let [in (java.io.BufferedInputStream. System/in)]
    (loop []
      (when-let [msg (read-message in)]
        (try (handle! msg) (catch Exception e (binding [*out* *err*] (println "fake:" (ex-message e)))))
        (recur)))
    (System/exit 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
