(ns eca-bb.state
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [charm.program :as program]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-bb.server :as server]
            [eca-bb.protocol :as protocol]
            [eca-bb.sessions :as sessions]
            [eca-bb.upgrade :as upgrade]
            [eca-bb.view :as view]
            [eca-bb.chat :as chat]
            [eca-bb.picker :as picker]
            [eca-bb.login :as login]
            [eca-bb.commands :as commands]))

;; Expose last-known state for nREPL inspection
(def debug-state (atom nil))

;; --- Commands ---

(defn- drain-queue-cmd [queue]
  (program/cmd
    (fn []
      {:type :eca-tick
       :msgs (server/read-batch! queue 50)})))

(defn- init-cmd [srv workspace]
  (program/cmd
    (fn []
      (try
        {:type :eca-initialized :result (protocol/initialize! srv workspace)}
        (catch Exception e
          {:type :eca-error :error (ex-message e)})))))

(defn- shutdown-cmd [srv]
  (program/sequence-cmds
    (program/cmd (fn [] (try (protocol/shutdown! srv) (catch Exception _)) nil))
    (program/cmd (fn [] (server/shutdown! srv) nil))
    program/quit-cmd))

(defn- handle-eca-notification [state notification]
  (case (:method notification)
    "chat/contentReceived"
    (chat/handle-content-received state notification)

    "providers/updated"
    (login/handle-providers-updated state (:params notification))

    "$/progress"
    (let [{:keys [type taskId title]} (:params notification)]
      [(case type
         "start"  (assoc-in state [:init-tasks taskId] {:title title :done? false})
         "finish" (if (contains? (:init-tasks state) taskId)
                    (assoc-in state [:init-tasks taskId :done?] true)
                    state)
         state)
       nil])

    "$/showMessage"
    (let [text (or (get-in notification [:params :message]) "Server message")]
      [(-> state
           (update :items conj {:type :system :text text})
           view/rebuild-lines)
       nil])

    "config/updated"
    (chat/handle-config-updated state notification)

    "chat/opened"
    (chat/handle-chat-opened state notification)

    "chat/cleared"
    (chat/handle-chat-cleared state notification)

    [state nil]))

(defn- handle-eca-tick [state msgs]
  (reduce
    (fn [[s cmd] m]
      (cond
        (= :reader-error (:type m))
        [(-> s
             (assoc :mode :ready)
             (update :items conj {:type :system
                                   :text (str "ECA disconnected: " (:error m))})
             (update :input ti/focus)
             view/rebuild-lines)
         nil]

        (= :eca-prompt-response (:type m))
        (let [s' (cond-> s
                   (:chat-id m) (assoc :chat-id (:chat-id m))
                   (:model m)   (assoc :model (:model m)))]
          (if (= "login" (:status m))
            [s' (program/batch cmd (login/start-login-cmd (:server s') (:pending-message s')))]
            [s' cmd]))

        (:method m)
        (let [[s'' extra-cmd] (handle-eca-notification s m)]
          [s'' (program/batch cmd extra-cmd)])

        :else [s cmd]))
    [state nil]
    msgs))


(defn- initial-state [srv opts]
  {:mode                  :connecting
   :server                srv
   :opts                  opts
   :trust                 (boolean (:trust opts))
   :chat-id               nil
   :chat-title            nil
   :items                 []
   :current-text          ""
   :tool-calls            {}
   :pending-approval      nil
   :pending-message       nil
   :echo-pending          false
   :session-trusted-tools #{}
   :init-tasks            {}
   :available-models      []
   :available-agents      []
   :available-variants    []
   :selected-model        nil
   :selected-agent        nil
   :selected-variant      nil
   :input                 (ti/text-input)
   :input-history         []
   :history-idx           nil
   :focus-path            nil
   :subagent-chats        {}
   :chat-lines            []
   :scroll-offset         0
   :width                 80
   :height                24
   :model                 nil
   :usage                 nil})

(defn make-init [opts]
  (fn []
    (let [workspace (:workspace opts)
          binary    (or (:eca opts) (server/find-eca-binary))
          srv       (-> (server/spawn! {:path binary})
                        (assoc :pending-requests protocol/pending-requests))
          warn      (upgrade/check-version binary)
          init-s    (cond-> (initial-state srv opts)
                      warn (-> (update :items conj {:type :system :text warn})
                               view/rebuild-lines))]
      (server/start-reader! srv)
      [init-s (init-cmd srv workspace)])))

;; --- Update ---
;;
;; Top-level dispatcher. Order is load-bearing — runtime events first, then
;; global key bindings, then per-mode delegation. Picker arms remain inline
;; pending step-9 extraction.

(defn- enter-slash-command? [state msg]
  (and (msg/key-press? msg)
       (msg/key-match? msg :enter)
       (= :ready (:mode state))
       (str/starts-with? (str/trim (ti/value (:input state))) "/")))

(defn- autocomplete-slash? [state msg]
  (and (picker/printable-char? msg)
       (= "/" (:key msg))
       (= :ready (:mode state))
       (= "" (str/trim (ti/value (:input state))))))

(defn update-state [state msg]
  (reset! debug-state {:state (dissoc state :server :input)
                       :msg-type (or (:type msg) (:method msg))
                       :queue-size (when-let [q (get-in state [:server :queue])] (.size q))})
  (let [queue (get-in state [:server :queue])]
    (cond
      ;; --- Runtime mode-agnostic events ---
      (= :window-size (:type msg))
      [(-> state (assoc :width (:width msg) :height (:height msg)) view/rebuild-lines) nil]

      (= :eca-initialized (:type msg))
      [(-> state (assoc :mode :ready) (update :input ti/focus))
       (drain-queue-cmd queue)]

      (= :eca-error (:type msg))
      [(-> state
           (assoc :mode :ready)
           (update :items conj {:type :assistant-text :text (str "Error: " (:error msg))})
           (update :input ti/focus)
           view/rebuild-lines)
       nil]

      (= :eca-tick (:type msg))
      (let [[new-state extra-cmd] (handle-eca-tick state (:msgs msg))]
        [new-state (program/batch extra-cmd (drain-queue-cmd queue))])

      (= :eca-login-action (:type msg))    (login/handle-eca-login-action state msg)
      (= :eca-login-complete (:type msg))  (login/handle-eca-login-complete state msg)

      (= :chat-list-loaded (:type msg))
      (let [chats  (:chats msg)
            error? (:error? msg)
            pairs  (mapv (fn [{:keys [id title messageCount]}]
                           (let [t   (if (seq title) title (subs (or id "") 0 (min 8 (count (or id "")))))
                                 cnt (when messageCount (str messageCount " msgs"))]
                             [(str/join "  •  " (remove nil? [t cnt])) id]))
                         chats)
            s'     (picker/open-session-picker state pairs)]
        [(if error?
           (-> s' (update :items conj {:type :system :text "⚠ Could not load sessions"}) view/rebuild-lines)
           s')
         nil])

      ;; --- Global key bindings (mode-aware but handled at dispatcher level) ---
      (or (msg/quit? msg) (and (msg/key-press? msg) (msg/key-match? msg "ctrl+c")))
      [state (shutdown-cmd (:server state))]

      (and (msg/key-press? msg) (msg/key-match? msg "ctrl+l") (= :ready (:mode state)))
      (commands/cmd-open-model-picker state)

      (enter-slash-command? state msg)
      (commands/dispatch-command state (str/trim (ti/value (:input state))))

      (autocomplete-slash? state msg)
      [(commands/open-command-picker state) nil]

      ;; --- Per-mode dispatch (single-arm delegation) ---
      (= :login (:mode state))      (login/handle-key state msg)
      (= :approving (:mode state))  (chat/handle-approval-key state msg)

      ;; Command-picker selection lives here so picker.clj can stay free
      ;; of a commands.clj dependency (which would create a cycle).
      (and (msg/key-press? msg) (msg/key-match? msg :enter)
           (= :picking (:mode state))
           (= :command (get-in state [:picker :kind])))
      (let [{:keys [list filtered]} (:picker state)
            idx                     (cl/selected-index list)
            [cmd-name _]            (when (and (some? idx) (< idx (count filtered)))
                                      (nth filtered idx))]
        (if cmd-name
          (commands/run-handler-from-picker
            (-> state (dissoc :picker) (assoc :mode :ready))
            cmd-name)
          [state nil]))

      (= :picking (:mode state))           (picker/handle-key state msg)
      (#{:ready :chatting} (:mode state))  (chat/handle-key state msg)

      :else [state nil])))

