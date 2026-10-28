(ns eca-cli.core
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl-server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [eca-cli.headless :as headless]
            [eca-cli.paths :as paths]
            [eca-cli.program :as program]
            [eca-cli.state :as state]
            [eca-cli.terminal :as terminal]
            [eca-cli.upgrade :as upgrade]
            [eca-cli.version :as version]
            [eca-cli.view :as view]))

(def ^:private cli-spec
  {:print         {:desc "Run the prompt, print the answer and exit (also when stdin or stdout isn't a terminal)"
                   :alias :p :coerce :boolean}
   :output-format {:desc "Print mode's output: text (the answer), json (a result object) or stream-json (events, one per line)"
                   :ref "<format>"}
   :continue      {:desc "Continue the workspace's latest chat" :alias :c :coerce :boolean}
   :file          {:desc "Attach a file or directory to the prompt (repeatable)" :alias :f :ref "<path>" :coerce []}
   :workspace     {:desc         "Project directory"
                   :ref          "<path>"
                   :default      (System/getProperty "user.dir")
                   :default-desc "current directory"}
   :model         {:desc "Model to use" :ref "<model>"}
   :agent         {:desc "Agent to use" :ref "<agent>"}
   :variant       {:desc "Model variant to use (e.g. reasoning effort)" :ref "<variant>"}
   :trust         {:desc "Auto-approve all tool calls" :coerce :boolean}
   :title         {:desc "Title of the chat" :ref "<title>"}
   :output-last-message {:desc "Print mode: also write the answer to this file" :alias :o :ref "<file>"}
   :ephemeral     {:desc "Print mode: delete the chat afterwards" :coerce :boolean}
   :list-models   {:desc "List the available models and exit" :coerce :boolean}
   :list-chats    {:desc "List the workspace's chats and exit" :coerce :boolean}
   :json          {:desc "JSON output for --list-models and --list-chats" :coerce :boolean}
   :fullscreen    {:desc "Take over the whole screen, instead of keeping the chat in the terminal's scrollback"
                   :coerce :boolean}
   :eca           {:desc "ECA server binary, a path or a command (default: `eca` on PATH, else auto-installed)"
                   :ref  "<path>"}
   :config-file   {:desc "ECA config file to use instead of the global and project ones" :ref "<file>"}
   :log-level     {:desc "ECA server log level: error, warn, info or debug" :ref "<level>"}
   :verbose       {:desc "Log the ECA protocol messages too (in the server log)" :coerce :boolean}
   :nrepl         {:desc "Start an nREPL server on this port (development)" :ref "<port>" :coerce :int}
   :version       {:desc "Print the version and exit" :coerce :boolean}
   :help          {:desc "Show this help and exit" :alias :h :coerce :boolean}})

(def ^:private cli-order
  [:print :output-format :continue :file :workspace :model :agent :variant :trust :title
   :output-last-message :ephemeral :list-models :list-chats :json :fullscreen :eca
   :config-file :log-level :verbose :nrepl :version :help])

(def output-formats #{"text" "json" "stream-json"})
(def log-levels #{"error" "warn" "info" "debug"})

(defn usage []
  (str "eca-cli " version/version " - ECA (Editor Code Assistant) in your terminal\n"
       "\n"
       "Usage: eca-cli [options] [prompt]\n"
       "\n"
       "Run it from your project directory, or point it at one with --workspace.\n"
       "A prompt starts the chat with it; with -p eca-cli prints the answer and exits:\n"
       "\n"
       "  eca-cli \"explain this project\"\n"
       "  eca-cli -p \"what does src/main.clj do?\"\n"
       "  git diff | eca-cli -p \"review this change\"\n"
       "  eca-cli -c \"and now add tests\"\n"
       "\n"
       "Options:\n"
       (cli/format-opts {:spec cli-spec :order cli-order})))

(defn- usage-error [msg]
  {:exit 2 :message (str "eca-cli: " msg "\nRun `eca-cli --help` for usage.")})

;; --- Parsing ---
;;
;; Not babashka.cli's parser: options after the prompt (`eca-cli "fix it"
;; --model x`) and -f's values next to the prompt (`-f a.clj "explain"`)
;; are common, and it would read them as more of the prompt or of -f.

(def ^:private aliases
  (into {} (keep (fn [[k {:keys [alias]}]] (when alias [(name alias) k]))) cli-spec))

(defn- boolean-opt? [k]
  (= :boolean (get-in cli-spec [k :coerce])))

(defn- coerce [k v]
  (case (get-in cli-spec [k :coerce])
    :int (or (parse-long v)
             (throw (ex-info (str "Invalid value for option --" (name k) ": " v) {::usage true})))
    v))

(defn- add-opt [opts k v]
  (if (= [] (get-in cli-spec [k :coerce]))
    (update opts k (fnil conj []) v)
    (assoc opts k (coerce k v))))

(defn- option? [arg]
  (and (str/starts-with? arg "-") (> (count arg) 1)))

(defn- tokenize
  "args -> [opts positional]. Long options take `--opt value` or
  `--opt=value`; short ones can be grouped (`-pc`), the last taking a value
  (`-f path`). After `--`, everything is the prompt."
  [args]
  (let [fail (fn [msg] (throw (ex-info msg {::usage true})))]
    (loop [args (seq args), opts {}, positional []]
      (if-let [arg (first args)]
        (cond
          (= "--" arg)
          (recur nil opts (into positional (rest args)))

          (str/starts-with? arg "--")
          (let [[n inline] (str/split (subs arg 2) #"=" 2)
                k          (keyword n)]
            (cond
              (not (contains? cli-spec k)) (fail (str "Unknown option: " arg))
              (boolean-opt? k)             (recur (next args) (assoc opts k true) positional)
              (some? inline)               (recur (next args) (add-opt opts k inline) positional)
              (or (nil? (second args)) (str/starts-with? (second args) "--"))
              (fail (str "Missing value for option --" n))
              :else                        (recur (nnext args) (add-opt opts k (second args)) positional)))

          (option? arg)
          (let [[opts' rest-args]
                (loop [cs (seq (subs arg 1)), opts opts]
                  (if-let [c (first cs)]
                    (let [k (or (aliases (str c)) (fail (str "Unknown option: -" c)))]
                      (if (boolean-opt? k)
                        (recur (next cs) (assoc opts k true))
                        ;; The value: the rest of this arg (-fpath), or the next one.
                        (let [inline (apply str (next cs))]
                          (cond
                            (seq inline)          [(add-opt opts k inline) (next args)]
                            (nil? (second args))  (fail (str "Missing value for option -" c))
                            :else                 [(add-opt opts k (second args)) (nnext args)]))))
                    [opts (next args)]))]
            (recur rest-args opts' positional))

          :else
          (recur (next args) opts (conj positional arg)))
        [opts positional]))))

(defn- validate
  "A usage error message for options that don't go together, or nil."
  [opts]
  (cond
    (and (:output-format opts) (not (output-formats (:output-format opts))))
    (str "--output-format must be text, json or stream-json, not " (:output-format opts))

    (and (:log-level opts) (not (log-levels (:log-level opts))))
    (str "--log-level must be error, warn, info or debug, not " (:log-level opts))

    (and (:list-models opts) (:list-chats opts))
    "--list-models and --list-chats can't be used together"

    (and (:json opts) (not (or (:list-models opts) (:list-chats opts))))
    "--json goes with --list-models or --list-chats (print mode has --output-format json)"))

(defn parse-args
  "Pure: command-line args -> {:opts opts} to start (the prompt, if any, in
  :prompt), or {:exit code :message text} for --help, --version and invalid
  input (exit 2)."
  [args]
  (try
    (let [[opts positional] (tokenize args)
          opts              (merge {:workspace (System/getProperty "user.dir")} opts)
          prompt            (not-empty (str/join " " positional))]
      (cond
        (:help opts)    {:exit 0 :message (usage)}
        (:version opts) {:exit 0 :message (str "eca-cli " version/version)}
        :else           (if-let [error (validate opts)]
                          (usage-error error)
                          {:opts (cond-> opts prompt (assoc :prompt prompt))})))
    (catch clojure.lang.ExceptionInfo e
      (if (::usage (ex-data e))
        (usage-error (ex-message e))
        (throw e)))))

;; --- Starting ---

(defn- exit-with-error!
  ([msg] (exit-with-error! msg 1))
  ([msg code]
   (binding [*out* *err*]
     (println (str "eca-cli: " msg)))
   (System/exit code)))

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

(defn file-contexts
  "-f's paths as contexts (absolute; relative ones are from the current
  directory), or {:error message} for one that doesn't exist."
  [paths]
  (reduce (fn [acc p]
            (let [f (fs/file p)]
              (if (fs/exists? f)
                (conj acc {:type (if (fs/directory? f) "directory" "file")
                           :path (str (fs/canonicalize f))})
                (reduced {:error (str "no such file or directory: " p)}))))
          []
          paths))

(defn server-args
  "What `eca server` gets from eca-cli's options."
  [{:keys [log-level verbose config-file]}]
  (cond-> []
    log-level   (into ["--log-level" log-level])
    verbose     (conj "--verbose")
    config-file (into ["--config-file" config-file])))

(defn print-mode-problem
  "Why options can't be used, given the mode (print or the TUI), or nil."
  [opts print?]
  (if print?
    (when (:fullscreen opts) "--fullscreen is for the interactive UI, not print mode")
    (some (fn [[k flag]] (when (k opts) (str flag " needs print mode (-p)")))
          [[:output-format "--output-format"]
           [:output-last-message "--output-last-message"]
           [:ephemeral "--ephemeral"]])))

(def ^:private stdin-wait-ms
  "How long piped stdin has to start coming to count."
  1000)

(defn- redirect-stdio-to-file! [path]
  (let [fos (java.io.FileOutputStream. (java.io.File. path) true)
        ps  (java.io.PrintStream. fos true)
        pw  (java.io.PrintWriter. fos true)]
    (System/setOut ps)
    (System/setErr ps)
    (alter-var-root #'*out* (constantly pw))
    (alter-var-root #'*err* (constantly pw))))

(defn- start-tui!
  "Hand the terminal to the TUI until the user quits."
  [opts]
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
                               ;; turn bracketed paste and mouse tracking off.
                               (print "\033[?25h\017\033[?2004l\033[?1006l\033[?1000l")
                               (flush))))
  (program/run {:init   (state/make-init opts)
                :update state/update-state
                :frame  view/frame}))

(defn- start!
  "Resolve the workspace, files and ECA server, then run the asked mode:
  a list, print mode, or the TUI."
  [opts]
  (let [workspace (resolve-workspace! (:workspace opts))
        files     (file-contexts (:file opts))
        _         (when (:error files) (exit-with-error! (:error files) 2))
        config    (some-> (:config-file opts) fs/file)
        _         (when (and config (not (fs/regular-file? config)))
                    (exit-with-error! (str "no such config file: " (:config-file opts)) 2))
        list?     (or (:list-models opts) (:list-chats opts))
        stdin?    (not (terminal/tty? 0))
        print?    (and (not list?) (or (:print opts) stdin? (not (terminal/tty? 1))))
        _         (when-let [problem (and (not list?) (print-mode-problem opts print?))]
                    (exit-with-error! problem 2))
        ;; Piped in: the prompt's context, or the prompt itself, if it
        ;; starts coming soon (see `terminal/read-stdin`).
        piped     (when (and print? stdin?) (terminal/read-stdin stdin-wait-ms))
        _         (when (and print? stdin? (nil? piped) (:prompt opts))
                    (binding [*out* *err*]
                      (println "eca-cli: nothing came on stdin within a second: going on without it.")))
        stdin     (some-> piped str/trim not-empty)
        prompt    (or (:prompt opts) stdin)
        _         (when (and print? (str/blank? prompt))
                    (exit-with-error! "no prompt: eca-cli -p \"…\", or pipe one in." 2))
        {:keys [path notices version]} (resolve-binary! opts)
        opts      (cond-> (assoc opts
                                 :workspace workspace
                                 :files files
                                 :eca path
                                 :server-args (server-args (cond-> opts config (assoc :config-file (str (fs/canonicalize config)))))
                                 ;; Known for the managed install only.
                                 :eca-version version
                                 :startup-notices notices
                                 :prompt prompt
                                 :progress? (terminal/tty? 2))
                    (and stdin (:prompt opts)) (assoc :stdin stdin))]
    (cond
      (:list-models opts) (System/exit (headless/list-models! opts))
      (:list-chats opts)  (System/exit (headless/list-chats! opts))
      print?              (do (doseq [n notices] (binding [*out* *err*] (println (str "eca-cli: " n))))
                              (System/exit (headless/print! opts)))
      :else               (start-tui! opts))))

(defn -main [& args]
  (let [{:keys [opts exit message]} (parse-args args)]
    (cond
      (nil? exit)  (start! opts)
      (zero? exit) (println message)
      :else        (binding [*out* *err*]
                     (println message)
                     (System/exit exit)))))
