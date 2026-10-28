(ns eca-cli.transcript-test
  "Which chat items go to the terminal's history (inline mode): only final
  ones, in order, never taken back; the history starts over when the items
  are replaced wholesale."
  (:require [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.test-support :as support]
            [eca-cli.chat :as chat]
            [eca-cli.clipboard :as clipboard]
            [eca-cli.history :as history]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.transcript :as transcript]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]
            [eca-cli.welcome :as welcome]))

(use-fixtures :each support/isolated-chats-file)

(defn- base-state []
  {:mode             :ready
   :chat-id          "chat1"
   :items            []
   :committed        0
   :epoch            1
   :current-text     ""
   :tool-calls       {}
   :approval-queue   []
   :questions        []
   :pending-contexts []
   :init-tasks       {}
   :input            (ti/text-input)
   :input-history    []
   :chat-lines       []
   :scroll-offset    0
   :width            80
   :height           24
   :mcps             {}
   :server           nil
   :opts             {:workspace "/tmp/test"}})

(def ^:private working {:turn-started-at 1 :mode :chatting})

(defn- tool [state] {:type :tool-call :id (name state) :name "read_file" :state state})

;; --- Finality ---

(deftest item-final-test
  (let [s (base-state)
        final? #(transcript/item-final? s %)]
    (testing "a tool call, once it ran or was rejected"
      (is (not (final? (tool :preparing))))
      (is (not (final? (tool :run))) "waiting for approval")
      (is (not (final? (tool :running))))
      (is (final? (tool :called)))
      (is (final? (tool :rejected))))
    (testing "a thought, once finished; a hook, once done"
      (is (not (final? {:type :thinking :status :thinking})))
      (is (final? {:type :thinking :status :thought}))
      (is (not (final? {:type :hook :status :running})))
      (is (final? {:type :hook :status :ok})))
    (testing "legacy nil statuses retain their actual finality semantics"
      (is (not (final? {:type :tool-call :state nil})))
      (is (final? {:type :thinking :status nil}))
      (is (final? {:type :hook :status nil}))
      (is (final? {:type :question :answer nil})))
    (testing "confirmed settlement makes orphan activity final"
      (doseq [item [(tool :preparing) (tool :run) (tool :running)
                    {:type :thinking :status :thinking} {:type :hook :status :running}]]
        (is (final? (assoc item :settled? true)))))
    (testing "a question, once answered or cancelled"
      (is (not (final? {:type :question :question "Which?"})))
      (is (final? {:type :question :question "Which?" :answer "This"}))
      (is (final? {:type :question :question "Which?" :cancelled? true})))
    (testing "messages and notes are final right away"
      (doseq [t [:user :assistant-text :system :url :flag :image]]
        (is (final? {:type t}) (str t))))))

(deftest welcome-settles-test
  (let [s (assoc (base-state) :welcome-pending #{:config :recent-chats})]
    (is (not (transcript/welcome-settled? s)) "waiting for the server's facts")
    (is (transcript/welcome-settled? (assoc s :welcome-pending #{})))
    (is (transcript/welcome-settled? (assoc s :mode :disconnected)) "nothing more will come")
    (is (transcript/welcome-settled? (assoc s :items [{:type :welcome} {:type :user :text "hi"}]))
        "the user started talking: the welcome won't change anymore")))

(deftest welcome-waits-for-initialization-test
  (let [s (-> (base-state)
              (assoc :welcome-pending #{:config}
                     :init-tasks {"models" {:title "Syncing models" :done? false}})
              welcome/fresh)
        receive (fn [s method params]
                  (first (state/update-state s {:type :eca-tick :msgs [{:method method :params params}]})))
        early (receive s "config/updated" {:chat {:selectAgent "code"}})
        models (receive early "config/updated" {:chat {:selectModel "provider/model"}})
        ready (receive models "$/progress" {:type "finish" :taskId "models"})]
    (is (= 0 (:committed early)))
    (is (= 0 (:committed models)))
    (is (= 1 (:committed ready)))
    (is (= "provider/model" (get-in ready [:items 0 :model])))
    (is (transcript/welcome-settled? (assoc early :mode :disconnected)))))

;; --- Advancing ---

(deftest advance-test
  (testing "nothing to commit: the very same state"
    (let [s (base-state)]
      (is (identical? s (transcript/advance s)))))

  (testing "idle replay still waits for real item completion"
    (let [s (assoc (base-state) :items [{:type :user :text "hi"} (tool :running) {:type :assistant-text :text "ok"}])]
      (is (= 1 (:committed (transcript/advance s))) "absence of a running turn is not completion")))

  (testing "during a turn, in order: a running tool call holds back what follows"
    (let [s  (merge (base-state) working
                    {:items [{:type :user :text "hi"} (tool :running) {:type :system :text "note"}]})
          s' (transcript/advance s)]
      (is (= 1 (:committed s')))
      (testing "and lets it through once done"
        (is (= 3 (:committed (transcript/advance (assoc-in s' [:items 1 :state] :called)))))))))

(deftest advance-never-goes-back-test
  (testing "printed stays printed, even if the state says otherwise"
    (let [s (merge (base-state) working {:items [(tool :called) (tool :running)] :committed 2})]
      (is (= 2 (:committed (transcript/advance s))))))
  (testing "but never past the items"
    (let [s (assoc (base-state) :items [{:type :user :text "a"}] :committed 5)]
      (is (= 1 (:committed (transcript/advance s)))))))

(deftest welcome-holds-back-test
  (let [s (assoc (base-state)
                 :mode :connecting
                 :welcome-pending #{:config}
                 :items [{:type :welcome} {:type :system :text "offline notice"}])]
    (is (= 0 (:committed (transcript/advance s))) "the welcome still waits, so does what follows")
    (is (= 2 (:committed (transcript/advance (assoc s :welcome-pending #{})))))))

;; --- Starting over ---

(deftest reset-items-test
  (let [s (transcript/reset-items (assoc (base-state) :items [{:type :user}] :committed 1 :epoch 4
                                       :history-removals [1 0 2]) [])]
    (is (= [] (:items s)))
    (is (= 0 (:committed s)))
    (is (= 5 (:epoch s)))
    (is (= [] (:history-removals s))))
  (testing "legacy states start a new epoch with an empty ledger"
    (let [s (transcript/reset-items {} [])]
      (is (= 1 (:epoch s)))
      (is (= [] (:history-removals s))))))

(deftest reset-sites-start-over-test
  (let [s0 (assoc (base-state) :items [{:type :user :text "hi"} {:type :assistant-text :text "ok"}]
                  :committed 2 :epoch 3)]
    (testing "/new: the welcome alone"
      (let [s (welcome/fresh s0)]
        (is (= [:welcome] (mapv :type (:items s))))
        (is (= [0 4] [(:committed s) (:epoch s)]))))
    (testing "/clear"
      (let [[s _] (state/update-state (assoc s0 :input (ti/set-value (ti/text-input) "/clear"))
                                      (msg/key-press :enter))]
        (is (= [] (:items s)))
        (is (= [0 4] [(:committed s) (:epoch s)]))))
    (testing "the server clears the chat to replay it (rollback, flags, resume)"
      (let [[s _] (chat/handle-chat-cleared s0 {:params {:chatId "chat1" :messages true}})]
        (is (= [] (:items s)))
        (is (= [0 4] [(:committed s) (:epoch s)]))))
    (testing "resuming another chat from /chats"
      (let [picking (assoc s0 :mode :picking
                              :picker {:kind :chat :query "" :list (cl/item-list ["Other chat"])
                                       :all [["Other chat" "chat2"]] :filtered [["Other chat" "chat2"]]})
            [s _]   (state/update-state picking (msg/key-press :enter))]
        (is (= "chat2" (:chat-id s)))
        (is (= 4 (:epoch s)))))))

(deftest remove-items-test
  (let [s0 (assoc (base-state)
                  :items [{:type :user :text "a"} {:type :flag :content-id "f1"} {:type :user :text "b"}
                          {:type :flag :content-id "f2"}]
                  :committed 3)]
    (testing "a removed flag that was printed: the count follows the items"
      (let [s (transcript/remove-items s0 #(= "f1" (:content-id %)))]
        (is (= 3 (count (:items s))))
        (is (= 2 (:committed s)))))
    (testing "one that wasn't: unchanged"
      (is (= 3 (:committed (transcript/remove-items s0 #(= "f2" (:content-id %)))))))
    (testing "through /flags → Remove it"
      (let [flag    (assoc (nth (:items s0) 1) :idx 1)
            picking (assoc s0 :mode :picking
                              :picker {:kind :flag-action :query ""
                                       :list (cl/item-list ["Remove it"])
                                       :all [["Remove it" [:remove flag]]] :filtered [["Remove it" [:remove flag]]]})
            [s _]   (with-redefs [protocol/remove-flag! (fn [& _])]
                      (history/handle-select picking))]
        (is (= 2 (:committed s)))))))

;; --- Removal ledger and positional references ---

(deftest removal-ledger-is-sequential-and-cumulative-test
  (let [s (assoc (base-state) :committed 4
                 :items (mapv #(hash-map :type :flag :content-id %) [:a :b :c :d :e :f]))
        removed (transcript/remove-items s #(#{:b :c :e} (:content-id %)))
        again (transcript/remove-items removed #(= :a (:content-id %)))]
    (is (= [:a :d :f] (mapv :content-id (:items removed))))
    (is (= [1 1 2] (:history-removals removed)) "indices reflect earlier removals in this operation")
    (is (= 2 (:committed removed)))
    (is (= [1 1 2 0] (:history-removals again)) "the ledger accumulates within an epoch")
    (is (= 1 (:committed again)))
    (is (= (:epoch s) (:epoch again)))
    (is (identical? again (transcript/remove-items again (constantly false))))))

(deftest removal-ledger-keeps-uncommitted-removals-test
  (let [s (assoc (base-state) :committed 1
                 :items [{:type :user :text "printed"} (tool :running)
                         {:type :flag :content-id "unprinted"}])
        removed (transcript/remove-items s #(= :flag (:type %)))]
    (is (= 1 (:committed removed)))
    (is (= [2] (:history-removals removed)))
    (is (= 2 (count (:items removed)))))
  (testing "missing optional keys stay absent unless needed"
    (let [s {:items [{:type :flag}]}
          unchanged (transcript/remove-items s (constantly false))
          removed (transcript/remove-items s (constantly true))]
      (is (identical? s unchanged))
      (is (= {:items [] :history-removals [0]} removed))))
  (testing "a legacy nil ledger starts as a vector"
    (is (= [0] (:history-removals
                 (transcript/remove-items {:items [{:type :flag}] :history-removals nil}
                                          (constantly true)))))))

(deftest removal-and-append-between-frames-model-test
  (doseq [printed [0 1 2 3]
          removed-index [0 1 2]]
    (testing (str "last frame printed " printed ", remove " removed-index)
      (let [items (mapv #(hash-map :type :flag :content-id %) (range 3))
            ;; The model may already have advanced farther than the last draw.
            s (assoc (base-state) :items items :committed 3)
            removed (transcript/remove-items s #(= removed-index (:content-id %)))
            appended (update removed :items conj {:type :system :text "new final item"})
            [after _] (state/update-state appended {:type :eca-tick :msgs []})
            ;; The renderer will consume this ledger; assert the model supplies
            ;; enough information without invoking or changing the renderer.
            rebased (reduce (fn [n i] (if (< i n) (dec n) n)) printed (:history-removals after))
            pending (subvec (:items after) rebased (:committed after))]
        (is (= [removed-index] (:history-removals after)))
        (is (= 2 (:committed removed)))
        (is (= 3 (:committed after)))
        (is (= (if (< removed-index printed) (dec printed) printed) rebased))
        (is (= "new final item" (:text (peek pending))))
        (is (= 1 (count (filter #(= "new final item" (:text %)) pending))))))))

(deftest removals-rebase-subagents-and-focus-test
  (doseq [[path expected] [[[0] [0]] [[2] [1]] [[5 0] [3 0]] [[1] nil] [[3 0] nil] [nil nil]]]
    (let [items (mapv (fn [id] {:type :tool-call :id id :name "spawn_agent" :state :running
                                :sub-items [{:type :tool-call :id (str id "-child") :state :running}]})
                      ["a" "b" "c" "d" "e" "f"])
          s (-> (base-state)
                (assoc :items items :focus-path path
                       :subagent-chats {"sub-a" 0 "sub-b" 1 "sub-c" 2 "sub-d" 3 "sub-f" 5})
                chat/sync-focus)
          after (transcript/remove-items s #(#{"b" "d"} (:id %)))]
      (is (= [1 2] (:history-removals after)))
      (is (= {"sub-a" 0 "sub-c" 1 "sub-f" 3} (:subagent-chats after)))
      (is (= expected (:focus-path after)) (str path))
      (when expected
        (let [[i j] expected]
          (is (true? (get-in after (if j [:items i :sub-items j :focused?] [:items i :focused?]))))))
      (when (nil? expected)
        (is (not-any? :focused? (mapcat #(cons % (:sub-items %)) (:items after)))))
      (let [empty-state (transcript/remove-items after (constantly true))]
        (is (= {} (:subagent-chats empty-state)))
        (is (nil? (:focus-path empty-state)))))))

;; --- Replay finality and confirmed completion ---

(defn- receive-content
  ([s content] (receive-content s "chat1" content))
  ([s chat-id content]
   (first (state/update-state s {:type :eca-tick
                                 :msgs [{:method "chat/contentReceived"
                                         :params {:chatId chat-id :role "assistant" :content content}}]}))))

(defn- receive-question [s]
  (first (state/update-state s {:type :eca-tick
                                :msgs [{:id 42 :method "chat/askQuestion"
                                        :params {:chatId "chat1" :question "Which?" :options ["This"]
                                                 :allowFreeform false}}]})))

(deftest idle-tool-replay-waits-for-completion-test
  (doseq [events [["toolCallPrepare" "toolCalled"]
                  ["toolCallPrepare" "toolCallRun" "toolCallRunning" "toolCalled"]]]
    (let [states (rest (reductions (fn [s type]
                                    (receive-content s {:type type :id "replay" :name "read_file"}))
                                  (base-state) events))]
      (doseq [s (butlast states)]
        (is (= :ready (:mode s)))
        (is (nil? (:turn-started-at s)))
        (is (= 0 (:committed s)))
        (is (not (:settled? (first (:items s))))))
      (is (= :called (get-in (last states) [:items 0 :state])))
      (is (= 1 (:committed (last states)))))))

(deftest replay-parent-gates-child-completion-test
  (let [parent (receive-content (base-state) {:type "toolCallRunning" :id "parent" :name "spawn_agent"
                                            :details {:type "subagent" :subagentChatId "sub1"}})
        child (receive-content parent "sub1" {:type "toolCallPrepare" :id "child" :name "ls"})
        child-done (receive-content child "sub1" {:type "toolCalled" :id "child" :name "ls"})
        parent-done (receive-content child-done {:type "toolCalled" :id "parent" :name "spawn_agent"})]
    (is (= {"sub1" 0} (:subagent-chats parent)))
    (is (= :preparing (get-in child [:items 0 :sub-items 0 :state])))
    (is (= :called (get-in child-done [:items 0 :sub-items 0 :state])))
    (is (= [0 0 0 1] (mapv :committed [parent child child-done parent-done])))
    (is (every? #(nil? (:turn-started-at %)) [parent child child-done parent-done]))))

(deftest standalone-question-waits-for-answer-test
  (doseq [[key shown response] [[:enter {:answer "This"} {:answer "This" :cancelled false}]
                                [:escape {:cancelled? true} {:answer nil :cancelled true}]]]
    (let [asked (receive-question (base-state))
          waiting (receive-content asked {:type "flag" :contentId "after-question" :text "later final item"})
          sent (atom [])
          [answered _] (with-redefs [protocol/send-response! (fn [& args] (swap! sent conj args))]
                         (state/update-state waiting (msg/key-press key)))]
      (is (= :asking (:mode asked)))
      (is (nil? (:turn-started-at asked)))
      (is (= 0 (:committed asked) (:committed waiting)))
      (is (= shown (select-keys (first (:items answered)) [:answer :cancelled?])))
      (is (= [[nil 42 response]] @sent))
      (is (= 2 (:committed answered)))
      (is (empty? (:questions answered))))))

(deftest settle-turn-only-marks-unfinished-activity-test
  (let [final-items [(tool :called) (tool :rejected)
                     {:type :thinking :status :thought} {:type :thinking :status nil}
                     {:type :hook :status :ok} {:type :hook :status nil}
                     {:type :question :answer nil} {:type :question :cancelled? true}
                     {:type :assistant-text :text "answer"}]
        unfinished [(tool :preparing) (tool :run) (tool :running) {:type :tool-call :state nil}
                    {:type :thinking :status :thinking} {:type :hook :status :running}]
        question {:type :question :question "Still waiting"}
        parent (assoc (tool :running) :sub-items [(tool :running) (tool :called) question])
        s (assoc (base-state) :items (vec (concat final-items unfinished [parent question])))
        after (transcript/settle-turn s)
        parent-after (nth (:items after) (+ (count final-items) (count unfinished)))]
    (is (= final-items (subvec (:items after) 0 (count final-items))))
    (is (= (mapv #(assoc % :settled? true) unfinished)
           (subvec (:items after) (count final-items) (+ (count final-items) (count unfinished)))))
    (is (:settled? parent-after))
    (is (= [(assoc (tool :running) :settled? true) (tool :called) question] (:sub-items parent-after)))
    (is (= question (peek (:items after))))
    (is (= after (transcript/settle-turn after)) "settlement is idempotent")
    (is (= (dec (count (:items after))) (:committed (transcript/advance after))))))

(deftest confirmed-finish-settles-orphans-but-not-questions-test
  (doseq [started? [false true]
          cancel? [false true]]
    (testing (str "started " started? ", cancelled " cancel?)
      (let [s (cond-> (base-state) started? (merge working))
            orphans (-> s
                        (receive-content {:type "toolCallPrepare" :id "orphan" :name "ls"})
                        (receive-content {:type "reasonStarted" :id "reason"})
                        (receive-content {:type "hookActionStarted" :id "hook" :name "check"}))
            stop-requests (atom [])
            stopped (if cancel?
                      (with-redefs [protocol/stop-prompt! (fn [& args] (swap! stop-requests conj args))]
                        (first (state/update-state (assoc orphans :mode :chatting)
                                                   (msg/key-press "c" :ctrl true))))
                      orphans)
            asked (receive-question stopped)
            finished (receive-content asked {:type "progress" :state "finished"})]
        (is (= 0 (:committed stopped)))
        (is (not-any? :settled? (:items stopped)) "a local stop request is not confirmation")
        (is (= (if cancel? [[nil "chat1"]] []) @stop-requests))
        (is (= 3 (:committed finished)))
        (is (every? :settled? (take 3 (:items finished))))
        (is (= (peek (:items asked)) (peek (:items finished))) "the question remains unanswered and live")
        (is (= (:questions asked) (:questions finished)))
        (is (= :asking (:mode finished)))
        (is (nil? (:turn-started-at finished)))))))

(deftest disconnect-settles-abandoned-activity-and-questions-test
  (let [completed {:type :question :request-id 41 :question "Earlier" :answer "Done"}
        s (-> (assoc (base-state) :items [completed])
              (receive-content {:type "toolCallPrepare" :id "orphan" :name "ls"})
              receive-question)
        [after _] (state/update-state s {:type :eca-tick :msgs [{:type :reader-error :error "gone"}]})]
    (is (= :disconnected (:mode after)))
    (is (empty? (:questions after)))
    (is (= completed (first (:items after))))
    (is (true? (get-in after [:items 1 :settled?])))
    (is (true? (get-in after [:items 2 :cancelled?])))
    (is (= "gone" (:text (peek (:items after)))))
    (is (= (count (:items after)) (:committed after)))))

;; --- Wiring ---

(deftest update-state-advances-test
  (testing "any message commits what became final"
    (let [s0    (assoc (base-state) :items [{:type :user :text "hi"} {:type :assistant-text :text "ok"}])
          [s _] (state/update-state s0 {:type :window-size :width 100 :height 30})]
      (is (= 2 (:committed s))))))

(deftest welcome-pending-test
  (testing "what the welcome waits for"
    (is (= #{:config :recent-chats :server-version} (welcome/pending {})))
    (is (= #{:config :recent-chats} (welcome/pending {:eca-version "0.130.1"})) "the managed install knows its version"))
  (let [s (assoc (base-state) :welcome-pending #{:config :recent-chats :server-version})]
    (testing "facts arrive, or are known not to"
      (is (= #{:config :server-version} (:welcome-pending (welcome/handle-recent-chats s nil))))
      (is (= #{:config :recent-chats} (:welcome-pending (welcome/handle-server-version s nil))))
      (is (= #{:recent-chats :server-version}
             (:welcome-pending (first (chat/handle-config-updated s {:params {:chat {:models ["m"]}}}))))))
    (testing "no :welcome-pending: left alone"
      (is (not (contains? (welcome/handle-recent-chats (base-state) nil) :welcome-pending))))))

;; --- Inline transcript navigation (through the real dispatcher) ---

(defn- press [s & events]
  (reduce (fn [s event] (first (state/update-state s event))) s events))

(def ^:private toggle-transcript (msg/key-press "o" :ctrl true))

(defn- item-tree [items]
  (mapcat #(tree-seq (comp seq :sub-items) :sub-items %) items))

(defn- marked-paths [s]
  (vec (mapcat (fn [[i item]]
                 (concat (when (:focused? item) [[i]])
                         (keep-indexed (fn [j child] (when (:focused? child) [i j]))
                                       (:sub-items item))))
               (map-indexed vector (:items s)))))

(defn- navigation-state []
  ;; A live turn with both printed history and unfinished activity.
  (view/rebuild-lines
    (merge (base-state) working
           {:committed 2
            :items [{:type :tool-call :id "old" :name "read_file" :state :called
                     :expanded? true
                     :sub-items [{:type :tool-call :id "old-child" :name "ls" :state :called}]}
                    {:type :thinking :id "thought" :text "done" :status :thought}
                    {:type :tool-call :id "live" :name "read_file" :state :running
                     :expanded? true
                     :sub-items [{:type :tool-call :id "live-child" :name "ls" :state :called}]}
                    {:type :hook :id "hook" :name "check" :status :running}
                    {:type :tool-call :id "last" :name "read_file" :state :running
                     :expanded? true
                     :sub-items [{:type :tool-call :id "last-child" :name "ls" :state :called}]}]})))

(deftest ctrl-o-is-global-and-preserves-interaction-test
  (doseq [mode [:connecting :ready :chatting :approving :asking :picking :login :disconnected]]
    (testing (str "Ctrl+O round trip in " mode)
      (let [s (assoc (base-state)
                     :mode mode :turn-started-at 1
                     :input (-> (ti/text-input) (ti/set-value "unfinished draft") ti/focus (assoc :pos 4))
                     :pending-approval {:tool-call-id "t1" :chat-id "chat1"}
                     :approval-queue [{:tool-call-id "t2"}]
                     :questions [{:id "q1" :question "Which?" :options [] :allow-freeform? true}]
                     :question-cursor 1
                     :pending-contexts [{:type "file" :path "/tmp/example"}]
                     :picker {:kind :model :query "m" :list (cl/item-list ["model"])}
                     :login {:provider "test" :action {:action "input" :fields []}})
            [opened open-cmd] (state/update-state s toggle-transcript)
            [closed close-cmd] (state/update-state opened toggle-transcript)
            preserved [:mode :input :pending-approval :approval-queue :questions :question-cursor
                       :pending-contexts :picker :login :turn-started-at]]
        (is (true? (:transcript? opened)))
        (is (true? (view/fullscreen? opened)))
        (is (not (:transcript? closed)))
        (is (false? (view/fullscreen? closed)))
        (is (= (select-keys s preserved) (select-keys opened preserved) (select-keys closed preserved)))
        (is (nil? open-cmd))
        (is (nil? close-cmd))))))

;; --- Transcript reader keys ---

(declare pending-interaction-state)

(defn- k [key & mods] (apply msg/key-press key mods))

(deftest transcript-opens-on-newest-block-test
  (testing "with nothing waiting, it opens on the newest block, focused"
    (let [opened (press (navigation-state) toggle-transcript)]
      (is (= [4 0] (:focus-path opened)))
      (is (= [[4 0]] (marked-paths opened)))))
  (testing "an approval or question waiting: no focus, its keys stay its own"
    (doseq [mode [:approving :asking :picking :login]]
      (is (nil? (:focus-path (press (pending-interaction-state mode) toggle-transcript))) (str mode)))))

(deftest transcript-reader-keys-test
  (let [opened (press (navigation-state) toggle-transcript)]
    (testing "j / k move like ↓ / ↑"
      (is (= [4] (:focus-path (press opened (k "k")))))
      (is (= [3] (:focus-path (press opened (k "k") (k "k")))))
      (is (= [4 0] (:focus-path (press opened (k "k") (k "j"))))))
    (testing "g / G: first and last block"
      (is (= [0] (:focus-path (press opened (k "g")))))
      (is (= [4 0] (:focus-path (press opened (k "g") (k "G"))))))
    (testing "Space toggles like Enter"
      (let [on-thought (press opened (k "g") (k "j") (k "j"))]
        (is (= [1] (:focus-path on-thought)))
        (is (true? (get-in (press on-thought (k :space)) [:items 1 :expanded?])))
        (is (true? (get-in (press on-thought (k " ")) [:items 1 :expanded?])))))
    (testing "h / ←, l / →: a tree — up to the parent, fold, unfold, into the first sub-item"
      (doseq [[left right] [[(k "h") (k "l")] [(k :left) (k :right)]]]
        (let [parent   (press opened left)
              folded   (press parent left)
              unfolded (press folded right)
              child    (press unfolded right)]
          (is (= [4] (:focus-path parent)))
          (is (true? (get-in parent [:items 4 :expanded?])))
          (is (false? (get-in folded [:items 4 :expanded?])))
          (is (= [4] (:focus-path folded)))
          (is (true? (get-in unfolded [:items 4 :expanded?])))
          (is (= [4 0] (:focus-path child))))))
    (testing "e / E: unfold, fold everything; a folded-away sub-item hands focus to its parent"
      (let [folded (press opened (k "E"))]
        (is (not-any? :expanded? (item-tree (:items folded))))
        (is (= [4] (:focus-path folded)))
        (is (every? :expanded? (filter #(#{:tool-call :thinking :hook} (:type %))
                                       (item-tree (:items (press folded (k "e")))))))))
    (testing "q closes the transcript"
      (let [closed (press opened (k "q"))]
        (is (not (:transcript? closed)))
        (is (nil? (:focus-path closed)))))
    (testing "Esc clears focus: letters type in the prompt again"
      (let [typing (press opened (k :escape) (k "j") (k "q"))]
        (is (nil? (:focus-path typing)))
        (is (true? (:transcript? typing)))
        (is (= "jq" (ti/value (:input typing))))))))

(deftest transcript-copy-test
  (let [s           (-> (navigation-state)
                        (update :items conj {:type :assistant-text :text "the answer\nin two lines"})
                        view/rebuild-lines)
        opened      (press s toggle-transcript)
        [copied cmd] (state/update-state opened (k "y"))]
    (is (= [5] (:focus-path opened)) "messages are focusable in the transcript")
    (is (some? cmd) "the platform's clipboard tool runs off the UI thread")
    (is (= (clipboard/osc52 "the answer\nin two lines") (get-in copied [:clipboard :osc])))
    (is (= (get-in copied [:clipboard :osc]) (get-in (view/frame copied) [:clipboard :osc])))
    (is (= "Copied 2 lines" (:flash copied)))
    (is (str/includes? (theme/strip-ansi (view/view copied)) "Copied 2 lines"))
    (testing "the next key clears the message; the next copy gets a new id"
      (let [moved (press copied (k "k") (k "j"))
            again (press moved (k "y"))]
        (is (nil? (:flash moved)))
        (is (= (inc (get-in copied [:clipboard :id])) (get-in again [:clipboard :id])))))
    (testing "nothing to copy says so"
      (let [empty (-> (navigation-state) (assoc-in [:items 3 :out-text] nil) view/rebuild-lines
                      (press toggle-transcript (k "k") (k "k") (k "y")))]
        (is (= [3] (:focus-path empty)))
        (is (= "Nothing to copy" (:flash empty)))
        (is (nil? (:clipboard empty)))))))

(deftest transcript-footer-test
  (let [opened (-> (navigation-state) (assoc :width 120) view/rebuild-lines (press toggle-transcript))
        footer #(last (str/split-lines (theme/strip-ansi (view/view %))))]
    (is (str/includes? (footer opened) "y copy"))
    (is (str/includes? (footer opened) "q close"))
    (is (str/includes? (footer (press opened (k :escape))) "Ctrl+O to return"))))

(deftest leaving-transcript-clears-focus-test
  (let [s (-> (navigation-state)
              (assoc :transcript? true :focus-path [0 0] :scroll-offset 7)
              ;; Include stale marks as well as the selected child's mark.
              (assoc-in [:items 0 :focused?] true)
              (assoc-in [:items 0 :sub-items 0 :focused?] true)
              (assoc-in [:items 2 :sub-items 0 :focused?] true)
              view/rebuild-lines)
        closed (press s toggle-transcript)]
    (is (not (:transcript? closed)))
    (is (nil? (:focus-path closed)))
    (is (= 0 (:scroll-offset closed)))
    (is (not-any? :focused? (item-tree (:items closed))))
    (is (= 2 (:committed closed)))
    (is (= (:input s) (:input closed)))
    (is (= (:chat-lines (view/rebuild-lines closed)) (:chat-lines closed))
        "cached rendered lines must not keep the old focus highlight")))

(deftest fullscreen-ctrl-o-is-a-noop-test
  (doseq [mode [:ready :chatting :approving :asking :picking :login :disconnected]]
    (let [s (-> (navigation-state)
                (assoc :mode mode :scroll-offset 3 :focus-path [2 0]
                       :picker {:kind :model :query "" :list (cl/item-list ["model"])})
                (assoc-in [:opts :fullscreen] true)
                chat/sync-focus
                view/rebuild-lines)
          [after cmd] (state/update-state s toggle-transcript)]
      (is (= s after) (str mode))
      (is (true? (view/fullscreen? after)))
      (is (not (:transcript? after)))
      (is (nil? cmd)))))

(deftest transcript-layout-and-clipped-return-hint-test
  (let [s (-> (base-state)
              (assoc :items [{:type :system :text "committed history marker"}] :committed 1)
              view/rebuild-lines)
        opened (assoc s :transcript? true)
        output (theme/strip-ansi (view/view opened))]
    (is (not (str/includes? (theme/strip-ansi (view/view s)) "committed history marker")))
    (is (str/includes? output "committed history marker"))
    (is (str/includes? output "Ctrl+O to return"))
    (is (true? (:fullscreen? (view/frame opened))))
    (let [width 12
          narrow (-> opened (assoc :width width) view/rebuild-lines)
          lines (str/split-lines (theme/strip-ansi (view/view narrow)))
          ;; The return hint replaces the status line, keeping approval and
          ;; progress instructions visible in the gutter.
          hint (last lines)]
      (is (not (str/blank? hint)))
      (is (<= (theme/width hint) width) (pr-str hint))
      (is (not (str/includes? hint "Ctrl+O to return"))))))

(defn- long-history-state [mode]
  (-> (base-state)
      (merge working)
      (assoc :mode mode :height 12
             :items (vec (repeat 40 {:type :system :text "history row"}))
             :committed 40
             :pending-approval {:tool-call-id "approval"}
             :approval-queue [{:tool-call-id "queued"}]
             :questions [{:id "q1" :question "Which?"}])
      view/rebuild-lines))

(deftest inline-page-up-opens-and-pages-transcript-test
  (doseq [mode [:ready :chatting :disconnected :approving :asking]]
    (testing (str "paging in " mode)
      (let [s (long-history-state mode)
            opened (press s (msg/key-press :page-up))
            higher (press opened (msg/key-press :page-up))
            lower (press higher (msg/key-press :page-down))
            bottom (press lower (msg/key-press :page-down) (msg/key-press :page-down))
            top (apply press opened (repeat 30 (msg/key-press :page-up)))
            preserved [:mode :input :pending-approval :approval-queue :questions]]
        (is (true? (:transcript? opened)))
        (is (view/fullscreen? opened))
        (is (= 7 (:scroll-offset opened)))
        (is (= 14 (:scroll-offset higher)))
        (is (= 7 (:scroll-offset lower)))
        (is (= 0 (:scroll-offset bottom)))
        (is (:transcript? bottom) "paging to the bottom does not leave transcript")
        (is (= (- (count (:chat-lines top)) 7) (:scroll-offset top)))
        (is (= (select-keys s preserved) (select-keys top preserved) (select-keys bottom preserved)))))))

(deftest inline-page-down-is-a-noop-test
  (doseq [mode [:ready :chatting :disconnected :approving :asking]
          offset [0 7]]
    (let [s (assoc (long-history-state mode) :scroll-offset offset)
          [after cmd] (state/update-state s (msg/key-press :page-down))]
      (is (= s after) (str mode " offset " offset))
      (is (nil? cmd)))))

(deftest fullscreen-pages-without-transcript-test
  (let [s (assoc-in (long-history-state :chatting) [:opts :fullscreen] true)
        up (press s (msg/key-press :page-up))
        down (press up (msg/key-press :page-down))]
    (is (= 7 (:scroll-offset up)))
    (is (= 0 (:scroll-offset down)))
    (is (not (:transcript? up)))
    (is (not (:transcript? down)))
    (is (view/fullscreen? down))))

(deftest inline-focus-is-confined-to-live-items-test
  (doseq [[event expected] [[(msg/key-press :tab) [[2] [2 0] [3] [4] [4 0] [2]]]
                            [(msg/key-press :down :alt true) [[2] [3] [4] [2]]]
                            [(msg/key-press :up :alt true) [[4] [3] [2] [4]]]
                            [(msg/key-press "g" :alt true) [[2] [2]]]
                            [(msg/key-press "G" :alt true) [[4 0] [4 0]]]]]
    (testing (pr-str event)
      (let [states (take (count expected) (rest (iterate #(press % event) (navigation-state))))]
        (is (= expected (mapv :focus-path states)))
        (doseq [s states]
          (is (= [(:focus-path s)] (marked-paths s)))
          (is (= 2 (:committed s)))
          (is (not (:transcript? s))))))))

(deftest no-inline-focus-when-all-items-are-committed-test
  (doseq [event [(msg/key-press :tab) (msg/key-press :down :alt true)
                 (msg/key-press :up :alt true) (msg/key-press "g" :alt true)
                 (msg/key-press "G" :alt true)]]
    (let [s (press (assoc (navigation-state) :committed 5) event)]
      (is (nil? (:focus-path s)) (pr-str event))
      (is (not-any? :focused? (item-tree (:items s)))))))

(deftest transcript-and-fullscreen-can-focus-history-test
  (doseq [s [(press (navigation-state) toggle-transcript)
             (assoc-in (navigation-state) [:opts :fullscreen] true)]]
    (let [states (take 9 (rest (iterate #(press % (msg/key-press :tab)) s)))]
      (is (= [[0] [0 0] [1] [2] [2 0] [3] [4] [4 0] [0]] (mapv :focus-path states)))
      (doseq [focused states]
        (is (= [(:focus-path focused)] (marked-paths focused)))))
    (is (= [0] (:focus-path (press s (msg/key-press :down :alt true)))))
    (is (= [0] (:focus-path (press s (msg/key-press "g" :alt true)))))
    (is (= [0] (:focus-path (press (assoc s :focus-path [1]) (msg/key-press :up :alt true)))))
    (is (= [4 0] (:focus-path (press s (msg/key-press "G" :alt true)))))))

(deftest inline-bulk-folding-only-changes-live-items-test
  (doseq [[key expanded?] [["o" true] ["c" false]]]
    (let [s (update (navigation-state) :items
                    (fn [items]
                      (mapv (fn [item]
                              (cond-> (assoc item :expanded? (not expanded?))
                                (:sub-items item)
                                (update :sub-items #(mapv (fn [child] (assoc child :expanded? (not expanded?))) %))))
                            items)))
          after (press s (msg/key-press key :alt true))]
      (is (= (subvec (:items s) 0 2) (subvec (:items after) 0 2)) "printed items, including children, are immutable")
      (is (every? #(= expanded? (:expanded? %)) (item-tree (subvec (:items after) 2))))
      (doseq [full [(press s toggle-transcript) (assoc-in s [:opts :fullscreen] true)]]
        (let [after (press full (msg/key-press key :alt true))]
          (is (every? #(= expanded? (:expanded? %)) (item-tree (:items after)))
              "full-screen navigation may fold printed history too"))))))

(deftest committing-focused-live-item-clears-only-inline-focus-test
  (doseq [layout [:inline :transcript :fullscreen]
          path [[1] [1 0]]]
    (testing (str layout " focused " path)
      (let [s (-> (base-state)
                  (merge working)
                  (assoc :committed 1
                         :items [{:type :tool-call :id "old" :name "ls" :state :called}
                                 {:type :tool-call :id "live" :name "ls" :state :running :expanded? true
                                  :sub-items [{:type :tool-call :id "child" :name "ls" :state :called}]}])
                  view/rebuild-lines)
            focused (-> s (assoc :focus-path path) chat/sync-focus view/rebuild-lines)
            visible (case layout
                      :inline focused
                      :transcript (assoc focused :transcript? true)
                      :fullscreen (assoc-in focused [:opts :fullscreen] true))
            ;; Completion makes the item final; update-state itself must advance
            ;; :committed and reconcile focus, not a directly called helper.
            completed (assoc-in visible [:items 1 :state] :called)
            [after _] (state/update-state completed {:type :window-size :width 80 :height 24})]
        (is (= path (:focus-path focused)))
        (is (= 1 (:committed visible)))
        (is (= 2 (:committed after)))
        (if (= :inline layout)
          (do (is (nil? (:focus-path after)))
              (is (not-any? :focused? (item-tree (:items after))))
              (is (= (:chat-lines (view/rebuild-lines after)) (:chat-lines after))))
          (do (is (= path (:focus-path after)))
              (is (= [path] (marked-paths after)))))))))

(defn- pending-interaction-state [mode]
  (-> (base-state)
      (assoc :mode mode :turn-started-at 1 :committed 2
             :items [{:type :tool-call :id "old-a" :name "ls" :state :called :expanded? false}
                     {:type :tool-call :id "old-b" :name "ls" :state :called :expanded? false}]
             :input (-> (ti/text-input) (ti/set-value "/clear") ti/focus)
             :input-draft "saved draft"
             :pending-approval {:chat-id "chat1" :tool-call-id "approval"}
             :approval-queue [{:chat-id "chat1" :tool-call-id "queued"}]
             :questions [{:request-id 42 :question "Choose" :allow-freeform? true
                          :options [{:label "First"} {:label "Second"}]}]
             :question-cursor 0
             :picker {:kind :timeline :query "" :list (cl/item-list ["First" "Second"])
                      :all [["First" {:idx 0}] ["Second" {:idx 1}]]
                      :filtered [["First" {:idx 0}] ["Second" {:idx 1}]]}
             :login {:provider "test" :field-idx 0 :collected {}
                     :action {:action "input" :fields [{:key "user" :label "User" :type "text"}]}})
      view/rebuild-lines))

(deftest transcript-block-navigation-preserves-pending-interactions-test
  (doseq [mode [:ready :chatting :approving :asking :picking :login :disconnected]]
    (testing (str "Tab/Enter/arrows/Escape in " mode)
      (let [sent (atom [])
            s (press (pending-interaction-state mode) toggle-transcript)
            preserved [:mode :input :input-draft :pending-approval :approval-queue :questions
                       :question-cursor :picker :login :turn-started-at]
            states (with-redefs [protocol/approve-tool! (fn [& args] (swap! sent conj [:approve args]))
                                 protocol/reject-tool! (fn [& args] (swap! sent conj [:reject args]))
                                 protocol/send-response! (fn [& args] (swap! sent conj [:answer args]))
                                 protocol/chat-prompt! (fn [& args] (swap! sent conj [:prompt args]))]
                     (rest (reductions (fn [s event]
                                         (let [[after cmd] (state/update-state s event)]
                                           (is (nil? cmd) "navigation must not enqueue login or prompt work")
                                           after))
                                       s (map msg/key-press [:tab :enter :down :up :escape]))))]
        (is (= [[0] [0] [1] [0] nil] (mapv :focus-path states)))
        (is (true? (get-in (second states) [:items 0 :expanded?])))
        (doseq [after states]
          (is (= (select-keys s preserved) (select-keys after preserved)))
          (is (true? (:transcript? after))))
        (is (not-any? :focused? (:items (last states))))
        (is (empty? @sent))))))

(deftest transcript-alt-navigation-is-global-test
  (doseq [mode [:approving :asking :picking :login :disconnected]]
    (let [s (press (pending-interaction-state mode) toggle-transcript)]
      (doseq [[event path] [[(msg/key-press :up :alt true) [1]]
                            [(msg/key-press :down :alt true) [0]]
                            [(msg/key-press "g" :alt true) [0]]
                            [(msg/key-press "G" :alt true) [1]]
                            [(msg/key-press "G" :alt true :shift true) [1]]]]
        (let [after (press s event)]
          (is (= path (:focus-path after)) (str mode " " event))
          (is (= (:input s) (:input after)))
          (is (= mode (:mode after)))))
      (let [opened (press s (msg/key-press "o" :alt true))
            closed (press opened (msg/key-press "c" :alt true))]
        (is (every? :expanded? (:items opened)))
        (is (not-any? :expanded? (:items closed)))
        (is (= (:input s) (:input opened) (:input closed)))))))

(deftest transcript-unfocused-keys-still-reach-interactions-test
  (testing "approval letters work even while a transcript block is focused"
    (doseq [[key action] [["y" :approve] ["n" :reject]]]
      (let [sent (atom [])
            s (press (pending-interaction-state :approving) toggle-transcript (msg/key-press :tab))
            after (with-redefs [protocol/approve-tool! (fn [& args] (swap! sent conj [:approve args]))
                                protocol/reject-tool! (fn [& args] (swap! sent conj [:reject args]))]
                    (press s (msg/key-press key)))]
        (is (= [[action [nil "chat1" "approval"]]] @sent))
        (is (= "queued" (get-in after [:pending-approval :tool-call-id]))))))
  (testing "question typing and unfocused arrows remain question controls"
    (let [s (press (pending-interaction-state :asking) toggle-transcript)
          moved (press s (msg/key-press :down))
          typed (press moved (msg/key-press "x"))]
      (is (= 1 (:question-cursor moved)))
      (is (= "/clearx" (ti/value (:input typed))))
      (is (nil? (:focus-path typed)))))
  (testing "picker arrows and Enter select the picker when no block is focused"
    (let [s (press (pending-interaction-state :picking) toggle-transcript)
          moved (press s (msg/key-press :down))
          picked (press moved (msg/key-press :enter))]
      (is (= 1 (cl/selected-index (get-in moved [:picker :list]))))
      (is (nil? (:focus-path moved)))
      (is (= :approving (:mode picked)) "the approval waiting since the picker opened comes next")
      (is (nil? (:picker picked)))))
  (testing "unfocused Escape still cancels login"
    (let [s (press (pending-interaction-state :login) toggle-transcript)
          after (press s (msg/key-press :escape))]
      (is (= :ready (:mode after)))
      (is (nil? (:login after)))))
  (testing "fullscreen alone does not enable the transcript-only key override"
    (doseq [fullscreen? [false true]]
      (let [s (assoc-in (pending-interaction-state :approving) [:opts :fullscreen] fullscreen?)
            after (press s (msg/key-press :tab))]
        (is (nil? (:focus-path after)))
        (is (not (:transcript? after)))))))

(deftest tall-inline-focus-opens-transcript-test
  (let [s (-> (base-state)
              (merge working)
              (assoc :height 12
                     :items [{:type :tool-call :id "live" :name "read_file" :summary "Tall live block"
                              :state :running :expanded? true
                              :out-text (str/join "\n" (repeat 40 "output row"))}])
              view/rebuild-lines)
        focused (press s (msg/key-press :tab))
        folded (press focused (msg/key-press :enter))]
    (is (> (count (str/split-lines (view/view s))) (:height s)))
    (is (true? (:transcript? focused)))
    (is (= [0] (:focus-path focused)))
    (is (pos? (:scroll-offset focused)))
    (is (str/includes? (theme/strip-ansi (view/render-chat focused)) "Tall live block"))
    (is (false? (get-in folded [:items 0 :expanded?])))
    (is (= [0] (:focus-path folded)))
    (is (true? (:transcript? folded)))
    (testing "expanding a previously short frame also keeps the focus visible"
      (let [short (-> s (assoc-in [:items 0 :expanded?] false) view/rebuild-lines)
            selected (press short (msg/key-press :tab))
            expanded (press selected (msg/key-press :enter))]
        (is (not (:transcript? selected)))
        (is (true? (:transcript? expanded)))
        (is (str/includes? (theme/strip-ansi (view/render-chat expanded)) "Tall live block"))))))

(deftest short-inline-focus-ignores-printed-history-height-test
  (let [s (-> (base-state)
              (merge working)
              (assoc :height 12 :committed 40
                     :items (conj (vec (repeat 40 {:type :system :text "printed history"}))
                                  {:type :tool-call :id "live" :name "ls" :state :running}))
              view/rebuild-lines)
        after (press s (msg/key-press :tab))]
    (is (> (count (:chat-lines s)) (:height s)))
    (is (<= (count (str/split-lines (view/view s))) (:height s)))
    (is (= [40] (:focus-path after)))
    (is (not (:transcript? after)))
    (is (= 0 (:scroll-offset after)))))

(deftest navigation-tolerates-legacy-state-keys-test
  (doseq [legacy [(dissoc (navigation-state) :committed :epoch :transcript? :focus-path :opts)
                  (assoc (navigation-state) :committed nil :transcript? nil :focus-path nil)]]
    (let [legacy (assoc-in legacy [:items 0 :state] :running)
          focused (press legacy (msg/key-press :tab))
          opened (press legacy toggle-transcript)
          closed (press opened toggle-transcript)]
      (is (= [0] (:focus-path focused)) "missing committed defaults to zero for legacy fixtures")
      (is (false? (view/fullscreen? legacy)))
      (is (true? (view/fullscreen? opened)))
      (is (false? (view/fullscreen? closed)))
      (is (nil? (:focus-path closed)))
      (is (= 0 (:scroll-offset closed))))))
