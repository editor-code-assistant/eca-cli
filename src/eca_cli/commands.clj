(ns eca-cli.commands
  "Slash command registry, dispatch, and command-picker entry. Each cmd-*
  handler returns [new-state cmd-or-nil] like the rest of the update pipeline."
  (:require [clojure.string :as str]
            [charm.program :as program]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [eca-cli.protocol :as protocol]
            [eca-cli.server :as server]
            [eca-cli.sessions :as sessions]
            [eca-cli.picker :as picker]
            [eca-cli.login :as login]
            [eca-cli.view :as view]))

(declare command-registry)

;; --- Command handlers ---

(defn cmd-open-model-picker [state]
  (if (seq (:available-models state))
    [(picker/open-picker state :model) nil]
    [(-> state
         (update :items conj {:type :system :text "⚠ No models available"})
         view/rebuild-lines)
     nil]))

(defn cmd-open-agent-picker [state]
  (if (seq (:available-agents state))
    [(picker/open-picker state :agent) nil]
    [(-> state
         (update :items conj {:type :system :text "⚠ No agents available"})
         view/rebuild-lines)
     nil]))

(defn cmd-new-chat [state]
  (if-let [old-chat-id (:chat-id state)]
    (do
      (sessions/save-chat-id! (get-in state [:opts :workspace]) nil)
      [(-> state
           (assoc :items [] :chat-lines [] :chat-id nil :chat-title nil :scroll-offset 0)
           (update :input #(-> % ti/reset ti/focus)))
       (sessions/delete-chat-cmd (:server state) old-chat-id)])
    [(update state :input #(-> % ti/reset ti/focus)) nil]))

(defn cmd-list-sessions [state]
  [(update state :input ti/reset) (sessions/list-chats-cmd (:server state))])

(defn cmd-clear-chat [state]
  [(assoc state :items [] :chat-lines [] :scroll-offset 0) nil])

(defn cmd-show-help [state]
  (let [lines (map (fn [[name {:keys [doc]}]] (str name "  —  " doc))
                   (sort-by key command-registry))
        text  (str/join "\n" (into ["Available commands:"] lines))]
    [(update state :items conj {:type :system :text text}) nil]))

(defn cmd-quit [state]
  ;; Inline of the shutdown sequence. state.clj keeps an identical Ctrl+C path.
  [state (program/sequence-cmds
           (program/cmd (fn [] (try (protocol/shutdown! (:server state)) (catch Exception _)) nil))
           (program/cmd (fn [] (server/shutdown! (:server state)) nil))
           program/quit-cmd)])

(defn cmd-login [state]
  [state (login/start-login-cmd (:server state) nil)])

(def command-registry
  {"/model"    {:doc "Open model picker"                  :handler cmd-open-model-picker}
   "/agent"    {:doc "Open agent picker"                  :handler cmd-open-agent-picker}
   "/new"      {:doc "Start a fresh chat"                 :handler cmd-new-chat}
   "/sessions" {:doc "Browse and resume previous chats"   :handler cmd-list-sessions}
   "/clear"    {:doc "Clear chat display (local only)"    :handler cmd-clear-chat}
   "/help"     {:doc "Show available commands"            :handler cmd-show-help}
   "/quit"     {:doc "Exit eca-cli"                        :handler cmd-quit}
   "/login"    {:doc "Manually trigger provider login"    :handler cmd-login}})

(defn open-command-picker [state]
  (let [all (mapv (fn [[name {:keys [doc]}]] [name doc])
                  (sort-by key command-registry))]
    (-> state
        (assoc :mode :picking
               :picker {:kind     :command
                        :query    ""
                        :list     (cl/item-list (mapv #(picker/item-display :command %) all) :height 8)
                        :all      all
                        :filtered all})
        (update :input ti/reset))))

(defn- finalize-handler-result [new-state cmd]
  [(cond-> (view/rebuild-lines new-state)
     (= :ready (:mode new-state)) (update :input #(-> % ti/reset ti/focus)))
   cmd])

(defn dispatch-command [state text]
  (if-let [{:keys [handler]} (get command-registry text)]
    (let [[new-state cmd] (handler state)]
      (finalize-handler-result new-state cmd))
    [(-> state
         (update :items conj {:type :system
                              :text (str "⚠ Unknown command: " text
                                         "  (type /help to see available commands)")})
         (update :input #(-> % ti/reset ti/focus))
         view/rebuild-lines)
     nil]))

(defn run-handler-from-picker
  "Run a registry handler from the command-picker selection arm.
  Equivalent to dispatch-command but takes a base state already cleared of the picker."
  [state cmd-name]
  (if-let [{:keys [handler]} (get command-registry cmd-name)]
    (let [[new-state cmd] (handler state)]
      (finalize-handler-result new-state cmd))
    [state nil]))

