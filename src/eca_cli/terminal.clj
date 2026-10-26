(ns eca-cli.terminal
  "Terminal setup that charm.clj / JLine don't do for us."
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.jline.terminal TerminalBuilder]
           [org.jline.utils InfoCmp$Capability]))

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
