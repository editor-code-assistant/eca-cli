(ns eca-cli.chat
  "Chat-domain state helpers, ECA content handlers, and key dispatch for
  :ready / :chatting / :approving modes. No back-references to eca-cli.state
  or eca-cli.commands — features depend on leaf utils only."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.protocol :as protocol]
            [eca-cli.chats :as chats]
            [eca-cli.paths :as paths]
            [eca-cli.picker :as picker]
            [eca-cli.view :as view]
            [eca-cli.view.blocks :as blocks]
            [eca-cli.welcome :as welcome]))

;; --- State helpers ---

(defn flush-current-text [state]
  (if (seq (:current-text state))
    (-> state
        (update :items conj {:type :assistant-text :text (:current-text state)})
        (assoc :current-text ""))
    state))

(defn spawn-call?
  "A tool call that runs a sub-agent: known by its `subagent` details (sent
  from toolCallRun on), or by the native tool's name before those arrive."
  [tool]
  (or (some? (:subagent-chat-id tool))
      (and (= "spawn_agent" (:name tool)) (contains? #{nil "eca"} (:server tool)))))

(defn upsert-tool-call [state tool-call]
  (let [id     (:id tool-call)
        merged (merge (get-in state [:tool-calls id]) tool-call)
        spawn? (spawn-call? merged)]
    (-> state
        (assoc-in [:tool-calls id] merged)
        (update :items
                (fn [items]
                  (if (some #(= id (:id %)) items)
                    (mapv (fn [item]
                            (if (= id (:id item))
                              ;; Protect interactive state from being clobbered by incoming events
                              (let [protected (select-keys item [:expanded? :focused? :sub-items])]
                                (cond-> (merge item {:type :tool-call}
                                               (dissoc tool-call :expanded? :focused? :sub-items)
                                               protected)
                                  (and spawn? (nil? (:sub-items item))) (assoc :sub-items [])))
                              item))
                          items)
                    (conj items (cond-> (assoc merged :type :tool-call
                                                      :expanded? false :focused? false)
                                  spawn? (assoc :sub-items [])))))))))

(def Edit
  "Malli schema for the normalized file-change payload attached to a tool-call
  item under `:edit`. Present iff the server shipped a `fileChange` detail
  (`details.type == \"fileChange\"`). It is plain data — no malli runtime
  dependency is needed to document the shape; `blocks/render-diff` consumes it.
    :path          absolute file path the edit targets
    :diff          server-computed unified-diff string (eca-cli only colourises)
    :lines-added   count of added lines, for the collapsed `+N` badge
    :lines-removed count of removed lines, for the collapsed `−M` badge"
  [:map {:closed true}
   [:path :string]
   [:diff :string]
   [:lines-added :int]
   [:lines-removed :int]])

(defn content->edit
  "Normalize a server `fileChange` tool-call detail into an `:edit` map (see
  `Edit`), or nil when the content carries no file-change payload. Detection is
  by payload presence — `details.type == \"fileChange\"` — never by tool name,
  so edit_file / write_file / apply_patch / any future edit tool are covered
  automatically. Key path/casing traced against the ECA server source
  (`filesystem.clj` fileChange arm → `shared.clj` recursive camelCase → cheshire
  keyword→string) and REPL-verified by replaying that transform — not yet
  captured from a live edit_file through the TUI. Expected shape: `{:type
  \"fileChange\" :path :diff :linesAdded :linesRemoved}` under `content[:details]`."
  [content]
  (let [d (:details content)]
    (when (= "fileChange" (:type d))
      {:path          (:path d)
       :diff          (:diff d)
       :lines-added   (:linesAdded d)
       :lines-removed (:linesRemoved d)})))

(def ^:private max-tool-output 8192)

(defn tool-output-text
  "Display text of a `toolCalled` result: its `outputs` text parts (a plain
  string on some server paths), or a legacy single `output`. Capped at 8 KB;
  nil when empty."
  [content]
  (let [outputs (:outputs content)
        text    (cond
                  (string? outputs)     outputs
                  (sequential? outputs) (some->> outputs (keep :text) seq (str/join "\n"))
                  :else                 (:output content))]
    (when (seq (str text))
      (if (> (count text) max-tool-output)
        (str (subs text 0 max-tool-output) "\n[truncated]")
        text))))

(defn content->shell
  "Normalize a `shellCommand` tool-call detail into `{:commands [...]
  :background? bool}`, or nil. Each command carries the key an approve &
  remember would save (`:approval-key`, nil when it always asks) and whether
  that key is already remembered for the chat."
  [content]
  (let [d (:details content)]
    (when (= "shellCommand" (:type d))
      {:commands    (mapv (fn [{:keys [command args approvalKey remembered]}]
                            {:command      command
                             :args         (vec args)
                             :approval-key approvalKey
                             :remembered?  (boolean remembered)})
                          (:commands d))
       :background? (boolean (:background d))})))

(defn subagent-chat-id
  "The chat id of the sub-agent a tool call runs (its `subagent` details), or nil."
  [content]
  (let [d (:details content)]
    (when (= "subagent" (:type d))
      (:subagentChatId d))))

(defn task-call?
  "The agent's task tool (its plan): shown as the task panel, not in the chat."
  [content]
  (and (= "eca" (:server content)) (= "task" (:name content))))

(defn content->tasks
  "Normalize the task tool's `task` details: `{:tasks [{:id :subject :status
  :priority :blocked? :blocked-by}] :active-summary}`, or nil (a failed or
  rejected call carries none)."
  [content]
  (let [d (:details content)]
    (when (= "task" (:type d))
      (cond-> {:tasks (mapv (fn [{:keys [id subject status priority isBlocked blockedBy]}]
                              {:id id :subject subject :status status :priority priority
                               :blocked? (boolean isBlocked) :blocked-by (vec blockedBy)})
                            (:tasks d))}
        (:activeSummary d) (assoc :active-summary (:activeSummary d))))))

(defn- args-text [arguments]
  (when arguments
    (try (json/generate-string arguments)
         (catch Exception _ (pr-str arguments)))))

(def ^:private tool-states
  {"toolCallPrepare"  :preparing
   "toolCallRun"      :run
   "toolCallRunning"  :running
   "toolCalled"       :called
   "toolCallRejected" :rejected})

(defn- tool-fields
  "Tool-call item fields carried by one lifecycle event. Fields the event
  doesn't carry are left out, so a later event never blanks an earlier one's."
  [content]
  (let [{:keys [type id name server summary arguments]} content
        edit  (content->edit content)
        shell (content->shell content)
        sub   (subagent-chat-id content)]
    (cond-> {:id id :name name :server server :state (tool-states type)}
      summary               (assoc :summary summary)
      arguments             (assoc :arguments arguments :args-text (args-text arguments))
      edit                  (assoc :edit edit)
      shell                 (assoc :shell shell)
      sub                   (assoc :subagent-chat-id sub)
      (= "toolCalled" type) (assoc :error? (:error content)
                                   :out-text (tool-output-text content)
                                   :total-ms (:totalTimeMs content)))))

(defn content->item [params]
  (let [content (:content params)]
    (case (:type content)
      "text"
      {:type :assistant-text :text (:text content)}

      ("toolCallPrepare" "toolCallRunning" "toolCallRun" "toolCalled" "toolCallRejected")
      (assoc (tool-fields content) :type :tool-call :expanded? false :focused? false)

      "reasonStarted"
      {:type :thinking :id (:id content) :text "" :status :thinking
       :expanded? false :focused? false}

      nil)))

(defn- update-by-id
  "Apply `f` to the last item in `items` with this `type` and `id`."
  [items type id f]
  (if-let [i (last (keep-indexed #(when (and (= type (:type %2)) (= id (:id %2))) %1) items))]
    (update items i f)
    items))

(defn add-sub-content
  "Fold one content event of a sub-agent chat into the spawn call's
  sub-items: text chunks extend the last text item, a tool call is a single
  item updated through its lifecycle, thoughts accumulate their text."
  [subs params]
  (let [subs    (vec subs)
        content (:content params)]
    (case (:type content)
      "text"
      (let [prev (peek subs)
            role (:role params)]
        (if (and (= :assistant-text (:type prev)) (= role (:role prev)))
          (conj (pop subs) (update prev :text str (:text content)))
          (conj subs {:type :assistant-text :role role :text (str (:text content))})))

      "reasonText"
      (update-by-id subs :thinking (:id content) #(update % :text str (:text content)))

      "reasonFinished"
      (update-by-id subs :thinking (:id content)
                    #(assoc % :status :thought :total-ms (:totalTimeMs content)))

      (if-let [item (content->item params)]
        (if (and (= :tool-call (:type item))
                 (some #(and (= :tool-call (:type %)) (= (:id item) (:id %))) subs))
          (update-by-id subs :tool-call (:id item) #(merge % (dissoc item :expanded? :focused?)))
          (conj subs item))
        subs))))

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

;; --- Tool-call approvals ---
;;
;; The server decides which calls need the user (`manualApproval`), trust mode
;; and remembered approvals included. Calls can wait in parallel, from the main
;; chat or a sub-agent: one is shown (`:pending-approval`), the others wait in
;; `:approval-queue`. Each is answered with the chat id it came from.

(defn enter-asking
  "Ask the first pending question (`eca-cli.question`): it takes over the
  input, which keeps what the user was typing for afterwards."
  [state]
  (if (= :asking (:mode state))
    state
    (-> state
        (assoc :mode :asking :question-cursor 0 :input-draft (ti/value (:input state)))
        (update :input #(-> % ti/reset ti/focus)))))

(defn settle-mode
  "After an approval or a question was answered, from :approving or
  :asking: the next thing waiting for the user, else the running turn
  (:chatting) or, when it's over, :ready."
  [state]
  (cond
    (not (#{:approving :asking} (:mode state))) state
    (:pending-approval state)                   (assoc state :mode :approving)
    (seq (:questions state))                    (enter-asking state)
    :else
    (let [mode (if (:turn-started-at state) :chatting :ready)]
      (cond-> (assoc state :mode mode)
        (contains? state :input-draft) (-> (update :input ti/set-value (:input-draft state))
                                           (dissoc :input-draft))
        ;; Live while the agent works too, for steering.
        :always                        (update :input ti/focus)))))

(defn request-approval
  "Ask the user to approve tool call `id` of chat `chat-id`, now or after
  what's already waiting. A question being answered isn't interrupted."
  [state chat-id id]
  (let [req {:chat-id chat-id :tool-call-id id}]
    (cond
      (= id (get-in state [:pending-approval :tool-call-id]))  state
      (some #(= id (:tool-call-id %)) (:approval-queue state)) state
      (:pending-approval state) (update state :approval-queue (fnil conj []) req)
      :else (cond-> (assoc state :pending-approval req)
              (not= :asking (:mode state)) (assoc :mode :approving)))))

(defn next-approval
  "The shown approval was answered: show the next waiting one, else whatever
  waits next (see `settle-mode`)."
  [state]
  (settle-mode
    (if-let [[req & more] (seq (:approval-queue state))]
      (assoc state :pending-approval req :approval-queue (vec more))
      (assoc state :pending-approval nil :approval-queue []))))

(defn- settle-approval
  "Tool call `id` is past its approval (it runs, finished or was rejected,
  e.g. answered elsewhere or stopped): stop asking about it."
  [state id]
  (if (= id (get-in state [:pending-approval :tool-call-id]))
    (next-approval state)
    (update state :approval-queue #(vec (remove (fn [req] (= id (:tool-call-id req))) %)))))

(defn clear-approvals [state]
  (assoc state :pending-approval nil :approval-queue []))

(defn- track-approval
  "Approval bookkeeping for a tool-call event of chat `chat-id` (the main chat
  or a sub-agent's)."
  [state chat-id content]
  (case (:type content)
    "toolCallRun"
    (if (:manualApproval content)
      (-> state
          (update-in [:tool-calls (:id content)] merge (tool-fields content))
          (request-approval chat-id (:id content)))
      state)

    ("toolCallRunning" "toolCalled" "toolCallRejected")
    (settle-approval state (:id content))

    state))



;; --- Outbound prompt ---
;;
;; `@label` marks a context the user picked (the `@` picker), `#label` a
;; path for the LLM to read (the `#` picker); labels are relative to the
;; workspace where they can be (`picker/at-label`). As eca-emacs does, the
;; text sent spells them out: `@/full/path` (which the server also reads as
;; a context) and, for `#`, the bare full path. Ones typed by hand, or
;; recalled from the history, count too when they name a workspace file.

(def ^:private ref-re
  ;; `@token` or `#token`, preceded by start-of-string or whitespace. The
  ;; `@` part matches view/blocks.clj's render-time regex.
  #"(^|\s)([@#])(\S+)")

(defn- refs-in
  "The `sigil` (`@` or `#`) tokens of `text`, in order, without the sigil."
  [text sigil]
  (into [] (comp (keep (fn [[_ _ s token]] (when (= sigil s) token))) (distinct))
        (re-seq ref-re (str text))))

(defn- existing-path
  "The full path of the file or directory `token` names (absolute, `~/…`,
  or relative to `workspace`), when there's one. Only a token that looks
  like a path (a `/` or a `.` in it) is looked up: pasted code's
  `#include` or `@Override` stays text even next to an `include/`."
  [token workspace]
  (when (re-find #"[/.]" token)
    (let [f (cond
              (str/starts-with? token "/")  (io/file token)
              (str/starts-with? token "~/") (io/file (paths/home) (subs token 2))
              workspace                     (io/file workspace token))]
      (when (and f (.exists ^java.io.File f))
        (.getCanonicalPath ^java.io.File f)))))

(defn- typed-context
  "The context a workspace-relative `@token` typed by hand names, if any.
  `@/abs`, `@~/…` and `@./…` ones the server reads from the text itself."
  [token workspace]
  (when-not (re-find #"^[/~.]" token)
    (when-let [path (existing-path token workspace)]
      {:type (if (.isDirectory (io/file path)) "directory" "file") :path path})))

(defn- contexts-for-text
  "The contexts `text` refers to: the picked ones (`contexts`) whose `@label`
  (see `picker/at-label`) it still has, so the ones deleted from the
  message are dropped, then the workspace files and directories it names
  with an `@` typed by hand."
  ([text contexts] (contexts-for-text text contexts nil))
  ([text contexts workspace]
   (let [tokens  (refs-in text "@")
         present (set tokens)
         picked  (filter #(present (picker/at-label % workspace)) contexts)
         labels  (set (map #(picker/at-label % workspace) picked))
         typed   (keep #(when-not (labels %) (typed-context % workspace)) tokens)]
     (vec (distinct (concat picked typed))))))

(defn- expand-refs
  "`text` as sent: the `@label`s of files and directories (`contexts`, or
  typed by hand) spelled out as `@/full/path`, `#path`s that exist as the
  bare full path. Anything else stays as typed."
  [text contexts workspace]
  (let [picked (into {} (keep (fn [ctx]
                                (when (:path ctx) [(picker/at-label ctx workspace) (:path ctx)])))
                     contexts)]
    (str/replace (str text) ref-re
                 (fn [[whole pre sigil token]]
                   (if-let [path (if (= "@" sigil)
                                   (or (get picked token) (:path (typed-context token workspace)))
                                   (existing-path token workspace))]
                     (str pre (when (= "@" sigil) "@") path)
                     whole)))))

(defn send-chat-prompt! [srv chat-id text opts]
  (protocol/chat-prompt!
    srv
    (cond-> {:message text}
      chat-id              (assoc :chat-id chat-id)
      (:model opts)        (assoc :model (:model opts))
      (:agent opts)        (assoc :agent (:agent opts))
      (:variant opts)      (assoc :variant (:variant opts))
      ;; Once known (--trust, /trust, the server's selectTrust), sent as is:
      ;; an explicit off must not let a new chat fall back to the user's
      ;; `chat.defaultTrust`. Unknown: left out, the server default applies.
      (some? (:trust opts)) (assoc :trust (boolean (:trust opts)))
      (seq (:contexts opts)) (assoc :contexts (:contexts opts)))
    (fn [result]
      (.put (:queue srv)
            {:type    :eca-prompt-response
             :chat-id (:chat-id result)
             :model   (:model result)
             :status  (:status result)}))))

(defn- start-turn
  "Send `text` as the user's next message and show it; the input is left
  alone. A new chat gets its id here, as the protocol recommends, so this
  chat's messages can be told apart from other chats' (e.g. one the server
  forks off)."
  [state text]
  (let [workspace (get-in state [:opts :workspace])
        contexts  (contexts-for-text text (:pending-contexts state) workspace)
        ;; What the server gets; the chat shows what was typed.
        message   (expand-refs text contexts workspace)
        chat-id   (or (:chat-id state) (str (random-uuid)))
        _         (when-not (:chat-id state)
                    (chats/save-chat-id! workspace chat-id))
        ;; Stashed on :opts so the login-retry path (login.clj) re-sends them
        ;; after authentication; dropped again by the next message.
        opts'     (if (seq contexts)
                    (assoc (:opts state) :contexts contexts)
                    (dissoc (:opts state) :contexts))]
    (send-chat-prompt! (:server state) chat-id message opts')
    (-> state
        ;; A replayed chat (resumed, forked) ends without a `finished`,
        ;; leaving its last reply in :current-text.
        flush-current-text
        (assoc :chat-id chat-id)
        (update :items conj {:type :user :text text})
        (assoc :mode :chatting :pending-message message :echo-pending true
               :turn-started-at (System/currentTimeMillis))
        (assoc :pending-contexts [])
        (assoc :opts opts')
        view/rebuild-lines)))

(defn submit-prompt
  "Send what the user typed, `text`, as their next message (slash commands
  included: the server runs its own). The input stays live, for steering."
  [state text]
  [(-> state
       (start-turn text)
       (update :input #(-> % ti/reset ti/focus))
       (update :input-history conj text)
       (assoc :history-idx nil))
   nil])

;; --- Steering & queue ---
;;
;; While the agent works, the input stays live. Enter steers the running
;; prompt (chat/promptSteer): the server injects the text at the next turn
;; boundary and echoes it as a user message, which clears :steered. Alt+Enter
;; queues a message for after the turn (:queued). Whatever wasn't consumed is
;; sent as the next prompt when the turn ends; Esc (stop) or ↑ on an empty
;; input gives it back to the input instead.

(defn- join-lines [& texts]
  (str/join "\n" (remove str/blank? texts)))

(defn pending-text
  "What was steered but not consumed yet, then what was queued; nil if none."
  [state]
  (not-empty (join-lines (:steered state) (:queued state))))

(defn- take-typed
  "Clear the input after its text was used, recording it in the history."
  [state text]
  (-> state
      (update :input ti/reset)
      (update :input-history conj text)
      (assoc :history-idx nil)))

(defn- steer [state text]
  (protocol/steer-prompt! (:server state) (:chat-id state) text)
  (-> state (update :steered join-lines text) (take-typed text)))

(defn- queue-message [state text]
  (-> state (update :queued join-lines text) (take-typed text)))

(defn take-back-pending
  "Give the unsent steer and queued text back to the input, in front of
  whatever is typed, and withdraw the steer from the server."
  [state]
  (if-let [text (pending-text state)]
    (do (when (:steered state)
          (protocol/steer-prompt-remove! (:server state) (:chat-id state)))
        (-> state
            (dissoc :steered :queued)
            (update :input #(ti/set-value % (join-lines text (ti/value %))))))
    state))

(defn- send-pending
  "The turn ended: send what was steered too late or queued as the next
  prompt (not while a question still waits for an answer)."
  [state]
  (if-let [text (and (empty? (:questions state)) (pending-text state))]
    (start-turn (dissoc state :steered :queued) text)
    state))


(def ^:private image-exts
  {"image/png" "png" "image/jpeg" "jpg" "image/gif" "gif" "image/webp" "webp" "image/svg+xml" "svg"})

(defn save-image
  "An `image` content's bytes (base64) written to a temp file named after
  them (so a replay doesn't write it again), as an :image item: terminals
  can't show it inline, the user can open the file."
  [{:keys [mediaType base64]}]
  (try
    (let [bytes (.decode (java.util.Base64/getMimeDecoder) (str base64))
          _     (when (zero? (alength bytes)) (throw (ex-info "no image data" {})))
          hash  (->> (.digest (java.security.MessageDigest/getInstance "SHA-1") bytes)
                     (map #(format "%02x" %)) (apply str))
          dir   (java.io.File. (System/getProperty "java.io.tmpdir") "eca-cli-images")
          file  (java.io.File. dir (str (subs hash 0 16) "." (get image-exts mediaType "img")))]
      (when-not (.exists file)
        (.mkdirs dir)
        (with-open [out (java.io.FileOutputStream. file)] (.write out bytes)))
      {:type :image :media-type mediaType :bytes (alength bytes) :path (str file)})
    (catch Exception e
      {:type :image :media-type mediaType :error (or (ex-message e) (str e))})))

(defn handle-status-changed
  "`chat/statusChanged` for the chat on screen: a turn the server runs shows
  as running; :stopping? while a stop winds down."
  [state {:keys [chatId status]}]
  (if (and (:chat-id state) (not= chatId (:chat-id state)))
    state
    (case status
      "running"  (cond-> (dissoc state :stopping?)
                   (nil? (:turn-started-at state)) (assoc :turn-started-at (System/currentTimeMillis))
                   (= :ready (:mode state))        (assoc :mode :chatting))
      "stopping" (assoc state :stopping? true)
      "idle"     (dissoc state :stopping?)
      state)))

;; --- ECA content handler ---

(defn- finish-turn
  "Record how long the turn took (status line) once the prompt finished."
  [state]
  (if-let [t0 (:turn-started-at state)]
    (-> state
        (assoc :last-turn-ms (- (System/currentTimeMillis) t0))
        (dissoc :turn-started-at))
    state))

(defn handle-content [state params]
  (let [content (:content params)]
    (case (:type content)
      "text"
      (-> state
          (update :current-text str (:text content))
          view/rebuild-lines)

      "progress"
      (case (:state content)
        "finished"
        (-> state
            flush-current-text
            finish-turn
            clear-approvals
            (assoc :echo-pending false)
            ;; A question can outlive the turn (the server's /login asks
            ;; in the background): still ask it.
            (as-> s (if (seq (:questions s))
                      (enter-asking s)
                      (-> s (assoc :mode :ready) (update :input ti/focus))))
            send-pending
            view/rebuild-lines)

        ;; Also a turn the server started itself (e.g. in a chat it forked
        ;; for /btw): show it as running.
        "running"
        (cond-> state
          (nil? (:turn-started-at state)) (assoc :turn-started-at (System/currentTimeMillis))
          (= :ready (:mode state))        (assoc :mode :chatting))

        state)

      ;; One path for the whole lifecycle. The server already applied trust
      ;; and remembered approvals: only `manualApproval` calls ask the user.
      ("toolCallPrepare" "toolCallRun" "toolCallRunning" "toolCalled" "toolCallRejected")
      (let [tool    (tool-fields content)
            sub     (:subagent-chat-id tool)
            chat-id (or (:chatId params) (:chat-id state))]
        (if (task-call? content)
          ;; The plan goes to the task panel (still approvable when asked).
          (let [tasks (content->tasks content)]
            (-> state
                (update-in [:tool-calls (:id tool)] merge tool)
                (assoc :task-loading? (contains? #{"toolCallPrepare" "toolCallRun" "toolCallRunning"}
                                                 (:type content)))
                (cond-> tasks (assoc :tasks tasks))
                (track-approval chat-id content)))
          (-> state
              flush-current-text
              (upsert-tool-call tool)
              (cond-> sub (register-subagent (:id tool) sub))
              (track-approval chat-id content)
              view/rebuild-lines)))

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
                             (assoc % :status :thought :total-ms (:totalTimeMs content))
                             %)
                          items)))
          view/rebuild-lines)

      "url"
      (-> state
          flush-current-text
          (update :items conj {:type :url :title (:title content) :url (:url content)})
          view/rebuild-lines)

      ;; The chat's (new) title, e.g. after /rename.
      "metadata"
      (cond-> state (:title content) (assoc :chat-title (:title content)))

      ;; An image the agent made: saved to a file the user can open.
      "image"
      (-> state
          flush-current-text
          (update :items conj (save-image content))
          view/rebuild-lines)

      ;; A checkpoint the user set (/flag); see eca-cli.history.
      "flag"
      (-> state
          flush-current-text
          (update :items conj {:type :flag :text (str (:text content)) :content-id (:contentId content)})
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
  variants, shown on the welcome too. The server's welcome message is the
  welcome box's job, unless the user wrote their own: then it follows."
  [state notification]
  (let [{:keys [chat chatId]} (:params notification)
        ;; Per-chat fields scoped to another chat don't concern this one.
        mine? (or (nil? chatId) (nil? (:chat-id state)) (= chatId (:chat-id state)))
        own-welcome (when (welcome/custom-message? (:welcomeMessage chat)) (:welcomeMessage chat))
        s'    (cond-> state
                (:models chat)                  (assoc :available-models (:models chat))
                (:agents chat)                  (assoc :available-agents (:agents chat))
                (and mine? (contains? chat :selectModel))   (assoc :selected-model (:selectModel chat))
                (and mine? (contains? chat :selectAgent))   (assoc :selected-agent (:selectAgent chat))
                (contains? chat :variants)      (assoc :available-variants (:variants chat))
                (and mine? (contains? chat :selectVariant)) (-> (assoc :selected-variant (:selectVariant chat))
                                                                (assoc-in [:opts :variant] (:selectVariant chat)))
                (and mine? (contains? chat :selectTrust))   (-> (assoc :trust (true? (:selectTrust chat)))
                                                                (assoc-in [:opts :trust] (true? (:selectTrust chat))))
                own-welcome                     (update :items conj {:type :assistant-text :text own-welcome}))]
    [(cond-> (welcome/refresh s') own-welcome view/rebuild-lines) nil]))

(defn- this-chat?
  "Whether a message about `chat-id` concerns the chat on screen (any chat
  when none is yet)."
  [state chat-id]
  (or (nil? (:chat-id state)) (nil? chat-id) (= chat-id (:chat-id state))))

(defn- clear-chat-view [state]
  (-> state
      (assoc :items [] :current-text "" :chat-lines [] :scroll-offset 0
             :subagent-chats {} :tool-calls {} :focus-path nil)
      (dissoc :tasks :task-loading?)
      clear-approvals))

(defn switch-to-chat
  "Show chat `chat-id` from now on, e.g. one the server forked off this one
  (`/fork`, `/btw`): its messages follow. Remembered for the workspace like
  any chat."
  [state chat-id title]
  (chats/save-chat-id! (get-in state [:opts :workspace]) chat-id)
  (-> state
      clear-chat-view
      (assoc :chat-id chat-id :chat-title title :mode :ready :echo-pending false)
      (dissoc :turn-started-at)
      (update :input ti/focus)
      view/rebuild-lines))

(defn handle-chat-opened
  "ECA `chat/opened`: the chat on screen got its id or title, or the server
  opened another chat for us (forked off this one), which is then shown."
  [state notification]
  (let [{:keys [chatId title]} (:params notification)]
    [(if (or (this-chat? state chatId) (contains? (:subagent-chats state) chatId))
       (cond-> (assoc state :chat-id (or (:chat-id state) chatId))
         title (assoc :chat-title title))
       (switch-to-chat state chatId title))
     nil]))

(defn handle-chat-cleared
  "ECA `chat/cleared` notification: when params.messages is truthy, drop
  rendered items + chat-lines + scroll position."
  [state notification]
  (let [{:keys [chatId messages]} (:params notification)]
    [(cond-> state
       (and messages (this-chat? state chatId)) clear-chat-view)
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
        content (:content params)
        ;; A sub-agent of this chat whose spawn call didn't announce it (no
        ;; `subagent` details): adopt it into the latest spawn call without
        ;; one. Otherwise it's dropped below, like any other chat's content.
        state   (if (and (:parentChatId params)
                         (= (:parentChatId params) (:chat-id state))
                         (not (contains? (:subagent-chats state) (:chatId params))))
                  (if-let [idx (last (keep-indexed #(when (and (= :tool-call (:type %2)) (spawn-call? %2)
                                                               (nil? (:subagent-chat-id %2)))
                                                      %1)
                                                   (:items state)))]
                    (-> state
                        (assoc-in [:subagent-chats (:chatId params)] idx)
                        (assoc-in [:items idx :subagent-chat-id] (:chatId params)))
                    state)
                  state)]
    (cond
      (get (:subagent-chats state) (:chatId params))
      [(-> state
           (update-in [:items (get (:subagent-chats state) (:chatId params)) :sub-items]
                      add-sub-content params)
           (track-approval (:chatId params) content)
           view/rebuild-lines)
       nil]

      ;; Another chat (e.g. the one this was forked from): not on screen.
      (not (this-chat? state (:chatId params)))
      [state nil]

      :else
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

            ;; Our own message, already shown: just learn its id (rollback).
            (:echo-pending state)
            [(-> state
                 (assoc :echo-pending false)
                 (update :items (fn [items]
                                  (if-let [i (last (keep-indexed #(when (and (= :user (:type %2))
                                                                             (nil? (:content-id %2)))
                                                                    %1)
                                                                 items))]
                                    (cond-> items
                                      (:contentId content) (assoc-in [i :content-id] (:contentId content)))
                                    items))))
             nil]

            ;; A replayed message, or the steer the server just injected
            ;; into the running turn (all of it: steers add up).
            :else
            [(-> state
                 flush-current-text
                 (dissoc :steered)
                 (update :items conj (cond-> {:type :user :text (or (:text content) "")}
                                       (:contentId content) (assoc :content-id (:contentId content))))
                 view/rebuild-lines)
             nil])
          [state nil])
        [(handle-content state params) nil]))))

;; --- Approval key dispatch (:approving mode) ---

(defn handle-approval-key
  "y approve · Y approve & remember (server-side, for the chat and its
  sub-agents; just the listed commands for shell calls) · n reject. Caller
  guarantees mode = :approving."
  [state msg]
  (let [{:keys [chat-id tool-call-id]} (:pending-approval state)
        press? #(and (msg/key-press? msg) (msg/key-match? msg %))]
    (cond
      (press? "y")
      (do (protocol/approve-tool! (:server state) chat-id tool-call-id)
          [(next-approval state) nil])

      ;; Nothing left to remember (e.g. only always-ask commands): a plain approve.
      (press? "Y")
      (let [save (when-not (= [] (blocks/remember-keys (get-in state [:tool-calls tool-call-id])))
                   "session")]
        (protocol/approve-tool! (:server state) chat-id tool-call-id save)
        [(next-approval state) nil])

      (press? "n")
      (do (protocol/reject-tool! (:server state) chat-id tool-call-id)
          [(next-approval state) nil])

      :else [state nil])))

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
      (submit-prompt state text)
      [state nil])))

(defn cancel-turn
  "Stop the running prompt (Esc, Ctrl+C); unsent steer and queued text go
  back to the input rather than being sent."
  [state]
  (when (:chat-id state)
    (protocol/stop-prompt! (:server state) (:chat-id state)))
  (-> state take-back-pending (assoc :mode :ready) (update :input ti/focus)))

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

(defn alt-enter?
  "Alt+Enter. Most terminals send it as ESC CR, which charm reads as an
  Alt'ed \"\\r\" character rather than :enter."
  [msg]
  (and (msg/key-press? msg)
       (or (msg/key-match? msg "alt+enter")
           (and (:alt msg) (contains? #{"\r" "\n"} (:key msg))))))

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

    ;; The task panel's checklist.
    (and (msg/key-press? msg) (msg/key-match? msg "ctrl+t"))
    [(update state :tasks-expanded? not) nil]

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
    (and (or (and (msg/key-press? msg) (msg/key-match? msg :enter)) (alt-enter? msg))
         (= :ready (:mode state)))
    (enter-submit-prompt state)

    ;; --- While the agent works: Enter steers, Alt+Enter queues, ↑ on an
    ;; empty input takes them back for editing ---
    ;; (Alt+Enter first: matching :enter ignores the modifier.)
    (and (alt-enter? msg) (= :chatting (:mode state)))
    (let [text (str/trim (ti/value (:input state)))]
      [(if (seq text) (queue-message state text) state) nil])

    (and (msg/key-press? msg) (msg/key-match? msg :enter) (= :chatting (:mode state)))
    (let [text (str/trim (ti/value (:input state)))]
      [(if (seq text) (steer state text) state) nil])

    (and (msg/key-press? msg) (msg/key-match? msg :up) (= :chatting (:mode state))
         (str/blank? (ti/value (:input state))) (pending-text state))
    [(take-back-pending state) nil]

    ;; --- Escape during :chatting cancels the in-flight prompt ---
    (and (msg/key-press? msg) (msg/key-match? msg :escape) (= :chatting (:mode state)))
    [(cancel-turn state) nil]

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
