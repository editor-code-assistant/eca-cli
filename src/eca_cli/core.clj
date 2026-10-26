(ns eca-cli.core
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl-server]
            [charm.program :as program]
            [clojure.java.io :as io]
            [eca-cli.paths :as paths]
            [eca-cli.state :as state]
            [eca-cli.terminal :as terminal]
            [eca-cli.upgrade :as upgrade]
            [eca-cli.view :as view]))

(def ^:private cli-spec
  {:trust     {:desc "Auto-approve all tool calls" :coerce :boolean}
   :workspace {:desc "Workspace path" :default (System/getProperty "user.dir")}
   :model     {:desc "Model to use"}
   :agent     {:desc "Agent to use"}
   :eca       {:desc "Path to ECA binary (default: `eca` on PATH, else auto-installed)"}
   :nrepl     {:desc "Start nREPL server on given port" :coerce :int}})

(defn- exit-with-error! [msg]
  (binding [*out* *err*]
    (println (str "eca-cli: " msg)))
  (System/exit 1))

(defn- resolve-workspace!
  "Absolute, canonical workspace path (the server gets it as a file:// URI)."
  [workspace]
  (if (fs/directory? workspace)
    (str (fs/canonicalize workspace))
    (exit-with-error! (str "workspace " workspace " is not a directory."))))

(defn- resolve-binary!
  "Find the ECA binary, installing or updating the managed one if needed —
  before the TUI takes over the terminal, so progress and errors print plainly."
  [opts]
  (try
    (upgrade/resolve-binary! opts (upgrade/console-reporter))
    (catch Exception e
      (exit-with-error! (or (ex-message e) (str e))))))

(defn- redirect-stdio-to-file! [path]
  (let [fos (java.io.FileOutputStream. (java.io.File. path) true)
        ps  (java.io.PrintStream. fos true)
        pw  (java.io.PrintWriter. fos true)]
    (System/setOut ps)
    (System/setErr ps)
    (alter-var-root #'*out* (constantly pw))
    (alter-var-root #'*err* (constantly pw))))

(defn -main [& args]
  (let [opts                   (cli/parse-opts args {:spec cli-spec})
        workspace              (resolve-workspace! (:workspace opts))
        {:keys [path notices]} (resolve-binary! opts)
        opts                   (assoc opts
                                      :workspace workspace
                                      :eca path
                                      :startup-notices notices)]
    ;; Terminal writes go before the --nrepl stdio redirect below.
    (terminal/enable-alt-charset!)
    (when-let [port (:nrepl opts)]
      (println (str "Starting nREPL server on port " port "..."))
      (let [log (paths/nrepl-log-file)]
        (io/make-parents log)
        (redirect-stdio-to-file! (str log)))
      (nrepl-server/start-server! {:host "localhost" :port port :quiet true}))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 ;; Show the cursor and shift back in to G0 (SI)
                                 ;; in case we died mid alternate-charset run.
                                 (print "\033[?25h\017")
                                 (flush))))
    (program/run {:init       (state/make-init opts)
                  :update     state/update-state
                  :view       view/view
                  :alt-screen true
                  :mouse      :normal
                  :fps        20})))
