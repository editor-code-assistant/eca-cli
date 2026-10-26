(ns eca-cli.protocol
  (:require [eca-cli.server :as server]
            [eca-cli.version :as version]))

(def ^:private request-id (atom 0))
(def pending-requests (atom {}))

(defn next-id! [] (swap! request-id inc))

;; --- Send helpers ---

(defn send-request!
  "Sends a JSON-RPC request and registers a callback for the response.
  Returns the request id, or nil when the server is gone (the callback is
  then never called)."
  [srv method params callback]
  (let [id (next-id!)
        msg {:jsonrpc "2.0" :id id :method method :params params}]
    (swap! pending-requests assoc id callback)
    (if (server/write-message! (:writer srv) msg)
      id
      (do (swap! pending-requests dissoc id)
          nil))))

(defn send-notification!
  "Sends a JSON-RPC notification (no response expected). Returns false when
  the server is gone."
  [srv method params]
  (server/write-message! (:writer srv)
                         {:jsonrpc "2.0" :method method :params params}))

;; --- Message constructors ---

(defn initialize-params
  "Builds the initialize request params. `processId` lets the server exit on
  its own if eca-cli dies without a clean shutdown (e.g. SIGKILL)."
  [workspace-path]
  (let [name (.getName (java.io.File. workspace-path))]
    {:processId (.pid (java.lang.ProcessHandle/current))
     :clientInfo {:name "eca-cli" :version version/version}
     :capabilities {:codeAssistant {:chat true}}
     :workspaceFolders [{:uri (str "file://" workspace-path)
                         :name name}]}))

(defn chat-prompt-params
  "Builds chat/prompt request params."
  [{:keys [chat-id message model agent trust contexts]}]
  (cond-> {:message message}
    chat-id  (assoc :chatId chat-id)
    model    (assoc :model model)
    agent    (assoc :agent agent)
    (some? trust) (assoc :trust (boolean trust))
    contexts (assoc :contexts contexts)))

(defn tool-approve-params
  "`save` \"session\" makes the server remember the approval for the chat and
  its sub-agents: the whole tool, or for shell/git just the approved commands."
  ([chat-id tool-call-id] (tool-approve-params chat-id tool-call-id nil))
  ([chat-id tool-call-id save]
   (cond-> {:chatId chat-id :toolCallId tool-call-id}
     save (assoc :save save))))

(defn tool-reject-params [chat-id tool-call-id]
  {:chatId chat-id :toolCallId tool-call-id})

(defn prompt-stop-params [chat-id]
  {:chatId chat-id})

;; --- Provider / login ---

(defn providers-list!
  "Lists all providers and their auth status."
  [srv callback]
  (send-request! srv "providers/list" {} callback))

(defn providers-login!
  "Initiates login for a provider. Pass nil method on first call to get available methods."
  [srv provider method callback]
  (send-request! srv "providers/login"
                 (cond-> {:provider provider}
                   method (assoc :method method))
                 callback))

(defn providers-login-input!
  "Submits collected login input data (API key, auth code, etc.)."
  [srv provider data callback]
  (send-request! srv "providers/loginInput"
                 {:provider provider :data data}
                 callback))

;; --- High-level lifecycle ---

(defn initialize!
  "Sends the initialize request; `callback` receives the raw response. Not
  bounded by a timeout: the server may legitimately take a while (e.g. config
  commands fetching provider tokens), and a server that dies instead is
  reported by the reader thread. Send `initialized!` once the response is in."
  [srv workspace-path callback]
  (send-request! srv "initialize" (initialize-params workspace-path) callback))

(defn initialized! [srv]
  (send-notification! srv "initialized" {}))

(defn shutdown!
  "Sends shutdown request, then exit notification. Doesn't wait for an answer
  when the request couldn't even be sent."
  [srv]
  (let [done (promise)]
    (when (send-request! srv "shutdown" {} (fn [_] (deliver done true)))
      (deref done 5000 nil))
    (send-notification! srv "exit" {})))

(defn chat-prompt!
  "Sends a chat/prompt request. Calls callback with {:chat-id, :model, :status}."
  [srv params callback]
  (send-request! srv "chat/prompt" (chat-prompt-params params)
                 (fn [response]
                   (let [r (:result response)]
                     (callback {:chat-id (:chatId r)
                                :model (:model r)
                                :status (:status r)})))))

(defn approve-tool!
  ([srv chat-id tool-call-id] (approve-tool! srv chat-id tool-call-id nil))
  ([srv chat-id tool-call-id save]
   (send-notification! srv "chat/toolCallApprove"
                       (tool-approve-params chat-id tool-call-id save))))

(defn reject-tool! [srv chat-id tool-call-id]
  (send-notification! srv "chat/toolCallReject"
                      (tool-reject-params chat-id tool-call-id)))

(defn stop-prompt! [srv chat-id]
  (send-notification! srv "chat/promptStop"
                      (prompt-stop-params chat-id)))

(defn selected-model-changed! [srv model]
  (send-notification! srv "chat/selectedModelChanged" {:model model}))

(defn selected-agent-changed! [srv agent]
  (send-notification! srv "chat/selectedAgentChanged" {:agent agent}))

(defn update-chat!
  "chat/update: change a chat's `:title` and/or `:trust`. Trust applies at
  once, even to the running prompt."
  [srv chat-id fields]
  (send-request! srv "chat/update" (assoc fields :chatId chat-id) (fn [_])))

(defn mcp-connect-server! [srv name]
  (send-notification! srv "mcp/connectServer" {:name name}))

(defn list-chats! [srv callback]
  (send-request! srv "chat/list" {:limit 20} callback))

(defn open-chat! [srv chat-id callback]
  (send-request! srv "chat/open" {:chatId chat-id} callback))

(defn delete-chat! [srv chat-id callback]
  (send-request! srv "chat/delete" {:chatId chat-id} callback))

(defn chat-query-files!
  "Fuzzy-searches workspace files via ECA. Empty query returns the full set
  (server-capped). Response: {:files [{:type :file :path string ...}]}."
  [srv chat-id query callback]
  (send-request! srv "chat/queryFiles"
                 (cond-> {:query (or query "")}
                   chat-id (assoc :chatId chat-id))
                 callback))

(defn chat-query-commands!
  "The server's commands (native ones, MCP prompts, custom prompts, skills).
  Response: {:commands [{:name :description :type :arguments}]}, names
  without the `/`."
  [srv chat-id query callback]
  (send-request! srv "chat/queryCommands"
                 (cond-> {:query (or query "")}
                   chat-id (assoc :chatId chat-id))
                 callback))

;; --- Background jobs ---

(defn jobs-list! [srv callback]
  (send-request! srv "jobs/list" {} callback))

(defn jobs-kill! [srv job-id callback]
  (send-request! srv "jobs/kill" {:jobId job-id} callback))

(defn jobs-read-output! [srv job-id callback]
  (send-request! srv "jobs/readOutput" {:jobId job-id} callback))
