(ns eca-cli.view
  (:require [clojure.string :as str]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [eca-cli.view.blocks :as blocks]))

(defn divider [width]
  (apply str (repeat width "─")))

(defn rebuild-chat-lines
  "Returns {:lines vec :spans {idx [start end]}} where spans key is the index
  of the source item in `items` (top-level only; streaming-text is excluded)."
  [items current-text width]
  (let [n-items (count items)
        all     (concat items
                        (when (seq current-text)
                          [{:type :streaming-text :text current-text}]))]
    (reduce
      (fn [{:keys [lines spans]} [idx item]]
        (let [rendered (blocks/render-item-lines item width)
              block    (if (= :user (:type item))
                         (vec rendered)
                         (conj (vec rendered) ""))
              start    (count lines)
              end      (+ start (count block))]
          {:lines (into lines block)
           :spans (if (< idx n-items)
                    (assoc spans idx [start end])
                    spans)}))
      {:lines [] :spans {}}
      (map-indexed vector all))))

(defn rebuild-lines
  "Rebuild a state's :chat-lines + :chat-line-spans from :items / :current-text / :width.
  Lives here (leaf util) because every feature ns calls it."
  [state]
  (let [{:keys [lines spans]} (rebuild-chat-lines (:items state) (:current-text state) (:width state))]
    (assoc state :chat-lines lines :chat-line-spans spans)))

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
