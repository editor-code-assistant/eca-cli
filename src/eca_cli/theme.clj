(ns eca-cli.theme
  "Colours and text attributes for the whole UI, by role.

  A style is a map of `:fg`/`:bg` colours plus `:bold` `:dim` `:italic`
  `:underline` `:reverse` `:strike`. A colour is an ANSI index (0-15 follow
  the terminal's own palette, 16-255 the fixed 256-colour cube and greys) or a
  `\"#rrggbb\"` string (server-provided colours). JLine re-encodes every colour
  for the actual terminal on output: truecolor where COLORTERM says so, the
  nearest 256-colour otherwise.

  Roles favour the ANSI 16 palette, so eca-cli takes on the colours of the
  user's terminal theme and stays readable on dark and light backgrounds alike;
  chrome greys are mid-tones that read on both. `NO_COLOR` (no-color.org)
  turns colours off and keeps the attributes."
  (:require [clojure.string :as str]
            [charm.ansi.width :as aw]))

(def ^:dynamic *color?*
  "False when colours are disabled (`NO_COLOR` set). Attributes still apply."
  (str/blank? (System/getenv "NO_COLOR")))

(def reset "\033[0m")

(def palette
  {;; --- chrome ---
   :muted        {:fg 245}                     ; secondary text
   :subtle       {:fg 242}                     ; tertiary text, separators
   :border       {:fg 240}                     ; dividers, box and table rules
   :accent       {:fg 6}                       ; ECA teal/cyan
   :accent-bold  {:fg 6 :bold true}
   :success      {:fg 2}
   :warning      {:fg 3}
   :error        {:fg 1}
   :info         {:fg 4}
   :bold         {:bold true}
   :prompt       {:fg 2 :bold true}            ; `> `, as eca-emacs
   :assistant    {:fg 6}                       ; ◆ marker
   :user-bar     {:fg 4}                       ; bar left of user messages
   :at-ref       {:fg 6 :bold true}            ; @path tokens
   :thinking     {:fg 245 :italic true}
   :focus        {:fg 245 :reverse true}       ; focused block (fg becomes the bar)
   :task-done    {:fg 245 :strike true}        ; task panel: finished tasks
   :key          {:bold true}                  ; key hints: the key
   :key-desc     {:fg 245}                     ; key hints: what it does
   ;; --- diffs ---
   :diff-add     {:fg 2}
   :diff-del     {:fg 1}
   :diff-hunk    {:fg 245 :dim true}
   :diff-ctx     {:fg 245}
   ;; --- markdown ---
   :md-h1        {:fg 6 :bold true :underline true}
   :md-h2        {:fg 6 :bold true}
   :md-h3        {:bold true}
   :md-h4        {:bold true :italic true}
   :md-code      {:fg 3}                       ; inline code
   :md-link      {:fg 4 :underline true}
   :md-url       {:fg 245}
   :md-bullet    {:fg 6}
   :md-quote-bar {:fg 240}
   :md-quote     {:italic true}
   :md-fence     {:fg 240}
   :md-hr        {:fg 240}
   :md-table     {:fg 240}                     ; table rules
   :md-th        {:bold true}
   :md-checked   {:fg 2}
   :md-unchecked {:fg 245}
   :md-html      {:fg 245}
   ;; --- code blocks ---
   :syn-comment  {:fg 245 :italic true}
   :syn-string   {:fg 2}
   :syn-number   {:fg 3}
   :syn-keyword  {:fg 5}
   :syn-constant {:fg 6}                       ; true/false/nil, Clojure :keywords
   :syn-type     {:fg 4}})

(defn hex->rgb
  "[r g b] for a `#rrggbb` (or `rrggbb`) string, nil when it isn't one."
  [s]
  (when-let [[_ hex] (and (string? s) (re-matches #"#?([0-9a-fA-F]{6})" s))]
    (mapv #(Integer/parseInt (subs hex % (+ % 2)) 16) [0 2 4])))

(defn- color-params [color base]
  (cond
    (and (int? color) (<= 0 color 7))    [(+ base color)]
    (and (int? color) (<= 8 color 15))   [(+ base 60 (- color 8))]
    (and (int? color) (<= 16 color 255)) [(+ base 8) 5 color]
    :else (when-let [rgb (hex->rgb color)]
            (into [(+ base 8) 2] rgb))))

(defn style-of
  "The style map for a role keyword, or `style` itself when it's a map."
  [style]
  (if (keyword? style) (get palette style) style))

(defn sgr
  "SGR escape for a style (map or role), \"\" when it sets nothing."
  [style]
  (let [{:keys [fg bg bold dim italic underline reverse strike]} (style-of style)
        params (cond-> []
                 bold      (conj 1)
                 dim       (conj 2)
                 italic    (conj 3)
                 underline (conj 4)
                 reverse   (conj 7)
                 strike    (conj 9)
                 (and *color?* fg) (into (color-params fg 30))
                 (and *color?* bg) (into (color-params bg 40)))]
    (if (seq params)
      (str "\033[" (str/join ";" params) "m")
      "")))

(defn paint
  "`text` wrapped in `style` (a map or role) and a reset."
  [style text]
  (let [text (str text)
        on   (sgr style)]
    (if (or (= "" on) (= "" text))
      text
      (str on text reset))))

(defn merge-styles
  "Combine styles (maps or roles); later ones win per attribute."
  [& styles]
  (apply merge (map style-of styles)))

(def strip-ansi aw/strip-ansi)

(def width
  "Display width in terminal cells, escape codes excluded."
  aw/string-width)

(defn pad-right
  "`s` padded with spaces to `w` display cells."
  [s w]
  (let [n (- w (width s))]
    (if (pos? n) (str s (apply str (repeat n " "))) s)))

;; --- Agents ---

(def ^:private agent-colors
  "Badge colours for agents without a fixed one, picked by name."
  [4 5 3 2 6])

(defn agent-color
  "A stable colour per agent: `code` gets the accent, `plan` magenta."
  [agent]
  (case agent
    "code" 6
    "plan" 5
    (nth agent-colors (mod (hash (str agent)) (count agent-colors)))))
