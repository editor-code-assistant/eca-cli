(ns eca-cli.view.status
  "The status line at the bottom of the screen, eca-cli's modeline.

  Left: what runs here — agent badge, model, variant, workspace, chat title.
  Right: how it goes — init progress, running jobs, MCP health, last turn
  time and speed, context-window bar, token usage, cost or subscription quota,
  and the SAFE/TRUST indicator.

  Every segment has a priority; when the terminal is too narrow the least
  important ones are dropped, so the line always fits (a line wider than the
  terminal would be cut and lose its colours)."
  (:require [clojure.string :as str]
            [charm.ansi.width :as aw]
            [eca-cli.mcp :as mcp]
            [eca-cli.theme :as theme]
            [eca-cli.view.blocks :as blocks]))

;; Lazy resolution to break the jobs → view → status → jobs require cycle.
(defn- jobs-fragment [state]
  ((requiring-resolve 'eca-cli.jobs/status-bar-fragment) state))

;; --- Numbers ---

(defn friendly-number
  "52900 → \"52.9K\", 1000000 → \"1M\", 999 → \"999\". Locale-independent."
  [n]
  (when (number? n)
    (let [fmt (fn [x suffix]
                (let [s (str (/ (Math/round (* 10.0 x)) 10.0))]
                  (str (if (str/ends-with? s ".0") (subs s 0 (- (count s) 2)) s) suffix)))]
      (cond
        (>= (abs n) 1000000) (fmt (/ n 1000000.0) "M")
        (>= (abs n) 1000)    (fmt (/ n 1000.0) "K")
        :else                (str (long n))))))

(defn- money [s]
  (when (seq (str s))
    (if (str/starts-with? (str s) "$") (str s) (str "$" s))))

(defn- level-role
  "Colour for a fill level in percent: warning from 70, error from 90."
  [pct]
  (cond (>= pct 90) :error (>= pct 70) :warning :else :muted))

;; --- Context bar ---

(def ^:private eighths ["" "▏" "▎" "▍" "▌" "▋" "▊" "▉"])

(defn allocate
  "Whole cells per category, proportional to its tokens, every category with
  tokens getting at least one cell when there is room (as eca-emacs does)."
  [tokens cells]
  (let [n         (count tokens)
        positives (count (filter pos? tokens))]
    (cond
      (or (<= cells 0) (zero? positives))
      (vec (repeat n 0))

      (<= cells positives)
      (let [top (set (take cells (sort-by #(- (nth tokens %)) (range n))))]
        (mapv #(if (and (top %) (pos? (nth tokens %))) 1 0) (range n)))

      :else
      (let [total     (reduce + tokens)
            remaining (- cells positives)
            rounded   (mapv #(Math/round (double (* remaining (/ % total)))) (reductions + tokens))
            extra     (map - rounded (cons 0 rounded))]
        (mapv (fn [tk e] (+ (if (pos? tk) 1 0) e)) tokens extra)))))

(defn context-pct
  "Percent of the context window in use per the server's breakdown, or nil."
  [usage]
  (let [{:keys [usedTokens contextLimit]} (:contextBreakdown usage)]
    (when (and (number? usedTokens) (number? contextLimit) (pos? contextLimit))
      (* 100.0 (/ usedTokens contextLimit)))))

(defn context-bar
  "The context window as `cells` block characters, coloured per category with
  the server's colours (used) and shaded (free); a `│` marks where the server
  auto-compacts. Nil until the server sends a breakdown."
  [usage cells]
  (when-let [{:keys [categories freeColor] :as breakdown} (:contextBreakdown usage)]
    (when (seq categories)
      (let [used     (or (:usedTokens breakdown) 0)
            limit    (:contextLimit breakdown)
            frac     (if (and (number? limit) (pos? limit)) (min 1.0 (/ used (double limit))) 1.0)
            exact    (* cells frac)
            full     (min cells (long (Math/floor exact)))
            edge     (if (< full cells) (min 7 (max 0 (Math/round (* 8 (- exact full))))) 0)
            alloc    (allocate (mapv #(max 0 (or (:tokens %) 0)) categories) full)
            style-of (fn [cat] (if (theme/hex->rgb (:color cat)) {:fg (:color cat)} (theme/style-of :accent)))
            painted  (vec (mapcat (fn [cat n] (repeat n {:ch "█" :style (style-of cat)})) categories alloc))
            ;; the partial cell continues the last category drawn
            edge-st  (or (:style (peek painted)) (style-of (first categories)))
            painted  (cond-> painted (pos? edge) (conj {:ch (nth eighths edge) :style edge-st}))
            free     {:ch "░" :style (if (theme/hex->rgb freeColor) {:fg freeColor} (theme/style-of :border))}
            painted  (into painted (repeat (- cells (count painted)) free))
            marker   (when-let [pct (:autoCompactPercentage usage)]
                       (when (and (number? pct) (pos? pct))
                         (min (dec cells) (max 0 (long (Math/floor (* cells (/ (min 100 pct) 100.0))))))))
            painted  (cond-> painted marker (assoc marker {:ch "│" :style (theme/style-of :muted)}))]
        (->> painted
             (partition-by :style)
             (map (fn [group] (theme/paint (:style (first group)) (apply str (map :ch group)))))
             (apply str))))))

;; --- Usage, cost, quota ---

(defn quota-text
  "Subscription quota windows, `5h 42% · 7d 13%`, each coloured by how close
  it is to its limit; a window past its reset shows `—` until refreshed."
  [quota now-secs]
  (when-let [windows (seq (:windows quota))]
    (str/join (theme/paint :muted " · ")
              (for [{:keys [label usedPercent resetsAt]} windows]
                (if (or (not (number? usedPercent)) (and (number? resetsAt) (<= resetsAt now-secs)))
                  (theme/paint :muted (str label " —"))
                  (theme/paint (level-role usedPercent) (str label " " (Math/round (double usedPercent)) "%")))))))

;; --- Segments ---

(defn- seg [id prio text]
  (when (seq text) {:id id :prio prio :text text :w (theme/width text)}))

(defn- model-seg
  "The model, provider muted. Before the whole segment goes, the provider
  prefix does (`:short`, at its own lower priority)."
  [model]
  (let [[provider name] (if (str/includes? model "/") (str/split model #"/" 2) [nil model])]
    (cond-> (seg :model 3 (str (when provider (theme/paint :muted (str provider "/"))) name))
      provider (assoc :short {:prio 9 :text name}))))

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(defn- init-text [init-tasks]
  (let [tasks   (vals init-tasks)
        pending (remove :done? tasks)]
    (when (seq pending)
      (theme/paint :muted (str "⏳ " (count (filter :done? tasks)) "/" (count tasks)
                               (some->> (first pending) :title (str " ")))))))

(defn- turn-text
  "How long the last turn took and how fast the model wrote, `14s · 48 tok/s`."
  [state]
  (let [took (:last-turn-ms state)
        tps  (get-in state [:usage :messageOutputTps])]
    (when (or (number? took) (number? tps))
      (theme/paint :muted
                   (str/join " · " (remove nil? [(when (number? took) (or (blocks/format-duration took) "<1s"))
                                                 (when (number? tps) (str tps " tok/s"))]))))))

(defn segments
  "[left right] segment vectors for `state`, before fitting."
  [state now-secs]
  (let [usage   (:usage state)
        agent   (:selected-agent state)
        model   (or (:selected-model state) (:model state) "…")
        title   (some-> (:chat-title state) str/trim not-empty (truncate 30))
        pct     (context-pct usage)
        limit   (or (get-in usage [:limit :context]) (get-in usage [:contextBreakdown :contextLimit]))
        tokens  (when (number? (:sessionTokens usage))
                  (str (friendly-number (:sessionTokens usage))
                       (when (number? limit) (str "/" (friendly-number limit)))))
        cost    (or (some-> (:quota usage) (quota-text now-secs))
                    (some->> (money (:sessionCost usage)) (theme/paint :muted)))
        ;; Not a reverse-video badge: it ends the line, and JLine drops a
        ;; line's trailing (even styled) spaces, which would lopside it.
        trust   (if (:trust state)
                  (theme/paint (theme/merge-styles :error :bold) "● TRUST")
                  (theme/paint :success "SAFE"))]
    [(filterv some?
              [(when agent (seg :agent 2 (theme/paint {:fg (theme/agent-color agent) :bold true :reverse true}
                                                      (str " " agent " "))))
               (model-seg model)
               (seg :variant 7 (some->> (:selected-variant state) (theme/paint :warning)))
               (seg :workspace 5 (theme/paint :bold (-> (get-in state [:opts :workspace] ".") java.io.File. .getName)))
               (seg :title 13 (some->> title (theme/paint (theme/merge-styles :muted {:italic true}))))])
     (filterv some?
              [(seg :init 6 (init-text (:init-tasks state)))
               (seg :jobs 11 (jobs-fragment state))
               (seg :mcp 8 (mcp/status-bar-fragment state))
               (seg :turn 12 (turn-text state))
               (seg :ctx-bar 10 (context-bar usage (if (>= (:width state 80) 120) 16 10)))
               (seg :ctx-pct 4 (when pct (theme/paint (level-role pct) (str (Math/round pct) "%"))))
               (seg :tokens 9 (some->> tokens (theme/paint :muted)))
               (seg :cost 7 cost)
               (seg :trust 1 trust)])]))

(def ^:private gap 2)

(defn- needed [left right]
  (let [width-of #(+ (reduce + (map :w %)) (* gap (max 0 (dec (count %)))))]
    (+ (width-of left) (width-of right) (if (and (seq left) (seq right)) gap 0))))

(defn fit
  "Make [left right] fit in `width`: repeatedly take the least important
  step, dropping a segment (`:prio`) or switching one to its `:short` form
  (`:short :prio`)."
  [[left right] width]
  (loop [left left, right right]
    (let [all (concat left right)]
      (if (or (<= (needed left right) width) (<= (count all) 1))
        [left right]
        (let [steps (concat (map (fn [s] [(:prio s) :drop s]) all)
                            (keep (fn [s] (when-let [sh (:short s)] [(:prio sh) :shorten s])) all))
              [_ op target] (apply max-key first steps)
              apply-step (fn [segs]
                           (into [] (keep (fn [s]
                                            (cond
                                              (not= (:id s) (:id target)) s
                                              (= op :drop)                nil
                                              :else (-> s
                                                        (assoc :text (get-in s [:short :text])
                                                               :w (theme/width (get-in s [:short :text])))
                                                        (dissoc :short)))))
                                 segs))]
          (recur (apply-step left) (apply-step right)))))))

(defn render
  "The status line for `state`: left segments, then the right ones flush with
  the right edge. Never wider than the terminal."
  ([state] (render state (quot (System/currentTimeMillis) 1000)))
  ([state now-secs]
   (let [width        (max 1 (or (:width state) 80))
         [left right] (fit (segments state now-secs) width)
         spacer       (apply str (repeat gap " "))
         l            (str/join spacer (map :text left))
         r            (str/join spacer (map :text right))
         pad          (if (seq right) (max (if (seq left) gap 0) (- width (theme/width l) (theme/width r))) 0)
         line         (str l (apply str (repeat pad " ")) r)]
     (if (> (theme/width line) width)
       (aw/truncate line width :tail "")   ; a single segment wider than the terminal
       line))))
