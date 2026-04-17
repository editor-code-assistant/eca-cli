(ns eca-bb.state
  (:require [clojure.string :as str]
            [charm.program :as program]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-bb.server :as server]
            [eca-bb.protocol :as protocol]
            [eca-bb.view :as view]))

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

;; --- Protocol send helpers ---

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
             :model   (:model result)}))))

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

(defn- handle-eca-notification [state notification]
  (case (:method notification)
    "chat/contentReceived" (handle-content state (:params notification))
    state))

(defn- handle-eca-tick [state msgs]
  (reduce
    (fn [s m]
      (cond
        (= :eca-prompt-response (:type m))
        (cond-> s
          (:chat-id m) (assoc :chat-id (:chat-id m))
          (:model m)   (assoc :model (:model m)))

        (:method m)
        (handle-eca-notification s m)

        :else s))
    state
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
   :session-trusted-tools #{}
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
      [(handle-eca-tick state (:msgs msg))
       (drain-queue-cmd queue)]

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
                              (assoc :mode :chatting)
                              (update :input #(-> % ti/reset ti/blur))
                              rebuild-lines)]
            (send-chat-prompt! (:server state) (:chat-id state) text (:opts state))
            [new-state nil])
          [state nil]))

      (and (msg/key-press? msg)
           (msg/key-match? msg :esc)
           (= :chatting (:mode state)))
      (do
        (when (:chat-id state)
          (protocol/stop-prompt! (:server state) (:chat-id state)))
        [state nil])

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
           (not= :approving (:mode state)))
      (let [max-offset (max 0 (- (count (:chat-lines state)) (- (:height state) 3)))]
        [(update state :scroll-offset #(min max-offset (inc %))) nil])

      (and (msg/key-press? msg)
           (or (msg/key-match? msg :down) (msg/key-match? msg "j"))
           (not= :approving (:mode state)))
      [(update state :scroll-offset #(max 0 (dec %))) nil]

      :else
      (let [[new-input cmd] (ti/text-input-update (:input state) msg)]
        [(assoc state :input new-input) cmd]))))
