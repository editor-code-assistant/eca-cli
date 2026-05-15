(ns eca-cli.at-refs-test
  "Phase 11a: `@` file-context references.
  Covers picker trigger guard, server query dispatch, picker selection,
  contexts wiring on send, and inline ANSI styling on user messages."
  (:require [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.login :as login]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.view.blocks :as blocks]))

(defn- base-state []
  {:mode                  :ready
   :trust                 false
   :chat-id               "chat1"
   :chat-title            nil
   :items                 []
   :current-text          ""
   :tool-calls            {}
   :pending-approval      nil
   :pending-message       nil
   :echo-pending          false
   :session-trusted-tools #{}
   :init-tasks            {}
   :pending-contexts      []
   :available-models      []
   :available-agents      []
   :available-variants    []
   :selected-model        nil
   :selected-agent        nil
   :selected-variant      nil
   :input                 (ti/text-input)
   :input-history         []
   :history-idx           nil
   :focus-path            nil
   :subagent-chats        {}
   :chat-lines            []
   :scroll-offset         0
   :width                 80
   :height                24
   :model                 nil
   :usage                 nil
   :server                nil
   :opts                  {:workspace "/tmp/test"}})

(deftest at-keystroke-opens-picker-test
  (testing "empty input + `@` opens at-file picker and dispatches query"
    (let [[s cmd] (state/update-state (base-state) (msg/key-press "@"))]
      (is (= :picking (:mode s)))
      (is (= :at-file (get-in s [:picker :kind])))
      (is (= "" (get-in s [:picker :query])))
      (is (some? cmd) "queryFiles cmd should be dispatched"))))

(deftest at-keystroke-mid-word-no-op-test
  (testing "mid-word `@` falls through to text-input: literal insert, no picker"
    (let [s0    (assoc (base-state) :input (ti/set-value (ti/text-input) "abc"))
          [s _] (state/update-state s0 (msg/key-press "@"))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))
      (is (= "abc@" (ti/value (:input s)))))))

(deftest at-keystroke-after-space-test
  (testing "text ending in space + `@` opens picker"
    (let [s0    (assoc (base-state) :input (ti/set-value (ti/text-input) "hi "))
          [s _] (state/update-state s0 (msg/key-press "@"))]
      (is (= :picking (:mode s)))
      (is (= :at-file (get-in s [:picker :kind]))))))

(deftest picker-selection-appends-tag-and-context-test
  (testing "Enter on selection appends @path and adds context"
    (let [[s-open _]   (state/update-state (base-state) (msg/key-press "@"))
          [s-loaded _] (state/update-state s-open
                                            {:type :at-files-loaded
                                             :paths ["src/eca_cli/state.clj"
                                                     "src/eca_cli/picker.clj"]})
          [s _]        (state/update-state s-loaded (msg/key-press :enter))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))
      (is (= "@src/eca_cli/state.clj" (ti/value (:input s))))
      (is (= [{:type "file" :path "src/eca_cli/state.clj"}]
             (:pending-contexts s))))))

(deftest escape-closes-picker-no-side-effects-test
  (testing "Escape closes picker without touching input or contexts"
    (let [[s-open _]   (state/update-state (base-state) (msg/key-press "@"))
          [s-loaded _] (state/update-state s-open
                                            {:type :at-files-loaded
                                             :paths ["a.clj"]})
          [s _]        (state/update-state s-loaded (msg/key-press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))
      (is (= "" (ti/value (:input s))))
      (is (= [] (:pending-contexts s))))))

(deftest pending-contexts-flushed-on-send-test
  (testing "non-empty pending-contexts included in prompt; reset after send"
    (let [prompts (atom [])]
      (with-redefs [protocol/chat-prompt! (fn [_srv params _cb]
                                            (swap! prompts conj params))]
        (let [s0    (-> (base-state)
                        (assoc :pending-contexts [{:type "file" :path "src/foo.clj"}])
                        (assoc :input (ti/set-value (ti/text-input)
                                                    "look at @src/foo.clj")))
              [s _] (state/update-state s0 (msg/key-press :enter))]
          (is (= :chatting (:mode s)))
          (is (= [] (:pending-contexts s)))
          (is (= 1 (count @prompts)))
          (is (= [{:type "file" :path "src/foo.clj"}]
                 (:contexts (first @prompts))))))))

  (testing "empty pending-contexts: prompt sent without :contexts key"
    (let [prompts (atom [])]
      (with-redefs [protocol/chat-prompt! (fn [_srv params _cb]
                                            (swap! prompts conj params))]
        (let [s0    (-> (base-state)
                        (assoc :input (ti/set-value (ti/text-input) "hello")))
              [_ _] (state/update-state s0 (msg/key-press :enter))]
          (is (= 1 (count @prompts)))
          (is (not (contains? (first @prompts) :contexts))))))))

(deftest slash-suppressed-while-picker-open-test
  (testing "`/` while at-file picker open feeds the filter, not a new picker"
    (let [[s-open _]   (state/update-state (base-state) (msg/key-press "@"))
          [s-loaded _] (state/update-state s-open
                                            {:type :at-files-loaded
                                             :paths ["src/foo.clj" "src/bar.clj"]})
          [s _]        (state/update-state s-loaded (msg/key-press "/"))]
      (is (= :picking (:mode s)))
      (is (= :at-file (get-in s [:picker :kind])))
      (is (= "/" (get-in s [:picker :query]))))))

(deftest user-message-render-ansi-bold-test
  (testing "user message render wraps @tokens in ANSI bold (1m / 22m)"
    (let [lines (blocks/render-item-lines
                  {:type :user :text "see @src/foo.clj and @docs/x.md please"}
                  80)
          joined (str/join "\n" lines)]
      (is (re-find #"\x1b\[1m@src/foo\.clj\x1b\[22m" joined))
      (is (re-find #"\x1b\[1m@docs/x\.md\x1b\[22m" joined))))

  (testing "no @-tokens: no bold escapes injected"
    (let [lines (blocks/render-item-lines
                  {:type :user :text "plain message"} 80)
          joined (str/join "\n" lines)]
      (is (not (re-find #"\x1b\[1m" joined)))))

  (testing "mid-word @ is not styled (user@host)"
    (let [lines (blocks/render-item-lines
                  {:type :user :text "ping user@host now"} 80)
          joined (str/join "\n" lines)]
      (is (not (re-find #"\x1b\[1m" joined))))))

(deftest at-files-loaded-populates-picker-test
  (testing ":at-files-loaded splices paths into the open picker"
    (let [[s-open _]   (state/update-state (base-state) (msg/key-press "@"))
          [s-loaded _] (state/update-state s-open
                                            {:type :at-files-loaded
                                             :paths ["a.clj" "b.clj" "c.clj"]})]
      (is (= 3 (cl/item-count (get-in s-loaded [:picker :list]))))
      (is (= ["a.clj" "b.clj" "c.clj"] (get-in s-loaded [:picker :all])))))

  (testing ":at-files-loaded preserves an in-flight query and filters"
    (let [[s-open _]    (state/update-state (base-state) (msg/key-press "@"))
          [s-typed _]   (state/update-state s-open (msg/key-press "b"))
          [s-loaded _]  (state/update-state s-typed
                                             {:type :at-files-loaded
                                              :paths ["a.clj" "b.clj" "bbq.clj"]})]
      (is (= "b" (get-in s-loaded [:picker :query])))
      (is (= ["b.clj" "bbq.clj"] (get-in s-loaded [:picker :filtered]))))))

(deftest chat-query-files-params-test
  (testing "chat-query-files! sends method chat/queryFiles with query (and chatId when given)"
    (let [sent (atom nil)]
      (with-redefs [protocol/send-request! (fn [_srv method params _cb]
                                             (reset! sent [method params])
                                             1)]
        (protocol/chat-query-files! :srv "chat-42" "fo" identity)
        (is (= "chat/queryFiles" (first @sent)))
        (is (= {:query "fo" :chatId "chat-42"} (second @sent)))

        (protocol/chat-query-files! :srv nil nil identity)
        (is (= {:query ""} (second @sent)))))))

;; --- PR #4 review fixes ---

(deftest stale-context-dropped-on-send-test
  (testing "Pending context whose @path token was deleted from text is dropped"
    (let [prompts (atom [])]
      (with-redefs [protocol/chat-prompt! (fn [_srv params _cb]
                                            (swap! prompts conj params))]
        (let [s0    (-> (base-state)
                        (assoc :pending-contexts [{:type "file" :path "foo.clj"}])
                        (assoc :input (ti/set-value (ti/text-input) "bar")))
              [s _] (state/update-state s0 (msg/key-press :enter))]
          (is (= :chatting (:mode s)))
          (is (= 1 (count @prompts)))
          (is (not (contains? (first @prompts) :contexts))
              "context whose @path no longer appears in text must be omitted")))))

  (testing "Subset of contexts retained when only some tokens remain"
    (let [prompts (atom [])]
      (with-redefs [protocol/chat-prompt! (fn [_srv params _cb]
                                            (swap! prompts conj params))]
        (let [s0    (-> (base-state)
                        (assoc :pending-contexts [{:type "file" :path "a.clj"}
                                                  {:type "file" :path "b.clj"}])
                        (assoc :input (ti/set-value (ti/text-input) "see @a.clj only")))
              [_ _] (state/update-state s0 (msg/key-press :enter))]
          (is (= [{:type "file" :path "a.clj"}]
                 (:contexts (first @prompts)))))))))

(deftest contexts-preserved-on-login-retry-test
  (testing "After login completes, pending-message is re-sent with original contexts"
    (let [prompts (atom [])]
      (with-redefs [protocol/chat-prompt! (fn [_srv params _cb]
                                            (swap! prompts conj params))]
        (let [s0       (-> (base-state)
                           (assoc :pending-contexts [{:type "file" :path "foo.clj"}])
                           (assoc :input (ti/set-value (ti/text-input) "look @foo.clj")))
              ;; First send caches contexts into :opts
              [s1 _]   (state/update-state s0 (msg/key-press :enter))
              ;; Simulate the login flow completing — login namespace sends a
              ;; second prompt using (:opts state). :contexts must survive.
              [_s2 _]  (login/handle-eca-login-complete
                         (assoc s1 :mode :login)
                         {:type :eca-login-complete
                          :pending-message (:pending-message s1)})]
          (is (= 2 (count @prompts)) "first send + login-retry send")
          (is (= [{:type "file" :path "foo.clj"}]
                 (:contexts (second @prompts)))
              "login retry must carry the same contexts as the original send"))))))

(defn- drive-at-select
  "Synthesize an open at-file picker with `base-text` in the input and cursor
  at `cursor-pos`, splice `paths`, select first via Enter. Returns final state.
  Bypasses state.clj's `@`-trigger guard so we can test insertion behaviour at
  arbitrary cursor positions (including mid-word) regardless of how the picker
  was opened."
  [base-text cursor-pos paths]
  (let [s-open       (-> (base-state)
                         (assoc :input
                                (-> (ti/text-input)
                                    (ti/set-value base-text)
                                    (assoc :pos cursor-pos)))
                         (assoc :mode :picking)
                         (assoc :picker {:kind     :at-file
                                         :list     (cl/item-list [] :height 8)
                                         :all      []
                                         :filtered []
                                         :query    ""}))
        [s-loaded _] (state/update-state s-open
                                          {:type :at-files-loaded :paths paths})
        [s _]        (state/update-state s-loaded (msg/key-press :enter))]
    s))

(deftest separator-inserted-mid-word-test
  (testing "Cursor in middle of word: leading + trailing space around @path"
    (let [s (drive-at-select "foobar" 3 ["path.clj"])]
      (is (= "foo @path.clj bar" (ti/value (:input s))))
      (is (= 13 (ti/position (:input s)))
          "cursor lands just past `@path.clj`, before the inserted trailing space"))))

(deftest separator-not-doubled-at-word-boundary-test
  (testing "Cursor at end of word: leading space only, no trailing"
    (let [s (drive-at-select "hello" 5 ["path.clj"])]
      (is (= "hello @path.clj" (ti/value (:input s))))))

  (testing "Cursor after whitespace: no leading space added"
    (let [s (drive-at-select "hi " 3 ["p.clj"])]
      (is (= "hi @p.clj" (ti/value (:input s)))))))

(defn- fire-cmd
  "Execute a charm cmd map by invoking its :fn — production charm does this
  automatically; tests need to invoke it explicitly to observe side effects."
  [cmd]
  (when (and cmd (= :cmd (:type cmd)) (:fn cmd))
    ((:fn cmd))))

(deftest query-files-redispatched-on-filter-typing-test
  (testing "Each filter keystroke fires chat/queryFiles with the current query"
    (let [queries (atom [])]
      (with-redefs [protocol/chat-query-files!
                    (fn [_srv chat-id query cb]
                      (swap! queries conj {:chat-id chat-id :query query})
                      (cb {:result {:files []}}))]
        (let [s0       (assoc (base-state)
                              :server {:queue (java.util.concurrent.LinkedBlockingQueue.)})
              [s1 c1]  (state/update-state s0 (msg/key-press "@"))
              _        (fire-cmd c1)
              [s2 c2]  (state/update-state s1 (msg/key-press "f"))
              _        (fire-cmd c2)
              [s3 c3]  (state/update-state s2 (msg/key-press "o"))
              _        (fire-cmd c3)
              [s4 c4]  (state/update-state s3 (msg/key-press "o"))
              _        (fire-cmd c4)
              [_s5 c5] (state/update-state s4 (msg/key-press :backspace))
              _        (fire-cmd c5)]
          ;; Empty open + 3 typed chars + 1 backspace = 5 dispatches.
          ;; Each dispatch carries the query as it stood after that keystroke.
          (is (= [{:chat-id "chat1" :query ""}
                  {:chat-id "chat1" :query "f"}
                  {:chat-id "chat1" :query "fo"}
                  {:chat-id "chat1" :query "foo"}
                  {:chat-id "chat1" :query "fo"}]
                 @queries)))))))
