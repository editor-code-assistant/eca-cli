(ns eca-cli.sessions
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [charm.program :as program]
            [eca-cli.protocol :as protocol]))

(defn sessions-path []
  (str (System/getProperty "user.home") "/.cache/eca/eca-cli-sessions.edn"))

(defn load-chat-id
  "Returns persisted chat-id for workspace, or nil."
  [workspace]
  (try
    (let [f (java.io.File. (sessions-path))]
      (when (.exists f)
        (get (edn/read-string (slurp f)) workspace)))
    (catch Exception _ nil)))

(defn save-chat-id!
  "Saves chat-id for workspace. Passing nil removes the entry."
  [workspace chat-id]
  (let [path     (sessions-path)
        existing (try
                   (let [f (java.io.File. path)]
                     (if (.exists f) (edn/read-string (slurp f)) {}))
                   (catch Exception _ {}))
        updated  (if chat-id
                   (assoc existing workspace chat-id)
                   (dissoc existing workspace))]
    (io/make-parents path)
    (spit path (pr-str updated))))

;; --- charm/program cmd builders for chat-list/open/delete ---

(defn delete-chat-cmd [srv chat-id]
  (program/cmd
    (fn []
      (protocol/delete-chat! srv chat-id (fn [_] nil))
      nil)))

(defn open-chat-cmd [srv chat-id]
  (program/cmd
    (fn []
      (protocol/open-chat! srv chat-id (fn [_] nil))
      nil)))

(defn list-chats-cmd [srv]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/list-chats! srv
          (fn [r]
            (deliver p {:chats  (or (get-in r [:result :chats]) [])
                        :error? (boolean (:error r))})))
        (let [{:keys [chats error?]} (deref p 10000 {:chats [] :error? true})]
          {:type :chat-list-loaded :chats chats :error? error?})))))
