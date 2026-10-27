(ns eca-cli.state
  (:require [clojure.string :as str]
            [charm.program :as program]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.server :as server]
            [eca-cli.protocol :as protocol]
            [eca-cli.view :as view]
            [eca-cli.chat :as chat]
            [eca-cli.picker :as picker]
            [eca-cli.login :as login]
            [eca-cli.jobs :as jobs]
            [eca-cli.mcp :as mcp]
            [eca-cli.question :as question]
            [eca-cli.history :as history]
            [eca-cli.commands :as commands]))

;; Expose last-known state for nREPL inspection
(def debug-state (atom nil))

;; --- Commands ---

(defn- drain-queue-cmd [queue]
  (program/cmd
    (fn []
      {:type :eca-tick
       :msgs (server/read-batch! queue 50)})))

(defn- init-cmd
  "Send `initialize`; the response comes back through the queue as an
  :eca-initialize-response, handled in `handle-eca-tick` like any other
  server message."
  [srv workspace]
  (program/cmd
    (fn []
      (protocol/initialize! srv workspace
                            (fn [response]
                              (.put ^java.util.concurrent.BlockingQueue (:queue srv)
                                    {:type :eca-initialize-response :response response})))
      nil)))

(defn- query-files-cmd [srv chat-id query]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/chat-query-files! srv chat-id query
          (fn [r] (deliver p (or (get-in r [:result :files]) []))))
        (let [files (deref p 5000 [])
              paths (mapv :path files)]
          {:type :at-files-loaded :paths paths})))))

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

    "jobs/updated"
    (jobs/handle-jobs-updated state (:params notification))

    "tool/serverUpdated"
    (mcp/apply-server-update state (:params notification))

    "chat/askQuestion"
    [(question/handle-ask-question state notification) nil]

    "chat/statusChanged"
    [(chat/handle-status-changed state (:params notification)) nil]

    ;; A request (it has an id) eca-cli doesn't support: say so, or the
    ;; server would wait for an answer forever.
    (do (when (:id notification)
          (protocol/send-error-response! (:server state) (:id notification) protocol/method-not-found
                                         (str "eca-cli does not support " (:method notification))))
        [state nil])))

(defn- disconnect
  "The server is gone (or refused us): show why and stop taking input. Only
  quitting and scrolling work in :disconnected."
  [state report]
  (-> state
      chat/flush-current-text
      chat/clear-approvals
      (assoc :mode :disconnected :questions [])
      (dissoc :picker :login)
      (update :items conj {:type :system :text report})
      (update :input ti/blur)
      view/rebuild-lines))

(defn- handle-initialize-response
  "Server answered `initialize`: confirm with `initialized` (the server then
  pushes config/updated, MCP status, …) and start taking input."
  [state {:keys [error]}]
  (cond
    (not= :connecting (:mode state))
    state

    error
    (disconnect state (str "The ECA server refused to initialize: "
                           (or (:message error) (pr-str error))))

    :else
    (do (protocol/initialized! (:server state))
        (-> state
            (assoc :mode :ready)
            (dissoc :connecting-since)
            (update :input ti/focus)))))

(defn- handle-eca-tick
  "Apply a batch of server messages. Chat lines are rebuilt once for the whole
  batch rather than by every handler (see `view/*rebuild-deferred*`)."
  [state msgs]
  (let [rebuild?  (volatile! false)
        [s' cmd]  (binding [view/*rebuild-deferred* rebuild?]
                    (reduce
                      (fn [[s cmd] m]
                        (cond
                          (= :reader-error (:type m))
                          [(disconnect s (:error m)) cmd]

                          (= :eca-initialize-response (:type m))
                          (let [s' (handle-initialize-response s (:response m))]
                            ;; Ready: fetch the server's commands for the `/` picker.
                            [s' (cond-> cmd
                                  (and (= :connecting (:mode s)) (= :ready (:mode s')))
                                  (program/batch (commands/query-commands-cmd (:server s') nil)))])

                          (= :eca-prompt-response (:type m))
                          ;; The chat id is ours (submit-prompt); the response
                          ;; may come after the view moved to another chat
                          ;; (e.g. this prompt was /fork), so don't take it back.
                          (let [s' (cond-> s
                                     (and (:chat-id m) (nil? (:chat-id s))) (assoc :chat-id (:chat-id m))
                                     (:model m)   (assoc :model (:model m)))]
                            (if (= "login" (:status m))
                              [s' (program/batch cmd (login/start-login-cmd (:server s') (:pending-message s')))]
                              [s' cmd]))

                          (:method m)
                          (let [[s'' extra-cmd] (handle-eca-notification s m)]
                            [s'' (program/batch cmd extra-cmd)])

                          :else [s cmd]))
                      [state nil]
                      msgs))]
    [(if @rebuild? (view/rebuild-lines s') s') cmd]))


(defn- initial-state [srv opts]
  {:mode                  :connecting
   :connecting-since      (System/currentTimeMillis)
   :server                srv
   :opts                  opts
   :trust                 (boolean (:trust opts))
   :chat-id               nil
   :chat-title            nil
   :items                 []
   :jobs                  {}
   :current-text          ""
   :tool-calls            {}
   :pending-approval      nil
   :approval-queue        []
   :questions             []
   :pending-message       nil
   :echo-pending          false
   :init-tasks            {}
   :pending-contexts      []
   :server-commands       []
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
   :mcps                  {}
   :model                 nil
   :usage                 nil})

(defn- add-notices
  "Append startup notices (e.g. an update check skipped while offline) as
  system items."
  [state notices]
  (if (seq notices)
    (-> state
        (update :items into (map (fn [n] {:type :system :text n}) notices))
        view/rebuild-lines)
    state))

(defn- spawn-server
  "Spawn the server and start its reader thread. Returns the server map, or
  the exception: charm calls init outside its try/finally, so anything thrown
  here would leave the terminal in raw mode."
  [binary]
  (try
    (let [srv (-> (server/spawn! {:path binary})
                  (assoc :pending-requests protocol/pending-requests))]
      (server/start-reader! srv)
      srv)
    (catch Exception e e)))

(defn make-init
  "`opts` carries the CLI flags; `:eca` is the resolved binary path (see
  `upgrade/resolve-binary!`) and `:startup-notices` any warnings from it.

  The queue is drained from the start, not only once connected, so a server
  that dies while starting up is reported right away."
  [opts]
  (fn []
    (let [srv (spawn-server (or (:eca opts) (server/find-eca-binary)))]
      (if (instance? Exception srv)
        [(-> (initial-state nil opts)
             (add-notices (:startup-notices opts))
             (disconnect (str "Could not start the ECA server: "
                              (or (ex-message srv) (str srv)))))
         nil]
        [(add-notices (initial-state srv opts) (:startup-notices opts))
         (program/batch (init-cmd srv (:workspace opts))
                        (drain-queue-cmd (:queue srv)))]))))

;; --- Update ---
;;
;; Top-level dispatcher. Order is load-bearing — runtime events first, then
;; global key bindings, then per-mode delegation. Picker arms remain inline
;; pending step-9 extraction.

(defn- enter-slash-command? [state msg]
  (let [text (str/trim (ti/value (:input state)))]
    (and (msg/key-press? msg)
         (msg/key-match? msg :enter)
         (str/starts-with? text "/")
         (or (= :ready (:mode state))
             ;; Mid-turn, a few of eca-cli's commands; other text steers.
             (and (= :chatting (:mode state)) (commands/runs-during-turn? text))))))

(defn- autocomplete-slash? [state msg]
  (and (picker/printable-char? msg)
       (= "/" (:key msg))
       (= :ready (:mode state))
       (= "" (str/trim (ti/value (:input state))))))

(defn- autocomplete-at?
  "True when `@` should open the file picker: ready mode, and the char left
  of the cursor is empty / space / newline (mid-word `@` is left to text-input)."
  [state msg]
  (and (picker/printable-char? msg)
       (= "@" (:key msg))
       (= :ready (:mode state))
       (let [value (ti/value (:input state))
             pos   (or (ti/position (:input state)) (count value))
             prev  (when (pos? pos) (.charAt ^String value (dec pos)))]
         (or (zero? pos)
             (= \space prev)
             (= \newline prev)))))

(defn- at-file-filter-keystroke?
  "True when `msg` is a keystroke that should re-query the server while the
  at-file picker is open: any printable char (extends the filter) or backspace
  (shrinks it). Other keys (Enter, Escape, arrows) fall through to picker."
  [state msg]
  (and (= :picking (:mode state))
       (= :at-file (get-in state [:picker :kind]))
       (or (picker/printable-char? msg)
           (and (msg/key-press? msg) (msg/key-match? msg :backspace)))))

(defn- pasting?
  "Inside a bracketed paste (see `eca-cli.program`). Bounded in time, in case
  the terminal never sends the end marker."
  [state]
  (when-let [t0 (:pasting-since state)]
    (< (- (System/currentTimeMillis) t0) 5000)))

(defn- insert-text
  "`s` typed into `input` at the cursor."
  [input s]
  (let [v   (str (ti/value input))
        pos (min (count v) (max 0 (or (ti/position input) (count v))))]
    (-> input
        (ti/set-value (str (subs v 0 pos) s (subs v pos)))
        (assoc :pos (+ pos (count s))))))

(defn- typing-mode? [state]
  (contains? #{:ready :chatting :asking} (:mode state)))

(defn- scroll-msg? [msg]
  (or (and (msg/key-press? msg)
           (or (msg/key-match? msg :page-up) (msg/key-match? msg :page-down)))
      (msg/wheel-up? msg)
      (msg/wheel-down? msg)))

(defn update-state [state msg]
  (reset! debug-state {:state (dissoc state :server :input)
                       :msg-type (or (:type msg) (:method msg))
                       :queue-size (when-let [q (get-in state [:server :queue])] (.size q))})
  (let [queue (get-in state [:server :queue])]
    (cond
      ;; --- Runtime mode-agnostic events ---
      (= :window-size (:type msg))
      [(-> state (assoc :width (:width msg) :height (:height msg)) view/rebuild-lines) nil]

      (= :eca-error (:type msg))
      [(-> state
           (cond-> (not= :disconnected (:mode state))
             (-> (assoc :mode :ready) (update :input ti/focus)))
           (update :items conj {:type :assistant-text :text (str "Error: " (:error msg))})
           view/rebuild-lines)
       nil]

      ;; Self-sustaining drain loop; stops once the server is gone (nothing
      ;; else will ever arrive on the queue).
      (= :eca-tick (:type msg))
      (let [[new-state extra-cmd] (handle-eca-tick state (:msgs msg))]
        [new-state (program/batch extra-cmd
                                  (when (and queue (not= :disconnected (:mode new-state)))
                                    (drain-queue-cmd queue)))])

      (= :eca-login-action (:type msg))    (login/handle-eca-login-action state msg)
      (= :eca-login-complete (:type msg))  (login/handle-eca-login-complete state msg)
      (= :eca-jobs-output (:type msg))     (jobs/handle-jobs-output state msg)

      (= :at-files-loaded (:type msg))
      [(picker/update-at-file-results state (:paths msg)) nil]

      (= :commands-loaded (:type msg))
      [(commands/apply-server-commands state (:commands msg)) nil]

      (= :chat-list-loaded (:type msg))
      (let [chats  (:chats msg)
            error? (:error? msg)
            pairs  (mapv (fn [{:keys [id title messageCount]}]
                           (let [t   (if (seq title) title (subs (or id "") 0 (min 8 (count (or id "")))))
                                 cnt (when messageCount (str messageCount " msgs"))]
                             [(str/join "  •  " (remove nil? [t cnt])) id]))
                         chats)
            s'     (picker/open-chat-picker state pairs)]
        [(if error?
           (-> s' (update :items conj {:type :system :text "⚠ Could not load chats"}) view/rebuild-lines)
           s')
         nil])

      ;; --- Bracketed paste: pasted text stays text ---
      (and (msg/key-press? msg) (= :paste-start (:key msg)))
      [(assoc state :pasting-since (System/currentTimeMillis)) nil]

      (and (msg/key-press? msg) (= :paste-end (:key msg)))
      [(dissoc state :pasting-since) nil]

      ;; A pasted newline is part of the text, not Enter (which would send
      ;; the first line and steer with the rest); a pasted tab isn't Tab.
      (and (msg/key-press? msg) (pasting? state)
           (or (msg/key-match? msg :enter) (msg/key-match? msg :tab)))
      [(cond-> state
         (typing-mode? state) (update :input insert-text (if (msg/key-match? msg :enter) "\n" "    ")))
       nil]

      ;; Pasted characters are text too: a leading `/` or `@` doesn't open a picker.
      (and (pasting? state) (typing-mode? state) (picker/printable-char? msg))
      (let [[input cmd] (ti/text-input-update (:input state) msg)]
        [(assoc state :input input) cmd])

      ;; --- Global key bindings (mode-aware but handled at dispatcher level) ---
      (or (msg/quit? msg) (and (msg/key-press? msg) (msg/key-match? msg "ctrl+c")))
      (commands/cmd-quit state)

      (and (msg/key-press? msg) (msg/key-match? msg "ctrl+l") (= :ready (:mode state)))
      (commands/cmd-open-model-picker state)

      (enter-slash-command? state msg)
      (commands/dispatch-command state (str/trim (ti/value (:input state))))

      (autocomplete-slash? state msg)
      [(commands/open-command-picker state)
       (commands/query-commands-cmd (:server state) (:chat-id state))]

      (autocomplete-at? state msg)
      [(picker/open-at-file-picker state)
       (query-files-cmd (:server state) (:chat-id state) "")]

      ;; --- Per-mode dispatch (single-arm delegation) ---
      (= :login (:mode state))      (login/handle-key state msg)
      (= :approving (:mode state))  (chat/handle-approval-key state msg)
      (= :asking (:mode state))     (if (scroll-msg? msg)
                                      (chat/handle-key state msg)
                                      (question/handle-key state msg))

      ;; Server gone: scrolling (to read the exit report) is all that's left.
      (= :disconnected (:mode state))
      (if (scroll-msg? msg) (chat/handle-key state msg) [state nil])

      ;; Command-picker selection lives here so picker.clj can stay free
      ;; of a commands.clj dependency (which would create a cycle).
      (and (msg/key-press? msg) (msg/key-match? msg :enter)
           (= :picking (:mode state))
           (= :command (get-in state [:picker :kind])))
      (let [{:keys [list filtered]} (:picker state)
            idx                     (cl/selected-index list)
            item                    (when (and (some? idx) (< idx (count filtered)))
                                      (nth filtered idx))]
        (if item
          (commands/run-picker-selection
            (-> state (dissoc :picker) (assoc :mode :ready))
            item)
          [state nil]))

      ;; Rollback / flags / timeline pickers (history.clj).
      (and (msg/key-press? msg) (msg/key-match? msg :enter)
           (= :picking (:mode state))
           (contains? history/picker-kinds (get-in state [:picker :kind])))
      (history/handle-select state)

      ;; --- At-file picker: re-query server on filter typing ---
      ;;
      ;; Server returns a capped list for empty query; subsequent typing must
      ;; re-query so matches outside the capped initial set become findable.
      ;; Picker.clj still mutates :query / :filtered client-side for snappy
      ;; UI feedback; the server response (`:at-files-loaded`) then splices
      ;; the canonical ranked list in.
      (at-file-filter-keystroke? state msg)
      (let [[s' _] (picker/handle-key state msg)]
        [s' (query-files-cmd (:server s') (:chat-id s')
                             (get-in s' [:picker :query]))])

      (and (= :picking (:mode state))
           (= :jobs (get-in state [:picker :kind])))
      (jobs/handle-key state msg)

      ;; MCP-picker Enter arm — connect on requires-auth rows; everything else
      ;; falls through to the generic picker dispatch (filter, navigation, Esc).
      (and (msg/key-press? msg) (msg/key-match? msg :enter)
           (mcp/picker-open? state))
      (mcp/handle-key state msg)

      (= :picking (:mode state))           (picker/handle-key state msg)
      (#{:ready :chatting} (:mode state))  (chat/handle-key state msg)

      :else [state nil])))

