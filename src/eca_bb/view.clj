(ns eca-bb.view
  (:require [clojure.string :as str]
            [eca-bb.wrap :as wrap]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]))

(defn divider [width]
  (apply str (repeat width "─")))

(def ^:private ansi-focus    "\033[48;5;238m")
(def ^:private ansi-thinking "\033[3;38;5;245m")
(def ^:private ansi-reset    "\033[0m")

(defn- render-box [label text width]
  (let [box-w   (max 4 (- width 2))
        inner-w (max 1 (- box-w 4))
        fill-n  (max 0 (- box-w 4 (count label) 1))
        top     (str "  ┌─ " label " " (apply str (repeat fill-n "─")) "┐")
        bot     (str "  └" (apply str (repeat (- box-w 2) "─")) "┘")
        lines   (when (seq (str text))
                  (mapcat (fn [line]
                            (let [wrapped (wrap/wrap-text line inner-w)]
                              (mapv (fn [l]
                                      (let [pad (apply str (repeat (max 0 (- inner-w (count l))) " "))]
                                        (str "  │ " l pad " │")))
                                    wrapped)))
                          (str/split-lines (str text))))]
    (concat [top] (or (seq lines) [(str "  │" (apply str (repeat (- box-w 2) " ")) "│")]) [bot])))

(defn- render-tool-icon [tool-call]
  (case (:state tool-call)
    :preparing "⏳"
    :run       "🚧"
    :running   "⏳"
    :called    (if (:error? tool-call) "❌" "✅")
    :rejected  "❌"
    "⏳"))

(defn render-item-lines [item width]
  (let [lines
        (case (:type item)
          :user
          ;; " ❯ " prefix = 4 cols, trailing " " = 1 col → inner budget = width - 5
          (let [inner-w (max 1 (- width 5))
                wrapped (wrap/wrap-text (str (:text item)) inner-w)]
            (into [""]
                  (conj (mapv #(str "\033[7m ❯ " % " \033[0m") wrapped)
                        "")))

          (:assistant-text :streaming-text)
          ;; "◆ " prefix = 2 cols; continuation "  " = 2 cols → inner budget = width - 2
          (let [inner-w (max 1 (- width 2))
                lines   (str/split-lines (str (:text item)))
                wrapped (mapcat #(wrap/wrap-text % inner-w) lines)]
            (if (seq wrapped)
              (into [(str "◆ " (first wrapped))]
                    (map #(str "  " %) (rest wrapped)))
              []))

          :tool-call
          (let [icon    (render-tool-icon item)
                name    (:name item)
                summary (or (:summary item) name)]
            (if (:expanded? item)
              (let [steps  (when (seq (:sub-items item))
                             (str "  ▸ " (count (:sub-items item)) " steps"))
                    header (str icon " " name "  " summary (or steps "") "  ▾")
                    boxes  (concat
                             (when (:args-text item)
                               (render-box "Arguments" (:args-text item) width))
                             (when (:out-text item)
                               (render-box "Output" (:out-text item) width)))
                    subs   (when (seq (:sub-items item))
                             (mapcat (fn [sub]
                                       (map #(str "  " %) (render-item-lines sub (- width 2))))
                                     (:sub-items item)))]
                (vec (concat [header] boxes subs)))
              (let [steps (when (seq (:sub-items item))
                            (str "  ▸ " (count (:sub-items item)) " steps"))]
                [(str icon " " summary (or steps ""))])))

          :thinking
          ;; Use › (same width as ▸) so focused swap doesn't change visual line width
          (let [status  (:status item)
                icon    (if (:focused? item) "›" "▸")
                label   (if (= :thought status) "Thought" "Thinking…")]
            (if (:expanded? item)
              (let [header  (str ansi-thinking icon " " label "  ▾" ansi-reset)
                    inner-w (max 1 (- width 2))
                    body    (when (seq (:text item))
                              (mapcat #(map (fn [l] (str "  " ansi-thinking l ansi-reset))
                                           (wrap/wrap-text % inner-w))
                                      (str/split-lines (:text item))))]
                (vec (cons header (or (seq body) [""]))))
              [(str ansi-thinking icon " " label ansi-reset)]))

          :hook
          (let [status (:status item)
                icon   (case status :failed "❌" "⚡")
                label  (case status :running "running…" :ok "ok" :failed "failed" "…")]
            (if (:expanded? item)
              (let [header (str icon " " (:name item) "  " label "  ▾")
                    boxes  (when (seq (str (:out-text item)))
                             (render-box "Output" (:out-text item) width))]
                (vec (cons header (or boxes []))))
              [(str icon " " (:name item) "  " label)]))

          :system
          ;; "⚠ " = 2 cols → inner budget = width - 2
          (let [inner-w (max 1 (- width 2))
                wrapped (wrap/wrap-text (str (:text item)) inner-w)]
            (into [(str "⚠ " (first wrapped))]
                  (map #(str "  " %) (rest wrapped))))

          [])]
    ;; Apply focus background to first line of focusable items
    (if (and (:focused? item) (seq lines))
      (into [(str ansi-focus (first lines) ansi-reset)] (rest lines))
      lines)))

(defn rebuild-chat-lines [items current-text width]
  (->> (concat items
               (when (seq current-text)
                 [{:type :streaming-text :text current-text}]))
       (mapcat (fn [item]
                 (let [lines (render-item-lines item width)]
                   ;; User items already include blank lines; add one after everything else
                   (if (= :user (:type item))
                     lines
                     (conj (vec lines) "")))))
       vec))

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

(defn- thinking-pulse []
  (let [frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"]
        idx    (mod (quot (System/currentTimeMillis) 120) (count frames))]
    (str "◆ " (nth frames idx))))

(defn render-approval [state]
  (when-let [{:keys [tool-call-id]} (:pending-approval state)]
    (let [tool    (get-in state [:tool-calls tool-call-id])
          summary (or (:summary tool) (:name tool) "tool call")]
      (str "🚧 " summary "\n[y] approve  [Y] always  [n] reject"))))

(defn- render-picker [state]
  (let [{:keys [kind query list]} (:picker state)
        label (case kind :model "model" :agent "agent" :session "chat" :command "command" "item")]
    (str "Select " label " (type to filter): " query "\n"
         (divider (:width state)) "\n"
         (cl/list-view list))))

(defn render-status-bar [state]
  (let [workspace  (-> (get-in state [:opts :workspace] ".")
                       java.io.File.
                       .getName)
        model      (or (:selected-model state) (:model state) "…")
        agent      (:selected-agent state)
        variant    (:selected-variant state)
        usage      (:usage state)
        tokens     (some-> usage :sessionTokens (str "tok"))
        cost       (some-> usage :sessionCost)
        ctx-pct    (when-let [l (:limit usage)]
                     (when (pos? (:context l))
                       (str (int (* 100 (/ (:sessionTokens usage) (:context l)))) "%")))
        loading    (when (some #(not (:done? %)) (vals (:init-tasks state))) "⏳")
        chat-title (let [t (:chat-title state)]
                     (when (and t (seq t))
                       (if (> (count t) 24)
                         (str "\"" (subs t 0 24) "…\"")
                         (str "\"" t "\""))))
        trust      (if (:trust state) "TRUST" "SAFE")]
    (str/join "  " (remove nil? [workspace loading model agent variant tokens cost ctx-pct chat-title trust]))))

(defn render-login [state]
  (let [{:keys [provider action field-idx]} (:login state)
        action-type (:action action)]
    (case action-type
      "choose-method"
      (str/join "\n"
                (into [(str "🔐 Login required for " provider ". Choose a method:")]
                      (map-indexed (fn [i m] (str "  [" (inc i) "] " (:label m)))
                                   (:methods action))))

      "input"
      (let [field (nth (:fields action) (or field-idx 0) nil)]
        (str "🔐 Login required for " provider ".\n"
             "Enter " (:label field) ":"))

      "authorize"
      (str "🔐 Login required for " provider ".\n"
           (:message action) "\n"
           "  URL: " (:url action)
           (when (seq (:fields action))
             (let [field (nth (:fields action) (or field-idx 0) nil)]
               (str "\nEnter " (:label field) " after authorizing:"))))

      "device-code"
      (str "🔐 Login required for " provider ".\n"
           (:message action) "\n"
           "  URL:  " (:url action) "\n"
           "  Code: " (:code action) "\n"
           "Waiting for authorization... [Esc to cancel]")

      (str "🔐 Login required for " provider ". [Esc to cancel]"))))

(defn view [state]
  (let [mode       (:mode state)
        input-area (cond
                     (= :approving mode)
                     (or (render-approval state) "")

                     (= :picking mode)
                     (render-picker state)

                     (= :chatting mode)
                     ""

                     (= :login mode)
                     (let [action-type  (get-in state [:login :action :action])
                           needs-input? (or (= "input" action-type)
                                            (and (= "authorize" action-type)
                                                 (seq (get-in state [:login :action :fields]))))]
                       (if needs-input?
                         (str (render-login state) "\n" (ti/text-input-view (:input state)))
                         (render-login state)))

                     :else
                     (ti/text-input-view (:input state)))
        gutter     (if (and (= :chatting mode) (empty? (:current-text state)))
                     (thinking-pulse)
                     "")]
    (str (render-chat state)
         "\n" gutter
         "\n" (divider (:width state))
         "\n" input-area
         "\n" (divider (:width state))
         "\n" (render-status-bar state))))
