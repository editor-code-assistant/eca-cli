(ns eca-cli.view
  (:require [clojure.string :as str]
            [charm.ansi.width :as aw]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [eca-cli.mcp :as mcp]
            [eca-cli.paths :as paths]
            [eca-cli.theme :as theme]
            [eca-cli.view.blocks :as blocks]
            [eca-cli.view.status :as status]))

;; Lazy resolution to break the jobs ↔ view circular require.
(defn- jobs-fn [sym]
  (requiring-resolve (symbol "eca-cli.jobs" (name sym))))

(defn divider
  ([width] (divider width :border))
  ([width role]
   (theme/paint role (apply str (repeat width "─")))))

;; --- Chat lines ---

(def ^:private render-cache-max 2000)

(defonce ^:private render-cache (atom {}))

(defn- render-item
  "Rendered lines of an item. Items are immutable values, so finished ones are
  memoized by value and width: markdown is only re-rendered for the message
  still streaming in, not for the whole chat on every chunk."
  [item width]
  (if (= :streaming-text (:type item))
    (blocks/render-item-lines item width)
    (let [k [item width]]
      (or (get @render-cache k)
          (let [lines (blocks/render-item-lines item width)]
            (swap! render-cache #(assoc (if (>= (count %) render-cache-max) {} %) k lines))
            lines)))))

(defn- compact?
  "One-line activity rows (collapsed tool calls, thoughts, hooks) stack without
  blank lines between them."
  [item]
  (and (#{:tool-call :thinking :hook} (:type item)) (not (:expanded? item))))

(defn rebuild-chat-lines
  "Returns {:lines vec :spans {idx [start end]}} where spans key is the index
  of the source item in `items` (top-level only; streaming-text is excluded)."
  [items current-text width]
  (let [n-items (count items)
        all     (vec (concat items
                             (when (seq current-text)
                               [{:type :streaming-text :text current-text}])))]
    (reduce
      (fn [{:keys [lines spans]} [idx item]]
        (let [rendered (render-item item width)
              next-it  (get all (inc idx))
              block    (if (or (= :user (:type item))
                               (and (compact? item) next-it (compact? next-it)))
                         rendered
                         (conj (vec rendered) ""))
              start    (count lines)
              end      (+ start (count block))]
          {:lines (into lines block)
           :spans (if (< idx n-items)
                    (assoc spans idx [start end])
                    spans)}))
      {:lines [] :spans {}}
      (map-indexed vector all))))

(def ^:dynamic *rebuild-deferred*
  "Bound to a volatile while a batch of server messages is processed: each
  `rebuild-lines` then just flags it, and the batch rebuilds once at the end
  (see `eca-cli.state`). A stream of text chunks costs one render, not one per
  chunk."
  nil)

(defn rebuild-lines
  "Rebuild a state's :chat-lines + :chat-line-spans from :items / :current-text / :width.
  Lives here (leaf util) because every feature ns calls it."
  [state]
  (if-let [pending *rebuild-deferred*]
    (do (vreset! pending true) state)
    (let [{:keys [lines spans]} (rebuild-chat-lines (:items state) (:current-text state) (:width state))]
      (assoc state :chat-lines lines :chat-line-spans spans))))

(defn- pad-to-height [lines height]
  (let [n (count lines)]
    (if (>= n height)
      lines
      (into (vec (repeat (- height n) "")) lines))))

(defn render-chat
  "The chat lines on screen: `visible-height` of them (the screen minus the
  rest of the layout), shifted up by :scroll-offset."
  ([state] (render-chat state (- (:height state) 5)))
  ([state visible-height]
   (let [visible-height (max 1 visible-height)
         lines          (:chat-lines state)
         total          (count lines)
         offset         (:scroll-offset state)
         end            (max 0 (- total offset))
         start          (max 0 (- end visible-height))
         visible        (pad-to-height (subvec lines start end) visible-height)]
     (str/join "\n" visible))))

(def ^:private spinner-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- spinner-frame [now]
  (nth spinner-frames (mod (quot now 120) (count spinner-frames))))

(defn render-connecting
  "Gutter line while the server hasn't answered `initialize` yet. That's
  normally instant, but config commands (e.g. fetching a provider token) can
  hold it up, so show the wait and, when it drags on, where to look."
  [state now]
  (let [secs (quot (- now (or (:connecting-since state) now)) 1000)
        log  (some-> (get-in state [:server :log-file]) paths/home-relative)]
    (str (theme/paint :accent (spinner-frame now)) " "
         (theme/paint :muted (str "Connecting to ECA server…"
                                  (when (>= secs 3) (str " " secs "s"))
                                  (when (and log (>= secs 15)) (str "  (taking long? see " log ")")))))))

(defn- activity
  "What the agent is doing, from the tail of the chat."
  [state]
  (let [last-item (peek (:items state))]
    (cond
      (seq (:current-text state))                                       "Writing…"
      (and (= :thinking (:type last-item)) (= :thinking (:status last-item))) "Thinking…"
      (and (= :tool-call (:type last-item))
           (#{:preparing :running} (:state last-item)))                 "Running tools…"
      :else                                                             "Working…")))

(declare clip)

(defn render-working
  "Gutter line during a turn: spinner, what's happening, for how long, and
  what the keys do now (steer, queue, stop)."
  [state now]
  (let [elapsed (some->> (:turn-started-at state) (- now) (max 0))
        head    (str (spinner-frame now) " " (activity state)
                     (when elapsed (str " " (or (blocks/format-duration elapsed) (str (quot elapsed 1000) "s")))))
        hint    (cond
                  (seq (str/trim (str (ti/value (:input state)))))
                  "enter to steer · alt+enter to queue · esc to interrupt"
                  (or (:steered state) (:queued state)) "↑ to edit · esc to interrupt"
                  :else                                 "esc to interrupt")
        room    (- (:width state 80) (theme/width head) 2)]
    (str (theme/paint :accent (spinner-frame now)) " "
         (theme/paint :bold (activity state))
         (when elapsed
           (theme/paint :muted (str " " (or (blocks/format-duration elapsed) (str (quot elapsed 1000) "s")))))
         (when (> room 8) (theme/paint :subtle (str "  " (clip hint room)))))))

(def ^:private max-task-rows 10)

(defn task-panel-lines
  "The agent's plan (its task tool), above the prompt as in eca-emacs: a line
  with what it's on and the progress, and the checklist under it when
  unfolded (Ctrl+T). `Creating tasks…` while the first plan is on its way."
  [state]
  (let [{:keys [tasks active-summary]} (:tasks state)
        width (:width state 80)]
    (cond
      (seq tasks)
      (let [done     (count (filter #(= "done" (:status %)) tasks))
            doing    (first (filter #(= "in-progress" (:status %)) tasks))
            label    (or active-summary (:subject doing) "")
            prefix   (cond active-summary "Task: " (seq label) "Tasks " :else "Tasks")
            progress (str " (" done "/" (count tasks) ")")
            open?    (:tasks-expanded? state)
            fixed    (+ 2 (count prefix) (count progress))
            ;; The key hint goes first when the label would be squeezed.
            key-hint (let [h (if open? "  ctrl+t to fold" "  ctrl+t for all")]
                       (if (>= (- width fixed (count h)) 12) h ""))
            room     (- width fixed (count key-hint))
            head     (str (theme/paint :accent "≡ ")
                          (theme/paint :bold prefix)
                          (when (pos? room) (theme/paint (when doing :accent) (clip label room)))
                          (theme/paint :muted progress)
                          (theme/paint :subtle key-hint))
            ids?     (some #(seq (:blocked-by %)) tasks)
            row      (fn [{:keys [id subject status priority blocked? blocked-by]}]
                       (let [[icon role] (case status
                                           "done"        ["✓" :task-done]
                                           "in-progress" ["◐" :accent-bold]
                                           [(if blocked? "◌" "○") (when blocked? :muted)])
                             num   (when ids? (str "#" id " "))
                             note  (str (when (= "high" priority) " !")
                                        (when (and blocked? (seq blocked-by))
                                          (str "  waits for " (str/join ", " (map #(str "#" %) blocked-by)))))
                             room  (- width 4 (count num) (count note))]
                         (str "  " (theme/paint role icon) " "
                              (some->> num (theme/paint :subtle))
                              (theme/paint role (clip (str subject) (max 1 room)))
                              (theme/paint (if (= "high" priority) :warning :muted) note))))
            rows     (when open? (map row tasks))]
        (into [head]
              (if (> (count rows) max-task-rows)
                (concat (take max-task-rows rows)
                        [(theme/paint :muted (str "  … " (- (count rows) max-task-rows) " more"))])
                rows)))

      (:task-loading? state)
      [(str (theme/paint :accent "≡ ") (theme/paint :muted "Creating tasks…"))]

      :else [])))

(defn- task-block [state]
  (apply str (map #(str % "\n") (task-panel-lines state))))

(defn- pending-lines
  "`Steering: …` and `Queued: …` lines, above the prompt while the agent works."
  [state]
  (let [width (:width state 80)
        line  (fn [label role text]
                (str (theme/paint role label)
                     (theme/paint :muted (clip (str/replace text #"\s+" " ") (max 1 (- width (count label)))))
                     "\n"))]
    (str (some->> (:steered state) (line "Steering: " :warning))
         (some->> (:queued state) (line "Queued: " :accent)))))

(def disconnected-hint "ECA server is not running. Press Ctrl+C to exit.")

(defn- clip
  "Plain `s` cut to `w` columns with an ellipsis."
  [s w]
  (if (> (theme/width s) w) (aw/truncate s (max 1 w) :tail "…") s))

(defn render-approval
  "Render the pending tool call through the shared block renderer, force-expanded
  (so a file edit shows its diff), followed by the `[y/n/Y]` prompt line. One
  block renderer, two mount points — the diff can never drift between the
  approval surface and the completed block."
  [state]
  (when-let [{:keys [tool-call-id]} (:pending-approval state)]
    (let [tool     (get-in state [:tool-calls tool-call-id])
          width    (:width state)
          item     (assoc tool :type :tool-call :expanded? true :approval? true)
          lines    (blocks/render-item-lines item width)
          key      (fn [k role]
                     (str (theme/paint :key-desc "[") (theme/paint role k) (theme/paint :key-desc "]")))
          remember (blocks/remember-keys tool)
          base     (str (key "y" (theme/merge-styles :success :bold)) " " (theme/paint :key-desc "approve")
                        "  " (key "n" (theme/merge-styles :error :bold)) " " (theme/paint :key-desc "reject"))
          ;; Shell calls remember just their commands; nothing left to remember, no Y.
          y-desc   (cond (nil? remember) "allow for this chat"
                         (seq remember)  (str "remember " (str/join ", " remember)))
          room     (- width (theme/width base) 6)]
      (str (str/join "\n" lines)
           "\n" base
           (when (and y-desc (> room 8))
             (str "  " (key "Y" (theme/merge-styles :success :bold)) " "
                  (theme/paint :key-desc (clip y-desc room))))))))

(defn- prompt-input
  "The prompt line: the `> ` prefix in the prompt colour, then the text."
  [input]
  (str (theme/paint :prompt (:prompt input)) (ti/text-input-view (assoc input :prompt ""))))

(defn render-question
  "The input area while the server asks a question (`eca-cli.question`): the
  question, its options (the highlighted one picked by Enter), how to answer,
  then the prompt when a free answer is allowed."
  [state]
  (when-let [{:keys [question options allow-freeform?]} (first (:questions state))]
    (let [width  (:width state 80)
          cursor (:question-cursor state 0)
          more   (dec (count (:questions state)))
          n      (count options)
          ;; "❯ " + "N. " + label, labels in one column, descriptions after.
          label-w (max 1 (min (- width 8) (reduce max 0 (map #(theme/width (:label %)) options))))
          rows    (map-indexed
                    (fn [i {:keys [label description]}]
                      (let [sel? (= i cursor)
                            num  (str (inc i) ". ")
                            room (- width 2 (count num) label-w 2)]
                        (str (if sel? (theme/paint :accent-bold "❯ ") "  ")
                             (theme/paint :key-desc num)
                             (theme/paint (if sel? :accent-bold :bold) (theme/pad-right (clip label label-w) label-w))
                             (when (and (seq description) (> room 3))
                               (theme/paint :muted (str "  " (clip description room)))))))
                    options)
          hint   (str (cond
                        (and (pos? n) allow-freeform?) "↑↓ Enter to pick, or type an answer"
                        (pos? n)                       (str "↑↓ Enter or 1-" (min 9 n) " to pick")
                        :else                          "type your answer, Enter to send")
                      " · Esc cancels"
                      (when (pos? more) (str " · " more " more question" (when (> more 1) "s"))))]
      (str/join "\n"
                (concat (blocks/prefixed (blocks/question-lines question (- width 2))
                                         (str (theme/paint :accent-bold "?") " ") "  ")
                        rows
                        [(theme/paint :muted (clip hint width))]
                        (when allow-freeform? [(prompt-input (:input state))]))))))

(defn command-hint
  "While the input starts with a server command that takes arguments, its
  signature (`<required> [optional]`) and description."
  [state]
  (when-let [[_ name] (re-find #"^/([\w:.-]+)(?:\s|$)" (str (ti/value (:input state))))]
    (when-let [{:keys [arguments description]} (some #(when (= name (:name %)) %) (:server-commands state))]
      (when (seq arguments)
        (let [sig (str/join " " (map (fn [{:keys [name required]}]
                                       (if required (str "<" name ">") (str "[" name "]")))
                                     arguments))]
          (str (theme/paint :accent (str "/" name)) " " (theme/paint :bold sig)
               (theme/paint :muted (clip (str "  " description)
                                         (max 1 (- (:width state 80) (theme/width (str "/" name " " sig)) 1))))))))))

(defn approval-gutter
  "`▲ Waiting for your approval`, saying when a sub-agent asks and how many
  more calls wait after this one."
  [state]
  (let [{:keys [chat-id]} (:pending-approval state)
        more (count (:approval-queue state))]
    (theme/paint :warning
                 (str "▲ Waiting for your approval"
                      (when (and chat-id (:chat-id state) (not= chat-id (:chat-id state)))
                        " · sub-agent")
                      (when (pos? more) (str " · " more " more"))))))

(defn- visible-rows
  "The slice of a charm list on screen, as [index item] pairs (charm keeps
  `:offset` in sync with the cursor)."
  [{:keys [items offset height]}]
  (let [items (vec items)
        start (if (pos? height) (min (or offset 0) (count items)) 0)
        end   (if (pos? height) (min (count items) (+ start height)) (count items))]
    (map vector (range start end) (subvec items start end))))

(defn- split-label
  "Command and chat picker labels carry a description after a separator:
  [main detail]."
  [kind label]
  (let [sep (case kind :command "  —  " :chat "  •  " nil)
        i   (when sep (str/index-of label sep))]
    (if i [(subs label 0 i) (subs label (+ i (count sep)))] [label nil])))

(defn render-list
  "Picker rows: the selected one marked and in the accent colour, details
  muted and aligned in a column. Rows never overflow: the detail is cut
  first, then the name."
  [kind lst width]
  (let [rows   (visible-rows lst)
        avail  (max 1 (- width 2))
        split  (map (fn [[i item]] [i (split-label kind (str (cl/item-title item)))]) rows)
        main-w (min avail (reduce max 0 (map (fn [[_ [main]]] (theme/width main)) split)))]
    (if (empty? rows)
      (theme/paint :muted "  (nothing matches)")
      (str/join "\n"
                (for [[i [main detail]] split
                      :let [selected? (= i (cl/selected-index lst))
                            role      (when selected? :accent-bold)
                            room      (- avail main-w 2)]]
                  (str (if selected? (theme/paint :accent-bold "❯ ") "  ")
                       (theme/paint role (theme/pad-right (clip main main-w) main-w))
                       (when (and detail (> room 3))
                         (theme/paint :muted (str "  " (clip detail room))))))))))

(defn- picker-header
  "`Select model  type to filter: query▏  3/12`."
  [title query lst]
  (let [n (count (:items lst))]
    (str (theme/paint :accent-bold title)
         (theme/paint :muted "  type to filter: ")
         (theme/paint :bold query) (theme/paint :accent "▏")
         (when (pos? n)
           (theme/paint :subtle (str "  " (inc (or (cl/selected-index lst) 0)) "/" n))))))

(defn- render-picker [state]
  (let [{:keys [kind query list]} (:picker state)
        width (:width state)]
    (cond
      (= :jobs kind)
      (case (get-in state [:jobs-view :kind])
        :output       ((jobs-fn 'render-output-popup-lines) state)
        :confirm-kill ((jobs-fn 'render-confirm-kill-lines) state)
        ((jobs-fn 'render-jobs-panel-lines) state))

      (= :mcp kind)
      (let [sel (cl/selected-index list)]
        (str (theme/paint :accent-bold "MCP servers")
             (theme/paint :muted "  Enter: connect  ·  Esc: close") "\n"
             (divider width) "\n"
             (str/join "\n" (map-indexed (fn [i row]
                                           (str (if (= i sel) (theme/paint :accent-bold "❯ ") "  ") row))
                                         (mcp/render-mcp-panel-lines state)))))

      :else
      (let [title (case kind
                    :model         "Select model"
                    :agent         "Select agent"
                    :chat          "Select chat"
                    :command       "Select command"
                    :at-file       "Select file"
                    :rollback      "Roll back to before"
                    :rollback-type "Roll back what"
                    :flags         "Select flag"
                    :flag-action   "Flag"
                    :timeline      "Jump to"
                    "Select item")]
        (str (picker-header title query list) "\n"
             (divider width) "\n"
             (render-list kind list width))))))

(defn render-status-bar [state]
  (status/render state))

(defn render-login [state]
  (let [{:keys [provider action field-idx]} (:login state)
        action-type (:action action)
        heading     (str "🔐 " (theme/paint :bold (str "Login required for " provider)))
        cancel      (theme/paint :muted "Esc to cancel")
        field       (nth (:fields action) (or field-idx 0) nil)
        url         (fn [label u] (str "  " (theme/paint :muted label) (theme/paint :md-link u)))]
    (case action-type
      "choose-method"
      (str/join "\n"
                (concat [(str heading (theme/paint :muted " · choose a method"))]
                        (map-indexed (fn [i m]
                                       (str "  " (theme/paint :accent-bold (str "[" (inc i) "]")) " " (:label m)))
                                     (:methods action))
                        [cancel]))

      "input"
      (str heading "\n"
           "Enter " (theme/paint :bold (:label field)) ":")

      "authorize"
      (str heading "\n"
           (:message action) "\n"
           (url "URL: " (:url action))
           (when field
             (str "\nEnter " (theme/paint :bold (:label field)) " after authorizing:")))

      "device-code"
      (str heading "\n"
           (:message action) "\n"
           (url "URL:  " (:url action)) "\n"
           "  " (theme/paint :muted "Code: ") (theme/paint (theme/merge-styles :warning :bold) (:code action)) "\n"
           (theme/paint :muted "Waiting for authorization… ") cancel)

      (str heading "  " cancel))))

(defn- login-input
  "The login field being typed, masked when it's a secret (API keys)."
  [state]
  (let [{:keys [action field-idx]} (:login state)
        field (nth (:fields action) (or field-idx 0) nil)]
    (prompt-input (cond-> (:input state)
                    (= "secret" (:type field)) (assoc :echo-mode :password :echo-char \•)))))

(defn view [state]
  (let [mode       (:mode state)
        now        (System/currentTimeMillis)
        input-area (cond
                     (= :approving mode)
                     (or (render-approval state) "")

                     (= :asking mode)
                     (or (render-question state) "")

                     (= :picking mode)
                     (render-picker state)

                     (= :chatting mode)
                     (str (task-block state) (pending-lines state) (prompt-input (:input state)))

                     (= :disconnected mode)
                     (str (theme/paint :error "✗ ") (theme/paint :muted disconnected-hint))

                     (= :login mode)
                     (let [action-type  (get-in state [:login :action :action])
                           needs-input? (or (= "input" action-type)
                                            (and (= "authorize" action-type)
                                                 (seq (get-in state [:login :action :fields]))))]
                       (if needs-input?
                         (str (render-login state) "\n" (login-input state))
                         (render-login state)))

                     :else
                     (str (task-block state) (prompt-input (:input state))))
        gutter     (case mode
                     :connecting (render-connecting state now)
                     :chatting   (render-working state now)
                     :approving  (approval-gutter state)
                     :asking     (theme/paint :accent "◆ Waiting for your answer")
                     :ready      (or (command-hint state) "")
                     "")
        rule-role  (case mode :approving :warning :asking :accent :disconnected :error :border)
        ;; Chat, gutter, rule, input area, rule, status line: the chat gets
        ;; what the (possibly multi-line) input area leaves.
        input-h    (count (str/split input-area #"\n" -1))]
    (str (render-chat state (- (:height state) 4 input-h))
         "\n" gutter
         "\n" (divider (:width state) rule-role)
         "\n" input-area
         "\n" (divider (:width state))
         "\n" (render-status-bar state))))