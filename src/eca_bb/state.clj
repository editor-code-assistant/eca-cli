(ns eca-bb.state
  (:require [clojure.string :as str]
            [charm.program :as program]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-bb.server :as server]
            [eca-bb.protocol :as protocol]
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

(defn- effective-opts
  "Merge CLI opts with runtime-selected model/agent from state.
   Selected values (from picker) take precedence over CLI flags."
  [state]
  (cond-> (:opts state)
    (:selected-model state) (assoc :model (:selected-model state))
    (:selected-agent state)  (assoc :agent (:selected-agent state))))

(defn- send-chat-prompt! [srv chat-id text opts]
  (protocol/chat-prompt!
    srv
    (cond-> {:message text}
      chat-id       (assoc :chat-id chat-id)
      (:model opts) (assoc :model (:model opts))
      (:agent opts) (assoc :agent (:agent opts)))
    (fn [result]
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
            (assoc :mode :ready)
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
            opts      (effective-opts state)
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
    [(handle-content state (:params notification)) nil]

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
                 (:models chat)                (assoc :available-models (:models chat))
                 (:agents chat)                (assoc :available-agents (:agents chat))
                 (contains? chat :selectModel) (assoc :selected-model (:selectModel chat))
                 (contains? chat :selectAgent) (assoc :selected-agent (:selectAgent chat))
                 (:welcomeMessage chat)        (update :items conj {:type :assistant-text
                                                                     :text (:welcomeMessage chat)}))]
      [(if (:welcomeMessage chat) (rebuild-lines s') s') nil])

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

;; --- Init ---

(defn- initial-state [srv opts]
  {:mode                  :connecting
   :server                srv
   :opts                  opts
   :trust                 (boolean (:trust opts))
   :chat-id               nil
   :items                 []
   :current-text          ""
   :tool-calls            {}
   :pending-approval      nil
   :pending-message       nil
   :session-trusted-tools #{}
   :init-tasks            {}
   :available-models      []
   :available-agents      []
   :available-variants    []
   :selected-model        nil
   :selected-agent        nil
   :selected-variant      nil
   :input                 (ti/text-input :placeholder "Send a message...")
   :chat-lines            []
   :scroll-offset         0
   :width                 80
   :height                24
   :model                 nil
   :usage                 nil})

(defn make-init [opts]
  (fn []
    (let [srv       (-> (server/spawn! {:path (:eca opts)})
                        (assoc :pending-requests protocol/pending-requests))
          workspace (:workspace opts)]
      (server/start-reader! srv)
      [(initial-state srv opts)
       (init-cmd srv workspace)])))

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
              (send-chat-prompt! (:server state) nil pending (effective-opts state)))
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
          (send-chat-prompt! (:server state) nil pending (effective-opts state)))
        [(-> state (assoc :mode :chatting) (dissoc :login) (update :input ti/blur)) nil])

      (or (msg/quit? msg)
          (and (msg/key-press? msg) (msg/key-match? msg "ctrl+c")))
      [state (shutdown-cmd (:server state))]

      (and (msg/key-press? msg)
           (msg/key-match? msg :enter)
           (= :ready (:mode state)))
      (let [text (str/trim (ti/value (:input state)))]
        (if (seq text)
          (let [new-state (-> state
                              (update :items conj {:type :user :text text})
                              (assoc :mode :chatting :pending-message text)
                              (update :input #(-> % ti/reset ti/blur))
                              rebuild-lines)]
            (send-chat-prompt! (:server state) (:chat-id state) text (effective-opts state))
            [new-state nil])
          [state nil]))

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

      (and (msg/key-press? msg)
           (or (msg/key-match? msg :up) (msg/key-match? msg "k"))
           (not (#{:approving :picking} (:mode state))))
      (let [max-offset (max 0 (- (count (:chat-lines state)) (- (:height state) 3)))]
        [(update state :scroll-offset #(min max-offset (inc %))) nil])

      (and (msg/key-press? msg)
           (or (msg/key-match? msg :down) (msg/key-match? msg "j"))
           (not (#{:approving :picking} (:mode state))))
      [(update state :scroll-offset #(max 0 (dec %))) nil]

      :else
      (let [[new-input cmd] (ti/text-input-update (:input state) msg)]
        [(assoc state :input new-input) cmd]))))
