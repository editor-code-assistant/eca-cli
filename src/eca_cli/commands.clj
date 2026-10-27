(ns eca-cli.commands
  "Slash command registry, dispatch, and command-picker entry. Each cmd-*
  handler returns [new-state cmd-or-nil] like the rest of the update pipeline."
  (:require [clojure.string :as str]
            [charm.program :as program]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [eca-cli.protocol :as protocol]
            [eca-cli.server :as server]
            [eca-cli.chat :as chat]
            [eca-cli.chats :as chats]
            [eca-cli.history :as history]
            [eca-cli.providers :as providers]
            [eca-cli.picker :as picker]
            [eca-cli.login :as login]
            [eca-cli.jobs :as jobs]
            [eca-cli.mcp :as mcp]
            [eca-cli.theme :as theme]
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

(defn cmd-open-variant-picker [state]
  (if (seq (:available-variants state))
    [(picker/open-picker state :variant) nil]
    [(-> state
         (update :items conj {:type :system :level :info :text "This model has no variants."})
         view/rebuild-lines)
     nil]))

(defn cmd-rename
  "/rename <title>: the chat's new title (chat/update); the server confirms
  with a `metadata` content, which updates it on screen."
  [state args]
  (let [title (str/trim (str args))
        note  #(-> state (update :items conj {:type :system :level :info :text %}) view/rebuild-lines)]
    (cond
      (str/blank? title)      [(note "Usage: /rename <title>") nil]
      (nil? (:chat-id state)) [(note "Nothing to rename yet: send a message first.") nil]
      :else (do (protocol/update-chat! (:server state) (:chat-id state) {:title title})
                [state nil]))))

(defn cmd-new-chat [state]
  (if-let [old-chat-id (:chat-id state)]
    (do
      (chats/save-chat-id! (get-in state [:opts :workspace]) nil)
      [(-> state
           (assoc :items [] :chat-lines [] :chat-id nil :chat-title nil :scroll-offset 0)
           (update :input #(-> % ti/reset ti/focus)))
       (chats/delete-chat-cmd (:server state) old-chat-id)])
    [(update state :input #(-> % ti/reset ti/focus)) nil]))

(defn cmd-list-chats [state]
  [(update state :input ti/reset) (chats/list-chats-cmd (:server state))])

(defn cmd-clear-chat [state]
  [(assoc state :items [] :chat-lines [] :scroll-offset 0) nil])

(defn cmd-show-help [state]
  (let [cmds  (sort-by key command-registry)
        w     (reduce max 0 (map (comp count key) cmds))
        lines (map (fn [[name {:keys [doc]}]]
                     (str (theme/paint :accent (theme/pad-right name w)) "  " doc))
                   cmds)
        text  (str/join "\n" (concat ["Available commands:"] lines
                                     ["" "The server's commands, skills and prompts too: type / to browse them all."]))]
    [(update state :items conj {:type :system :level :info :text text}) nil]))

(defn cmd-quit
  "Quit: graceful protocol `shutdown`/`exit`, then kill the server process.
  The protocol step is skipped when the server never finished initializing
  (:connecting) or is already gone (:disconnected), so quitting is instant
  there. Also the Ctrl+C path (state.clj)."
  [state]
  (let [srv       (:server state)
        graceful? (and srv (not (#{:connecting :disconnected} (:mode state))))]
    [state (program/sequence-cmds
             (when graceful?
               (program/cmd (fn [] (try (protocol/shutdown! srv) (catch Exception _)) nil)))
             (program/cmd (fn [] (server/shutdown! srv) nil))
             program/quit-cmd)]))

(defn cmd-login [state]
  [state (login/start-login-cmd (:server state) nil)])

(defn cmd-open-jobs-panel [state]
  (jobs/cmd-open-jobs-panel state))

(defn cmd-toggle-trust
  "Trust mode: the server runs tool calls that would ask without asking
  (deny rules still apply). Sent with each prompt while on, and to the
  current chat right away so it covers the running turn too."
  [state]
  (let [on? (not (:trust state))]
    (when-let [chat-id (:chat-id state)]
      (protocol/update-chat! (:server state) chat-id {:trust on?}))
    [(-> state
         (assoc :trust on?)
         (assoc-in [:opts :trust] on?)
         (update :items conj {:type :system :level :info
                              :text (if on?
                                      "Trust mode on: tool calls run without asking (deny rules still apply)."
                                      "Trust mode off: tool calls ask for approval again.")}))
     nil]))

(def command-registry
  {"/model"    {:doc "Open model picker"                  :handler cmd-open-model-picker}
   "/agent"    {:doc "Open agent picker"                  :handler cmd-open-agent-picker}
   "/variant"  {:doc "Pick the model's variant (e.g. reasoning effort)" :handler cmd-open-variant-picker}
   "/rename"   {:doc "Rename the chat: /rename <title>"   :handler cmd-rename :args? true}
   "/mcp"      {:doc "MCP servers: status; start, stop, enable, disable, log out" :handler mcp/cmd-open-mcp-panel}
   "/providers" {:doc "Providers: login state, models, quota; log in or out" :handler providers/cmd-open-providers}
   "/new"      {:doc "Start a fresh chat"                 :handler cmd-new-chat}
   "/chats"    {:doc "Browse and resume previous chats"   :handler cmd-list-chats}
   "/clear"    {:doc "Clear chat display (local only)"    :handler cmd-clear-chat}
   "/jobs"     {:doc "Show background shell jobs"         :handler cmd-open-jobs-panel}
   "/trust"    {:doc "Toggle trust mode (run tool calls without asking)" :handler cmd-toggle-trust}
   "/rollback" {:doc "Roll the chat back to before one of your messages" :handler history/cmd-rollback}
   "/flag"     {:doc "Flag your latest message or tool call: /flag <text>" :handler history/cmd-flag :args? true}
   "/flags"    {:doc "Jump to, fork from or remove a flag"            :handler history/cmd-flags}
   "/timeline" {:doc "Jump to one of your messages or a flag"         :handler history/cmd-timeline}
   "/help"     {:doc "Show available commands"            :handler cmd-show-help}
   "/quit"     {:doc "Exit eca-cli"                        :handler cmd-quit}
   "/login"    {:doc "Manually trigger provider login"    :handler cmd-login}})

;; --- Server commands ---
;;
;; The server has commands of its own (`/compact`, `/init`, `/costs`, `/fork`,
;; …), plus MCP prompts, custom prompts and skills. They run by sending their
;; text as a prompt. `chat/queryCommands` lists them for the `/` picker; the
;; list is cached in :server-commands (names without the `/`).

(defn query-commands-cmd
  "Fetch the server's commands, delivered as :commands-loaded (`nil`
  :commands when the server didn't answer: what's known is kept)."
  [srv chat-id]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/chat-query-commands! srv chat-id "" (fn [r] (deliver p (get-in r [:result :commands]))))
        {:type :commands-loaded :commands (deref p 5000 nil)}))))

(def ^:private type-badge
  {"skill" "skill" "mcpPrompt" "mcp" "customPrompt" "prompt"})

(defn- server-command-item
  "Picker item for a server command: [\"/name\" description command]."
  [{:keys [name description type] :as cmd}]
  [(str "/" name)
   (str (some-> (type-badge type) (str " · ")) (or description ""))
   cmd])

(defn command-items
  "Picker items: eca-cli's commands and the server's, by name. Where both
  have one (`/model`), eca-cli's wins."
  [state]
  (let [local  (map (fn [[name {:keys [doc]}]] [name doc]) command-registry)
        names  (set (map first local))
        remote (->> (:server-commands state)
                    (map server-command-item)
                    (remove #(names (first %))))]
    (vec (sort-by first (concat local remote)))))

(defn- set-command-items
  "Replace the command picker's items, keeping what the user typed."
  [state items]
  (let [filtered (picker/filter-items :command items (get-in state [:picker :query]))]
    (-> state
        (assoc-in [:picker :all] items)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items (mapv #(picker/item-display :command %) filtered)))))

(defn apply-server-commands
  "The server's commands arrived: keep them, and show them if the command
  picker is open."
  [state commands]
  (if (nil? commands)
    state
    (let [s (assoc state :server-commands (vec commands))]
      (if (and (= :picking (:mode s)) (= :command (get-in s [:picker :kind])))
        (set-command-items s (command-items s))
        s))))

(defn open-command-picker [state]
  (let [all (command-items state)]
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
     (#{:ready :chatting} (:mode new-state)) (update :input #(-> % ti/reset ti/focus)))
   cmd])

(def ^:private during-turn
  "eca-cli commands that make sense while the agent works; any other text
  typed then steers the running prompt (the server has its own rules for its
  commands)."
  #{"/trust" "/help" "/clear" "/quit"})

(defn runs-during-turn? [text]
  (contains? during-turn (str/trim text)))

(defn- parse-command
  "`/name args…` → [\"/name\" args-or-nil]. Nil when the text only starts
  with a slash, like a path (`/home/me/notes.md is empty`)."
  [text]
  (when-let [[_ name args] (re-matches #"(?s)(/[\w:.-]+)(?:\s+(.*))?" (str/trim text))]
    [name (not-empty (some-> args str/trim))]))

(defn- server-command [state name]
  (let [n (subs name 1)]
    (some #(when (= n (:name %)) %) (:server-commands state))))

(defn dispatch-command
  "Enter on input starting with `/`. eca-cli's own commands run here (given
  arguments, a name the server also has goes to the server: `/model x/y`);
  the server's are sent as a prompt; text that isn't a command at all is an
  ordinary message."
  [state text]
  (let [[name args] (parse-command text)
        local       (get command-registry name)
        remote      (when name (server-command state name))]
    (cond
      (nil? name)
      (chat/submit-prompt state (str/trim text))

      (and local (or (nil? args) (nil? remote) (:args? local)))
      (let [[new-state cmd] (if (:args? local)
                              ((:handler local) state args)
                              ((:handler local) state))]
        (finalize-handler-result new-state cmd))

      ;; Not listed yet (still loading): let the server decide.
      (or remote (empty? (:server-commands state)))
      (chat/submit-prompt state (str/trim text))

      :else
      [(-> state
           (update :items conj {:type :system
                                :text (str "⚠ Unknown command: " name
                                           "  (type / to browse commands)")})
           (update :input #(-> % ti/reset ti/focus))
           view/rebuild-lines)
       ;; It may have just appeared (an MCP server finished starting).
       (query-commands-cmd (:server state) (:chat-id state))])))

(defn run-picker-selection
  "Enter on a command-picker item, the picker already closed. A server
  command taking arguments goes to the input for them, the others run."
  [state [name _doc cmd]]
  (cond
    ;; It needs text after it (/flag <text>): to the input.
    (:args? (get command-registry name))
    [(update state :input #(-> % (ti/set-value (str name " ")) ti/focus)) nil]

    (get command-registry name)
    (let [[new-state c] ((:handler (get command-registry name)) state)]
      (finalize-handler-result new-state c))

    (seq (:arguments cmd))
    [(update state :input #(-> % (ti/set-value (str name " ")) ti/focus)) nil]

    :else
    (chat/submit-prompt state name)))

