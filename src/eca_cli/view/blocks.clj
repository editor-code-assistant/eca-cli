(ns eca-cli.view.blocks
  "Per-item block renderers for the chat area: assistant text, user message,
  tool call (collapsed + expanded with arguments / output / sub-items),
  thinking, hook, system. Each item type maps to a vector of pre-wrapped
  display lines in `render-item-lines`. ANSI styling constants live here
  because they're block-level styling concerns."
  (:require [clojure.string :as str]
            [eca-cli.wrap :as wrap]))

(def ^:private ansi-focus    "\033[48;5;238m")
(def ^:private ansi-thinking "\033[3;38;5;245m")
(def ^:private ansi-yellow   "\033[33m")
(def ^:private ansi-green    "\033[32m")
(def ^:private ansi-red      "\033[31m")
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
    (vec (concat [top] lines [bot]))))

(defn render-tool-icon [tool-call]
  (case (:state tool-call)
    :preparing (str ansi-yellow "◌" ansi-reset)
    :run       (str ansi-yellow "▸" ansi-reset)
    :running   (str ansi-yellow "◌" ansi-reset)
    :called    (if (:error? tool-call)
                 (str ansi-red "✗" ansi-reset)
                 (str ansi-green "✓" ansi-reset))
    :rejected  (str ansi-red "✗" ansi-reset)
    (str ansi-yellow "◌" ansi-reset)))

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
                icon   (case status :failed (str ansi-red "✗" ansi-reset) (str ansi-yellow "⚑" ansi-reset))
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
