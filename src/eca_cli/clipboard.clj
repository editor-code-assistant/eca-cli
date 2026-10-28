(ns eca-cli.clipboard
  "Copy text to the system clipboard. Two ways at once, as neither always
  works: an OSC 52 sequence, which the terminal turns into a copy (it works
  over SSH, and in tmux with `set-clipboard on`; the renderer writes it, see
  `eca-cli.render`), and the platform's clipboard tool when there is one
  (pbcopy, wl-copy, xclip, xsel), for terminals without OSC 52."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [eca-cli.paths :as paths])
  (:import [java.util Base64]))

(defn osc52
  "The OSC 52 sequence that puts `text` in the clipboard."
  [text]
  (str "\u001b]52;c;"
       (.encodeToString (Base64/getEncoder) (.getBytes (str text) "UTF-8"))
       "\u0007"))

(defn copy-in
  "`state` copying `text` through the terminal: the renderer writes its OSC 52
  sequence once, with the next frame (`:clipboard`, a new `:id` per copy).
  The platform's tool is `copy-with-tool!`, for a cmd."
  [state text]
  (assoc state :clipboard {:id  (inc (or (get-in state [:clipboard :id]) 0))
                           :osc (osc52 text)}))

(defn- tool
  "The clipboard tool to use here, as a command vector, or nil."
  []
  (cond
    (fs/which "pbcopy")                                          ["pbcopy"]
    (and (paths/getenv "WAYLAND_DISPLAY") (fs/which "wl-copy"))  ["wl-copy"]
    (and (paths/getenv "DISPLAY") (fs/which "xclip"))            ["xclip" "-selection" "clipboard"]
    (and (paths/getenv "DISPLAY") (fs/which "xsel"))             ["xsel" "--clipboard" "--input"]))

(defn copy-with-tool!
  "Hand `text` to the platform's clipboard tool. Best effort: nothing
  happens when there's none or it fails. Its output is discarded, not
  captured: wl-copy and xclip leave a process behind to serve the clipboard,
  which would hold a captured stdout open forever. Waits 2s at most."
  [text]
  (when-let [cmd (tool)]
    (try
      (let [proc (p/process cmd {:in (str text) :out :discard :err :discard})]
        (when (= ::timeout (deref proc 2000 ::timeout))
          (p/destroy proc)))
      (catch Exception _ nil)))
  nil)
