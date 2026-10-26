(ns eca-cli.server
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [eca-cli.paths :as paths])
  (:import [java.io BufferedInputStream BufferedWriter OutputStreamWriter]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn find-eca-binary
  "Non-installing lookup, for REPL/dev use: `eca` on PATH, else the managed
  binary if present. `bb run` resolves through `upgrade/resolve-binary!`
  instead, which also installs and updates the managed binary."
  []
  (or (some-> (fs/which "eca") str)
      (let [f (paths/eca-binary)]
        (when (.exists f) (str f)))))

(defn- ensure-writable
  "Ensures parent dir exists and returns the file if its parent is a directory, else nil."
  [^java.io.File f]
  (io/make-parents f)
  (when (.isDirectory (.getParentFile f))
    f))

(defn spawn!
  "Spawns the ECA server process. Returns a map with :process, :reader,
  :writer, :queue, :alive? and :log-file (where the server's stderr goes, nil
  when inherited)."
  ([] (spawn! {}))
  ([{:keys [path log-file]}]
   (let [binary    (or path (find-eca-binary))
         _         (when-not binary
                     (throw (ex-info
                              (str "ECA binary not found. Run `bb upgrade-eca` to install it, "
                                   "or pass --eca /path/to/eca.")
                              {})))
         candidate (or log-file (paths/log-file))
         log       (ensure-writable candidate)
         err       (or log
                       (do (.println System/err (str "eca-cli: warning: could not create " (.getParent candidate) " — ECA logs will appear in terminal"))
                           :inherit))
         p         (proc/process [binary "server"]
                                 {:err err :shutdown proc/destroy-tree})
         reader    (BufferedInputStream. (:out p))
         writer    (BufferedWriter. (OutputStreamWriter. (:in p) "UTF-8"))
         queue     (LinkedBlockingQueue.)]
     {:process  p
      :reader   reader
      :writer   writer
      :queue    queue
      :alive?   (atom true)
      :log-file log})))

;; --- JSON-RPC framing ---

(defn- read-line-from-stream
  "Reads one CRLF-terminated line from a BufferedInputStream. Returns nil on EOF."
  [^BufferedInputStream stream]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read stream)]
        (cond
          (= b -1)           (when (pos? (.length sb)) (.toString sb))
          (= b (int \return)) (do (.read stream) (.toString sb)) ;; consume \n
          (= b (int \newline)) (.toString sb)
          :else              (do (.append sb (char b)) (recur)))))))

(defn- read-content-length
  "Reads headers from the stream until the blank line, returns Content-Length."
  [^BufferedInputStream stream]
  (loop [content-length nil]
    (let [line (read-line-from-stream stream)]
      (cond
        (nil? line) nil
        (= "" line) content-length
        :else
        (let [cl (when (.startsWith line "Content-Length: ")
                   (parse-long (subs line 16)))]
          (recur (or cl content-length)))))))

(defn- read-body
  "Reads exactly n bytes from the stream and decodes as UTF-8."
  [^BufferedInputStream stream n]
  (let [buf (byte-array n)]
    (loop [offset 0]
      (when (< offset n)
        (let [read (.read stream buf offset (- n offset))]
          (when (pos? read)
            (recur (+ offset read))))))
    (String. buf "UTF-8")))

(defn read-message!
  "Blocking read of one JSON-RPC message from the stream. Returns parsed map or nil on EOF."
  [^BufferedInputStream stream]
  (when-let [content-length (read-content-length stream)]
    (let [body (read-body stream content-length)]
      (json/parse-string body true))))

(defn write-message!
  "Writes a JSON-RPC message with Content-Length framing. Serialized on the
  writer, since the update loop, cmd threads and response callbacks all send.
  Returns true when written, false when the server is gone (no writer, or its
  stdin is closed): the reader thread observes the exit and reports it, so a
  write racing a dying server must not crash the UI."
  [^BufferedWriter writer msg]
  (if-not writer
    false
    (let [body   (json/generate-string msg)
          bytes  (.getBytes body "UTF-8")
          header (str "Content-Length: " (count bytes) "\r\n\r\n")]
      (try
        (locking writer
          (.write writer header)
          (.write writer body)
          (.flush writer))
        true
        (catch java.io.IOException _ false)))))

;; --- Exit report ---

(defn log-tail
  "Last `n` non-blank lines of `file`, or [] when it can't be read."
  [file n]
  (try
    (->> (str/split-lines (slurp file))
         (remove str/blank?)
         (take-last n)
         vec)
    (catch Exception _ [])))

(defn exit-code
  "Exit code of the server process, waiting up to `wait-ms` for an exit in
  progress to finish; nil while it's still running."
  [srv wait-ms]
  (when-let [^Process p (some-> srv :process :proc)]
    (when (.waitFor p (long wait-ms) TimeUnit/MILLISECONDS)
      (.exitValue p))))

(defn format-exit-report
  "User-facing explanation of a server that went away: why (exit code or
  error), then the tail of its stderr log, where the real cause usually is."
  [{:keys [exit-code error log-file log-lines]}]
  (str/join "\n"
            (concat
              [(cond
                 exit-code (str "ECA server exited unexpectedly (exit code " exit-code ").")
                 error     (str "Lost connection to the ECA server: " error)
                 :else     "Lost connection to the ECA server.")]
              (cond
                (seq log-lines) (cons (str "Last lines of " log-file ":")
                                      ;; A gutter sets the log apart from
                                      ;; the report around it.
                                      (map #(str "│ " %) log-lines))
                log-file        [(str "It wrote nothing to " log-file ".")]))))

(defn exit-report
  "Build the exit report for `srv`; `error` is the reader exception, if any."
  [srv error]
  (let [log-file (:log-file srv)]
    (format-exit-report {:exit-code (exit-code srv 2000)
                         :error     error
                         :log-file  (some-> log-file paths/home-relative)
                         :log-lines (when log-file (log-tail log-file 8))})))

;; --- Reader thread ---

(defn start-reader!
  "Starts a background thread that reads JSON-RPC messages and dispatches them.
   Responses (have :id, no :method) go to pending-requests callbacks.
   Notifications (have :method) go onto the queue for charm.clj.
   When the server goes away unexpectedly, queues a :reader-error whose
   :error is a user-facing exit report (see `exit-report`)."
  [{:keys [reader queue alive? pending-requests] :as srv}]
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (when @alive?
                   (if-let [msg (read-message! reader)]
                     (do (if (and (:id msg) (not (:method msg)))
                           ;; Response — invoke pending callback. A failing
                           ;; callback must not take the connection down.
                           (when-let [cb (get @pending-requests (:id msg))]
                             (swap! pending-requests dissoc (:id msg))
                             (try (cb msg) (catch Exception _ nil)))
                           ;; Notification — queue for charm.clj
                           (.put queue msg))
                         (recur))
                     ;; nil = EOF: ECA process died unexpectedly
                     (when @alive?
                       (.put queue {:type :reader-error :error (exit-report srv nil)})))))
               (catch Exception e
                 (when @alive?
                   (.put queue {:type :reader-error :error (exit-report srv (str e))}))))))]
    (.setDaemon t true)
    (.setName t "eca-cli-reader")
    (.start t)
    t))

(defn read-batch!
  "Non-blocking drain of all available messages from the queue.
   Returns a vector of messages (may be empty)."
  [^LinkedBlockingQueue queue timeout-ms]
  (let [first-msg (.poll queue timeout-ms TimeUnit/MILLISECONDS)]
    (if first-msg
      (loop [msgs [first-msg]]
        (if-let [next-msg (.poll queue)]
          (recur (conj msgs next-msg))
          msgs))
      [])))

;; --- Lifecycle ---

(defn shutdown!
  "Stops the server process (tree). Nil-safe: there's no server when it
  failed to spawn."
  [{:keys [process alive?]}]
  (when alive?
    (reset! alive? false))
  (when process
    (proc/destroy-tree process)))
