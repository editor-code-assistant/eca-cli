(ns eca-cli.chats
  "Chat-id persistence (workspace → chatId map) + charm/program cmd builders
  for the chat list/open/delete protocol calls."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [charm.program :as program]
            [eca-cli.paths :as paths]
            [eca-cli.protocol :as protocol]))

(defn- read-edn-file [^java.io.File f]
  (try
    (when (.exists f) (edn/read-string (slurp f)))
    (catch Exception _ nil)))

(defn- load-chats
  "Reads the current chats map from the XDG state file; falls back to the
  legacy cache locations (renamed chats file, then original sessions file)
  for transparent migration. Returns {} when nothing is found."
  []
  (or (read-edn-file (paths/chats-file))
      (read-edn-file (paths/legacy-chats-file))
      (read-edn-file (paths/legacy-sessions-file))
      {}))

(defn load-chat-id
  "Returns persisted chat-id for workspace, or nil."
  [workspace]
  (get (load-chats) workspace))

(defn save-chat-id!
  "Saves chat-id for workspace. Passing nil removes the entry. Always writes
  the current XDG state chats file."
  [workspace chat-id]
  (let [path     (paths/chats-file)
        existing (load-chats)
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
