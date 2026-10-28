(ns eca-cli.startup
  "What the command line asks of the TUI:

  - a prompt (`eca-cli \"…\"`), sent once the server is up (its models
    synced and its startup tasks done: a prompt sent earlier fails), with
    -f's files as `@path`s; -f alone puts them in the input;
  - `-c`: first open the workspace's latest chat (any client's);
  - --title: names the chat once its first turn is over (the server's own
    title for a new chat would replace an earlier one);
  - --model, --agent, --variant: shown from the start and kept (`:pins`)
    over the server's defaults (`config/updated`) until the user picks
    others; the server hears of the model and agent, so it offers the
    right variants."
  (:require [charm.components.text-input :as ti]
            [charm.program :as program]
            [clojure.string :as str]
            [eca-cli.chat :as chat]
            [eca-cli.chats :as chats]
            [eca-cli.picker :as picker]
            [eca-cli.protocol :as protocol]
            [eca-cli.transcript :as transcript]
            [eca-cli.view :as view]))

(defn init
  "The initial state's part from `opts` (the CLI's)."
  [{:keys [prompt files continue model agent variant]}]
  (cond-> {:pins (cond-> #{} model (conj :model) agent (conj :agent) variant (conj :variant))}
    model                                (assoc :selected-model model)
    agent                                (assoc :selected-agent agent)
    variant                              (assoc :selected-variant variant)
    (or prompt (seq files) continue)     (assoc :startup (cond-> {:prompt prompt :files (vec files)}
                                                           continue (assoc :continue? true)))))

(defn- note [state text]
  (-> state
      (update :items conj {:type :system :level :info :text text})
      view/rebuild-lines))

;; --- -c: the latest chat ---

(defn continue-cmd
  "The workspace's latest chat (not an inline one), as :startup-continue."
  [srv]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/list-chats! srv 20 #(deliver p (get-in % [:result :chats])))
        {:type    :startup-continue
         :chat-id (->> (deref p 10000 nil) (remove :kind) first :id)}))))

(defn- open-chat-cmd
  "Open `chat-id`; its replay comes through the server queue, then
  :startup-continued (on the queue too, so after the replay)."
  [srv chat-id]
  (program/cmd
    (fn []
      (protocol/open-chat! srv chat-id
                           (fn [_] (.put ^java.util.concurrent.BlockingQueue (:queue srv)
                                         {:type :startup-continued})))
      nil)))

(defn on-ready
  "The server is initialized: tell it the model and agent asked for, and
  look up the chat to continue. [state cmd]"
  [state]
  (let [srv (:server state)
        {:keys [model agent]} (:opts state)]
    ;; The agent first: changing it resets the session's model server-side.
    (when agent (protocol/selected-agent-changed! srv agent))
    (when model (protocol/selected-model-changed! srv model))
    [(assoc state :ready-at (System/currentTimeMillis))
     (when (get-in state [:startup :continue?])
       (continue-cmd srv))]))

(defn- apply-title
  "--title, now that the chat exists and the server won't title it itself."
  [state]
  (if-let [title (not-empty (get-in state [:opts :title]))]
    (do (when (:chat-id state)
          (protocol/update-chat! (:server state) (:chat-id state) {:title title}))
        (update state :opts dissoc :title))
    state))

(defn handle-continue
  "The latest chat's id arrived (nil: none). [state cmd]"
  [state chat-id]
  (if chat-id
    (do (chats/save-chat-id! (get-in state [:opts :workspace]) chat-id)
        [(-> state
             (transcript/reset-items [])
             (assoc :chat-id chat-id :chat-lines [] :scroll-offset 0))
         (open-chat-cmd (:server state) chat-id)])
    [(-> state
         (note "No chat to continue in this workspace: starting a new one.")
         (update :startup dissoc :continue?))
     nil]))

(defn handle-continued
  "The chat to continue is open (replayed)."
  [state]
  (cond-> (update state :startup dissoc :continue?)
    ;; No prompt to send: it can be named right away.
    (nil? (get-in state [:startup :prompt])) apply-title))

;; --- The prompt ---

(def quiet-ms
  "How long without a new startup task the server must stay before the
  prompt goes (a task can start right after another ends)."
  500)

(defn server-ready?
  "Models synced, no startup task running (`$/progress`), none started for
  a moment."
  [state now]
  (let [tasks (vals (:init-tasks state))]
    (and (seq (:available-models state))
         (every? :done? tasks)
         (:ready-at state)
         (> (- now (max (:ready-at state) (:init-tasks-at state 0))) quiet-ms))))

(defn- refs
  "`@label`s for -f's files, as the `@` picker writes them."
  [state files]
  (str/join " " (map #(str "@" (picker/at-label % (get-in state [:opts :workspace]))) files)))

(defn tick
  "Send the startup prompt (or fill the input with -f's files) once the
  server is ready and the chat to continue open. [state cmd]"
  [state now]
  (let [{:keys [prompt files continue?] :as startup} (:startup state)]
    (if (and startup (not continue?) (= :ready (:mode state)) (server-ready? state now))
      (let [state (-> state (dissoc :startup) (update :pending-contexts (fnil into []) files))]
        (cond
          (seq prompt)
          (chat/submit-prompt state (str/trim (str (when (seq files) (str (refs state files) " ")) prompt)))

          (seq files)
          [(update state :input
                   (fn [input]
                     (let [text (str (refs state files) " " (ti/value input))]
                       (-> input (ti/set-value text) (assoc :pos (count text)) ti/focus))))
           nil]

          :else [state nil]))
      [state nil])))

(defn after-turn
  "--title for a new chat, once its first turn is over."
  [state]
  (if (and (get-in state [:opts :title]) (:chat-id state) (:last-turn-ms state)
           (nil? (:turn-started-at state)))
    (apply-title state)
    state))
