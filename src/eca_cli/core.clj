(ns eca-cli.core
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl-server]
            [eca-cli.program :as program]
            [clojure.java.io :as io]
            [eca-cli.paths :as paths]
            [eca-cli.state :as state]
            [eca-cli.terminal :as terminal]
            [eca-cli.upgrade :as upgrade]
            [eca-cli.version :as version]
            [eca-cli.view :as view]))

(def ^:private cli-spec
  {:workspace {:desc         "Project directory"
               :ref          "<path>"
               :default      (System/getProperty "user.dir")
               :default-desc "current directory"}
   :model     {:desc "Model to use" :ref "<model>"}
   :agent     {:desc "Agent to use" :ref "<agent>"}
   :trust     {:desc "Auto-approve all tool calls" :coerce :boolean}
   :eca       {:desc "ECA server binary, a path or a command (default: `eca` on PATH, else auto-installed)"
               :ref  "<path>"}
   :nrepl     {:desc "Start an nREPL server on this port (development)" :ref "<port>" :coerce :int}
   :version   {:desc "Print the version and exit" :coerce :boolean}
   :help      {:desc "Show this help and exit" :alias :h :coerce :boolean}})

(def ^:private cli-order [:workspace :model :agent :trust :eca :nrepl :version :help])

(defn usage []
  (str "eca-cli " version/version " - ECA (Editor Code Assistant) in your terminal\n"
       "\n"
       "Usage: eca-cli [options]\n"
       "\n"
       "Run it from your project directory, or point it at one with --workspace.\n"
       "\n"
       "Options:\n"
       (cli/format-opts {:spec cli-spec :order cli-order})))

(defn- usage-error [msg]
  {:exit 1 :message (str "eca-cli: " msg "\nRun `eca-cli --help` for usage.")})

(defn parse-args
  "Pure: command-line args -> {:opts opts} to start the TUI, or
  {:exit code :message text} for --help, --version and invalid input."
  [args]
  (try
    (let [{:keys [opts] positional :args} (cli/parse-args args {:spec cli-spec :restrict true})]
      (cond
        (:help opts)     {:exit 0 :message (usage)}
        (:version opts)  {:exit 0 :message (str "eca-cli " version/version)}
        (seq positional) (usage-error (str "unexpected argument: " (first positional)))
        :else            {:opts opts}))
    (catch clojure.lang.ExceptionInfo e
      (if (= :org.babashka/cli (:type (ex-data e)))
        (usage-error (ex-message e))
        (throw e)))))

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

(defn- start!
  "Resolve the workspace and ECA server, then hand the terminal to the TUI
  until the user quits."
  [opts]
  (let [workspace              (resolve-workspace! (:workspace opts))
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
                                 ;; Show the cursor, shift back in to G0 (SI) in
                                 ;; case we died mid alternate-charset run, and
                                 ;; turn bracketed paste back off.
                                 (print "\033[?25h\017\033[?2004l")
                                 (flush))))
    (program/run {:init       (state/make-init opts)
                  :update     state/update-state
                  :view       view/view
                  :alt-screen true
                  :mouse      :normal
                  :fps        20})))

(defn -main [& args]
  (let [{:keys [opts exit message]} (parse-args args)]
    (cond
      (nil? exit)  (start! opts)
      (zero? exit) (println message)
      :else        (binding [*out* *err*]
                     (println message)
                     (System/exit exit)))))
