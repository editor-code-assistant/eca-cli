(ns eca-cli.tasks-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.chat :as chat]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(use-fixtures :each support/isolated-chats-file)

(defn- base []
  {:mode :chatting :turn-started-at 1 :chat-id "c1" :items [] :current-text "" :tool-calls {}
   :questions [] :approval-queue [] :input (ti/focus (ti/text-input)) :width 80 :height 24
   :chat-lines [] :scroll-offset 0 :opts {:workspace "/tmp/ws"} :mcps {} :init-tasks {}})

(def ^:private details
  {:type "task"
   :activeSummary "Wiring the parser"
   :tasks [{:id 1 :subject "Write the parser" :status "done" :priority "medium" :isBlocked false}
           {:id 2 :subject "Add tests" :status "in-progress" :priority "high" :isBlocked false}
           {:id 3 :subject "Update docs" :status "pending" :priority "low" :isBlocked true :blockedBy [2]}]
   :inProgressTaskIds [2]
   :summary {:done 1 :inProgress 1 :pending 1 :total 3}})

(defn- task-event [type & {:as extra}]
  {:method "chat/contentReceived"
   :params {:chatId "c1" :role "assistant"
            :content (merge {:type type :id "tk1" :name "task" :server "eca" :summary "Planning"} extra)}})

(defn- server [s & msgs] (first (state/update-state s {:type :eca-tick :msgs (vec msgs)})))

(deftest content->tasks-test
  (is (= {:tasks [{:id 1 :subject "Write the parser" :status "done" :priority "medium" :blocked? false :blocked-by []}
                  {:id 2 :subject "Add tests" :status "in-progress" :priority "high" :blocked? false :blocked-by []}
                  {:id 3 :subject "Update docs" :status "pending" :priority "low" :blocked? true :blocked-by [2]}]
          :active-summary "Wiring the parser"}
         (chat/content->tasks {:details details})))
  (is (nil? (chat/content->tasks {:details {:type "fileChange"}}))))

(deftest task-calls-feed-the-panel-test
  (testing "while the first plan is on its way: Creating tasks…, nothing in the chat"
    (let [s (server (base) (task-event "toolCallPrepare"))]
      (is (= [] (:items s)))
      (is (= ["≡ Creating tasks…"] (map theme/strip-ansi (view/task-panel-lines s))))))
  (testing "the finished call's details become the panel"
    (let [s (server (base) (task-event "toolCallPrepare") (task-event "toolCalled" :details details :error false))]
      (is (= [] (:items s)))
      (is (false? (:task-loading? s)))
      (is (= ["≡ Task: Wiring the parser (1/3)  ctrl+t for all"]
             (map theme/strip-ansi (view/task-panel-lines s))))
      (testing "Ctrl+T unfolds the checklist"
        (let [s2    (first (state/update-state s (msg/key-press "t" :ctrl true)))
              lines (map theme/strip-ansi (view/task-panel-lines s2))]
          (is (= ["≡ Task: Wiring the parser (1/3)  ctrl+t to fold"
                  "  ✓ #1 Write the parser"
                  "  ◐ #2 Add tests !"
                  "  ◌ #3 Update docs  waits for #2"]
                 lines))
          (is (every? #(<= (theme/width %) 80) (view/task-panel-lines s2)))))
      (testing "it shows above the prompt"
        (is (str/includes? (theme/strip-ansi (view/view s)) "≡ Task: Wiring the parser")))
      (testing "a cleared plan (all done, next prompt) hides it"
        (let [s3 (server s (task-event "toolCalled" :details (assoc details :tasks []) :error false))]
          (is (= [] (view/task-panel-lines s3))))))))

(deftest task-panel-label-test
  (testing "no summary: the task in progress; none at all: just the count"
    (let [lines #(map theme/strip-ansi (view/task-panel-lines (assoc (base) :tasks %)))]
      (is (= ["≡ Tasks Add tests (1/3)  ctrl+t for all"]
             (lines (chat/content->tasks {:details (dissoc details :activeSummary)}))))
      (is (= ["≡ Tasks (0/1)  ctrl+t for all"]
             (lines {:tasks [{:id 1 :subject "x" :status "pending"}]}))))))

(deftest task-panel-narrow-test
  (let [s (assoc (base) :width 30 :tasks (chat/content->tasks {:details details}))]
    (is (every? #(<= (theme/width %) 30) (view/task-panel-lines (assoc s :tasks-expanded? true))))))
