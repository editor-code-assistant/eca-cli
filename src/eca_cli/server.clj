(ns eca-cli.server
  (:require [cheshire.core :as json]
            [babashka.process :as proc])
  (:import [java.io BufferedInputStream BufferedWriter OutputStreamWriter]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn find-eca-binary
  "Finds the ECA binary. Preference: eca-cli managed, PATH, editor plugin caches."
  []
  (let [home (System/getProperty "user.home")]
    (or (let [p (str home "/.cache/eca/eca-cli/eca")]
          (when (.exists (java.io.File. p)) p))
        (some-> (proc/process ["which" "eca"] {:err :string :out :string})
                deref :out clojure.string/trim
                not-empty)
        (some (fn [path]
                (when (.exists (java.io.File. path)) path))
              [(str home "/.cache/nvim/eca/eca")
               (str home "/Library/Caches/nvim/eca/eca")
               (str home "/.emacs.d/eca/eca")]))))

(defn- default-log-file []
  (let [dir (java.io.File. (str (System/getProperty "user.home") "/.cache/eca"))]
    (.mkdirs dir)
    (when (.isDirectory dir)
      (java.io.File. dir "eca-cli.log"))))

(defn spawn!
  "Spawns the ECA server process. Returns a map with :process, :reader, :writer, :queue."
  ([] (spawn! {}))
  ([{:keys [path log-file]}]
   (let [binary (or path (find-eca-binary))
         _      (when-not binary
                  (throw (ex-info
                           (str "ECA binary not found.\n"
                                "Install via a supported editor plugin or download from:\n"
                                "  https://github.com/editor-code-assistant/eca/releases\n"
                                "Or specify path with --eca <path>")
                           {})))
         log    (or log-file (default-log-file))
         err    (or log
                    (do (.println System/err "eca-cli: warning: could not create ~/.cache/eca/ — ECA logs will appear in terminal")
                        :inherit))
         p      (proc/process [binary "server"]
                              {:err err :shutdown proc/destroy-tree})
         reader (BufferedInputStream. (:out p))
         writer (BufferedWriter. (OutputStreamWriter. (:in p) "UTF-8"))
         queue (LinkedBlockingQueue.)]
     {:process p
      :reader reader
      :writer writer
      :queue queue
      :alive? (atom true)})))

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
  "Writes a JSON-RPC message with Content-Length framing."
  [^BufferedWriter writer msg]
  (let [body (json/generate-string msg)
        bytes (.getBytes body "UTF-8")
        header (str "Content-Length: " (count bytes) "\r\n\r\n")]
    (.write writer header)
    (.write writer body)
    (.flush writer)))

;; --- Reader thread ---

(defn start-reader!
  "Starts a background thread that reads JSON-RPC messages and dispatches them.
   Responses (have :id, no :method) go to pending-requests callbacks.
   Notifications (have :method) go onto the queue for charm.clj."
  [{:keys [reader queue alive? pending-requests]}]
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (when @alive?
                   (if-let [msg (read-message! reader)]
                     (do (if (and (:id msg) (not (:method msg)))
                           ;; Response — invoke pending callback
                           (when-let [cb (get @pending-requests (:id msg))]
                             (swap! pending-requests dissoc (:id msg))
                             (cb msg))
                           ;; Notification — queue for charm.clj
                           (.put queue msg))
                         (recur))
                     ;; nil = EOF: ECA process died unexpectedly
                     (when @alive?
                       (.put queue {:type :reader-error :error "ECA process disconnected"})))))
               (catch Exception e
                 (when @alive?
                   (.put queue {:type :reader-error :error (str e)}))))))]
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
  "Gracefully stops the server."
  [{:keys [process alive?]}]
  (reset! alive? false)
  (when process
    (proc/destroy-tree process)))
