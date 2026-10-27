(ns eca-cli.view.welcome
  "The welcome screen opening a new chat, in the spirit of Claude Code's: a
  box with ECA's logo, the versions, the model and the workspace, tips to get
  started and the recent chats to resume. Pure: renders a `:welcome` item
  (see `eca-cli.welcome`) for a width."
  (:require [clojure.string :as str]
            [eca-cli.theme :as theme]))

;; --- Logo ---

(def ^:private logo-rows
  "ECA's logo, three cubes around a prompt (`images/logo.png` in the eca
  repo), traced in braille: 36x32 dots, two by four to a character, so they
  come out square. Made symmetric, but for the prompt."
  ["⠀⠀⠀⠀⠀⢀⣠⣴⠟⠻⣦⣄⡀⠀⠀⠀⠀⠀"
   "⠀⠀⠀⠀⠀⣿⠿⣦⣄⣠⣴⠿⣿⠀⠀⠀⠀⠀"
   "⠀⠀⠀⠀⣀⣿⠀⠀⠉⠉⠀⠀⣿⣀⠀⠀⠀⠀"
   "⠀⠀⠀⣿⠋⠀⠀⠛⠶⣦⡄⠀⠀⠙⣿⠀⠀⠀"
   "⣀⣤⠾⠻⢶⣄⡀⠶⠛⠋⠁⢀⣠⡶⠟⠷⣤⣀"
   "⣿⠷⣦⣠⣴⠿⣿⠀⠀⠀⠀⣿⠿⣦⣄⣴⠾⣿"
   "⣿⡀⠈⣿⠀⣀⣿⠀⠀⠀⠀⣿⣀⠀⣿⠁⢀⣿"
   "⠈⠛⠷⡿⠞⠋⠙⠳⣦⣴⠞⠋⠙⠳⢿⠾⠛⠁"])

(def logo-width (count (first logo-rows)))

(defn- prompt-cell?
  "The `>` in the middle (rows 3-4, columns 7-10)."
  [row col]
  (and (<= 3 row 4) (<= 7 col 10)))

(defn logo-lines
  "The logo as painted lines, `logo-width` cells each: the cubes in the
  accent colour, the prompt in the text's. Empty cells are plain spaces."
  []
  (vec (map-indexed
         (fn [row s]
           (->> (map-indexed (fn [col ch]
                               (cond
                                 (= \⠀ ch)            [nil " "]
                                 (prompt-cell? row col) [:bold (str ch)]
                                 :else                [:accent (str ch)]))
                             s)
                (partition-by first)
                (map (fn [cells]
                       (let [role (ffirst cells) text (apply str (map second cells))]
                         (if role (theme/paint role text) text))))
                (apply str)))
         logo-rows)))

;; --- Layout helpers ---

(defn- clip
  "Plain `s` cut to `w` cells, with an ellipsis when it doesn't fit."
  [s w]
  (let [s (str s)]
    (cond
      (<= w 0)                 ""
      (<= (theme/width s) w)   s
      :else                    (str (subs s 0 (max 0 (dec w))) "…"))))

(defn- fit
  "A painted line padded (or cut, losing its style) to exactly `w` cells."
  [line w]
  (if (<= (theme/width line) w)
    (theme/pad-right line w)
    (clip (theme/strip-ansi line) w)))

(defn- center-in
  "`lines` in the middle of `h` rows."
  [lines h]
  (let [above (max 0 (quot (- h (count lines)) 2))]
    (vec (take h (concat (repeat above "") lines (repeat ""))))))

(defn- rule [w] (apply str (repeat (max 0 w) "─")))

(defn- box
  "`rows` (painted, any width) inside a rounded box `w` wide, `title` on its
  top border like Claude Code's."
  [title rows w]
  (let [inner  (- w 4)
        label  (str " " (clip title (- w 8)) " ")
        border (fn [s] (theme/paint :accent s))]
    (vec (concat [(str (border "╭──") (theme/paint :accent-bold label)
                       (border (str (rule (- w 5 (theme/width label))) "─╮")))]
                 (for [r rows] (str (border "│") " " (fit r inner) " " (border "│")))
                 [(border (str "╰" (rule (- w 2)) "╯"))]))))

;; --- Content ---

(defn- title [{:keys [cli-version server-version]}]
  (str "eca-cli " cli-version (when server-version (str " · server " server-version))))

(defn- greeting [{:keys [recent]}]
  (if (seq recent) "Welcome back!" "Welcome to ECA!"))

(defn- model-line [{:keys [model variant]} w]
  (when model
    (let [[provider name] (if (str/includes? model "/") (str/split model #"/" 2) [nil model])
          extra (when variant (str " · " variant))
          plain (str (when provider (str provider "/")) name extra)]
      (if (> (theme/width plain) w)
        (theme/paint :bold (clip plain w))
        (str (when provider (theme/paint :muted (str provider "/")))
             (theme/paint :bold name)
             (when extra (theme/paint :muted extra)))))))

(defn- info-lines
  "Next to the logo: the greeting, the model, the agent, the workspace."
  [item w]
  (cond-> [(theme/paint :bold (clip (greeting item) w)) ""]
    (:model item)     (conj (model-line item w))
    (:agent item)     (conj (str (theme/paint {:fg (theme/agent-color (:agent item))} (clip (:agent item) (- w 6)))
                                 (theme/paint :muted " agent")))
    (:workspace item) (conj (theme/paint :muted (clip (:workspace item) w)))))

(defn tips
  "What to try first, [key what short]: `/init` while the project has no
  AGENTS.md, as Claude Code suggests its CLAUDE.md."
  [{:keys [agents-md?]}]
  (cond-> []
    (not agents-md?) (conj ["/init" "create an AGENTS.md" "AGENTS.md"])
    true             (into [["/" "commands, skills and prompts" "commands"]
                            ["@" "add files, folders as context" "context"]
                            ["#" "insert a path for the agent" "paths"]])))

(defn- tip-lines [item n w]
  (let [ts   (take n (tips item))
        keyw (reduce max 0 (map (comp count first) ts))]
    (for [[k desc] ts]
      (str (theme/paint :accent-bold (theme/pad-right k keyw)) "  "
           (theme/paint :muted (clip desc (- w keyw 2)))))))

(defn- chat-line [{:keys [title ago]} w]
  (let [ago  (str ago)
        room (- w (theme/width ago) 2)]
    (str (theme/pad-right (clip (or (not-empty title) "(untitled)") room) room)
         "  " (theme/paint :muted ago))))

(defn- resume-hint []
  (str (theme/paint :accent-bold "/chats") (theme/paint :muted " to resume one")))

(defn- side-lines
  "The right column, `h` rows: tips, then the recent chats. The more chats
  there are to resume, the fewer tips: someone coming back knows them."
  [{:keys [recent] :as item} w h]
  (let [shown  (take 2 recent)
        chats  (cond
                 (nil? recent)   []
                 (empty? recent) [(theme/paint :muted "No recent chats yet")]
                 :else           (concat (map #(chat-line % w) shown) [(resume-hint)]))
        recent (when (some? recent)
                 (concat [(theme/paint :border (rule w)) (theme/paint :bold "Recent chats")] chats))
        n-tips (max 1 (min 4 (- h 1 (count recent))))]
    (vec (concat [(theme/paint :bold "Tips for getting started")]
                 (tip-lines item n-tips w)
                 recent))))

(defn- compact-lines
  "Under the logo on narrow screens: the tips on one line, the latest chat."
  [item w]
  (let [tip-text (str/join "  " (for [[k _ short] (tips item)]
                                  (str (theme/paint :accent-bold k) " " (theme/paint :muted short))))
        latest   (first (:recent item))]
    (cond-> [(fit tip-text w)]
      latest (conj (let [tail (str "  " (:ago latest) " · /chats")]
                     (str (theme/paint :muted "Last: ")
                          (clip (or (not-empty (:title latest)) "(untitled)") (- w 6 (theme/width tail)))
                          (theme/paint :muted (str "  " (:ago latest) " · "))
                          (theme/paint :accent-bold "/chats")))))))

;; --- Render ---

(def ^:private max-width 110)

(defn render
  "The welcome box for `item` at `width`: logo, info and side column side by
  side when wide; the side column folded under them when not; no logo on
  very narrow screens."
  [item width]
  (let [w      (min width max-width)
        inner  (- w 4)
        logo   (logo-lines)
        h      (count logo)]
    (cond
      ;; Logo | info | tips and recent chats.
      (>= inner 86)
      (let [side-w 36
            info-w (- inner logo-width 3 side-w 3)
            info   (center-in (info-lines item info-w) h)
            side   (side-lines item side-w h)
            rows   (for [i (range h)]
                     (str (nth logo i) "   "
                          (fit (nth info i) info-w)
                          (theme/paint :border " │ ")
                          (get side i "")))]
        (box (title item) rows w))

      ;; Logo | info, then tips and the latest chat.
      (>= inner (+ logo-width 3 20))
      (let [info-w (- inner logo-width 3)
            info   (center-in (info-lines item info-w) h)
            rows   (concat (for [i (range h)] (str (nth logo i) "   " (nth info i)))
                           [(theme/paint :border (rule inner))]
                           (compact-lines item inner))]
        (box (title item) rows w))

      ;; Too narrow for the logo.
      :else
      (let [w (max 24 w)]
        (box (title item) (concat (info-lines item (- w 4)) [""] (compact-lines item (- w 4))) w)))))
