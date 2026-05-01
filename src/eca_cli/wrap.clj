(ns eca-cli.wrap
  "Word-wrap utilities for terminal display.

   Uses charm.ansi.width for ANSI-aware cell-width measurement so that
   escape sequences (bold, colour, reverse-video, etc.) are correctly treated
   as zero-width when calculating line lengths."
  (:require [clojure.string :as str]
            [charm.ansi.width :as aw]))

(defn wrap-text
  "Wrap string `s` to fit within `width` terminal columns, returning a vector
   of lines.

   - Splits on whitespace word boundaries where possible.
   - Hard-breaks words longer than `width` column by column.
   - ANSI escape sequences are preserved and have zero display width.
   - Existing newlines in `s` are honoured (each paragraph wrapped separately).

   Example:
     (wrap-text \"hello world\" 7)  ; => [\"hello\" \"world\"]
     (wrap-text \"hi there\" 80)    ; => [\"hi there\"]"
  [s width]
  (if (or (nil? s) (empty? s) (<= width 0))
    [(or s "")]
    ;; Process each existing newline-delimited paragraph independently.
    (vec
     (mapcat
      (fn [paragraph]
        ;; Split into alternating non-space / space tokens so whitespace is
        ;; preserved inside a line but dropped at wrap points.
        (let [words (if (empty? paragraph)
                      [""]
                      (str/split paragraph #"(?<=\S)(?=\s)|(?<=\s)(?=\S)"))]
          (loop [words        words
                 current-line ""
                 current-w    0
                 result       []]
            (if (empty? words)
              ;; Flush whatever remains.
              (conj result current-line)
              (let [word      (first words)
                    word-w    (aw/string-width word)
                    ws-only?  (re-matches #"\s+" word)]
                (cond
                  ;; ── Whitespace token ─────────────────────────────────────
                  ;; Keep it only if it fits and there is already content on
                  ;; the current line (avoids leading spaces after a wrap).
                  ws-only?
                  (if (and (pos? current-w) (<= (+ current-w word-w) width))
                    (recur (rest words)
                           (str current-line word)
                           (+ current-w word-w)
                           result)
                    ;; Drop the whitespace at the wrap boundary.
                    (recur (rest words) current-line current-w result))

                  ;; ── Word fits on the current line ────────────────────────
                  (<= (+ current-w word-w) width)
                  (recur (rest words)
                         (str current-line word)
                         (+ current-w word-w)
                         result)

                  ;; ── Word doesn't fit — flush current line, retry word ────
                  (pos? current-w)
                  (recur words "" 0 (conj result (str/trimr current-line)))

                  ;; ── Word alone exceeds width — hard-break it ─────────────
                  ;; Slice column by column using JLine's AttributedString so
                  ;; ANSI sequences are preserved in the right chunks.
                  :else
                  (let [attr-s (org.jline.utils.AttributedString/fromAnsi word)
                        total  (.columnLength attr-s)
                        chunks (loop [col 0 acc []]
                                 (if (>= col total)
                                   acc
                                   (let [end (min total (+ col width))]
                                     (recur end
                                            (conj acc
                                                  (str (.columnSubSequence attr-s (int col) (int end))))))))]
                    ;; Emit all complete chunks except the last, which
                    ;; becomes the new current line (may still have room).
                    (recur (rest words)
                           (last chunks)
                           (aw/string-width (last chunks))
                           (into result (butlast chunks))))))))))
      (str/split-lines s)))))
