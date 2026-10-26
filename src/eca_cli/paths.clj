(ns eca-cli.paths
  "XDG-aware path resolution for eca-cli (cache, state, config)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn getenv
  "Indirection over `System/getenv` so tests can stub via `with-redefs`."
  [k]
  (System/getenv k))

(defn- expand-or [env-var fallback-relative]
  (let [v (getenv env-var)]
    (if (and v (seq v))
      (io/file v)
      (io/file (System/getProperty "user.home") fallback-relative))))

(defn cache-home  [] (expand-or "XDG_CACHE_HOME"  ".cache"))
(defn state-home  [] (expand-or "XDG_STATE_HOME"  ".local/state"))
(defn config-home [] (expand-or "XDG_CONFIG_HOME" ".config"))

(defn eca-cache-dir [] (io/file (cache-home) "eca"))
(defn eca-state-dir [] (io/file (state-home) "eca"))

(defn home-relative
  "`path` with the home directory abbreviated to `~`, for display."
  [path]
  (let [path (str path)
        home (System/getProperty "user.home")]
    (if (and (seq home) (str/starts-with? path (str home "/")))
      (str "~" (subs path (count home)))
      path)))

(defn chats-file     [] (io/file (eca-state-dir) "eca-cli-chats.edn"))
(defn log-file       [] (io/file (eca-state-dir) "eca-cli.log"))
(defn nrepl-log-file [] (io/file (eca-state-dir) "eca-cli-nrepl.log"))

;; Managed ECA server install (auto-downloaded / auto-updated by eca-cli).
(defn eca-binary-dir     [] (io/file (eca-cache-dir) "eca-cli"))
(defn eca-binary         [] (io/file (eca-binary-dir) "eca"))
(defn eca-version-file   [] (io/file (eca-binary-dir) "eca-version"))
(defn release-check-file [] (io/file (eca-binary-dir) "latest-release.edn"))

;; Legacy read-only fallbacks for transparent migration: the pre-XDG cache
;; location (both the renamed "chats" file and the original "sessions" file).
(defn legacy-chats-file []
  (io/file (System/getProperty "user.home") ".cache" "eca" "eca-cli-chats.edn"))

(defn legacy-sessions-file []
  (io/file (System/getProperty "user.home") ".cache" "eca" "eca-cli-sessions.edn"))
