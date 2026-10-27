#!/usr/bin/env bb
;; A scripted stand-in for the ECA server, to record eca-cli's demo (`bb demo`,
;; see eca-cli.demo). It speaks just enough of the protocol for one
;; conversation, with made-up models, chats, MCP servers and an answer written
;; in advance, so the recording shows nobody's account or code:
;;
;;   eca-cli --eca build/demo/fake_eca.clj --workspace <a demo project>
;;
;; The conversation: the agent reads the file the prompt mentions, asks to edit
;; it (a diff to approve), then sums up.
(ns fake-eca
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def version "0.168.0")

;; --- JSON-RPC over stdio, Content-Length framed ---

(def ^:private lock (Object.))

(defn- send! [msg]
  (let [body (.getBytes ^String (json/generate-string (assoc msg :jsonrpc "2.0")) "UTF-8")]
    (locking lock
      (.write System/out (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "UTF-8"))
      (.write System/out body)
      (.flush System/out))))

(defn- respond! [id result] (send! {:id id :result result}))
(defn- notify! [method params] (send! {:method method :params params}))

(defn- header-line [^java.io.InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.length sb)) (str sb))
          (= 10 b) (str/trimr (str sb))
          :else    (do (.append sb (char b)) (recur)))))))

(defn- read-message
  "The next message, or nil at the end of the input."
  [^java.io.InputStream in]
  (loop [len nil]
    (when-let [line (header-line in)]
      (cond
        (and (str/blank? line) len)
        (let [buf (byte-array len)]
          (loop [off 0]
            (when (< off len)
              (let [n (.read in buf off (- len off))]
                (when (pos? n) (recur (+ off n))))))
          (json/parse-string (String. buf "UTF-8") true))

        (str/blank? line) (recur nil)
        :else (recur (or (some-> (re-find #"(?i)^content-length:\s*(\d+)" line) second parse-long) len))))))

;; --- The made-up world ---

(def ^:private workspace (atom nil))

(def ^:private model "anthropic/claude-sonnet-4-6")

(defn- now [] (System/currentTimeMillis))

(defn- rel [path] (subs path (inc (count @workspace))))

(defn- files []
  (->> (file-seq (io/file @workspace))
       (remove #(.isDirectory ^java.io.File %))
       (map str)
       (remove #(str/includes? (rel %) "/."))
       (remove #(str/starts-with? (rel %) "."))
       sort))

(defn- matching
  "Files (and, for a query, their folders) whose path has `query`, basename
  matches first, as the server orders them."
  [query]
  (let [q     (str/lower-case (str query))
        hit?  #(str/includes? (str/lower-case (rel %)) q)
        fs    (filter hit? (files))
        dirs  (when (seq q)
                (->> (files) (map #(str (.getParent (io/file %)))) distinct
                     (remove #{@workspace}) (filter hit?)))
        score #(if (str/includes? (str/lower-case (.getName (io/file %))) q) 0 1)]
    (concat (map (fn [d] {:type "directory" :path d}) dirs)
            (map (fn [f] {:type "file" :path f}) (sort-by score fs)))))

(defn- chat-content! [chat-id role content]
  (notify! "chat/contentReceived" {:chatId chat-id :role role :content content}))

(defn- stream!
  "`text` as the model writes it: a few words at a time."
  [chat-id text]
  (doseq [chunk (re-seq #"\S+\s*|\s+" text)]
    (chat-content! chat-id "assistant" {:type "text" :text chunk})
    (Thread/sleep 28)))

(def ^:private approvals (atom {}))

(defn- tool! [chat-id {:keys [id name summary arguments details output approve? ms]}]
  (let [base {:id id :name name :server "eca" :origin "native" :summary summary}]
    (chat-content! chat-id "assistant" (assoc base :type "toolCallPrepare"
                                              :argumentsText (json/generate-string arguments)))
    (Thread/sleep 250)
    (chat-content! chat-id "assistant" (cond-> (assoc base :type "toolCallRun" :arguments arguments
                                                      :manualApproval (boolean approve?))
                                         details (assoc :details details)))
    (when approve?
      (let [answer (promise)]
        (swap! approvals assoc id answer)
        (deref answer 120000 :reject)))
    (chat-content! chat-id "assistant" (assoc base :type "toolCallRunning" :arguments arguments))
    (Thread/sleep (or ms 300))
    (chat-content! chat-id "assistant" (cond-> (assoc base :type "toolCalled" :arguments arguments
                                                      :outputs [{:type "text" :text output}]
                                                      :error false :totalTimeMs (or ms 300))
                                         details (assoc :details details)))))

(def ^:private diff
  (str/join "\n"
            ["@@ -2,6 +2,13 @@"
             "   (:require [babashka.http-client :as http]))"
             " "
             " (defn fetch"
             "-  \"GETs `url`, returning the response body.\""
             "+  \"GETs `url`, returning the response body. Timeouts and 5xx responses"
             "+  are retried, waiting 200ms, 400ms, then 800ms.\""
             "   [url]"
             "-  (:body (http/get url {:timeout 5000})))"
             "+  (loop [attempt 0]"
             "+    (let [resp (try (http/get url {:timeout 5000 :throw false})"
             "+                    (catch java.net.http.HttpTimeoutException _ nil))]"
             "+      (if (and (< attempt 3) (or (nil? resp) (>= (:status resp) 500)))"
             "+        (do (Thread/sleep (* 200 (bit-shift-left 1 attempt)))"
             "+            (recur (inc attempt)))"
             "+        (:body resp)))))"]))

(defn- conversation!
  "The answer to the demo's prompt."
  [chat-id message]
  (let [path (str @workspace "/src/my_app/http.clj")]
    (chat-content! chat-id "user" {:type "text" :text message :contentId "m1"})
    (chat-content! chat-id "system" {:type "progress" :state "running" :text "Generating"})
    (chat-content! chat-id "assistant" {:type "reasonStarted" :id "r1"})
    (Thread/sleep 1300)
    (chat-content! chat-id "assistant" {:type "reasonText" :id "r1" :text "The user wants retries in fetch."})
    (chat-content! chat-id "assistant" {:type "reasonFinished" :id "r1" :totalTimeMs 1400})
    (stream! chat-id "I'll look at how `fetch` calls the API first.")
    (chat-content! chat-id "system" {:type "metadata" :title "Retries for fetch"})
    (tool! chat-id {:id "t1" :name "read_file" :summary "Reading http.clj" :arguments {:path path}
                    :output (slurp path) :ms 40})
    (stream! chat-id (str "`fetch` makes a single attempt and gives up on the first timeout. "
                          "I'll retry timeouts and 5xx responses, backing off exponentially:"))
    (tool! chat-id {:id "t2" :name "edit_file" :summary "Editing http.clj" :approve? true :ms 30
                    :arguments {:path path :original_content "…" :new_content "…"}
                    :details {:type "fileChange" :path path :diff diff :linesAdded 9 :linesRemoved 2}
                    :output (str "Edited " path)})
    (stream! chat-id (str "Done. `fetch` now retries up to **3 times**:\n\n"
                          "- waits `200ms`, `400ms`, then `800ms` between attempts\n"
                          "- retries timeouts and 5xx responses only: a 4xx won't get better\n"
                          "- returns the first good response's body, as before\n\n"
                          "Want me to add a test for the retry path?"))
    (chat-content! chat-id "system"
                   {:type "usage" :sessionTokens 21840 :lastMessageCost "0.04" :sessionCost "0.04"
                    :messageOutputTps 82
                    :limit {:context 200000 :output 64000}
                    :contextBreakdown {:usedTokens 21840 :contextLimit 200000 :autoCompactPercentage 85
                                       :freeColor "#3b3f51"
                                       :categories [{:name "System" :tokens 9200 :color "#7aa2f7"}
                                                    {:name "Tools" :tokens 7100 :color "#bb9af7"}
                                                    {:name "Messages" :tokens 5540 :color "#9ece6a"}]}})
    (chat-content! chat-id "system" {:type "progress" :state "finished"})))

;; --- Dispatch ---

(defn- initialized! []
  (notify! "config/updated"
           {:chat {:models ["anthropic/claude-sonnet-4-6" "anthropic/claude-opus-4-7" "openai/gpt-5.2"]
                   :agents ["code" "plan"] :selectModel model :selectAgent "code"
                   :variants ["low" "medium" "high"] :selectVariant "medium"}})
  (doseq [[name tools] [["github" ["create_issue" "get_pull_request" "search_code"]]
                        ["context7" ["resolve-library-id" "get-library-docs"]]]]
    (notify! "tool/serverUpdated" {:type "mcp" :name name :status "running" :disabled false :hasAuth false
                                   :tools (mapv (fn [t] {:name t}) tools)})))

(defn- handle! [{:keys [id method params]}]
  (case method
    "initialize"
    (do (reset! workspace (some-> params :workspaceFolders first :uri
                                  (str/replace #"^file://" "") (java.net.URLDecoder/decode "UTF-8")))
        (respond! id {}))

    "initialized"       (initialized!)
    "chat/list"         (respond! id {:chats [{:id "c1" :title "Fix the flaky login test" :status "idle"
                                               :messageCount 14 :updatedAt (- (now) (* 2 3600000)) :model model}
                                              {:id "c2" :title "Add a dark theme to the settings page" :status "idle"
                                               :messageCount 31 :updatedAt (- (now) 86400000) :model model}]})
    "chat/queryCommands" (respond! id {:commands [{:name "compact" :description "Summarize the chat to free context" :type "native"}
                                                  {:name "init" :description "Create or update AGENTS.md" :type "native"}
                                                  {:name "costs" :description "What this chat cost" :type "native"}]})
    "chat/queryContext" (respond! id {:contexts (vec (matching (:query params)))})
    "chat/queryFiles"   (respond! id {:files (vec (matching (:query params)))})
    "chat/prompt"       (let [chat-id (or (:chatId params) "demo")]
                          (respond! id {:chatId chat-id :model model :status "prompting"})
                          (future (conversation! chat-id (:message params))))
    "chat/toolCallApprove" (some-> (get @approvals (:toolCallId params)) (deliver :approve))
    "chat/toolCallReject"  (some-> (get @approvals (:toolCallId params)) (deliver :reject))
    "shutdown"          (respond! id nil)
    "exit"              (System/exit 0)
    ;; Anything else: a request gets an empty answer, a notification nothing.
    (when id (respond! id {}))))

(defn -main [& args]
  (if (some #{"--version"} args)
    (println "eca" version)
    (let [in (java.io.BufferedInputStream. System/in)]
      (loop []
        (when-let [msg (read-message in)]
          (try (handle! msg) (catch Exception e (binding [*out* *err*] (println "fake-eca:" (ex-message e)))))
          (recur)))
      (System/exit 0))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
