(ns eca-cli.protocol
  (:require [eca-cli.server :as server]))

(def ^:private request-id (atom 0))
(def pending-requests (atom {}))

(defn next-id! [] (swap! request-id inc))

;; --- Send helpers ---

(defn send-request!
  "Sends a JSON-RPC request and registers a callback for the response."
  [srv method params callback]
  (let [id (next-id!)
        msg {:jsonrpc "2.0" :id id :method method :params params}]
    (swap! pending-requests assoc id callback)
    (server/write-message! (:writer srv) msg)
    id))

(defn send-notification!
  "Sends a JSON-RPC notification (no response expected)."
  [srv method params]
  (server/write-message! (:writer srv)
                         {:jsonrpc "2.0" :method method :params params}))

;; --- Message constructors ---

(defn initialize-params
  "Builds the initialize request params."
  [workspace-path]
  (let [name (.getName (java.io.File. workspace-path))]
    {:processId nil
     :clientInfo {:name "eca-cli" :version "0.1.0"}
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
    trust    (assoc :trust true)
    contexts (assoc :contexts contexts)))

(defn tool-approve-params [chat-id tool-call-id]
  {:chatId chat-id :toolCallId tool-call-id})

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
  "Sends initialize request, waits for response, sends initialized notification.
   Returns the initialize result (contains welcome message etc.)."
  [srv workspace-path]
  (let [result (promise)]
    (send-request! srv "initialize" (initialize-params workspace-path)
                   (fn [response]
                     (deliver result (or (:result response) (:error response)))))
    (let [res (deref result 10000 ::timeout)]
      (when (= res ::timeout)
        (throw (ex-info "Initialize timed out" {})))
      (send-notification! srv "initialized" {})
      res)))

(defn shutdown!
  "Sends shutdown request, then exit notification."
  [srv]
  (let [done (promise)]
    (send-request! srv "shutdown" {} (fn [_] (deliver done true)))
    (deref done 5000 nil)
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

(defn approve-tool! [srv chat-id tool-call-id]
  (send-notification! srv "chat/toolCallApprove"
                      (tool-approve-params chat-id tool-call-id)))

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

(defn list-chats! [srv callback]
  (send-request! srv "chat/list" {:limit 20} callback))

(defn open-chat! [srv chat-id callback]
  (send-request! srv "chat/open" {:chatId chat-id} callback))

(defn delete-chat! [srv chat-id callback]
  (send-request! srv "chat/delete" {:chatId chat-id} callback))
