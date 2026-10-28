(ns eca-cli.browser
  "Open a URL in the user's browser, as eca-emacs's `browse-url` does for a
  provider login: `open` on macOS, `xdg-open` in a Linux graphical session.
  Never from a bare terminal session (SSH, a console): `xdg-open` there may
  start a text browser on eca-cli's own terminal. The URL is shown and copied
  anyway (see `eca-cli.login`)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [eca-cli.paths :as paths]))

(defn command
  "The command opening `url` here, or nil when there's no browser to open."
  [url]
  (cond
    (and (str/starts-with? (str (System/getProperty "os.name")) "Mac") (fs/which "open"))
    ["open" (str url)]

    (and (or (not-empty (paths/getenv "DISPLAY")) (not-empty (paths/getenv "WAYLAND_DISPLAY")))
         (fs/which "xdg-open"))
    ["xdg-open" (str url)]))

(defn open!
  "Hand `url` to the browser when there's one, not waiting for it; true when
  handed. Its output is discarded: a browser starting may print anything."
  [url]
  (if-let [cmd (command url)]
    (try (p/process cmd {:out :discard :err :discard}) true
         (catch Exception _ false))
    false))
