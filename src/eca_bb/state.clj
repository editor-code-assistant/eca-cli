(ns eca-bb.state
  (:require [clojure.string :as str]
            [charm.program :as program]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-bb.server :as server]
            [eca-bb.protocol :as protocol]
            [eca-bb.sessions :as sessions]
            [eca-bb.upgrade :as upgrade]
            [eca-bb.view :as view]))

;; Expose last-known state for nREPL inspection
(def debug-state (atom nil))

;; --- State helpers ---

(defn- rebuild-lines [state]
  (assoc state :chat-lines
         (view/rebuild-chat-lines (:items state) (:current-text state) (:width state))))

(defn- flush-current-text [state]
  (if (seq (:current-text state))
    (-> state
        (update :items conj {:type :assistant-text :text (:current-text state)})
        (assoc :current-text ""))
    state))

(defn- upsert-tool-call [state tool-call]
  (let [id     (:id tool-call)
        merged (merge (get-in state [:tool-calls id]) tool-call)]
    (-> state
        (assoc-in [:tool-calls id] merged)
        (update :items
                (fn [items]
                  (if (some #(= id (:id %)) items)
                    (mapv (fn [item]
                            (if (= id (:id item))
                              (merge item {:type :tool-call} tool-call)
                              item))
                          items)
                    (conj items (assoc merged :type :tool-call))))))))

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

(defn- delete-chat-cmd [srv chat-id]
  (program/cmd
    (fn []
      (protocol/delete-chat! srv chat-id (fn [_] nil))
      nil)))

(defn- open-chat-cmd [srv chat-id]
  (program/cmd
    (fn []
      (protocol/open-chat! srv chat-id (fn [_] nil))
      nil)))

(defn- list-chats-cmd [srv]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/list-chats! srv
          (fn [r]
            (deliver p {:chats  (or (get-in r [:result :chats]) [])
                        :error? (boolean (:error r))})))
        (let [{:keys [chats error?]} (deref p 10000 {:chats [] :error? true})]
          {:type :chat-list-loaded :chats chats :error? error?})))))

(defn- start-login-cmd [srv pending-message]
  (program/cmd
    (fn []
      (let [providers-result (promise)
            _                (protocol/providers-list! srv
                               (fn [r] (deliver providers-result (or (:result r) {}))))]
        (let [providers (-> (deref providers-result 10000 {:providers []}) :providers)
              provider  (first (filter #(contains? #{"unauthenticated" "expired"}
                                                   (get-in % [:auth :status]))
                                       providers))]
          (if-not provider
            {:type :eca-error :error "Login required but no unauthenticated provider found"}
            (let [login-result (promise)
                  _            (protocol/providers-login! srv (:id provider) nil
                                 (fn [r] (deliver login-result (or (:result r) (:error r)))))]
              {:type            :eca-login-action
               :provider        (:id provider)
               :action          (deref login-result 10000 nil)
               :pending-message pending-message})))))))

(defn- choose-login-method-cmd [srv provider method]
  (program/cmd
    (fn []
      (let [result (promise)
            _      (protocol/providers-login! srv provider method
                     (fn [r] (deliver result (or (:result r) (:error r)))))]
        {:type     :eca-login-action
         :provider provider
         :action   (deref result 10000 nil)}))))

(defn- submit-login-cmd [srv provider collected pending-message]
  (program/cmd
    (fn []
      (let [result (promise)
            _      (protocol/providers-login-input! srv provider collected
                     (fn [r] (deliver result (or (:result r) (:error r)))))]
        (let [r (deref result 10000 ::timeout)]
          (cond
            (= r ::timeout)        {:type :eca-error :error "Login timed out"}
            (= "done" (:action r)) {:type :eca-login-complete :pending-message pending-message}
            :else                  {:type :eca-error :error (str "Login failed: " r)}))))))

;; --- Protocol send helpers ---

(defn- send-chat-prompt! [srv chat-id text opts]
  (protocol/chat-prompt!
    srv
    (cond-> {:message text}
      chat-id       (assoc :chat-id chat-id)
      (:model opts) (assoc :model (:model opts))
      (:agent opts) (assoc :agent (:agent opts)))
    (fn [result]
      (when-let [new-id (:chat-id result)]
        (sessions/save-chat-id! (:workspace opts) new-id))
      (.put (:queue srv)
            {:type    :eca-prompt-response
             :chat-id (:chat-id result)
             :model   (:model result)
             :status  (:status result)}))))

;; --- ECA content handler ---

(defn- handle-content [state params]
  (let [content (:content params)]
    (case (:type content)
      "text"
      (-> state
          (update :current-text str (:text content))
          rebuild-lines)

      "progress"
      (if (= "finished" (:state content))
        (-> state
            flush-current-text
            (assoc :mode :ready :echo-pending false)
            (update :input ti/focus)
            rebuild-lines)
        state)

      "toolCallPrepare"
      (-> state
          flush-current-text
          (upsert-tool-call {:id             (:id content)
                             :name           (:name content)
                             :server         (:server content)
                             :summary        (:summary content)
                             :arguments-text (:argumentsText content)
                             :state          :preparing})
          rebuild-lines)

      "toolCallRun"
      (let [{:keys [id name server summary arguments manualApproval]} content
            trust? (or (:trust state)
                       (contains? (:session-trusted-tools state) name))
            tool   {:id id :name name :server server
                    :summary summary :arguments arguments :state :run}]
        (if (and manualApproval (not trust?))
          (-> state
              (upsert-tool-call tool)
              (assoc :mode :approving
                     :pending-approval {:chat-id (:chat-id state) :tool-call-id id})
              rebuild-lines)
          (do
            (protocol/approve-tool! (:server state) (:chat-id state) id)
            (-> state (upsert-tool-call tool) rebuild-lines))))

      "toolCallRunning"
      (-> state
          (upsert-tool-call {:id        (:id content) :name (:name content)
                             :server    (:server content) :summary (:summary content)
                             :arguments (:arguments content) :state :running})
          rebuild-lines)

      "toolCalled"
      (-> state
          (upsert-tool-call {:id        (:id content) :name (:name content)
                             :server    (:server content) :summary (:summary content)
                             :arguments (:arguments content) :state :called
                             :error?    (:error content)})
          rebuild-lines)

      "toolCallRejected"
      (-> state
          (upsert-tool-call {:id        (:id content) :name (:name content)
                             :server    (:server content) :summary (:summary content)
                             :arguments (:arguments content) :state :rejected})
          rebuild-lines)

      "usage"
      (assoc state :usage content)

      state)))

;; --- Login notification handler ---

(defn- handle-providers-updated [state provider-status]
  (let [auth-status (get-in provider-status [:auth :status])
        provider-id (:id provider-status)]
    (if (and (= :login (:mode state))
             (contains? #{"authenticated" "expiring"} auth-status)
             (= provider-id (get-in state [:login :provider])))
      (let [pending   (:pending-message state)
            srv       (:server state)
            opts      (:opts state)
            new-state (-> state
                          (assoc :mode :chatting)
                          (dissoc :login)
                          (update :input ti/blur))]
        [new-state (when pending
                     (program/cmd (fn []
                                    (send-chat-prompt! srv nil pending opts)
                                    nil)))])
      [state nil])))

(defn- handle-eca-notification [state notification]
  (case (:method notification)
    "chat/contentReceived"
    ;; ECA echoes the user's message back (role:"user") so editor plugins that don't
    ;; track sent messages can display it. We render user messages immediately on send,
    ;; so consume the echo via :echo-pending flag and skip rendering it.
    ;; Non-echo role:"user" text is a replayed historical message (session resume):
    ;; flush :current-text first so prior assistant responses land in the right position.
    ;; Non-text role:"user" content (e.g. progress start markers) is ignored.
    ;; parentChatId present means this is sub-agent content — suppress from main view.
    ;; The parent-level eca__spawn_agent tool call item is sufficient feedback.
    (let [params  (:params notification)
          content (:content params)]
      (if (:parentChatId params)
        [state nil]
        (if (= "user" (:role params))
        (if (= "text" (:type content))
          (if (:echo-pending state)
            [(assoc state :echo-pending false) nil]
            [(-> state
                 flush-current-text
                 (update :items conj {:type :user :text (or (:text content) "")})
                 rebuild-lines)
             nil])
          [state nil])
        [(handle-content state params) nil])))

    "providers/updated"
    (handle-providers-updated state (:params notification))

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
           rebuild-lines)
       nil])

    "config/updated"
    (let [chat (get-in notification [:params :chat])
          s'   (cond-> state
                 (:models chat)                  (assoc :available-models (:models chat))
                 (:agents chat)                  (assoc :available-agents (:agents chat))
                 (contains? chat :selectModel)   (assoc :selected-model (:selectModel chat))
                 (contains? chat :selectAgent)   (assoc :selected-agent (:selectAgent chat))
                 (contains? chat :variants)      (assoc :available-variants (:variants chat))
                 (contains? chat :selectVariant) (assoc :selected-variant (:selectVariant chat))
                 (:welcomeMessage chat)          (update :items conj {:type :assistant-text
                                                                       :text (:welcomeMessage chat)}))]
      [(if (:welcomeMessage chat) (rebuild-lines s') s') nil])

    "chat/opened"
    (let [{:keys [chatId title]} (:params notification)]
      [(-> state
           (assoc :chat-id chatId)
           (assoc :chat-title title))
       nil])

    "chat/cleared"
    (let [clear-msgs? (get-in notification [:params :messages])]
      [(cond-> state
         clear-msgs? (-> (assoc :items [])
                         (assoc :current-text "")
                         (assoc :chat-lines [])
                         (assoc :scroll-offset 0)))
       nil])

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
             rebuild-lines)
         nil]

        (= :eca-prompt-response (:type m))
        (let [s' (cond-> s
                   (:chat-id m) (assoc :chat-id (:chat-id m))
                   (:model m)   (assoc :model (:model m)))]
          (if (= "login" (:status m))
            [s' (program/batch cmd (start-login-cmd (:server s') (:pending-message s')))]
            [s' cmd]))

        (:method m)
        (let [[s'' extra-cmd] (handle-eca-notification s m)]
          [s'' (program/batch cmd extra-cmd)])

        :else [s cmd]))
    [state nil]
    msgs))

;; --- Picker helpers ---

(defn- item-display [kind item]
  (case kind
    :session (first item)
    :command (str (first item) "  —  " (second item))
    item))

(defn- open-picker [state kind]
  (let [items (if (= :model kind) (:available-models state) (:available-agents state))]
    (if (empty? items)
      state
      (-> state
          (assoc :mode :picking
                 :picker {:kind     kind
                          :list     (cl/item-list items :height 8)
                          :all      items
                          :filtered items
                          :query    ""})
          (update :input ti/reset)))))

(defn- open-session-picker [state session-pairs]
  (let [labels (mapv first session-pairs)]
    (-> state
        (assoc :mode :picking
               :picker {:kind     :session
                        :list     (cl/item-list labels :height 8)
                        :all      session-pairs
                        :filtered session-pairs
                        :query    ""})
        (update :input ti/reset))))

(defn- filter-picker [state ch]
  (let [query    (str (get-in state [:picker :query]) ch)
        kind     (get-in state [:picker :kind])
        all      (get-in state [:picker :all])
        filtered (filterv #(str/includes? (str/lower-case (item-display kind %))
                                           (str/lower-case query))
                           all)
        labels   (mapv #(item-display kind %) filtered)]
    (-> state
        (assoc-in [:picker :query] query)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items labels))))

(defn- unfilter-picker [state]
  (let [query    (get-in state [:picker :query])
        new-q    (if (seq query) (subs query 0 (dec (count query))) "")
        kind     (get-in state [:picker :kind])
        all      (get-in state [:picker :all])
        filtered (if (seq new-q)
                   (filterv #(str/includes? (str/lower-case (item-display kind %))
                                             (str/lower-case new-q))
                              all)
                   all)
        labels   (mapv #(item-display kind %) filtered)]
    (-> state
        (assoc-in [:picker :query] new-q)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items labels))))

(declare command-registry)

;; --- Command handlers ---

(defn- cmd-open-model-picker [state]
  (if (seq (:available-models state))
    [(open-picker state :model) nil]
    [(-> state
         (update :items conj {:type :system :text "⚠ No models available"})
         rebuild-lines)
     nil]))

(defn- cmd-open-agent-picker [state]
  (if (seq (:available-agents state))
    [(open-picker state :agent) nil]
    [(-> state
         (update :items conj {:type :system :text "⚠ No agents available"})
         rebuild-lines)
     nil]))

(defn- cmd-new-chat [state]
  (if-let [old-chat-id (:chat-id state)]
    (do
      (sessions/save-chat-id! (get-in state [:opts :workspace]) nil)
      [(-> state
           (assoc :items [] :chat-lines [] :chat-id nil :chat-title nil :scroll-offset 0)
           (update :input #(-> % ti/reset ti/focus)))
       (delete-chat-cmd (:server state) old-chat-id)])
    [(update state :input #(-> % ti/reset ti/focus)) nil]))

(defn- cmd-list-sessions [state]
  [(update state :input ti/reset) (list-chats-cmd (:server state))])

(defn- cmd-clear-chat [state]
  [(assoc state :items [] :chat-lines [] :scroll-offset 0) nil])

(defn- cmd-show-help [state]
  (let [lines (map (fn [[name {:keys [doc]}]] (str name "  —  " doc))
                   (sort-by key command-registry))
        text  (str/join "\n" (into ["Available commands:"] lines))]
    [(update state :items conj {:type :system :text text}) nil]))

(defn- cmd-quit [state]
  [state (shutdown-cmd (:server state))])

(defn- cmd-login [state]
  [state (start-login-cmd (:server state) nil)])

(def command-registry
  {"/model"    {:doc "Open model picker"                  :handler cmd-open-model-picker}
   "/agent"    {:doc "Open agent picker"                  :handler cmd-open-agent-picker}
   "/new"      {:doc "Start a fresh chat"                 :handler cmd-new-chat}
   "/sessions" {:doc "Browse and resume previous chats"   :handler cmd-list-sessions}
   "/clear"    {:doc "Clear chat display (local only)"    :handler cmd-clear-chat}
   "/help"     {:doc "Show available commands"            :handler cmd-show-help}
   "/quit"     {:doc "Exit eca-bb"                        :handler cmd-quit}
   "/login"    {:doc "Manually trigger provider login"    :handler cmd-login}})

(defn- open-command-picker [state]
  (let [all (mapv (fn [[name {:keys [doc]}]] [name doc])
                  (sort-by key command-registry))]
    (-> state
        (assoc :mode :picking
               :picker {:kind     :command
                        :query    ""
                        :list     (cl/item-list (mapv #(item-display :command %) all) :height 8)
                        :all      all
                        :filtered all})
        (update :input ti/reset))))

(defn- finalize-handler-result [new-state cmd]
  [(cond-> (rebuild-lines new-state)
     (= :ready (:mode new-state)) (update :input #(-> % ti/reset ti/focus)))
   cmd])

(defn- dispatch-command [state text]
  (if-let [{:keys [handler]} (get command-registry text)]
    (let [[new-state cmd] (handler state)]
      (finalize-handler-result new-state cmd))
    [(-> state
         (update :items conj {:type :system
                               :text (str "⚠ Unknown command: " text
                                          "  (type /help to see available commands)")})
         (update :input #(-> % ti/reset ti/focus))
         rebuild-lines)
     nil]))

(defn- printable-char? [msg]
  (and (msg/key-press? msg)
       (string? (:key msg))
       (= 1 (count (:key msg)))
       (not (:ctrl msg))
       (not (:alt msg))))

;; --- Init ---

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
                               rebuild-lines))]
      (server/start-reader! srv)
      [init-s (init-cmd srv workspace)])))

;; --- Update ---

(defn update-state [state msg]
  (reset! debug-state {:state (dissoc state :server :input)
                        :msg-type (or (:type msg) (:method msg))
                        :queue-size (when-let [q (get-in state [:server :queue])] (.size q))})
  (let [queue (get-in state [:server :queue])]
    (cond
      (= :window-size (:type msg))
      [(-> state
           (assoc :width (:width msg) :height (:height msg))
           rebuild-lines)
       nil]

      (= :eca-initialized (:type msg))
      [(-> state (assoc :mode :ready) (update :input ti/focus))
       (drain-queue-cmd queue)]

      (= :eca-error (:type msg))
      [(-> state
           (assoc :mode :ready)
           (update :items conj {:type :assistant-text :text (str "Error: " (:error msg))})
           (update :input ti/focus)
           rebuild-lines)
       nil]

      (= :eca-tick (:type msg))
      (let [[new-state extra-cmd] (handle-eca-tick state (:msgs msg))]
        [new-state (program/batch extra-cmd (drain-queue-cmd queue))])

      ;; Login: action received from providers/login
      (= :eca-login-action (:type msg))
      (let [{:keys [provider action]} msg
            pending (or (:pending-message msg) (get-in state [:login :pending-message]))]
        (cond
          (nil? action)
          [(-> state
               (assoc :mode :ready)
               (update :input ti/focus)
               (update :items conj {:type :system :text "Login failed: timed out"})
               rebuild-lines)
           nil]

          (= "done" (:action action))
          (do
            (when pending
              (send-chat-prompt! (:server state) nil pending (:opts state)))
            [(-> state (assoc :mode :chatting) (dissoc :login) (update :input ti/blur)) nil])

          :else
          (let [needs-input? (or (= "input" (:action action))
                                 (and (= "authorize" (:action action))
                                      (seq (:fields action))))
                login-state  {:provider        provider
                               :action          action
                               :field-idx       0
                               :collected       {}
                               :pending-message pending}]
            [(-> state
                 (assoc :mode :login :login login-state)
                 (update :input #(if needs-input? (ti/focus %) (ti/blur %))))
             nil])))

      ;; Login: input submitted successfully
      (= :eca-login-complete (:type msg))
      (let [pending (:pending-message msg)]
        (when pending
          (send-chat-prompt! (:server state) nil pending (:opts state)))
        [(-> state (assoc :mode :chatting) (dissoc :login) (update :input ti/blur)) nil])

      ;; Session list loaded from chat/list response
      (= :chat-list-loaded (:type msg))
      (let [chats  (:chats msg)
            error? (:error? msg)
            pairs  (mapv (fn [{:keys [id title messageCount]}]
                           (let [t   (if (seq title) title (subs (or id "") 0 (min 8 (count (or id "")))))
                                 cnt (when messageCount (str messageCount " msgs"))]
                             [(str/join "  •  " (remove nil? [t cnt])) id]))
                         chats)
            s'     (open-session-picker state pairs)]
        [(if error?
           (-> s' (update :items conj {:type :system :text "⚠ Could not load sessions"}) rebuild-lines)
           s')
         nil])

      (or (msg/quit? msg)
          (and (msg/key-press? msg) (msg/key-match? msg "ctrl+c")))
      [state (shutdown-cmd (:server state))]

      ;; Ctrl+L: open model picker (same guard as /model command)
      (and (msg/key-press? msg)
           (msg/key-match? msg "ctrl+l")
           (= :ready (:mode state)))
      (cmd-open-model-picker state)

      (and (msg/key-press? msg)
           (msg/key-match? msg :enter)
           (= :ready (:mode state)))
      (let [text (str/trim (ti/value (:input state)))]
        (cond
          (str/starts-with? text "/")
          (dispatch-command state text)

          (seq text)
          (let [new-state (-> state
                              (update :items conj {:type :user :text text})
                              (assoc :mode :chatting :pending-message text :echo-pending true)
                              (update :input #(-> % ti/reset ti/blur))
                              (update :input-history conj text)
                              (assoc :history-idx nil)
                              rebuild-lines)]
            (send-chat-prompt! (:server state) (:chat-id state) text (:opts state))
            [new-state nil])

          :else [state nil]))

      (and (msg/key-press? msg)
           (msg/key-match? msg :escape)
           (= :chatting (:mode state)))
      (do
        (when (:chat-id state)
          (protocol/stop-prompt! (:server state) (:chat-id state)))
        [(-> state (assoc :mode :ready) (update :input ti/focus)) nil])

      ;; Login: choose method with digit key
      (and (msg/key-press? msg)
           (= :login (:mode state))
           (= "choose-method" (get-in state [:login :action :action]))
           (re-matches #"[1-9]" (str (:key msg))))
      (let [idx    (dec (parse-long (str (:key msg))))
            methods (get-in state [:login :action :methods])
            method  (nth methods idx nil)]
        (if method
          [state (choose-login-method-cmd (:server state)
                                          (get-in state [:login :provider])
                                          (:key method))]
          [state nil]))

      ;; Login: enter to submit input field
      (and (msg/key-press? msg)
           (msg/key-match? msg :enter)
           (= :login (:mode state))
           (let [action-type (get-in state [:login :action :action])]
             (or (= "input" action-type)
                 (and (= "authorize" action-type)
                      (seq (get-in state [:login :action :fields]))))))
      (let [login     (:login state)
            fields    (get-in login [:action :fields])
            field     (nth fields (:field-idx login) nil)
            value     (str/trim (ti/value (:input state)))
            collected (assoc (:collected login) (:key field) value)
            next-idx  (inc (:field-idx login))]
        (if (< next-idx (count fields))
          [(-> state
               (update :login assoc :field-idx next-idx :collected collected)
               (update :input #(-> % ti/reset ti/focus)))
           nil]
          [(-> state (update :input #(-> % ti/reset ti/blur)))
           (submit-login-cmd (:server state)
                             (:provider login)
                             collected
                             (:pending-message login))]))

      ;; Login: escape to cancel
      (and (msg/key-press? msg)
           (msg/key-match? msg :escape)
           (= :login (:mode state)))
      [(-> state
           (assoc :mode :ready)
           (dissoc :login :pending-message)
           (update :input ti/focus))
       nil]

      (and (msg/key-press? msg)
           (msg/key-match? msg "y")
           (= :approving (:mode state)))
      (let [{:keys [chat-id tool-call-id]} (:pending-approval state)]
        (protocol/approve-tool! (:server state) chat-id tool-call-id)
        [(assoc state :mode :chatting :pending-approval nil) nil])

      (and (msg/key-press? msg)
           (msg/key-match? msg "Y")
           (= :approving (:mode state)))
      (let [{:keys [chat-id tool-call-id]} (:pending-approval state)
            tool-name (get-in state [:tool-calls tool-call-id :name])]
        (protocol/approve-tool! (:server state) chat-id tool-call-id)
        [(-> state
             (assoc :mode :chatting :pending-approval nil)
             (update :session-trusted-tools conj tool-name))
         nil])

      (and (msg/key-press? msg)
           (msg/key-match? msg "n")
           (= :approving (:mode state)))
      (let [{:keys [chat-id tool-call-id]} (:pending-approval state)]
        (protocol/reject-tool! (:server state) chat-id tool-call-id)
        [(assoc state :mode :chatting :pending-approval nil) nil])

      ;; Picker: Enter to select
      (and (msg/key-press? msg)
           (msg/key-match? msg :enter)
           (= :picking (:mode state)))
      (let [{:keys [kind list filtered]} (:picker state)]
        (case kind
          (:model :agent)
          (let [selected (cl/selected-item list)]
            (if selected
              (do
                (if (= :model kind)
                  (protocol/selected-model-changed! (:server state) selected)
                  (protocol/selected-agent-changed! (:server state) selected))
                [(-> state
                     (assoc :mode :ready)
                     (assoc (if (= :model kind) :selected-model :selected-agent) selected)
                     (cond-> (= :model kind) (assoc :selected-variant nil))
                     (assoc-in [:opts (if (= :model kind) :model :agent)] selected)
                     (dissoc :picker)
                     (update :input ti/focus))
                 nil])
              [state nil]))

          :session
          (let [idx             (cl/selected-index list)
                [_display chat-id] (when (and (some? idx) (< idx (count filtered)))
                                     (nth filtered idx))]
            (when chat-id
              (sessions/save-chat-id! (get-in state [:opts :workspace]) chat-id))
            [(-> state
                 (assoc :mode :ready
                        :items []
                        :chat-lines []
                        :scroll-offset 0)
                 (assoc :chat-id (or chat-id (:chat-id state)))
                 (dissoc :picker)
                 (update :input ti/focus))
             (when chat-id (open-chat-cmd (:server state) chat-id))])

          :command
          (let [idx           (cl/selected-index list)
                [cmd-name _]  (when (and (some? idx) (< idx (count filtered)))
                                (nth filtered idx))]
            (if-let [{:keys [handler]} (when cmd-name (get command-registry cmd-name))]
              (let [base            (-> state (dissoc :picker) (assoc :mode :ready))
                    [new-state cmd] (handler base)]
                (finalize-handler-result new-state cmd))
              [state nil]))))

      ;; Picker: Escape to cancel
      (and (msg/key-press? msg)
           (msg/key-match? msg :escape)
           (= :picking (:mode state)))
      [(-> state
           (assoc :mode :ready)
           (dissoc :picker)
           (update :input ti/focus))
       nil]

      ;; Picker: Backspace removes last filter char
      ;; For the command picker, backspace on empty query exits to :ready
      (and (msg/key-press? msg)
           (msg/key-match? msg :backspace)
           (= :picking (:mode state)))
      (if (and (= :command (get-in state [:picker :kind]))
               (= "" (get-in state [:picker :query])))
        [(-> state (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)) nil]
        [(unfilter-picker state) nil])

      ;; Picker: printable char narrows filter
      (and (printable-char? msg)
           (= :picking (:mode state)))
      [(filter-picker state (:key msg)) nil]

      ;; Picker: navigation keys passed to list-update
      (= :picking (:mode state))
      (let [[new-list _] (cl/list-update (get-in state [:picker :list]) msg)]
        [(assoc-in state [:picker :list] new-list) nil])

      ;; Input history navigation (up/down in :ready mode)
      (and (msg/key-press? msg)
           (msg/key-match? msg :up)
           (= :ready (:mode state)))
      (let [history (:input-history state)
            cur-idx (:history-idx state)
            new-idx (if (nil? cur-idx)
                      (dec (count history))
                      (max 0 (dec cur-idx)))]
        (if (seq history)
          [(-> state
               (assoc :history-idx new-idx)
               (update :input #(ti/set-value % (nth history new-idx))))
           nil]
          [state nil]))

      (and (msg/key-press? msg)
           (msg/key-match? msg :down)
           (= :ready (:mode state))
           (some? (:history-idx state)))
      (let [history (:input-history state)
            new-idx (inc (:history-idx state))]
        (if (< new-idx (count history))
          [(-> state
               (assoc :history-idx new-idx)
               (update :input #(ti/set-value % (nth history new-idx))))
           nil]
          [(-> state
               (assoc :history-idx nil)
               (update :input #(ti/set-value % "")))
           nil]))

      ;; PgUp/PgDn scroll (full page)
      (and (msg/key-press? msg)
           (msg/key-match? msg :page-up)
           (not (#{:approving :picking} (:mode state))))
      (let [max-offset (max 0 (- (count (:chat-lines state)) (- (:height state) 5)))
            page       (max 1 (- (:height state) 5))]
        [(update state :scroll-offset #(min max-offset (+ % page))) nil])

      (and (msg/key-press? msg)
           (msg/key-match? msg :page-down)
           (not (#{:approving :picking} (:mode state))))
      (let [page (max 1 (- (:height state) 5))]
        [(update state :scroll-offset #(max 0 (- % page))) nil])

      ;; Mouse wheel scroll (3 lines per tick)
      (and (msg/wheel-up? msg)
           (not (#{:approving :picking} (:mode state))))
      (let [max-offset (max 0 (- (count (:chat-lines state)) (- (:height state) 5)))]
        [(update state :scroll-offset #(min max-offset (+ % 3))) nil])

      (and (msg/wheel-down? msg)
           (not (#{:approving :picking} (:mode state))))
      [(update state :scroll-offset #(max 0 (- % 3))) nil]

      ;; Autocomplete: "/" as first char in empty :ready input opens command picker
      (and (printable-char? msg)
           (= "/" (:key msg))
           (= :ready (:mode state))
           (= "" (str/trim (ti/value (:input state)))))
      [(open-command-picker state) nil]

      :else
      (let [[new-input cmd] (ti/text-input-update (:input state) msg)]
        [(assoc state :input new-input) cmd]))))
