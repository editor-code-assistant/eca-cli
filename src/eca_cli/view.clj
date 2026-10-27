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

;; --- The terminal's history (inline mode) ---

(defn- separated?
  "Whether a blank line comes between `prev` and `item`: the spacing of
  `rebuild-chat-lines`, decided by the item before rather than the one after."
  [prev item]
  (boolean (and prev
                (not= :user (:type prev))
                (not (and (compact? prev) (compact? item))))))

(defn- unfocused
  "`item` without the focus highlight: the history keeps no selection."
  [item]
  (if (or (:focused? item) (some :focused? (:sub-items item)))
    (cond-> (assoc item :focused? false)
      (:sub-items item) (update :sub-items #(mapv (fn [s] (assoc s :focused? false)) %)))
    item))

(defn- item-range-lines
  "Render a range with leading separators; only printed history drops focus."
  [items from to width printed?]
  (let [items (vec items)]
    (into []
          (mapcat (fn [i]
                    (let [item (nth items i)
                          prev (when (pos? i) (nth items (dec i)))]
                      (concat (when (separated? prev item) [""])
                              (render-item (if printed? (unfocused item) item) width)))))
          (range from to))))

(defn history-lines
  "The lines of `items` `from`..`to` (exclusive) as they go to the
  terminal's history, without focus highlights. Leading separators keep
  the spacing identical to the full-screen chat."
  [items from to width]
  (item-range-lines items from to width true))

(defn- with-streaming
  "The chat's items, and the reply streaming in as the last one."
  [state]
  (cond-> (vec (:items state))
    (seq (:current-text state)) (conj {:type :streaming-text :text (:current-text state)})))

(defn fullscreen?
  "Whether the full-screen layout is on screen: started with --fullscreen,
  or the transcript (Ctrl+O) is open. Otherwise eca-cli draws inline, the
  chat going to the terminal's own history (`eca-cli.render`)."
  [state]
  (boolean (or (get-in state [:opts :fullscreen]) (:transcript? state))))

(defn- live-chat-lines
  "Inline: what of the chat isn't in the terminal's history yet (items still
  changing, the reply streaming in), then a blank line before the prompt,
  as in the full-screen chat."
  [state]
  (let [items (with-streaming state)
        n     (count items)
        from  (min n (or (:committed state) 0))]
    (into (item-range-lines items from n (:width state) false)
          (when (and (pos? n) (not= :user (:type (peek items)))) [""]))))

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

(defn- pad-to-height
  "`lines` filled up to `height` with blank lines below: a chat starts at the
  top of the screen, like Claude Code's and eca-emacs's, and once it fills
  the screen its newest lines are kept in view (`render-chat`)."
  [lines height]
  (let [n (count lines)]
    (if (>= n height)
      lines
      (into (vec lines) (repeat (- height n) "")))))

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

(defn approval-gutter
  "`▲ Waiting for your approval`, saying when a sub-agent asks, how many more
  calls wait after this one, and how long the turn has been going (the clock
  keeps running while it waits, as in eca-emacs)."
  ([state] (approval-gutter state (System/currentTimeMillis)))
  ([state now]
   (let [{:keys [chat-id]} (:pending-approval state)
         more    (count (:approval-queue state))
         elapsed (some->> (:turn-started-at state) (- now) (max 0) blocks/format-duration)]
     (theme/paint :warning
                  (str "▲ Waiting for your approval"
                       (when (and chat-id (:chat-id state) (not= chat-id (:chat-id state)))
                         " · sub-agent")
                       (when (pos? more) (str " · " more " more"))
                       (when elapsed (str " · " elapsed)))))))

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

(def disconnected-hint "ECA server is not running. Press Ctrl+C twice to exit.")

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

(def ^:private max-input-lines 8)

(defn- input-rows
  "The input's text (masked when it's a secret) as display rows of
  [index char] cells: broken at newlines and at `w` columns. A newline, and
  the end of the text, are cells too, where the cursor can sit."
  [text w]
  (let [n (count text)]
    (loop [i 0, row [], rows [], col 0]
      (cond
        (= i n)   (conj rows (conj row [n \space]))
        (>= col w) (recur i [] (conj rows row) 0)
        :else
        (let [c (.charAt ^String text i)]
          (if (= c \newline)
            (recur (inc i) [] (conj rows (conj row [i \space])) 0)
            (recur (inc i) (conj row [i c]) rows
                   (+ col (if (< (int c) 0x7f) 1 (max 1 (theme/width (str c))))))))))))

(defn- input-row-budget [state]
  ;; Gutter, two rules and status remain outside the input area. Other
  ;; panels may be tail-clipped, but the prompt's cursor row must survive.
  (max 1 (- (:height state 24) 4)))

(defn- prompt-input
  "The prompt: `> ` in the prompt colour, then the text wrapped to `width`
  (charm's input would run off the screen), the cursor in reverse video. A
  long text shows the rows around the cursor within the terminal's budget."
  ([input] (prompt-input input 80))
  ([input width] (prompt-input input width max-input-lines))
  ([input width row-budget]
   (let [row-budget (max 1 (min max-input-lines row-budget))
         value  (str (ti/value input))
         text   (if (= :password (:echo-mode input))
                  (apply str (repeat (count value) (or (:echo-char input) \•)))
                  value)
         pos    (min (count text) (max 0 (or (ti/position input) (count text))))
         rows   (input-rows text (max 4 (- width 3)))
         here   (or (first (keep-indexed (fn [r row] (when (some #(= pos (first %)) row) r)) rows)) 0)
         start  (max 0 (min (- here (dec row-budget)) (- (count rows) row-budget)))
         shown  (subvec rows start (min (count rows) (+ start row-budget)))
         cell   (fn [[i c]] (if (and (= i pos) (:focused input))
                              (theme/paint {:reverse true} (str c))
                              (str c)))]
     (str/join "\n"
               (map-indexed (fn [r row]
                              (str (if (and (zero? r) (zero? start))
                                     (theme/paint :prompt (:prompt input))
                                     "  ")
                                   (str/trimr (apply str (map cell row)))))
                            shown)))))

(defn render-question
  "The question input area, bounded to the space above the fixed chrome.
  Keep the selected option and free-answer cursor visible; window options
  and shorten only the displayed header when the complete panel won't fit."
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
                      (when (pos? more) (str " · " more " more question" (when (> more 1) "s"))))
          header (vec (blocks/prefixed (blocks/question-lines question (- width 2))
                                       (str (theme/paint :accent-bold "?") " ") "  "))
          budget (input-row-budget state)
          ;; Reserve a header, hint and selected option before allocating a
          ;; multi-line draft. prompt-input still windows around its cursor.
          prompt (when allow-freeform?
                   (prompt-input (:input state) width (max 1 (- budget 2 (if (pos? n) 1 0)))))
          prompt-lines (if prompt (str/split prompt #"\n" -1) [])
          room (- budget (count prompt-lines))
          option-min (if (and (pos? n) (pos? room)) 1 0)
          hint? (> room option-min)
          hint-rows (if hint? 1 0)
          header-min (if (> room (+ option-min hint-rows)) 1 0)
          option-count (min n (max 0 (- room hint-rows header-min)))
          header-count (min (count header) (max 0 (- room hint-rows option-count)))
          start (max 0 (min (- n option-count) (- cursor (quot option-count 2))))
          shown-header (cond-> (vec (take header-count header))
                         (and (pos? header-count) (< header-count (count header)))
                         (update (dec header-count) #(clip (str % " …") width)))]
      (str/join "\n"
                (concat shown-header
                        (take option-count (drop start rows))
                        (when hint? [(theme/paint :muted (clip hint width))])
                        prompt-lines)))))

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
  (let [sep (case kind :command "  —  " (:chat :providers) "  •  " nil)
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

(defn- fit-picker-list
  "A render-only window: keep the selection visible without changing the
  model's list height or its scrolling offset."
  [lst available]
  (let [height (max 1 (min available (if (pos? (:height lst 0)) (:height lst) available)))
        selected (or (cl/selected-index lst) 0)
        offset (-> (or (:offset lst) 0)
                   (min selected)
                   (max (- (inc selected) height))
                   (min (max 0 (- (count (:items lst)) height)))
                   (max 0))]
    (assoc lst :height height :offset offset)))

(defn- render-picker [state]
  (let [{:keys [kind query list]} (:picker state)
        display-list (fit-picker-list list (max 1 (- (:height state 24) 6)))
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
             (theme/paint :muted "  Enter: actions  ·  Esc: close") "\n"
             (divider width) "\n"
             (str/join "\n" (map (fn [[i row]]
                                   (str (if (= i sel) (theme/paint :accent-bold "❯ ") "  ") row))
                                 (visible-rows (assoc display-list :items (mcp/render-mcp-panel-lines state)))))))

      :else
      (let [title (case kind
                    :model         "Select model"
                    :agent         "Select agent"
                    :variant       "Select variant"
                    :chat          "Select chat"
                    :command       "Select command"
                    :at-file       (if (get-in state [:picker :hash?]) "Insert path" "Add context")
                    :providers     "Providers"
                    :provider-action (str "Provider " (get-in state [:picker :provider]))
                    :mcp-action    (str "MCP " (get-in state [:picker :server]))
                    :rollback      "Roll back to before"
                    :rollback-type "Roll back what"
                    :flags         "Select flag"
                    :flag-action   "Flag"
                    :timeline      "Jump to"
                    "Select item")]
        (str (picker-header title query list) "\n"
             (divider width) "\n"
             (render-list kind display-list width))))))

(defn render-status-bar [state]
  (status/render state))

(defn- quit-hint
  "While a first Ctrl+C (or Ctrl+D) waits for the second: in the status
  line's place, below the prompt, as Claude Code shows it."
  [state]
  (when-let [k (get-in state [:quit-armed :key])]
    (str (theme/paint :muted "Press ")
         (theme/paint :bold (if (= "ctrl+d" k) "Ctrl+D" "Ctrl+C"))
         (theme/paint :muted " again to exit"))))

(defn render-login [state]
  (let [{:keys [provider action field-idx pending-message]} (:login state)
        action-type (:action action)
        ;; A prompt needed it, or the user asked for it (/login, /providers).
        heading     (str "🔐 " (theme/paint :bold (str (if pending-message "Login required for " "Log in to ")
                                                     provider)))
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
      (str heading "  " cancel "\n"
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
                    (= "secret" (:type field)) (assoc :echo-mode :password :echo-char \•))
                  (:width state 80) (input-row-budget state))))

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
                     (str (task-block state) (pending-lines state) (prompt-input (:input state) (:width state 80) (input-row-budget state)))

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
                     (str (task-block state) (prompt-input (:input state) (:width state 80) (input-row-budget state))))
        gutter     (case mode
                     :connecting (render-connecting state now)
                     :chatting   (render-working state now)
                     :approving  (approval-gutter state now)
                     :asking     (theme/paint :accent "◆ Waiting for your answer")
                     :ready      (or (command-hint state)
                                     (when (:stopping? state) (theme/paint :muted "◌ Stopping…"))
                                     (when-not (fullscreen? state)
                                       (theme/paint :subtle (clip "ctrl+o for transcript" (:width state))))
                                     "")
                     "")
        rule-role  (case mode :approving :warning :asking :accent :disconnected :error :border)
        ;; Chat, gutter, rule, input area, rule, status line. Full screen,
        ;; the chat gets what the (possibly multi-line) input area leaves;
        ;; inline, only what isn't in the terminal's history yet.
        input-h    (count (str/split input-area #"\n" -1))
        chat       (if (fullscreen? state)
                     (str (render-chat state (- (:height state) 4 input-h)) "\n")
                     (let [lines (live-chat-lines state)]
                       (when (seq lines) (str (str/join "\n" lines) "\n"))))]
    (str chat
         gutter
         "\n" (divider (:width state) rule-role)
         "\n" input-area
         "\n" (divider (:width state))
         "\n" (or (quit-hint state)
                   (when (:transcript? state)
                     (theme/paint :muted
                                  (clip "Transcript · Ctrl+O to return · PgUp/PgDn to scroll · Tab to focus" (:width state))))
                   (render-status-bar state)))))

(defn frame
  "What `eca-cli.render` draws for `state`: the full-screen layout or the
  inline live region (`:content`), and unless started with --fullscreen,
  the chat as the terminal's history should hold it: `:committed` items of
  `:total` (the reply streaming in counts, for the last frame), their
  `:lines`, and the `:epoch` they belong to."
  [state]
  (let [items (with-streaming state)
        width (:width state)]
    {:fullscreen? (fullscreen? state)
     :content     (view state)
     :history     (when-not (get-in state [:opts :fullscreen])
                    {:epoch     (or (:epoch state) 0)
                     :removals  (or (:history-removals state) [])
                     :committed (min (count (:items state)) (or (:committed state) 0))
                     :total     (count items)
                     :lines     (fn [from to] (history-lines items from to width))})}))