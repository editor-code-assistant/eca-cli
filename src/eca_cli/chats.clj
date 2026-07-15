(ns eca-cli.chats
  "Chat-id persistence (workspace → chatId map) + charm/program cmd builders
  for the chat list/open/delete protocol calls."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [charm.program :as program]
            [eca-cli.protocol :as protocol]))

(defn chats-path []
  (str (System/getProperty "user.home") "/.cache/eca/eca-cli-chats.edn"))

(defn legacy-chats-path []
  (str (System/getProperty "user.home") "/.cache/eca/eca-cli-sessions.edn"))

(defn- read-map [path]
  (try
    (let [f (java.io.File. path)]
      (when (.exists f) (edn/read-string (slurp f))))
    (catch Exception _ nil)))

(defn load-chat-id
  "Returns persisted chat-id for workspace, or nil. Reads the current chats
  file; falls back to the legacy eca-cli-sessions.edn when the new file is
  absent (transitional — removable once old files are gone)."
  [workspace]
  (let [m (or (read-map (chats-path)) (read-map (legacy-chats-path)))]
    (get m workspace)))

(defn save-chat-id!
  "Saves chat-id for workspace. Passing nil removes the entry. Always writes
  the current chats file."
  [workspace chat-id]
  (let [path     (chats-path)
        existing (or (read-map path) {})
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
