(ns eca-cli.markdown
  "Markdown -> terminal lines, for chat messages.

  Parses with nextjournal.markdown (built into babashka) and lays the AST out
  for a given width: wrapped paragraphs, nested lists with hanging indents,
  quotes, fenced code with light syntax highlighting, GFM tables. Every
  output line carries its own escape codes and ends with a reset (JLine
  renders each line on its own, so a style never spans lines).

  Inline content is first flattened into runs, `{:text s :style m}` or a
  `{:br true}` line break, then grouped into words (a word may mix styles,
  e.g. `**bold**ness`) and filled greedily. Soft line breaks are kept, as chat
  UIs do: models put single newlines where they mean them.

  Rendering is total: on any failure the raw text comes back plainly wrapped."
  (:require [clojure.string :as str]
            [nextjournal.markdown :as md]
            [eca-cli.markdown.highlight :as hl]
            [eca-cli.theme :as theme]
            [eca-cli.wrap :as wrap]))

;; --- Widths ---

(defn- ascii? [^String s]
  (let [n (.length s)]
    (loop [i 0]
      (or (= i n)
          (let [c (int (.charAt s i))]
            (and (<= 32 c 126) (recur (inc i))))))))

(defn- text-width
  "Display width of plain text (no escape codes)."
  [^String s]
  (if (ascii? s) (.length s) (theme/width s)))

(defn- segs-width [segs]
  (reduce + 0 (map #(text-width (:text %)) segs)))

(defn- spaces [n] (apply str (repeat (max 0 n) " ")))

(defn- style [role] (theme/style-of role))

;; --- Inline content -> runs ---

(defn- plain-text
  "Concatenated text of a node and its children."
  [node]
  (cond
    (:text node)    (:text node)
    (:content node) (apply str (map plain-text (:content node)))
    :else           ""))

(def ^:private at-ref-re
  ;; `@path` at the start of the text or after whitespace, as typed in prompts.
  #"(?<![^\s])@\S+")

(defn- split-at-refs [text st]
  (let [m (re-matcher at-ref-re text)]
    (loop [from 0, runs []]
      (if (.find m)
        (recur (.end m)
               (cond-> runs
                 (< from (.start m)) (conj {:text (subs text from (.start m)) :style st})
                 true                (conj {:text (.group m) :style (merge st (style :at-ref))})))
        (cond-> runs
          (< from (count text)) (conj {:text (subs text from) :style st}))))))

(defn- text-runs
  "Runs for a text node: tabs expanded, embedded newlines turned into breaks,
  `@path` references highlighted when asked."
  [text st opts]
  (->> (str/split (str/replace (str text) "\t" "    ") #"\n" -1)
       (map (fn [line]
              (if (:at-refs? opts)
                (split-at-refs line st)
                (if (= "" line) [] [{:text line :style st}]))))
       (interpose [{:br true}])
       (apply concat)
       vec))

(defn- show-url?
  "Print a link's destination after its text, unless the text already is it
  (autolinks, `mailto:` addresses)."
  [label href]
  (let [label (str/trim label)]
    (and (not (str/blank? href))
         (not (contains? #{label (str "mailto:" label)} href)))))

(declare inline-runs)

(defn- node-runs [node st opts]
  (case (:type node)
    :text          (text-runs (:text node) st opts)
    :strong        (inline-runs (:content node) (merge st {:bold true}) opts)
    :em            (inline-runs (:content node) (merge st {:italic true}) opts)
    :strikethrough (inline-runs (:content node) (merge st {:strike true}) opts)
    :monospace     (text-runs (plain-text node) (merge st (style :md-code)) {})
    :link          (let [href  (str (get-in node [:attrs :href]))
                         label (plain-text node)]
                     (cond-> (inline-runs (:content node) (merge st (style :md-link)) opts)
                       (show-url? label href)
                       (conj {:text (str " (" href ")") :style (merge st (style :md-url))})))
    :image         (let [alt (plain-text node)]
                     [{:text (str "[image" (when (seq alt) (str ": " alt)) "]")
                       :style (merge st (style :muted))}])
    (:softbreak :hardbreak) [{:br true}]
    :html-inline   (text-runs (plain-text node) (merge st (style :md-html)) {})
    :formula       (text-runs (:text node) (merge st (style :md-code)) {})
    (cond
      (:content node) (inline-runs (:content node) st opts)
      (:text node)    (text-runs (:text node) st opts)
      :else           [])))

(defn- inline-runs [nodes st opts]
  (into [] (mapcat #(node-runs % st opts)) nodes))

;; --- Runs -> words -> lines ---

(defn- tokens
  "Words, spaces and breaks: {:k :word|:space|:br :segs [...] :w n}. Adjacent
  non-space text from different runs joins into one word."
  [runs]
  (reduce
    (fn [toks run]
      (if (:br run)
        (conj toks {:k :br})
        (reduce (fn [toks piece]
                  (let [k    (if (Character/isWhitespace (.charAt ^String piece 0)) :space :word)
                        seg  {:text piece :style (:style run)}
                        w    (text-width piece)
                        prev (peek toks)]
                    (if (= k (:k prev))
                      (conj (pop toks) (-> prev (update :segs conj seg) (update :w + w)))
                      (conj toks {:k k :segs [seg] :w w}))))
                toks
                (re-seq #"\s+|\S+" (:text run)))))
    []
    runs))

(defn- code-points [^String s]
  (loop [i 0, out []]
    (if (>= i (.length s))
      out
      (let [n (Character/charCount (.codePointAt s i))]
        (recur (+ i n) (conj out (subs s i (+ i n))))))))

(defn- add-text [segs text st]
  (let [prev (peek segs)]
    (if (and prev (= st (:style prev)))
      (conj (pop segs) (update prev :text str text))
      (conj segs {:text text :style st}))))

(defn- hard-break
  "Split segments into chunks of at most `width` columns, by character.
  Returns [full-chunks last-chunk last-chunk-width]."
  [segs width]
  (reduce
    (fn [[lines cur cur-w] {:keys [text style]}]
      (reduce (fn [[lines cur cur-w] ch]
                (let [cw (text-width ch)]
                  (if (and (pos? cur-w) (> (+ cur-w cw) width))
                    [(conj lines cur) [{:text ch :style style}] cw]
                    [lines (add-text cur ch style) (+ cur-w cw)])))
              [lines cur cur-w]
              (code-points text)))
    [[] [] 0]
    segs))

(defn- trim-line [segs]
  (vec (reverse (drop-while :ws? (reverse segs)))))

(defn- fill
  "Greedy word wrap of tokens into lines (vectors of segments) of `width`."
  [toks width]
  (loop [toks toks, line [], w 0, out []]
    (if-let [t (first toks)]
      (case (:k t)
        :br    (recur (rest toks) [] 0 (conj out (trim-line line)))
        :space (cond
                 (zero? w)                 (recur (rest toks) line w out)
                 (<= (+ w (:w t)) width)   (recur (rest toks) (into line (map #(assoc % :ws? true)) (:segs t))
                                                  (+ w (:w t)) out)
                 :else                     (recur (rest toks) [] 0 (conj out (trim-line line))))
        :word  (cond
                 (<= (+ w (:w t)) width)   (recur (rest toks) (into line (:segs t)) (+ w (:w t)) out)
                 (pos? w)                  (recur toks [] 0 (conj out (trim-line line)))
                 :else                     (let [[full last-chunk last-w] (hard-break (:segs t) width)]
                                             (recur (rest toks) last-chunk last-w (into out full)))))
      (cond-> out (seq line) (conj (trim-line line))))))

(defn- paint-segs
  "One display line from segments, adjacent equal styles merged."
  [segs]
  (->> segs
       (partition-by :style)
       (map (fn [group] (theme/paint (:style (first group)) (apply str (map :text group)))))
       (apply str)))

(defn- render-inline [nodes width st opts]
  (mapv paint-segs (fill (tokens (inline-runs nodes st opts)) (max 1 width))))

;; --- Blocks ---

(declare render-blocks)

(def ^:private bullets ["•" "◦" "▪"])

(defn- heading-style [level]
  (style (case level 1 :md-h1 2 :md-h2 3 :md-h3 :md-h4)))

(defn- prefix-lines
  "`first-prefix` on the first line, `rest-prefix` on the others; blank lines
  stay blank (no trailing spaces)."
  [lines first-prefix rest-prefix]
  (into [(str first-prefix (first lines))]
        (map #(if (= "" %) "" (str rest-prefix %)))
        (rest lines)))

(defn- render-list [node width ctx kind]
  (let [depth    (:depth ctx 0)
        items    (:content node)
        loose?   (some (fn [item] (some #(= :paragraph (:type %)) (:content item))) items)
        start    (or (get-in node [:attrs :start]) 1)
        markers  (map-indexed (fn [i item]
                                (case kind
                                  :bullet   (nth bullets (mod depth (count bullets)))
                                  :numbered (str (+ start i) ".")
                                  :todo     (if (get-in item [:attrs :checked]) "[x]" "[ ]")))
                              items)
        marker-w (reduce max 0 (map text-width markers))
        indent   (inc marker-w)
        ctx'     (assoc ctx :depth (inc depth) :tight? (not loose?))
        rendered (map (fn [item marker]
                        (let [body   (render-blocks (:content item) (- width indent) ctx')
                              body   (if (seq body) body [""])
                              role   (case kind
                                       :todo (if (get-in item [:attrs :checked]) :md-checked :md-unchecked)
                                       :md-bullet)
                              mstr   (theme/paint role (str (spaces (- marker-w (text-width marker))) marker))]
                          (prefix-lines body (str mstr " ") (spaces indent))))
                      items markers)]
    (vec (apply concat (if loose? (interpose [""] rendered) rendered)))))

(defn- render-quote [node width ctx]
  (let [bar  (theme/paint :md-quote-bar "│")
        body (render-blocks (:content node) (- width 2)
                            (update ctx :base merge (style :md-quote)))]
    (mapv #(if (= "" %) bar (str bar " " %)) body)))

(defn- code-lines
  "Highlighted, hard-wrapped lines of a code block body."
  [info code width]
  (let [code  (-> (str code) (str/replace "\t" "    ") (str/replace #"\n$" ""))
        lines (str/split code #"\n" -1)]
    (into []
          (mapcat (fn [segs]
                    (let [[full last-chunk _] (hard-break segs (max 1 width))]
                      (map paint-segs (conj full last-chunk)))))
          (hl/highlight info lines))))

(defn- render-code
  "Fences kept (dim), so a copied block is still valid markdown."
  [node width]
  (let [info (some-> (:info node) str/trim not-empty)
        lang (some-> info (str/split #"\s+" 2) first)]
    (vec (concat [(str (theme/paint :md-fence "```") (theme/paint :muted lang))]
                 (code-lines info (plain-text node) width)
                 [(theme/paint :md-fence "```")]))))

;; --- Tables ---

(defn- table-parts [node]
  (let [section (fn [t] (some #(when (= t (:type %)) %) (:content node)))
        header  (vec (:content (first (:content (section :table-head)))))
        rows    (mapv #(vec (:content %)) (:content (section :table-body)))
        n       (reduce max (count header) (map count rows))
        pad     (fn [cells] (into cells (repeat (- n (count cells)) {:type :table-data :content []})))]
    {:aligns (mapv #(:alignment (get header %)) (range n))
     :header (pad header)
     :rows   (mapv pad rows)
     :n      n}))

(defn- cell-tokens [cell st opts]
  (tokens (inline-runs (:content cell) st opts)))

(defn- natural-width [toks]
  (reduce max 1 (map segs-width (fill toks Integer/MAX_VALUE))))

(defn- fit-widths
  "Shrink the widest columns first until the row fits `budget`."
  [widths budget]
  (loop [ws widths]
    (if (<= (reduce + ws) budget)
      ws
      (let [i (apply max-key ws (range (count ws)))]
        (recur (update ws i dec))))))

(defn- align-cell [segs w align]
  (let [pad (- w (segs-width segs))
        s   (paint-segs segs)]
    (case align
      :right  (str (spaces pad) s)
      :center (str (spaces (quot pad 2)) s (spaces (- pad (quot pad 2))))
      (str s (spaces pad)))))

(defn- render-records
  "Table too narrow for columns: one `header: value` line per cell."
  [{:keys [header rows]} width opts]
  (let [th (style :md-th)]
    (vec (apply concat
                (interpose
                  [""]
                  (for [row rows]
                    (mapcat (fn [h cell]
                              (mapv paint-segs
                                    (fill (tokens (concat (inline-runs (:content h) th opts)
                                                          [{:text ": " :style th}]
                                                          (inline-runs (:content cell) {} opts)))
                                          (max 1 width))))
                            header row)))))))

(defn- render-table [node width ctx]
  (let [{:keys [aligns header rows n] :as parts} (table-parts node)
        opts   (:opts ctx)
        th     (style :md-th)
        head-t (mapv #(cell-tokens % th opts) header)
        rows-t (mapv (fn [row] (mapv #(cell-tokens % {} opts) row)) rows)
        budget (- width (inc (* 3 n)))]
    (if (or (zero? n) (< budget (* 3 n)))
      (render-records parts width opts)
      (let [natural (mapv (fn [i] (reduce max (natural-width (nth head-t i))
                                          (map #(natural-width (nth % i)) rows-t)))
                          (range n))
            widths  (fit-widths natural budget)
            bar     (theme/paint :md-table "│")
            rule    (fn [l m r]
                      (theme/paint :md-table
                                   (str l (str/join m (map #(apply str (repeat (+ % 2) "─")) widths)) r)))
            row-lines (fn [cells-toks]
                        (let [cell-lines (mapv #(fill %1 %2) cells-toks widths)
                              height     (reduce max 1 (map count cell-lines))]
                          (vec (for [i (range height)]
                                 (str bar " "
                                      (str/join (str " " bar " ")
                                                (map (fn [lines w align] (align-cell (get lines i []) w align))
                                                     cell-lines widths aligns))
                                      " " bar)))))
            body    (mapv row-lines rows-t)
            sep?    (some #(> (count %) 1) body)]
        (vec (concat [(rule "┌" "┬" "┐")]
                     (row-lines head-t)
                     [(rule "├" "┼" "┤")]
                     (apply concat (if sep? (interpose [(rule "├" "┼" "┤")] body) body))
                     [(rule "└" "┴" "┘")]))))))

;; --- Block dispatch ---

(defn- render-block [node width ctx]
  (let [base (:base ctx {})
        opts (:opts ctx)]
    (case (:type node)
      (:paragraph :plain) (render-inline (:content node) width base opts)
      :heading       (render-inline (:content node) width
                                    (merge base (heading-style (:heading-level node))) opts)
      :bullet-list   (render-list node width ctx :bullet)
      :numbered-list (render-list node width ctx :numbered)
      :todo-list     (render-list node width ctx :todo)
      :blockquote    (render-quote node width ctx)
      :code          (render-code node width)
      :table         (render-table node width ctx)
      :ruler         [(theme/paint :md-hr (apply str (repeat (max 1 width) "─")))]
      :html-block    (mapv #(theme/paint :md-html %) (wrap/wrap-text (str/trimr (plain-text node)) (max 1 width)))
      :block-formula (code-lines nil (:text node) width)
      (cond
        (:content node) (render-blocks (:content node) width ctx)
        (:text node)    (render-inline [node] width base opts)
        :else           []))))

(defn- render-blocks
  "Blocks separated by a blank line, or packed when inside a tight list."
  [nodes width ctx]
  (let [ctx'     (dissoc ctx :tight?)
        rendered (remove empty? (map #(render-block % (max 1 width) ctx') nodes))]
    (vec (apply concat (if (:tight? ctx) rendered (interpose [""] rendered))))))

(defn render
  "Display lines for markdown `text` laid out in `width` columns.
  `opts`: `:at-refs?` highlights `@path` tokens (prompts)."
  ([text width] (render text width {}))
  ([text width opts]
   (let [text (str/replace (str text) "\r\n" "\n")]
     (try
       (render-blocks (:content (md/parse text)) (max 1 width) {:opts opts :depth 0})
       (catch Exception _
         (wrap/wrap-text text (max 1 width)))))))
