(ns eca-cli.view.blocks
  "Per-item block renderers for the chat area: assistant text, user message,
  tool call (collapsed + expanded with arguments / output / sub-items),
  thinking, hook, system, link. Each item type maps to a vector of pre-wrapped
  display lines in `render-item-lines`. Message text goes through the
  markdown renderer; colours come from `eca-cli.theme` roles."
  (:require [clojure.string :as str]
            [eca-cli.markdown :as markdown]
            [eca-cli.theme :as theme]
            [eca-cli.view.welcome :as welcome]
            [eca-cli.wrap :as wrap]))

(defn- paint [role s] (theme/paint role s))

(defn format-duration
  "`850ms` → nil (not worth showing), `12s`, `2m 5s`."
  [ms]
  (when (and (number? ms) (>= ms 1000))
    (let [secs (quot (long ms) 1000)]
      (if (< secs 60)
        (str secs "s")
        (str (quot secs 60) "m" (when (pos? (mod secs 60)) (str " " (mod secs 60) "s")))))))

(defn- render-box [label text width]
  (let [box-w   (max 4 (- width 2))
        inner-w (max 1 (- box-w 4))
        fill-n  (max 0 (- box-w 4 (count label) 1))
        top     (str "  " (paint :border "┌─ ") (paint :muted label)
                     (paint :border (str " " (apply str (repeat fill-n "─")) "┐")))
        bot     (str "  " (paint :border (str "└" (apply str (repeat (- box-w 2) "─")) "┘")))
        side    (paint :border "│")
        lines   (when (seq (str text))
                  (mapcat (fn [line]
                            (let [wrapped (wrap/wrap-text line inner-w)]
                              (mapv (fn [l]
                                      (let [pad (apply str (repeat (max 0 (- inner-w (theme/width l))) " "))]
                                        (str "  " side " " l pad " " side)))
                                    wrapped)))
                          (str/split-lines (str text))))]
    (vec (concat [top] lines [bot]))))

(defn render-tool-icon [tool-call]
  (case (:state tool-call)
    :preparing (paint :muted "◌")
    :run       (paint :warning "▸")
    :running   (paint :accent "◌")
    :called    (if (:error? tool-call)
                 (paint :error "✗")
                 (paint :success "✓"))
    :rejected  (paint :error "✗")
    (paint :muted "◌")))

(def ^:private diff-max-lines 500)

(defn render-diff
  "Colourise a server-computed unified-diff string into display lines.
  Input is the normalized `:edit` map (see `eca-cli.chat/Edit`); only `:diff`
  is read here. Each line is coloured by its leading character: `-` red
  (removed), `+` green (added), `@@` dim (hunk header), everything else grey
  (context). No hunk computation — the server already chose the context.
  Lines flow through the ANSI/CJK-aware `wrap-text` at `width`. Diffs over 500
  rendered lines are clipped with a `[truncated]` footer. Cost is bounded in
  BOTH dimensions — total diff size and per-line length — so this stays O(1) in
  the size of the underlying edit even though `render-item-lines` re-runs it on
  every ~50ms render tick while an edit awaits approval."
  [{:keys [diff]} width]
  (let [inner-w  (max 1 width)
        ;; Clamp each source line before wrapping. wrap-text's hard-break is
        ;; super-linear per line, and the diff-max-lines cap can't short-circuit
        ;; within a single line — so one pathological newline-free line (a
        ;; minified asset, a huge base64 blob) would hang the render loop. Cap
        ;; at a few visual rows' worth of chars; the tail is elided.
        line-cap (* 4 inner-w)
        ;; Bound total work BEFORE the eager `str/split-lines` (which fully
        ;; materializes a String[] — the lazy `take` below can't short-circuit
        ;; it). At most (diff-max-lines+1) lines of (line-cap+1) chars can ever
        ;; survive truncation, so scanning past that prefix is pure waste. This
        ;; keeps a huge server diff from re-splitting megabytes every tick.
        budget   (* (inc diff-max-lines) (inc line-cap))
        raw      (str diff)
        clamped  (if (> (count raw) budget) (subs raw 0 budget) raw)
        styled   (mapcat
                  (fn [line]
                    (let [line (if (> (count line) line-cap)
                                 (str (subs line 0 line-cap) "…")
                                 line)
                          role (cond
                                 (str/starts-with? line "@@") :diff-hunk
                                 (str/starts-with? line "+")  :diff-add
                                 (str/starts-with? line "-")  :diff-del
                                 :else                        :diff-ctx)]
                      (map #(paint role %) (wrap/wrap-text line inner-w))))
                  (str/split-lines clamped))
        ;; Realize at most one line past the cap — `styled` is lazy, so this
        ;; short-circuits wrapping on huge diffs instead of forcing the whole body.
        head    (vec (take (inc diff-max-lines) styled))]
    (if (> (count head) diff-max-lines)
      (conj (subvec head 0 diff-max-lines)
            (paint :diff-hunk "[truncated]"))
      head)))

(defn prefixed
  "`first-prefix` before the first line, `rest-prefix` before the others."
  [lines first-prefix rest-prefix]
  (if (seq lines)
    (into [(str first-prefix (first lines))] (map #(str rest-prefix %)) (rest lines))
    []))

(declare render-item-lines)

(defn remember-keys
  "What an approve & remember saves for a tool call: nil for most tools (the
  whole tool), the shell commands not remembered yet for a shell call (its
  `:shell` breakdown, see `eca-cli.chat/content->shell`). An empty vector
  means there's nothing left to remember."
  [tool]
  (when-let [shell (:shell tool)]
    (->> (:commands shell)
         (keep (fn [{:keys [approval-key remembered?]}]
                 (when (and approval-key (not remembered?)) approval-key)))
         distinct
         vec)))

(defn- shell-lines
  "A shell call as the command it runs. While it waits for approval
  (`:approval?`), or when it chains several commands, each command follows,
  coloured by what an approve & remember does with it: remembered already
  (green), would be remembered (yellow), or always asks."
  [item width]
  (let [raw        (str (get-in item [:arguments :command]))
        cmds       (get-in item [:shell :commands])
        pending?   (:approval? item)
        role-of    (fn [{:keys [approval-key remembered?]}]
                     (cond (nil? approval-key) nil
                           remembered?         :success
                           pending?            :warning))
        inner-w    (max 1 (- width 4))
        always?    (some #(nil? (:approval-key %)) cmds)
        breakdown? (or (> (count cmds) 1) (and pending? always?))
        raw-role   (when (= 1 (count cmds)) (role-of (first cmds)))
        cmd-lines  (fn [{:keys [command args approval-key remembered?] :as c}]
                     (let [note  (cond (and pending? (nil? approval-key)) "(always asks)"
                                       remembered?                        "(remembered)")
                           lines (-> (mapv #(paint (role-of c) %)
                                           (wrap/wrap-text (str/join " " (cons command args))
                                                           (max 1 (- inner-w 2))))
                                     (prefixed (str "    " (paint :muted "↳") " ") "      "))
                           last' (str (peek lines) "  " (paint :muted note))]
                       (cond
                         (nil? note)                  lines
                         (<= (theme/width last') width) (conj (pop lines) last')
                         :else                        (conj lines (str "      " (paint :muted note))))))]
    (vec (concat
           (prefixed (mapv #(paint raw-role %) (wrap/wrap-text raw inner-w))
                     (str "  " (paint :muted "$") " ") "    ")
           (when breakdown? (mapcat cmd-lines cmds))
           (when-let [wd (get-in item [:arguments :working_directory])]
             [(str "  " (paint :muted (str "in " wd)))])
           (when (get-in item [:shell :background?])
             [(str "  " (paint :muted "runs in the background"))])))))

(defn- steps-badge [item]
  (when (seq (:sub-items item))
    (paint :muted (str "  ▸ " (count (:sub-items item)) " steps"))))

(defn- tool-call-lines [item width]
  (let [icon     (render-tool-icon item)
        name     (:name item)
        summary  (or (:summary item) name)
        edit     (:edit item)
        duration (some->> (format-duration (:total-ms item)) (str "  ") (paint :muted))]
    (if (:expanded? item)
      (let [header (str icon " " (paint :bold name) "  " (paint :muted summary)
                        (steps-badge item) (or duration "") "  " (paint :muted "▾"))
            body   (cond
                     edit
                     (render-diff edit width)

                     (and (:shell item) (get-in item [:arguments :command]))
                     (concat (shell-lines item width)
                             (when (:out-text item)
                               (render-box "Output" (:out-text item) width)))

                     :else
                     (concat
                       (when (:args-text item)
                         (render-box "Arguments" (:args-text item) width))
                       (when (:out-text item)
                         (render-box "Output" (:out-text item) width))))
            subs   (when (seq (:sub-items item))
                     (mapcat (fn [sub]
                               (map #(str "  " %) (render-item-lines sub (- width 2))))
                             (:sub-items item)))]
        (vec (concat [header] body subs)))
      (let [badge  (when edit
                     (str "  " (paint :diff-add (str "+" (:lines-added edit)))
                          " " (paint :diff-del (str "−" (:lines-removed edit)))))
            ;; A question it asked the user (ask_user), answered.
            answer (cond
                     (:cancelled? item)   (paint :muted "  ✗ cancelled")
                     (some? (:answer item)) (str "  " (paint :success "→") " "
                                                 (let [a (str/replace (str (:answer item)) #"\s+" " ")]
                                                   (if (> (count a) 60) (str (subs a 0 59) "…") a))))
            label  (if (= :run (:state item)) (paint :bold summary) summary)]
        [(str icon " " label (or badge "") (or answer "") (steps-badge item) (or duration ""))]))))

(defn- thinking-lines [item width]
  (let [label   (if (= :thought (:status item))
                  (str "Thought" (some->> (format-duration (:total-ms item)) (str " for ")))
                  "Thinking…")
        icon    (if (:expanded? item) "▾" "▸")
        header  (paint :thinking (str icon " " label))]
    (if (:expanded? item)
      (let [inner-w (max 1 (- width 2))
            body    (when (seq (:text item))
                      (mapcat #(map (fn [l] (str "  " (paint :thinking l)))
                                    (wrap/wrap-text % inner-w))
                              (str/split-lines (:text item))))]
        (vec (cons header (or (seq body) [""]))))
      [header])))

(defn- hook-lines [item width]
  (let [status (:status item)
        icon   (case status :failed (paint :error "✗") (paint :warning "⚑"))
        label  (case status :running "running…" :ok "ok" :failed "failed" "…")
        head   (str icon " " (:name item) "  " (paint :muted label))]
    (if (:expanded? item)
      (vec (cons (str head "  " (paint :muted "▾"))
                 (when (seq (str (:out-text item)))
                   (render-box "Output" (:out-text item) width))))
      [head])))

(defn question-lines
  "A question's text (`eca-cli.question`), rendered as markdown."
  [text width]
  (vec (markdown/render (str text) (max 1 width))))

(defn- question-item-lines
  "`? question` then the answer, `cancelled`, or that it waits."
  [item width]
  (vec (concat
         (prefixed (question-lines (:question item) (- width 2)) (str (paint :accent-bold "?") " ") "  ")
         (cond
           (:cancelled? item)     [(str "  " (paint :muted "✗ cancelled"))]
           (some? (:answer item)) (prefixed (wrap/wrap-text (:answer item) (max 1 (- width 4)))
                                            (str "  " (paint :success "→") " ") "    ")
           :else                  [(str "  " (paint :muted "waiting for your answer…"))]))))

(defn- image-lines
  "An image the agent produced: its kind, size and where it was saved."
  [item width]
  (let [kind (some-> (:media-type item) (str/replace #"^image/" ""))
        size (when-let [b (:bytes item)]
               (if (< b 1024) (str b " B") (str (Math/round (/ b 1024.0)) " KB")))
        head (str (paint :accent "▣") " " (paint :bold "image")
                  (some->> (not-empty (str/join " · " (remove nil? [kind size]))) (str "  ") (paint :muted)))]
    (if-let [path (:path item)]
      (into [head] (map #(str "  " (paint :md-url %)) (wrap/wrap-text path (max 1 (- width 2)))))
      [head (str "  " (paint :error (str "couldn't save it: " (:error item))))])))

(defn- flag-lines
  "A flag (checkpoint) as a rule across the chat: `── ⚑ text ─────`."
  [item width]
  (let [w     (max 8 width)
        label (str " ⚑ " (:text item) " ")
        label (if (> (theme/width label) (- w 4)) (str (subs label 0 (max 1 (- w 6))) "… ") label)
        fill  (max 0 (- w 2 (theme/width label)))]
    [(str (paint :accent "──") (paint :accent-bold label) (paint :accent (apply str (repeat fill "─"))))]))

(defn- focus-line
  "A focused block's first line highlighted, readable on dark and light
  terminals alike (reverse video of a mid grey). Not padded to the width:
  JLine turns trailing spaces into an erase, which drops the reverse video."
  [line]
  (paint :focus (theme/strip-ansi line)))

(defn render-item-lines [item width]
  (let [lines
        (case (:type item)
          :user
          ;; "┃ " bar = 2 cols on every line, like the user turn border in
          ;; ECA's webview. Blank lines frame the message.
          (let [body (markdown/render (:text item) (max 1 (- width 2)) {:at-refs? true})
                bar  (paint :user-bar "┃")]
            (-> [""]
                (into (map #(if (= "" %) bar (str bar " " %))) (if (seq body) body [""]))
                (conj "")))

          (:assistant-text :streaming-text)
          ;; "◆ " marker = 2 cols; continuation lines indent 2.
          (prefixed (markdown/render (:text item) (max 1 (- width 2)))
                    (str (paint :assistant "◆") " ") "  ")

          :tool-call (tool-call-lines item width)
          :thinking  (thinking-lines item width)
          :hook      (hook-lines item width)
          :question  (question-item-lines item width)
          :flag      (flag-lines item width)
          :image     (image-lines item width)
          :welcome   (welcome/render item width)

          :url
          (let [inner-w (max 1 (- width 2))]
            (vec (concat (prefixed (wrap/wrap-text (str (or (not-empty (:title item)) (:url item))) inner-w)
                                   (str (paint :accent "↗") " ") "  ")
                         (when (seq (:title item))
                           (map #(str "  " (paint :md-url %)) (wrap/wrap-text (str (:url item)) inner-w))))))

          :system
          ;; "⚠ " = 2 cols → inner budget = width - 2. Callers sometimes
          ;; include the ⚠ themselves; don't print it twice. `:level :info`
          ;; is for notes that aren't warnings (e.g. /help).
          (let [text (str/replace-first (str (:text item)) #"^⚠\s*" "")
                icon (if (= :info (:level item)) (paint :accent "•") (paint :warning "⚠"))]
            (prefixed (wrap/wrap-text text (max 1 (- width 2))) (str icon " ") "  "))

          [])]
    (if (and (:focused? item) (seq lines))
      (into [(focus-line (first lines))] (rest lines))
      lines)))
