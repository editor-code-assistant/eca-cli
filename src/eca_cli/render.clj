(ns eca-cli.render
  "Draws eca-cli's frames on the terminal, one of two ways.

  Inline, the default, as Claude Code does: the chat goes to the terminal's
  own history. A finished item is printed once, above the live region:
  what still changes (a reply streaming in, a running tool call), the
  prompt and the status line, redrawn in place below what's been printed
  by JLine's Display (as LineReader prints above its prompt). The terminal
  scrolls, searches and keeps the chat like any output, after eca-cli exits
  too. eca-cli starts below the command that launched it and grows.

  Full screen (--fullscreen, or the transcript with Ctrl+O): the alternate
  screen, the mouse captured for its wheel, eca-cli scrolling the chat.

  What's printed belongs to the terminal. New chats start below earlier
  output; resizing lets the terminal reflow its history and JLine reflow
  the live region. Never clear the terminal's scrollback. If a resize makes
  the old live region taller than the screen, only its visible portion is
  cleared to establish a fresh drawing origin.

  `plan` decides what a frame takes (pure); the rest does it."
  (:require [charm.render.screen :as scr]
            [charm.terminal :as term])
  (:import [java.util ArrayList Collection]
           [org.jline.terminal Terminal]
           [org.jline.utils AttributedString Display]))

;; --- Planning ---

(def repaint-delay-ms
  "How long a repaint waits after the last resize: dragging a window's edge
  sends a resize for every step."
  150)

(defn content-lines
  "A frame's content as lines, the last `height` of them: the live region
  can't outgrow the screen, or the Display couldn't get back to its top."
  [content height]
  (let [lines (vec (scr/content->lines (if (empty? content) " " content)))
        n     (count lines)]
    (if (and (pos? height) (> n height)) (subvec lines (- n height)) lines)))

(defn- printed-after-removals
  "Rebase the actual printed boundary over removals since the last frame.
  The model can advance several times before drawing, so counts alone
  cannot tell a removal followed by an append from an unchanged chat."
  [shown removals]
  (reduce (fn [printed idx] (if (< idx printed) (dec printed) printed))
          (:printed shown 0)
          (drop (:removals-seen shown 0) removals)))

(defn plan
  "Pure frame planner. `env` carries terminal geometry, resize timing and
  `:reanchor?` if a resize scrolled the live region's origin off-screen.
  Returns drawing `:steps`, the next `:shown`, and an optional `:retry-at`.

  Finished items are printed once, above the live region. Epoch changes
  start a new section rather than deleting terminal history. A final frame
  prints the remaining chat and removes the prompt. Fullscreen geometry
  changes reset only the alternate screen."
  [shown {:keys [fullscreen? content history final?]} {:keys [width height now resized-at reanchor?]}]
  (cond
    (and (not final?) resized-at (< (- now resized-at) repaint-delay-ms))
    {:steps [] :shown shown :retry-at (+ resized-at repaint-delay-ms)}

    (and fullscreen? (not final?))
    {:steps (cond-> []
              (not= :fullscreen (:screen shown)) (conj [:enter-fullscreen])
              (and (= :fullscreen (:screen shown))
                   (not= [width height] (:full-size shown))) (conj [:reset-fullscreen])
              true (conj [:fullscreen (content-lines content height)]))
     :shown (assoc shown :screen :fullscreen :full-size [width height])}

    :else
    (let [leave (when (= :fullscreen (:screen shown)) [[:leave-fullscreen]])
          lines (when-not final? (content-lines content height))
          live  (when-not final? [[:live lines]])
          shown (cond-> (assoc shown :screen :inline :inline-width width)
                  lines (assoc :live-rows (count lines)))]
      (if-not history
        ;; --fullscreen never prints a chat into the terminal's history.
        {:steps (into (vec leave) live) :shown shown}
        (let [{:keys [epoch committed total removals]} history
              upto     (if final? total committed)
              new?     (not= epoch (:epoch shown))
              started? (contains? shown :epoch)
              printed  (if new? 0 (printed-after-removals shown removals))
              append   (when (> upto printed) ((:lines history) printed upto))]
          {:steps (-> (vec leave)
                      (cond-> reanchor? (conj [:reanchor]))
                      (into (cond
                              (and new? started?) [[:print (into [""] append)]]
                              (seq append)        [[:print append]]
                              final?              [[:print []]]
                              :else               []))
                      (into live))
           :shown (assoc shown :epoch epoch :printed upto :removals-seen (count removals))})))))

;; --- Doing ---

(def ^:private sync-start
  "Synchronized output (mode 2026): the terminal shows a frame at once, not
  half drawn. Terminals that don't know it ignore it."
  "\u001b[?2026h")

(def ^:private sync-end "\u001b[?2026l")

(def ^:private mouse-on "\u001b[?1000h\u001b[?1006h")

(def ^:private mouse-off "\u001b[?1006l\u001b[?1000l")

(defn- write! [^Terminal terminal ^String s]
  (let [w (.writer terminal)]
    (.print w s)
    (.flush w)))

(defn- display [^Terminal terminal width height fullscreen?]
  (doto (Display. terminal fullscreen?)
    (.resize (int height) (int width))))

(defn- attributed
  "Hard-line boundaries matter to Display.resize in both rendering modes:
  without them it concatenates short independent rows when reflowing."
  [lines width]
  (ArrayList. ^Collection (mapv #(AttributedString/fromAnsi
                                   (str (scr/truncate-line % width) "\n"))
                                lines)))

(defn- draw-lines! [^Display d lines width]
  ;; Keep the hidden physical cursor inside the NEW content, not on a row
  ;; just erased when a picker/diff shrinks. Display.resize reconstructs
  ;; its position from these cached lines. Column zero avoids pending wrap
  ;; state being lost when the alternate screen restores the cursor.
  (.update d (attributed lines width) (int (* (inc width) (max 0 (dec (count lines)))))))

(defn reflow-rows
  "Rows occupied by previously drawn hard lines after the terminal narrows.
  Ignore the empty row after the last newline: the cursor is before it."
  [lines old-width width]
  (let [joined (AttributedString/join AttributedString/EMPTY (attributed lines old-width))
        rows   (vec (.columnSplitLength joined (int (max 1 width)) true false))]
    (count (if (and (seq rows) (zero? (.length ^AttributedString (peek rows)))) (pop rows) rows))))

(defn- print-lines!
  "History lines, as they are: the terminal wraps one that's too wide."
  [terminal lines]
  (when (seq lines)
    (write! terminal (apply str (map #(str % "\r\n") lines)))))

(defn- reset-fullscreen! [r]
  (let [{:keys [terminal width height]} @r]
    (term/clear-screen terminal)
    (term/cursor-home terminal)
    (swap! r assoc :full (display terminal width height true))))

(defn- step! [r [op lines]]
  (let [{:keys [terminal width height]} @r]
    (case op
      :enter-fullscreen (do (swap! r assoc :alt? true)
                            (term/enter-alt-screen terminal)
                            (reset-fullscreen! r)
                            (write! terminal mouse-on))
      :reset-fullscreen (reset-fullscreen! r)
      :leave-fullscreen (do (write! terminal mouse-off)
                            (term/exit-alt-screen terminal)
                            (swap! r assoc :alt? false))
      :fullscreen       (draw-lines! (:full @r) lines width)
      ;; As LineReader.printAbove: erase live rows, print history, then let
      ;; the Display start afresh below it. No terminal scrollback is erased.
      :print            (let [^Display d (:inline @r)]
                          (.update d (ArrayList.) 0)
                          (print-lines! terminal lines)
                          (.reset d)
                          (swap! r assoc :inline-lines []))
      ;; A resize pushed the old live origin above the screen. Its visible
      ;; portion is all live content: clear ONLY the screen, never scrollback.
      :reanchor         (do (term/clear-screen terminal)
                            (term/cursor-home terminal)
                            (swap! r assoc :inline (display terminal width height false)
                                           :inline-lines []))
      :live             (do (draw-lines! (:inline @r) lines width)
                            (swap! r assoc :inline-lines lines :drawn-width width)))))

(defn create
  "A renderer for `terminal`, nothing drawn yet."
  [^Terminal terminal]
  (let [{:keys [width height]} (term/get-size terminal)]
    (atom {:terminal terminal :width width :height height
           :inline (display terminal width height false) :full nil :alt? false
           :inline-lines [] :drawn-width width :reanchor? false
           :shown {} :resized-at nil :retry-at nil})))

(defn start! [r]
  (term/hide-cursor (:terminal @r)))

(defn frame!
  "Draw `frame` (see `eca-cli.view/frame`)."
  [r frame now]
  (let [{:keys [shown width height resized-at reanchor? terminal]} @r
        {:keys [steps retry-at] shown' :shown} (plan shown frame {:width width :height height
                                                               :now now :resized-at resized-at
                                                               :reanchor? reanchor?})]
    (swap! r assoc :retry-at retry-at)
    (when (seq steps)
      (try
        (write! terminal sync-start)
        (run! #(step! r %) steps)
        ;; Commit bookkeeping only once the output succeeded. Terminal mode
        ;; changes are tracked separately so cleanup survives a partial frame.
        (swap! r assoc :shown shown')
        (when-not (= :fullscreen (:screen shown'))
          (swap! r assoc :reanchor? false))
        (finally
          (write! terminal sync-end))))))

(defn due?
  "Whether a frame put off (a repaint waiting for a resize to settle) is due."
  [r now]
  (when-let [t (:retry-at @r)]
    (>= now t)))

(defn resize!
  "The terminal's new size."
  [r width height now]
  (let [{w :width h :height :keys [inline inline-lines drawn-width]} @r]
    (when (and (pos? width) (pos? height) (or (not= w width) (not= h height)))
      (.resize ^Display inline (int height) (int width))
      (swap! r #(-> %
                    (assoc :width width :height height :resized-at now)
                    ;; Sticky across narrow -> wide resizes, including while
                    ;; the transcript hides the main screen.
                    (update :reanchor? (fn [lost?]
                                         (or lost? (> (reflow-rows inline-lines drawn-width width) height)))))))))

(defn finish!
  "The last frame, on the way out: the full screen goes away, and inline the
  live region gives way to the rest of the chat, which stays in the
  terminal with the shell prompt after it."
  [r frame now]
  (frame! r (assoc frame :final? true) now))

(defn suspend!
  "Hand the terminal to another program (Ctrl+G's editor): the full screen
  is left, or inline the live region erased; the cursor shows. What's
  printed in the terminal's history stays."
  [r]
  (let [{:keys [terminal alt? ^Display inline]} @r]
    (if alt?
      ;; The main screen keeps the live region, and the inline Display what
      ;; it drew there: the program uses (and leaves) its own alternate screen.
      (do (write! terminal mouse-off)
          (term/exit-alt-screen terminal)
          (swap! r #(-> % (assoc :alt? false :suspended :fullscreen) (assoc-in [:shown :screen] :suspended))))
      (do (.update inline (ArrayList.) 0)
          (swap! r assoc :inline-lines [] :suspended :inline)))
    (term/show-cursor terminal)))

(defn resume!
  "The terminal is back (see `suspend!`): the next frame draws afresh,
  re-entering the full screen if it was in it."
  [r]
  (let [{:keys [terminal ^Display inline suspended]} @r]
    (term/hide-cursor terminal)
    (when (= :inline suspended)
      (.reset inline)
      (swap! r assoc :inline-lines []))
    (swap! r dissoc :suspended)))

(defn stop! [r]
  (let [{:keys [terminal alt?]} @r]
    (doseq [restore [(fn [] (write! terminal (str sync-end mouse-off)))
                     (fn [] (when alt? (term/exit-alt-screen terminal)))
                     (fn [] (term/show-cursor terminal))]]
      (try (restore) (catch Throwable _ nil)))))
