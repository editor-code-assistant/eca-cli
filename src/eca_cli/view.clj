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

(defn render-chat [state]
  (let [visible-height (max 1 (- (:height state) 5))
        lines          (:chat-lines state)
        total          (count lines)
        offset         (:scroll-offset state)
        end            (max 0 (- total offset))
        start          (max 0 (- end visible-height))
        visible        (pad-to-height (subvec lines start end) visible-height)]
    (str/join "\n" visible)))

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

(defn render-working
  "Gutter line during a turn: spinner, what's happening, for how long, and
  how to stop it."
  [state now]
  (let [elapsed (some->> (:turn-started-at state) (- now) (max 0))]
    (str (theme/paint :accent (spinner-frame now)) " "
         (theme/paint :bold (activity state))
         (when elapsed
           (theme/paint :muted (str " " (or (blocks/format-duration elapsed) (str (quot elapsed 1000) "s")))))
         (theme/paint :subtle "  esc to interrupt"))))

(def disconnected-hint "ECA server is not running. Press Ctrl+C to exit.")

(defn render-approval
  "Render the pending tool call through the shared block renderer, force-expanded
  (so a file edit shows its diff), followed by the `[y/n/Y]` prompt line. One
  block renderer, two mount points — the diff can never drift between the
  approval surface and the completed block."
  [state]
  (when-let [{:keys [tool-call-id]} (:pending-approval state)]
    (let [tool  (get-in state [:tool-calls tool-call-id])
          width (:width state)
          item  (assoc tool :type :tool-call :expanded? true)
          lines (blocks/render-item-lines item width)
          key   (fn [k role]
                  (str (theme/paint :key-desc "[") (theme/paint role k) (theme/paint :key-desc "]")))]
      (str (str/join "\n" lines)
           "\n" (key "y" (theme/merge-styles :success :bold)) " " (theme/paint :key-desc "approve")
           "  " (key "Y" (theme/merge-styles :success :bold)) " " (theme/paint :key-desc "always")
           "  " (key "n" (theme/merge-styles :error :bold)) " " (theme/paint :key-desc "reject")))))

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

(defn- clip
  "Plain `s` cut to `w` columns with an ellipsis."
  [s w]
  (if (> (theme/width s) w) (aw/truncate s (max 1 w) :tail "…") s))

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
      (let [label (case kind :model "model" :agent "agent" :chat "chat" :command "command" :at-file "file" "item")]
        (str (picker-header (str "Select " label) query list) "\n"
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

(defn- prompt-input
  "The prompt line: the `> ` prefix in the prompt colour, then the text."
  [input]
  (str (theme/paint :prompt (:prompt input)) (ti/text-input-view (assoc input :prompt ""))))

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

                     (= :picking mode)
                     (render-picker state)

                     (= :chatting mode)
                     ""

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
                     (prompt-input (:input state)))
        gutter     (case mode
                     :connecting (render-connecting state now)
                     :chatting   (render-working state now)
                     :approving  (theme/paint :warning "▲ Waiting for your approval")
                     "")
        rule-role  (case mode :approving :warning :disconnected :error :border)]
    (str (render-chat state)
         "\n" gutter
         "\n" (divider (:width state) rule-role)
         "\n" input-area
         "\n" (divider (:width state))
         "\n" (render-status-bar state))))