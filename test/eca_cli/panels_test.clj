(ns eca-cli.panels-test
  "Pass 8: /providers, the login it starts, and the `@` / `#` pickers
  (contexts and paths, workspace-relative labels, references spelled out on
  send). The /mcp actions are in mcp-test."
  (:require [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.test-support :as support]
            [eca-cli.commands :as commands]
            [eca-cli.login :as login]
            [eca-cli.picker :as picker]
            [eca-cli.protocol :as protocol]
            [eca-cli.providers :as providers]
            [eca-cli.state :as state]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(def ^:dynamic *ws*
  "A scratch workspace: a.txt, src/core.clj, docs/."
  nil)

(defn- with-workspace [t]
  (let [dir (.getCanonicalFile
              (.toFile (java.nio.file.Files/createTempDirectory
                         "eca-cli-ws" (make-array java.nio.file.attribute.FileAttribute 0))))]
    (spit (io/file dir "a.txt") "a")
    (.mkdirs (io/file dir "src"))
    (spit (io/file dir "src" "core.clj") "(ns core)")
    (.mkdirs (io/file dir "docs"))
    (try
      (binding [*ws* (str dir)] (t))
      (finally (run! io/delete-file (reverse (file-seq dir)))))))

(use-fixtures :each support/isolated-chats-file with-workspace)

(defn- base-state []
  {:mode               :ready
   :trust              false
   :chat-id            "chat1"
   :items              []
   :current-text       ""
   :tool-calls         {}
   :pending-approval   nil
   :approval-queue     []
   :questions          []
   :pending-message    nil
   :echo-pending       false
   :init-tasks         {}
   :pending-contexts   []
   :server-commands    []
   :available-models   []
   :available-agents   []
   :available-variants []
   :input              (ti/text-input)
   :input-history      []
   :history-idx        nil
   :focus-path         nil
   :subagent-chats     {}
   :chat-lines         []
   :scroll-offset      0
   :width              120
   :height             30
   :mcps               {}
   :server             nil
   :opts               {:workspace *ws*}})

(defn- run-cmd
  "Run a charm cmd's function, as charm would: its message."
  [cmd]
  (when (and cmd (= :cmd (:type cmd)) (:fn cmd))
    ((:fn cmd))))

(defn- send-text
  "`text` in the input, then Enter: [state the chat/prompt params]."
  [state text]
  (let [sent (atom [])]
    (with-redefs [protocol/chat-prompt! (fn [_srv params _cb] (swap! sent conj params))]
      (let [[s _] (state/update-state (assoc state :input (ti/set-value (ti/text-input) text))
                                      (msg/key-press :enter))]
        [s (first @sent)]))))

(defn- ws [& parts] (str/join "/" (cons *ws* parts)))

(defn- labels [state] (vec (cl/items (get-in state [:picker :list]))))

;; --- Labels ---

(deftest at-label-test
  (testing "files and directories: relative to the workspace when in it"
    (is (= "src/core.clj" (picker/at-label {:type "file" :path "/ws/src/core.clj"} "/ws")))
    (is (= "src/" (picker/at-label {:type "directory" :path "/ws/src"} "/ws")))
    (is (= "/elsewhere/x.clj" (picker/at-label {:type "file" :path "/elsewhere/x.clj"} "/ws")))
    (is (= "/ws/" (picker/at-label {:type "directory" :path "/ws"} "/ws")) "the workspace itself")
    (is (= "/wsx/y.clj" (picker/at-label {:type "file" :path "/wsx/y.clj"} "/ws"))
        "a sibling sharing the prefix isn't inside"))
  (testing "without a workspace, as is"
    (is (= "/ws/a.txt" (picker/at-label {:type "file" :path "/ws/a.txt"}))))
  (testing "other contexts"
    (is (= "docs:Getting-started"
           (picker/at-label {:type "mcpResource" :server "docs" :name "Getting started"} "/ws"))
        "one token, so it can be found in the text")
    (is (= "https://eca.dev" (picker/at-label {:type "web" :url "https://eca.dev"} "/ws")))
    (is (= "a.txt" (picker/at-label "a.txt" "/ws")) "a plain string is a path")))

;; --- @: contexts ---

(defn- server-contexts []
  [{:type "repoMap"}
   {:type "cursor"}
   {:type "directory" :path *ws*}
   {:type "directory" :path (ws "src")}
   {:type "file" :path (ws "src" "core.clj")}
   {:type "mcpResource" :server "docs" :name "Guide" :uri "docs://guide"
    :description "The guide" :mimeType "text/markdown"}])

(defn- open-at
  "`@` typed, the server's contexts loaded: [state what was asked]."
  [state]
  (let [asked (atom nil)]
    (with-redefs [protocol/chat-query-context!
                  (fn [_srv chat-id query contexts cb]
                    (reset! asked {:chat-id chat-id :query query :contexts contexts})
                    (cb {:result {:contexts (server-contexts)}}))]
      (let [[s cmd] (state/update-state state (msg/key-press "@"))
            [s _]   (state/update-state s (run-cmd cmd))]
        [s @asked]))))

(defn- pick [state idx]
  (first (state/update-state (update-in state [:picker :list] cl/select idx)
                             (msg/key-press :enter))))

(deftest at-picker-offers-contexts-test
  (let [added       {:type "file" :path (ws "a.txt")}
        [s asked]   (open-at (assoc (base-state) :pending-contexts [added]))]
    (testing "asks with the contexts already added, so they aren't offered again"
      (is (= {:chat-id "chat1" :query "" :contexts [added]} asked)))
    (testing "titled for what it does"
      (is (str/includes? (theme/strip-ansi (view/view s)) "Add context")))
    (testing "no editor cursor, no deprecated repo map; labels relative to the workspace"
      (is (= [(str *ws* "/") "src/" "src/core.clj" "docs:Guide"] (labels s))))))

(deftest at-picked-contexts-sent-test
  (testing "a file: @label in the text, the context added, spelled out on send"
    (let [[opened _] (open-at (base-state))
          s          (pick opened 2)
          _          (is (= "@src/core.clj " (ti/value (:input s))))
          _          (is (= [{:type "file" :path (ws "src" "core.clj")}] (:pending-contexts s)))
          [s' params] (send-text s "explain @src/core.clj please")]
      (is (= (str "explain @" (ws "src" "core.clj") " please") (:message params)))
      (is (= [{:type "file" :path (ws "src" "core.clj")}] (:contexts params)))
      (is (= "explain @src/core.clj please" (:text (peek (:items s'))))
          "the chat shows what was typed")
      (is (= (:message params) (:pending-message s'))
          "a login retry re-sends what was sent")))

  (testing "a directory: @dir/ in the text, @/full/path sent"
    (let [[opened _]  (open-at (base-state))
          s           (pick opened 1)
          [_ params]  (send-text s (str (ti/value (:input s)) "summarize"))]
      (is (= (str "@" (ws "src") " summarize") (:message params)))
      (is (= [{:type "directory" :path (ws "src")}] (:contexts params)))))

  (testing "an MCP resource: added as is"
    (let [[opened _]  (open-at (base-state))
          s           (pick opened 3)
          [_ params]  (send-text s (str (ti/value (:input s)) "use it"))]
      (is (= "@docs:Guide use it" (:message params)))
      (is (= [(nth (server-contexts) 5)] (:contexts params)))))

  (testing "a context deleted from the text is dropped"
    (let [[opened _]  (open-at (base-state))
          s           (pick opened 2)
          [_ params]  (send-text s "never mind")]
      (is (= "never mind" (:message params)))
      (is (not (contains? params :contexts))))))

(deftest late-answers-dropped-test
  (let [[s1 _] (state/update-state (base-state) (msg/key-press "@"))
        [s2 _] (state/update-state s1 (msg/key-press "s"))
        late   {:type :at-contexts-loaded :query "" :hash? false
                :contexts [{:type "file" :path (ws "a.txt")}]}
        fresh  {:type :at-contexts-loaded :query "s" :hash? false
                :contexts [{:type "file" :path (ws "src" "core.clj")}]}]
    (testing "the answer to an older query (the user typed on) is dropped"
      (is (= [] (get-in (first (state/update-state s2 late)) [:picker :all]))))
    (testing "the current query's is shown"
      (is (= ["src/core.clj"] (labels (first (state/update-state s2 fresh))))))
    (testing "so is the other picker's (#)"
      (is (= [] (get-in (first (state/update-state s2 (assoc fresh :hash? true))) [:picker :all]))))))

;; --- #: paths ---

(deftest hash-picker-inserts-a-path-test
  (let [asked (atom nil)]
    (with-redefs [protocol/chat-query-files!
                  (fn [_srv chat-id query cb]
                    (reset! asked [chat-id query])
                    (cb {:result {:files [{:type "file" :path (ws "a.txt")}
                                          {:type "directory" :path (ws "docs")}]}}))]
      (let [s0       (assoc (base-state) :input (ti/set-value (ti/text-input) "summarize "))
            [s1 cmd] (state/update-state s0 (msg/key-press "#"))
            [s2 _]   (state/update-state s1 (run-cmd cmd))
            [s3 _]   (state/update-state s2 (msg/key-press :enter))]
        (is (= ["chat1" ""] @asked) "chat/queryFiles")
        (is (str/includes? (theme/strip-ansi (view/view s2)) "Insert path"))
        (is (= ["a.txt" "docs/"] (labels s2)))
        (testing "the path goes in the text, not in the contexts"
          (is (= "summarize #a.txt " (ti/value (:input s3))))
          (is (= [] (:pending-contexts s3))))
        (testing "sent as the bare full path; the chat shows what was typed"
          (let [[s4 params] (send-text s3 (str (ti/value (:input s3)) "please"))]
            (is (= (str "summarize " (ws "a.txt") " please") (:message params)))
            (is (not (contains? params :contexts)))
            (is (= "summarize #a.txt please" (:text (peek (:items s4)))))))))))

(deftest hash-mid-word-is-text-test
  (let [s0    (assoc (base-state) :input (ti/set-value (ti/text-input) "issue"))
        [s _] (state/update-state s0 (msg/key-press "#"))]
    (is (= :ready (:mode s)))
    (is (= "issue#" (ti/value (:input s))))))

;; --- References typed by hand (or recalled from the history) ---

(deftest typed-references-test
  (testing "an @ naming a workspace file or directory is a context too"
    (let [[_ params] (send-text (base-state) "check @src/core.clj and @docs/")]
      (is (= (str "check @" (ws "src" "core.clj") " and @" (ws "docs")) (:message params)))
      (is (= [{:type "file" :path (ws "src" "core.clj")}
              {:type "directory" :path (ws "docs")}]
             (:contexts params)))))

  (testing "other @s stay as typed: no such file, an e-mail, @/abs (the server reads those)"
    (let [[_ params] (send-text (base-state) "ask @nobody or me@host.dev about @/etc/hosts")]
      (is (= "ask @nobody or me@host.dev about @/etc/hosts" (:message params)))
      (is (not (contains? params :contexts)))))

  (testing "# paths: spelled out when they exist, else left alone"
    (let [[_ params] (send-text (base-state) "see #a.txt and issue #42")]
      (is (= (str "see " (ws "a.txt") " and issue #42") (:message params)))))

  (testing "only what looks like a path is looked up: pasted code stays code"
    (.mkdirs (io/file *ws* "include"))
    (let [[_ params] (send-text (base-state) "#include <stdio.h>\n@docs @Override")]
      (is (= "#include <stdio.h>\n@docs @Override" (:message params)))
      (is (not (contains? params :contexts)))))

  (testing "the same file picked and typed is one context"
    (let [[opened _] (open-at (base-state))
          s          (pick opened 2)
          [_ params] (send-text s "@src/core.clj vs @src/core.clj")]
      (is (= [{:type "file" :path (ws "src" "core.clj")}] (:contexts params))))))

;; --- /providers ---

(def ^:private now 1000000)

(defn- providers-fixture []
  [{:id "openai" :label "OpenAI" :configured true
    :auth {:status "unauthenticated"} :models []
    :login {:methods [{:key "chatgpt" :label "ChatGPT"} {:key "api-key" :label "API key"}]}}
   {:id "anthropic" :label "Anthropic" :configured true
    :auth {:status "authenticated" :type "oauth" :mode "max" :source "login" :expiresAt (+ now 7200)}
    :models [{:id "a"} {:id "b"}]
    :login {:methods [{:key "max"} {:key "manual"}]}
    :quota {:windows [{:label "5h" :usedPercent 42}]}}
   {:id "deepseek" :configured true
    :auth {:status "authenticated" :type "api-key" :source "env" :envVar "DEEPSEEK_API_KEY"}
    :models [{:id "x"}] :login {:methods [{:key "api-key"}]}}
   {:id "ollama" :configured true :auth {:status "local"} :models [{:id "llama"}]}
   {:id "google" :label "Google" :configured false
    :auth {:status "unauthenticated"} :models [] :login {:methods [{:key "api-key"}]}}])

(defn- provider [id] (some #(when (= id (:id %)) %) (providers-fixture)))

(deftest provider-row-label-test
  (is (= "Anthropic (anthropic)  •  ✓ logged in · Max (login) · expires in 2h · 2 models · 5h 42%"
         (providers/row-label (provider "anthropic") now)))
  (is (= "OpenAI (openai)  •  ✗ not logged in · 0 models"
         (providers/row-label (provider "openai") now)))
  (is (= "deepseek  •  ✓ configured · API key (env: DEEPSEEK_API_KEY) · 1 model"
         (providers/row-label (provider "deepseek") now)))
  (is (= "ollama  •  local · 1 model"
         (providers/row-label (provider "ollama") now)))
  (is (= "Google (google)  •  ✗ not logged in"
         (providers/row-label (provider "google") now))
      "not configured, no models: no count")
  (is (str/includes? (providers/row-label (assoc-in (provider "anthropic") [:auth :status] "expired") now)
                     "✗ login expired")))

(deftest provider-actions-test
  (testing "no credentials: log in, or add a key when that's the only way"
    (is (= [["Log in" [:login "openai"]]] (providers/actions-for (provider "openai"))))
    (is (= [["Add API key" [:login "google"]]] (providers/actions-for (provider "google")))))
  (testing "a login: log out; log in again when it's running out"
    (is (= [["Log out" [:logout "anthropic"]]] (providers/actions-for (provider "anthropic"))))
    (is (= [["Log in again" [:login "anthropic"]] ["Log out" [:logout "anthropic"]]]
           (providers/actions-for (assoc-in (provider "anthropic") [:auth :status] "expiring")))))
  (testing "nothing to do: a key from the environment, a local provider, no login at all"
    (is (= [] (providers/actions-for (provider "deepseek"))))
    (is (= [] (providers/actions-for (provider "ollama"))))
    (is (= [] (providers/actions-for {:id "custom" :auth {:status "unauthenticated"}})))))

(defn- open-providers
  "/providers typed, the server's list loaded."
  [state providers-list]
  (with-redefs [protocol/providers-list! (fn [_srv cb] (cb {:result {:providers providers-list}}))]
    (let [[s cmd] (commands/dispatch-command state "/providers")]
      (first (state/update-state s (run-cmd cmd))))))

(deftest providers-panel-test
  (let [s (open-providers (base-state) (providers-fixture))]
    (testing "logged in first, then local, configured, the rest (eca-emacs's order)"
      (is (= :providers (get-in s [:picker :kind])))
      (is (= ["anthropic" "deepseek" "ollama" "openai" "google"]
             (mapv (comp :id second) (get-in s [:picker :all])))))
    (testing "rendered with its title, details after the name"
      (let [screen (theme/strip-ansi (view/view s))]
        (is (str/includes? screen "Providers"))
        (is (str/includes? screen "Anthropic (anthropic)"))
        (is (str/includes? screen "✓ logged in"))))
    (testing "Enter on one with nothing to do says why"
      (let [s' (pick s 1)]
        (is (= :ready (:mode s')))
        (is (= "deepseek uses an API key from the environment (DEEPSEEK_API_KEY)."
               (:text (peek (:items s'))))))))

  (testing "no answer, no providers"
    (is (= "⚠ Could not list the providers."
           (:text (peek (:items (providers/handle-loaded (base-state) nil))))))
    (is (= "No providers configured."
           (:text (peek (:items (providers/handle-loaded (base-state) []))))))))

(deftest provider-login-test
  (let [s       (open-providers (base-state) (providers-fixture))
        actions (pick s 3)
        _       (is (= :provider-action (get-in actions [:picker :kind])))
        _       (is (= ["Log in"] (labels actions)))
        _       (is (str/includes? (theme/strip-ansi (view/view actions)) "Provider OpenAI"))
        [s' cmd] (state/update-state actions (msg/key-press :enter))
        asked   (atom nil)
        answer  (with-redefs [protocol/providers-login!
                              (fn [_srv provider method cb]
                                (reset! asked [provider method])
                                (cb {:result {:action "choose-method"
                                              :methods [{:key "chatgpt" :label "ChatGPT"}
                                                        {:key "api-key" :label "API key"}]}}))]
                  (run-cmd cmd))
        [login _] (state/update-state s' answer)]
    (is (= :ready (:mode s')) "the panel closes")
    (is (= ["openai" nil] @asked) "providers/login, the method to be chosen")
    (is (= :login (:mode login)))
    (is (nil? (get-in login [:login :pending-message])))
    (let [screen (theme/strip-ansi (view/view login))]
      (is (str/includes? screen "Log in to openai") "asked for, not required")
      (is (str/includes? screen "[1] ChatGPT")))))

(deftest provider-logout-test
  (let [s       (open-providers (base-state) (providers-fixture))
        actions (pick s 0)
        _       (is (= ["Log out"] (labels actions)))
        [s' cmd] (state/update-state actions (msg/key-press :enter))]
    (testing "providers/logout, then said so"
      (let [asked  (atom nil)
            answer (with-redefs [protocol/providers-logout!
                                 (fn [_srv provider cb] (reset! asked provider) (cb {:result {}}))]
                     (run-cmd cmd))
            [done _] (state/update-state s' answer)]
        (is (= "anthropic" @asked))
        (is (= "Logged out of anthropic." (:text (peek (:items done)))))))
    (testing "a failure says why"
      (let [answer (with-redefs [protocol/providers-logout!
                                 (fn [_srv _provider cb] (cb {:error {:code -32603 :message "boom"}}))]
                     (run-cmd cmd))
            [done _] (state/update-state s' answer)]
        (is (= "⚠ Could not log out of anthropic: boom" (:text (peek (:items done)))))))))

;; --- Login, finished ---

(deftest login-complete-after-providers-updated-test
  (testing "providers/updated finished the login first: the late answer sends nothing more"
    (let [prompts (atom [])]
      (with-redefs [protocol/chat-prompt! (fn [_srv params _cb] (swap! prompts conj params))]
        (let [s0       (assoc (base-state) :mode :login :pending-message "hi"
                              :server {:queue (java.util.concurrent.LinkedBlockingQueue.)}
                              :login {:provider "anthropic" :pending-message "hi"
                                      :action {:action "input" :fields [{:key "api-key"}]}})
              [s1 cmd] (login/handle-providers-updated s0 {:id "anthropic" :auth {:status "authenticated"}})
              _        (run-cmd cmd)
              [s2 c2]  (login/handle-eca-login-complete s1 {:type :eca-login-complete :pending-message "hi"})]
          (is (= :chatting (:mode s1)))
          (is (= ["hi"] (map :message @prompts)) "sent once")
          (is (= s1 s2))
          (is (nil? c2)))))))
