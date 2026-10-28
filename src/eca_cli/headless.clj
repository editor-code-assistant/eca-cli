(ns eca-cli.headless
  "eca-cli without its UI, for scripts and pipes:

  - Print mode (`-p`, and whenever stdin or stdout isn't a terminal): one
    prompt, its answer on stdout, then exit. `--output-format json` prints
    a result object instead; `stream-json` the server's chat events as they
    come, one JSON object per line, then the result. While it runs, what
    the agent does goes to stderr when that's a terminal.
  - `--list-models`, `--list-chats`: what's there, as text or (`--json`) JSON.

  Nobody is there to approve a tool call: the server runs the ones trust or
  the user's config allow (`--trust` allows the rest), and eca-cli rejects
  any that would ask, saying so on stderr. Nor to answer a question: the
  server isn't told eca-cli can (so no `ask_user` tool).

  Exit codes: 0, 1 when the turn failed (a provider error, a login needed,
  the server gone). Ctrl+C stops the prompt (130)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [eca-cli.chat :as chat]
            [eca-cli.protocol :as protocol]
            [eca-cli.server :as server]
            [eca-cli.welcome :as welcome])
  (:import [java.util.concurrent BlockingQueue TimeUnit]))

;; --- Output ---

(defn- out! [s]
  (print s)
  (flush))

(defn- emit-json!
  "One JSON object on its own line (stream-json, json)."
  [m]
  (out! (str (json/generate-string m) "\n")))

(defn- warn!
  "A line on stderr, always: warnings and errors."
  [& parts]
  (binding [*out* *err*]
    (println (apply str "eca-cli: " parts))
    (flush)))

(defn- progress!
  "What the agent does, on stderr while it's a terminal (a person watching)."
  [st text]
  (when (get-in st [:opts :progress?])
    (binding [*out* *err*]
      (println text)
      (flush))))

;; --- The server ---

(defn- start-server
  "Spawn `eca server` and read it; its messages arrive on the queue. Nil
  (after saying why) when it doesn't start."
  [{:keys [eca server-args]}]
  (try
    (let [srv (-> (server/spawn! {:path eca :args server-args})
                  (assoc :pending-requests protocol/pending-requests))]
      (server/start-reader! srv)
      srv)
    (catch Exception e
      (warn! "could not start the ECA server: " (or (ex-message e) (str e)))
      nil)))

(defn- put! [srv msg]
  (.put ^BlockingQueue (:queue srv) msg))

(defn- next-msg [srv timeout-ms]
  (.poll ^BlockingQueue (:queue srv) (long timeout-ms) TimeUnit/MILLISECONDS))

(defn- request!
  "Send a request and wait (up to `timeout-ms`) for its result; nil when
  there's none. For the steps after the run, with the server still there."
  [send-fn timeout-ms]
  (let [p (promise)]
    (send-fn #(deliver p %))
    (deref p timeout-ms nil)))

(defn- answer-server-request!
  "A request from the server: questions are cancelled (nobody to answer,
  though the server shouldn't ask: `askQuestion` isn't advertised), the
  rest unsupported. Either way it doesn't wait forever."
  [srv {:keys [id method]}]
  (if (= "chat/askQuestion" method)
    (protocol/send-response! srv id {:answer nil :cancelled true})
    (protocol/send-error-response! srv id protocol/method-not-found
                                   (str "eca-cli does not support " method))))

(defn- initialize!
  "Initialize; the response arrives on the queue as ::initialized."
  [srv workspace]
  (protocol/initialize! srv workspace {:ask-question? false}
                        #(put! srv {:type ::initialized :response %})))

;; --- Print mode ---

(defn final-text
  "The answer: what the agent wrote after its last tool call, else before
  it; with no text at all, the server's notes (a command's output), unless
  the turn failed (they hold the error, said on stderr)."
  [{:keys [segment last-text notes error]}]
  (let [seg (str/trim (str segment))]
    (cond
      (seq seg)             seg
      (seq last-text)       last-text
      error                 ""
      :else                 (str/trim (str/join "\n\n" notes)))))

(defn result
  "The `json` / `stream-json` result object."
  [{:keys [opts chat-id model usage error rejected started-at finished-at] :as st}]
  (cond-> {:type       "result"
           :chatId     chat-id
           :result     (final-text st)
           :isError    (boolean error)
           :durationMs (- (or finished-at started-at 0) (or started-at 0))}
    model              (assoc :model model)
    (:agent opts)      (assoc :agent (:agent opts))
    (:variant opts)    (assoc :variant (:variant opts))
    usage              (assoc :usage usage)
    error              (assoc :error error)
    (seq rejected)     (assoc :rejectedToolCalls rejected)))

(defn- own-chat? [st params]
  (= (:chat-id st) (:chatId params)))

(defn- in-run?
  "Content of this run's chat or one of its sub-agents."
  [st params]
  (or (own-chat? st params) (some? (:parentChatId params))))

(defn- reject-tool!
  "A tool call that needs approval: rejected, nobody being there to
  approve it."
  [st {:keys [chatId content]}]
  (let [{:keys [id name server summary]} content
        tool (if (and server (not= "eca" server)) (str server "__" name) name)]
    (protocol/reject-tool! (:srv st) chatId id)
    (warn! "rejected " (or tool "a tool call")
           (some->> summary (str " (") (#(str % ")")))
           ": it needs approval. Allow it with --trust, or in your ECA config (toolCall.approval).")
    (update st :rejected (fnil conj []) (cond-> {:id id :name name}
                                          server            (assoc :server server)
                                          (not= chatId (:chat-id st)) (assoc :chatId chatId)))))

(defn- finish
  "The turn is over: `error` (the server's, when it failed) or nil."
  [st error]
  (assoc st :done? true :error error :finished-at (System/currentTimeMillis)))

(defn handle-content
  "A `chat/contentReceived` for this run (`params`)."
  [st {:keys [role content] :as params}]
  (let [own?  (own-chat? st params)
        type  (:type content)]
    (cond
      ;; Tool calls needing approval, here or in a sub-agent, can't be approved.
      (and (= "toolCallRun" type) (:manualApproval content))
      (cond-> (reject-tool! st params)
        own? (as-> s (if (str/blank? (:segment s)) s
                       (assoc s :last-text (str/trim (:segment s)) :segment ""))))

      (not own?) st

      (and (= "text" type) (= "assistant" role))
      (update st :segment str (:text content))

      (and (= "text" type) (= "system" role))
      (let [t (str/trim (str (:text content)))]
        (cond-> st (seq t) (update :notes (fnil conj []) t)))

      (= "toolCallRun" type)
      (do (progress! st (str "• " (or (:summary content) (:name content))))
          (if (str/blank? (:segment st))
            st
            (assoc st :last-text (str/trim (:segment st)) :segment "")))

      (= "usage" type)
      (assoc st :usage (dissoc content :type))

      (and (= "progress" type) (= "finished" (:state content)))
      (finish st (:error content))

      :else st)))

(defn handle-message
  "Apply one message from the queue to the run `st`."
  [st msg]
  (let [srv (:srv st)]
    (cond
      (= :reader-error (:type msg))
      (do (warn! (:error msg))
          (assoc st :done? true :exit 1 :error {:message "The ECA server went away."}))

      (= ::prompted (:type msg))
      (let [{:keys [status model]} msg]
        (case status
          "login" (do (warn! "a provider needs a login: run `eca-cli` and use /login.")
                      (assoc st :done? true :exit 1 :error {:message "Login required" :type "auth"}))
          "error" (do (warn! "the server could not run the prompt.")
                      (assoc st :done? true :exit 1 :error {:message "The prompt failed."}))
          (cond-> (assoc st :prompted? true)
            model (assoc :model model))))

      (and (:id msg) (:method msg))
      (do (answer-server-request! srv msg) st)

      (= "chat/contentReceived" (:method msg))
      (let [params (:params msg)]
        (if (in-run? st params)
          (do (when (= "stream-json" (get-in st [:opts :output-format]))
                (emit-json! {:type "chat/contentReceived" :params params}))
              (handle-content st params))
          st))

      (= "$/showMessage" (:method msg))
      (do (some-> (get-in msg [:params :message]) warn!) st)

      :else st)))

(defn prompt-params
  "chat/prompt's params for this run: the message (its `@path`s spelled
  out, as the TUI sends them), the files given with -f, the workspace files
  it names with `@`, piped stdin as a text context."
  [{:keys [prompt files stdin workspace model agent variant trust]} chat-id]
  (let [typed    (chat/contexts-for-text prompt [] workspace)
        contexts (vec (distinct (concat files typed
                                        (when (seq stdin)
                                          [{:type "text" :label "stdin" :content stdin}]))))]
    (cond-> {:chat-id chat-id
             :message (chat/expand-refs prompt typed workspace)}
      model           (assoc :model model)
      agent           (assoc :agent agent)
      variant         (assoc :variant variant)
      trust           (assoc :trust true)
      (seq contexts)  (assoc :contexts contexts))))

(defn- latest-chat
  "The workspace's most recently updated chat id (not an inline one), or nil."
  [srv]
  (let [r (request! #(protocol/list-chats! srv 20 %) 30000)]
    (->> (get-in r [:result :chats])
         (remove :kind)
         first
         :id)))

(defn- send-prompt! [st]
  (let [{:keys [srv opts chat-id]} st]
    (when (= "stream-json" (:output-format opts))
      (emit-json! (cond-> {:type "init" :chatId chat-id :workspace (:workspace opts)}
                    (:model opts) (assoc :model (:model opts)))))
    (protocol/chat-prompt! srv (prompt-params opts chat-id)
                           #(put! srv (assoc % :type ::prompted)))
    (assoc st :started-at (System/currentTimeMillis))))

(defn- output!
  "The run's outcome, in the asked format; -o writes the answer to a file."
  [st]
  (let [text (final-text st)
        fmt  (get-in st [:opts :output-format] "text")]
    (case fmt
      ("json" "stream-json") (emit-json! (result st))
      (when (seq text) (out! (str text "\n"))))
    (when-let [error (:error st)]
      (when-not (:exit st) ; already said
        (warn! (or (:message error) "the turn failed."))))
    (when-let [f (get-in st [:opts :output-last-message])]
      (try (spit (io/file f) (str text "\n"))
           (catch Exception e (warn! "could not write " f ": " (ex-message e)))))))

(defn- after-run!
  "--title names the chat (only now, so the title the server generates for
  a new chat can't replace it); --ephemeral deletes it."
  [st]
  (let [{:keys [srv chat-id opts]} st]
    (when (and chat-id (not (:ephemeral opts)) (seq (:title opts)))
      (request! #(protocol/send-request! srv "chat/update" {:chatId chat-id :title (:title opts)} %) 5000))
    (when (and chat-id (:ephemeral opts))
      (request! #(protocol/delete-chat! srv chat-id %) 5000))))

(defn- settled?
  "Done, and the prompt's answer is in (it carries the model; a quick turn
  can finish first), or it won't come anymore."
  [st]
  (and (:done? st)
       (or (:prompted? st) (:exit st)
           (> (- (System/currentTimeMillis) (:finished-at st 0)) 2000))))

(defn- run-loop
  "Handle the server's messages until the run is done."
  [st]
  (loop [st st]
    (if (settled? st)
      st
      (recur (if-let [msg (next-msg (:srv st) 100)]
               (handle-message st msg)
               st)))))

(defn- await-initialized
  "Wait for `initialize`'s answer (as long as it takes: the server may run
  commands loading its config), handling what else comes. A run state
  with :done? when it failed."
  [st]
  (loop [st st]
    (let [msg (next-msg (:srv st) 200)]
      (cond
        (nil? msg) (recur st)

        (= ::initialized (:type msg))
        (if-let [error (get-in msg [:response :error])]
          (do (warn! "the ECA server refused to initialize: " (or (:message error) (pr-str error)))
              (assoc st :done? true :exit 1))
          (do (protocol/initialized! (:srv st)) st))

        :else
        (let [st' (handle-message st msg)]
          (if (:done? st') st' (recur st')))))))

(def ^:private ready-timeout-ms
  "The longest the server's startup is waited for (MCP servers take their
  time); after it, eca-cli goes on anyway."
  120000)

(def ready-quiet-ms
  "How long the server must stay quiet, models in and no startup task
  running, to count as started (a task can start right after another ends)."
  500)

(defn await-ready
  "After `initialized`, wait for the server's startup: its models (a prompt
  sent before they're synced fails: \"No available model found\") and its
  startup tasks (`$/progress`: plugins, MCP servers, whose tools the prompt
  should have). Ready once models are in, no task runs and nothing came for
  a moment; without models, once the tasks are done and a few seconds went
  by. Returns {:chat config/updated's chat fields, :dead? the server went
  away}."
  ([srv] (await-ready srv ready-timeout-ms))
  ([srv timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop [chat {} tasks {} quiet-since nil]
       (let [now    (System/currentTimeMillis)
             msg    (next-msg srv 100)
             idle?  (every? true? (vals tasks))
             quiet  (if (and (nil? msg) quiet-since) (- now quiet-since) 0)]
         (cond
           (> now deadline)                                  {:chat chat}
           (and idle? (seq (:models chat)) (> quiet ready-quiet-ms)) {:chat chat}
           (and idle? (> quiet (* 10 ready-quiet-ms)))              {:chat chat}
           (nil? msg)                                        (recur chat tasks (or quiet-since now))

           (= :reader-error (:type msg))
           (do (warn! (:error msg)) {:chat chat :dead? true})

           (and (:id msg) (:method msg))
           (do (answer-server-request! srv msg) (recur chat tasks nil))

           (= "config/updated" (:method msg))
           (recur (merge chat (get-in msg [:params :chat])) tasks nil)

           (= "$/progress" (:method msg))
           (let [{:keys [type taskId]} (:params msg)]
             (recur chat (case type
                           "start"  (assoc tasks taskId false)
                           "finish" (assoc tasks taskId true)
                           tasks)
                    nil))

           (= "$/showMessage" (:method msg))
           (do (some-> (get-in msg [:params :message]) warn!) (recur chat tasks nil))

           :else (recur chat tasks nil)))))))

(defn print!
  "Print mode: run `(:prompt opts)` and print the answer. Returns the exit
  code. `opts`: the CLI's, plus :workspace, :eca, :server-args, :files
  (contexts), :stdin (text) and :progress? (stderr is a terminal)."
  [opts]
  (if-let [srv (start-server opts)]
    (let [st       {:srv srv :opts opts :segment "" :notes []}
          stopped? (atom false)
          running  (atom nil) ; the chat id, once prompted
          hook     (Thread. (fn []
                              ;; Ctrl+C: stop the prompt, then the server.
                              (when-not @stopped?
                                (when-let [chat-id @running]
                                  (protocol/stop-prompt! srv chat-id))
                                (server/shutdown! srv))))]
      (.addShutdownHook (Runtime/getRuntime) hook)
      (try
        (initialize! srv (:workspace opts))
        (let [st (await-initialized st)]
          (if (or (:done? st) (:dead? (await-ready srv)))
            (or (:exit st) 1)
            (let [chat-id (or (when (:continue opts)
                                (or (latest-chat srv)
                                    (do (warn! "no chat to continue in this workspace: starting a new one.")
                                        nil)))
                              (str (random-uuid)))
                  _       (reset! running chat-id)
                  st      (-> st (assoc :chat-id chat-id) send-prompt! run-loop)]
              (output! st)
              (when-not (:exit st)
                (after-run! st))
              (cond
                (:exit st)  (:exit st)
                (:error st) 1
                :else       0))))
        (finally
          (reset! stopped? true)
          (try (.removeShutdownHook (Runtime/getRuntime) hook) (catch IllegalStateException _ nil))
          (try (protocol/shutdown! srv) (catch Exception _ nil))
          (server/shutdown! srv))))
    1))

;; --- Lists ---

(defn- with-server
  "Start the server, initialize, run `(f srv)` (an exit code), shut down."
  [opts f]
  (if-let [srv (start-server opts)]
    (try
      (initialize! srv (:workspace opts))
      (let [st (await-initialized {:srv srv :opts opts})]
        (if (:done? st)
          (or (:exit st) 1)
          (f srv)))
      (finally
        (try (protocol/shutdown! srv) (catch Exception _ nil))
        (server/shutdown! srv)))
    1))

(defn list-models!
  "--list-models: one model per line, or with --json the models, agents
  and the defaults. Returns the exit code."
  [opts]
  (with-server opts
    (fn [srv]
      (let [{:keys [chat dead?]} (await-ready srv)]
        (when-not dead?
          (if (:json opts)
          (emit-json! (cond-> {:models (vec (:models chat)) :agents (vec (:agents chat))}
                        (:selectModel chat) (assoc :defaultModel (:selectModel chat))
                        (:selectAgent chat) (assoc :defaultAgent (:selectAgent chat))))
          (doseq [m (:models chat)] (out! (str m "\n")))))
        (cond
          dead?                1
          (seq (:models chat)) 0
          :else (do (warn! "no models: log in to a provider (run `eca-cli`, then /login) or configure one.")
                    1))))))

(defn chat-lines
  "--list-chats as text: id, age, messages and title, a chat per line."
  [chats now]
  (for [{:keys [id title updatedAt createdAt messageCount]} chats]
    (str/join "  " [id
                    (if-let [t (or updatedAt createdAt)] (welcome/ago t now) "-")
                    (str (or messageCount 0) " msgs")
                    (or (not-empty title) "(untitled)")])))

(defn list-chats!
  "--list-chats: the workspace's chats, latest first. Returns the exit code."
  [opts]
  (with-server opts
    (fn [srv]
      (if-let [r (request! #(protocol/list-chats! srv 0 %) 30000)]
        (let [chats (->> (get-in r [:result :chats]) (remove :kind) vec)]
          (if (:json opts)
            (emit-json! chats)
            (doseq [line (chat-lines chats (System/currentTimeMillis))]
              (out! (str line "\n"))))
          0)
        (do (warn! "the server didn't list the chats.") 1)))))
