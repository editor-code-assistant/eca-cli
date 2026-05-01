(ns eca-cli.chat
  "Chat-domain state helpers, ECA content handlers, and key dispatch for
  :ready / :chatting / :approving modes. No back-references to eca-cli.state
  or eca-cli.commands — features depend on leaf utils only."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.protocol :as protocol]
            [eca-cli.sessions :as sessions]
            [eca-cli.view :as view]))

;; --- State helpers ---

(defn flush-current-text [state]
  (if (seq (:current-text state))
    (-> state
        (update :items conj {:type :assistant-text :text (:current-text state)})
        (assoc :current-text ""))
    state))

(defn upsert-tool-call [state tool-call]
  (let [id     (:id tool-call)
        merged (merge (get-in state [:tool-calls id]) tool-call)]
    (-> state
        (assoc-in [:tool-calls id] merged)
        (update :items
                (fn [items]
                  (if (some #(= id (:id %)) items)
                    (mapv (fn [item]
                            (if (= id (:id item))
                              ;; Protect interactive state from being clobbered by incoming events
                              (let [protected (select-keys item [:expanded? :focused? :sub-items])]
                                (merge item {:type :tool-call}
                                       (dissoc tool-call :expanded? :focused? :sub-items)
                                       protected))
                              item))
                          items)
                    (let [spawn? (= "eca__spawn_agent" (:name merged))
                          base   (cond-> (assoc merged :type :tool-call
                                                       :expanded? false :focused? false)
                                   spawn? (assoc :sub-items []))]
                      (conj items base))))))))

(defn content->item [params]
  (let [content (:content params)]
    (case (:type content)
      "text"
      {:type :assistant-text :text (:text content)}

      ("toolCallPrepare" "toolCallRunning" "toolCallRun" "toolCalled" "toolCallRejected")
      {:type :tool-call :id (:id content) :name (:name content)
       :server (:server content) :summary (:summary content)
       :state (case (:type content)
                "toolCallPrepare"  :preparing
                "toolCallRun"      :run
                "toolCallRunning"  :running
                "toolCalled"       :called
                "toolCallRejected" :rejected)
       :expanded? false :focused? false}

      "reasonStarted"
      {:type :thinking :id (:id content) :text "" :status :thinking
       :expanded? false :focused? false}

      nil)))

(defn focusable-paths [items]
  (into []
    (mapcat
      (fn [[i item]]
        (when (#{:tool-call :thinking :hook} (:type item))
          (cons [i]
            (when (:expanded? item)
              (keep-indexed
                (fn [j sub]
                  (when (#{:tool-call :thinking :hook} (:type sub))
                    [i j]))
                (or (:sub-items item) []))))))
      (map-indexed vector items))))

(defn sync-focus [state]
  (let [path   (:focus-path state)
        items  (mapv (fn [item]
                       (cond-> (assoc item :focused? false)
                         (:sub-items item)
                         (update :sub-items #(mapv (fn [s] (assoc s :focused? false)) %))))
                     (:items state))
        items' (if path
                 (let [[i j] path]
                   (if j
                     (assoc-in items [i :sub-items j :focused?] true)
                     (assoc-in items [i :focused?] true)))
                 items)]
    (assoc state :items items')))

(defn register-subagent [state tool-id subagent-chat-id]
  (if-let [idx (first (keep-indexed #(when (= tool-id (:id %2)) %1) (:items state)))]
    (assoc-in state [:subagent-chats subagent-chat-id] idx)
    state))

;; --- Outbound prompt ---

(defn send-chat-prompt! [srv chat-id text opts]
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

(defn handle-content [state params]
  (let [content (:content params)]
    (case (:type content)
      "text"
      (-> state
          (update :current-text str (:text content))
          view/rebuild-lines)

      "progress"
      (if (= "finished" (:state content))
        (-> state
            flush-current-text
            (assoc :mode :ready :echo-pending false)
            (update :input ti/focus)
            view/rebuild-lines)
        state)

      "toolCallPrepare"
      (if (and (= "eca" (:server content)) (= "task" (:name content)))
        state
        (-> state
            flush-current-text
            (upsert-tool-call {:id             (:id content)
                               :name           (:name content)
                               :server         (:server content)
                               :summary        (:summary content)
                               :arguments-text (:argumentsText content)
                               :state          :preparing})
            view/rebuild-lines))

      "toolCallRun"
      (let [{:keys [id name server summary arguments manualApproval subagentDetails]} content]
        (if (and (= "eca" server) (= "task" name))
          state
          (let [trust?    (or (:trust state)
                              (contains? (:session-trusted-tools state) name))
                args-text (when arguments
                            (try (json/generate-string arguments)
                                 (catch Exception _ (pr-str arguments))))
                tool      {:id id :name name :server server
                           :summary summary :arguments arguments
                           :args-text args-text :state :run}]
            (if (and manualApproval (not trust?))
              (let [s' (-> state
                           (upsert-tool-call tool)
                           (assoc :mode :approving
                                  :pending-approval {:chat-id (:chat-id state) :tool-call-id id})
                           view/rebuild-lines)]
                (cond-> s'
                  subagentDetails
                  (register-subagent id (:subagentChatId subagentDetails))))
              (do
                (protocol/approve-tool! (:server state) (:chat-id state) id)
                (let [s' (-> state (upsert-tool-call tool) view/rebuild-lines)]
                  (cond-> s'
                    subagentDetails
                    (register-subagent id (:subagentChatId subagentDetails)))))))))

      "toolCallRunning"
      (-> state
          (upsert-tool-call {:id        (:id content) :name (:name content)
                             :server    (:server content) :summary (:summary content)
                             :arguments (:arguments content) :state :running})
          view/rebuild-lines)

      "toolCalled"
      (let [{:keys [id name server summary arguments output error]} content
            out-text (when (seq (str output))
                       (if (> (count output) 8192)
                         (str (subs output 0 8192) "\n[truncated]")
                         output))]
        (-> state
            (upsert-tool-call {:id id :name name :server server
                               :summary summary :arguments arguments
                               :state :called :error? error :out-text out-text})
            view/rebuild-lines))

      "toolCallRejected"
      (-> state
          (upsert-tool-call {:id        (:id content) :name (:name content)
                             :server    (:server content) :summary (:summary content)
                             :arguments (:arguments content) :state :rejected})
          view/rebuild-lines)

      "reasonStarted"
      (-> state
          (update :items conj {:type :thinking :id (:id content) :text ""
                               :status :thinking :expanded? false :focused? false})
          view/rebuild-lines)

      "reasonText"
      (-> state
          (update :items
                  (fn [items]
                    (mapv #(if (and (= :thinking (:type %)) (= (:id content) (:id %)))
                             (update % :text str (:text content))
                             %)
                          items)))
          view/rebuild-lines)

      "reasonFinished"
      (-> state
          (update :items
                  (fn [items]
                    (mapv #(if (and (= :thinking (:type %)) (= (:id content) (:id %)))
                             (assoc % :status :thought)
                             %)
                          items)))
          view/rebuild-lines)

      "hookActionStarted"
      (-> state
          (update :items conj {:type :hook :id (:id content) :name (:name content)
                               :status :running :out-text nil :expanded? false :focused? false})
          view/rebuild-lines)

      "hookActionFinished"
      (-> state
          (update :items
                  (fn [items]
                    (mapv #(if (and (= :hook (:type %)) (= (:id content) (:id %)))
                             (assoc % :status (keyword (:status content))
                                      :out-text (:output content))
                             %)
                          items)))
          view/rebuild-lines)

      "usage"
      (assoc state :usage content)

      state)))

(defn handle-config-updated
  "ECA `config/updated` notification: populate available models / agents /
  variants and surface the welcome message as assistant text."
  [state notification]
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
    [(if (:welcomeMessage chat) (view/rebuild-lines s') s') nil]))

(defn handle-chat-opened
  "ECA `chat/opened` notification: store the chat-id and title on state."
  [state notification]
  (let [{:keys [chatId title]} (:params notification)]
    [(-> state
         (assoc :chat-id chatId)
         (assoc :chat-title title))
     nil]))

(defn handle-chat-cleared
  "ECA `chat/cleared` notification: when params.messages is truthy, drop
  rendered items + chat-lines + scroll position."
  [state notification]
  (let [clear-msgs? (get-in notification [:params :messages])]
    [(cond-> state
       clear-msgs? (-> (assoc :items [])
                       (assoc :current-text "")
                       (assoc :chat-lines [])
                       (assoc :scroll-offset 0)))
     nil]))

(defn handle-content-received
  "ECA `chat/contentReceived` notification handler.

  ECA echoes the user's message back (role:\"user\") so editor plugins that
  don't track sent messages can display it. We render user messages
  immediately on send, so consume the echo via :echo-pending flag and skip
  rendering it.
  Non-echo role:\"user\" text is a replayed historical message (session
  resume): flush :current-text first so prior assistant responses land in
  the right position. Non-text role:\"user\" content (e.g. progress start
  markers) is ignored.
  Route by chatId: any message from a known sub-agent chat goes to the
  spawn tool call's :sub-items. parentChatId is not required — ECA omits
  it on role:\"user\" messages (the task prompt sent to the sub-agent)."
  [state notification]
  (let [params  (:params notification)
        content (:content params)]
    (if-let [parent-idx (get (:subagent-chats state) (:chatId params))]
      [(-> state
           (update-in [:items parent-idx :sub-items]
                      (fn [subs]
                        (if-let [item (content->item params)]
                          (conj (or subs []) item)
                          (or subs []))))
           view/rebuild-lines)
       nil]
      (if (= "user" (:role params))
        (if (= "text" (:type content))
          (cond
            ;; Sub-agent task prompt: parentChatId marks it as machine-generated,
            ;; never typed by the human — render as assistant text, not user input.
            (:parentChatId params)
            [(-> state
                 flush-current-text
                 (update :items conj {:type :assistant-text :text (or (:text content) "")})
                 view/rebuild-lines)
             nil]

            (:echo-pending state)
            [(assoc state :echo-pending false) nil]

            :else
            [(-> state
                 flush-current-text
                 (update :items conj {:type :user :text (or (:text content) "")})
                 view/rebuild-lines)
             nil])
          [state nil])
        [(handle-content state params) nil]))))

;; --- Approval key dispatch (:approving mode) ---

(defn handle-approval-key
  "y / Y / n keypresses in :approving mode. Caller guarantees mode = :approving."
  [state msg]
  (cond
    (and (msg/key-press? msg) (msg/key-match? msg "y"))
    (let [{:keys [chat-id tool-call-id]} (:pending-approval state)]
      (protocol/approve-tool! (:server state) chat-id tool-call-id)
      [(assoc state :mode :chatting :pending-approval nil) nil])

    (and (msg/key-press? msg) (msg/key-match? msg "Y"))
    (let [{:keys [chat-id tool-call-id]} (:pending-approval state)
          tool-name (get-in state [:tool-calls tool-call-id :name])]
      (protocol/approve-tool! (:server state) chat-id tool-call-id)
      [(-> state
           (assoc :mode :chatting :pending-approval nil)
           (update :session-trusted-tools conj tool-name))
       nil])

    (and (msg/key-press? msg) (msg/key-match? msg "n"))
    (let [{:keys [chat-id tool-call-id]} (:pending-approval state)]
      (protocol/reject-tool! (:server state) chat-id tool-call-id)
      [(assoc state :mode :chatting :pending-approval nil) nil])

    :else [state nil]))

;; --- Chat key dispatch (:ready / :chatting modes) ---
;;
;; State.clj's dispatcher catches Ctrl+L, Ctrl+C, Enter+slash, "/" autocomplete
;; BEFORE delegating here, so handle-key never needs to know about commands.

(defn- ensure-focus-visible
  "Adjust :scroll-offset so the focused item's line span sits inside the
  visible window. Top-level path [i] uses span i; sub-item path [i j] falls
  back to parent span [i]. Prefers showing the start of the item if its span
  exceeds visible-height. No-op if no focus-path or span unknown."
  [state]
  (let [path (:focus-path state)]
    (if-let [[i] path]
      (if-let [[s e] (get (:chat-line-spans state) i)]
        (let [total          (count (:chat-lines state))
              visible-height (max 1 (- (:height state) 5))
              offset         (or (:scroll-offset state) 0)
              end-vis        (- total offset)
              start-vis      (- end-vis visible-height)
              max-offset     (max 0 (- total visible-height))
              new-offset     (cond
                               (or (< s start-vis)
                                   (> (- e s) visible-height))
                               (- total s visible-height)

                               (> e end-vis)
                               (- total e)

                               :else offset)]
          (assoc state :scroll-offset (max 0 (min max-offset new-offset))))
        state)
      state)))

(defn- focus-paths-cycle [state direction]
  (let [paths (focusable-paths (:items state))]
    (if (empty? paths)
      [state nil]
      (let [cur     (:focus-path state)
            n       (count paths)
            cur-idx (when cur (first (keep-indexed #(when (= cur %2) %1) paths)))
            next    (case direction
                      :forward  (if (nil? cur-idx) (first paths) (nth paths (mod (inc cur-idx) n)))
                      :backward (if (nil? cur-idx) (last paths)  (nth paths (mod (dec cur-idx) n))))]
        [(-> state (assoc :focus-path next) sync-focus view/rebuild-lines ensure-focus-visible) nil]))))

(defn- enter-submit-prompt [state]
  (let [text (str/trim (ti/value (:input state)))]
    (if (seq text)
      (let [new-state (-> state
                          (update :items conj {:type :user :text text})
                          (assoc :mode :chatting :pending-message text :echo-pending true)
                          (update :input #(-> % ti/reset ti/blur))
                          (update :input-history conj text)
                          (assoc :history-idx nil)
                          view/rebuild-lines)]
        (send-chat-prompt! (:server state) (:chat-id state) text (:opts state))
        [new-state nil])
      [state nil])))

(defn- escape-cancel-chatting [state]
  (when (:chat-id state)
    (protocol/stop-prompt! (:server state) (:chat-id state)))
  [(-> state (assoc :mode :ready) (update :input ti/focus)) nil])

(defn- history-prev [state]
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
      [state nil])))

(defn- history-next [state]
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
       nil])))

(defn- scroll-page [state direction]
  (let [page       (max 1 (- (:height state) 5))
        max-offset (max 0 (- (count (:chat-lines state)) (- (:height state) 5)))]
    (case direction
      :up   [(update state :scroll-offset #(min max-offset (+ % page))) nil]
      :down [(update state :scroll-offset #(max 0 (- % page))) nil])))

(defn- scroll-wheel [state direction]
  (let [max-offset (max 0 (- (count (:chat-lines state)) (- (:height state) 5)))]
    (case direction
      :up   [(update state :scroll-offset #(min max-offset (+ % 3))) nil]
      :down [(update state :scroll-offset #(max 0 (- % 3))) nil])))

(defn- top-level-paths
  "Filter focusable-paths to top-level entries only (`[[i] ...]`),
  used for Alt+↑/↓ block-jumps that skip sub-items."
  [items]
  (filterv #(= 1 (count %)) (focusable-paths items)))

(defn- jump-top-level [state direction]
  (let [paths (top-level-paths (:items state))]
    (if (empty? paths)
      [state nil]
      (let [cur     (:focus-path state)
            ;; Map sub-item focus to its top-level parent before stepping.
            cur-top (when cur [(first cur)])
            n       (count paths)
            cur-idx (when cur-top (first (keep-indexed #(when (= cur-top %2) %1) paths)))
            next    (case direction
                      :forward  (if (nil? cur-idx) (first paths) (nth paths (mod (inc cur-idx) n)))
                      :backward (if (nil? cur-idx) (last paths)  (nth paths (mod (dec cur-idx) n))))]
        [(-> state (assoc :focus-path next) sync-focus view/rebuild-lines ensure-focus-visible) nil]))))

(defn- focus-edge [state edge]
  (let [paths (focusable-paths (:items state))]
    (if (empty? paths)
      [state nil]
      [(-> state
           (assoc :focus-path (case edge :first (first paths) :last (last paths)))
           sync-focus
           view/rebuild-lines
           ensure-focus-visible)
       nil])))

(defn- expandable? [item]
  (#{:tool-call :thinking :hook} (:type item)))

(defn- toggle-all-expanded [state expanded?]
  (let [walk (fn walk [item]
               (cond-> item
                 (expandable? item)   (assoc :expanded? expanded?)
                 (:sub-items item)    (update :sub-items #(mapv walk %))))
        items' (mapv walk (:items state))]
    [(-> state (assoc :items items') view/rebuild-lines) nil]))

(defn handle-key
  "Dispatch keypresses + mouse-wheel events when mode is :ready or :chatting.
  Caller guarantees mode is :ready or :chatting."
  [state msg]
  (cond
    ;; --- Block navigation (Alt-prefixed; safe alongside text input) ---
    (and (msg/key-press? msg) (msg/key-match? msg "alt+up"))
    (jump-top-level state :backward)

    (and (msg/key-press? msg) (msg/key-match? msg "alt+down"))
    (jump-top-level state :forward)

    ;; Match raw key chars so capital G works whether or not the terminal
    ;; sets the :shift modifier flag (varies between iTerm / Ghostty / tmux).
    (and (msg/key-press? msg) (= "g" (:key msg)) (:alt msg) (not (:ctrl msg)))
    (focus-edge state :first)

    (and (msg/key-press? msg) (= "G" (:key msg)) (:alt msg) (not (:ctrl msg)))
    (focus-edge state :last)

    (and (msg/key-press? msg) (msg/key-match? msg "alt+c"))
    (toggle-all-expanded state false)

    (and (msg/key-press? msg) (msg/key-match? msg "alt+o"))
    (toggle-all-expanded state true)

    ;; --- Focus navigation ---
    (and (msg/key-press? msg) (msg/key-match? msg :tab) (not (:alt msg)))
    (focus-paths-cycle state :forward)

    (and (msg/key-press? msg) (msg/key-match? msg :up) (some? (:focus-path state)))
    (focus-paths-cycle state :backward)

    (and (msg/key-press? msg) (msg/key-match? msg :down) (some? (:focus-path state)))
    (focus-paths-cycle state :forward)

    (and (msg/key-press? msg) (msg/key-match? msg :escape) (some? (:focus-path state)))
    [(-> state (assoc :focus-path nil) sync-focus view/rebuild-lines) nil]

    (and (msg/key-press? msg) (msg/key-match? msg :enter) (some? (:focus-path state)))
    (let [[i j] (:focus-path state)
          item-path (if j [:items i :sub-items j] [:items i])]
      [(-> state (update-in item-path update :expanded? not) view/rebuild-lines) nil])

    ;; --- Enter in :ready: non-slash text submission. State.clj handles slash dispatch. ---
    (and (msg/key-press? msg) (msg/key-match? msg :enter) (= :ready (:mode state)))
    (enter-submit-prompt state)

    ;; --- Escape during :chatting cancels the in-flight prompt ---
    (and (msg/key-press? msg) (msg/key-match? msg :escape) (= :chatting (:mode state)))
    (escape-cancel-chatting state)

    ;; --- Input history (only :ready, no focus path) ---
    (and (msg/key-press? msg) (msg/key-match? msg :up) (= :ready (:mode state)))
    (history-prev state)

    (and (msg/key-press? msg) (msg/key-match? msg :down)
         (= :ready (:mode state)) (some? (:history-idx state)))
    (history-next state)

    ;; --- Page scroll ---
    (and (msg/key-press? msg) (msg/key-match? msg :page-up))
    (scroll-page state :up)

    (and (msg/key-press? msg) (msg/key-match? msg :page-down))
    (scroll-page state :down)

    ;; --- Mouse wheel ---
    (msg/wheel-up? msg)   (scroll-wheel state :up)
    (msg/wheel-down? msg) (scroll-wheel state :down)

    ;; --- Default: forward to text-input ---
    :else
    (let [[new-input cmd] (ti/text-input-update (:input state) msg)]
      [(assoc state :input new-input) cmd])))
