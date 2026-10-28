(ns eca-cli.terminal
  "Terminal setup that charm.clj / JLine don't do for us."
  (:require [babashka.process :as proc])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.jline.terminal TerminalBuilder]
           [org.jline.utils InfoCmp$Capability]))

(defn tty?
  "Whether eca-cli's file descriptor `fd` (0 stdin, 1 stdout, 2 stderr) is a
  terminal, asked of `test -t` sharing it: the JVM can't tell for stdin."
  [fd]
  (try
    (zero? (:exit @(proc/process ["test" "-t" (str fd)] {:in :inherit :out :inherit :err :inherit})))
    (catch Exception _ false)))

(defn read-stdin
  "Piped stdin's text, read to its end, or nil when nothing came within
  `first-char-ms`: a program running eca-cli may leave its stdin open with
  nothing to say (an agent's shell tool, some CI), and eca-cli mustn't wait
  on it forever. Once it starts, it's read however long it takes."
  [first-char-ms]
  (let [rdr   *in*
        first (future (.read ^java.io.Reader rdr))
        c     (deref first first-char-ms ::timeout)]
    (cond
      (= ::timeout c) nil
      (neg? c)        ""
      :else           (str (char c) (slurp rdr)))))

(defn ena-acs
  "The terminfo `enacs` (enable alternate character set) string for
  `term-type`, or nil when the terminal has none, is dumb or is unknown.

  Resolved through JLine's own terminfo handling (bundled caps, falling back
  to `infocmp`) so it matches exactly what the renderer will assume."
  [term-type]
  (when (and (seq term-type) (not= "dumb" term-type))
    (try
      (let [out  (ByteArrayOutputStream.)
            term (-> (TerminalBuilder/builder)
                     (.system false)
                     (.type ^String term-type)
                     (.streams (ByteArrayInputStream. (byte-array 0)) out)
                     (.build))]
        (try
          (.puts term InfoCmp$Capability/ena_acs (object-array 0))
          (.flush term)
          (not-empty (.toString out "UTF-8"))
          (finally (.close term))))
      (catch Exception _ nil))))

(defn enable-alt-charset!
  "Send the terminal's `enacs` sequence, if it has one.

  JLine draws the light box-drawing glyphs (─ │ ┌ ┐ └ ┘ ├ ┤ ┬ ┴ ┼) with the
  terminal's alternate character set: it swaps them for their ACS letters
  (`─` → `q`, `│` → `x`, …) wrapped in the terminfo `smacs`/`rmacs` pair. In
  screen/tmux terminfo (`screen-256color`, `tmux-256color`) that pair is
  SO/SI, which only selects line drawing once `enacs` (`ESC ( B ESC ) 0`) has
  designated G1 as the DEC graphics set. ncurses sends `enacs` on startup;
  JLine never does, so inside tmux every divider rendered as `qqqq…`.
  Terminals whose `smacs` is `ESC ( 0` (xterm, kitty, …) have no `enacs`, so
  this is a no-op there."
  []
  (when-let [s (ena-acs (System/getenv "TERM"))]
    (print s)
    (flush)))
