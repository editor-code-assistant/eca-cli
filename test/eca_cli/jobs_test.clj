(ns eca-cli.jobs-test
  (:require [charm.components.text-input :as ti]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.commands :as commands]
            [eca-cli.jobs :as jobs]
            [eca-cli.protocol :as protocol]))

(defn- key-msg [k]
  {:type :key-press :key k})

(defn- base-state []
  {:mode     :ready
   :width    160
   :height   24
   :server   nil
   :opts     {:workspace "/tmp"}
   :items    []
   :jobs     {}
   :input    (ti/text-input)})

(defn- job [id chat-label status & {:keys [summary label elapsed exit started-at]
                                    :or   {summary    "do thing"
                                           label      "do thing --flag"
                                           elapsed    "10s"
                                           started-at "2026-05-15T10:00:00Z"}}]
  {:id         id
   :type       "shell"
   :status     status
   :label      label
   :summary    summary
   :startedAt  started-at
   :elapsed    elapsed
   :exitCode   exit
   :chatId     (str "c-" chat-label)
   :chatLabel  chat-label})

(deftest jobs-updated-handler-replaces-map-test
  (testing "second update fully replaces the first map"
    (let [s0       (base-state)
          [s1 _]   (jobs/handle-jobs-updated s0 {:jobs [(job "j1" "Chat A" "running")
                                                        (job "j2" "Chat B" "completed")]})
          [s2 _]   (jobs/handle-jobs-updated s1 {:jobs [(job "j3" "Chat C" "failed")]})]
      (is (= #{"j1" "j2"} (set (keys (:jobs s1)))))
      (is (= #{"j3"} (set (keys (:jobs s2)))))
      (is (= "failed" (get-in s2 [:jobs "j3" :status]))))))

(deftest status-bar-fragment-empty-test
  (testing "no jobs returns nil at any width"
    (is (nil? (jobs/status-bar-fragment (base-state) 80)))
    (is (nil? (jobs/status-bar-fragment (base-state) 160)))))

(deftest status-bar-fragment-wide-test
  (let [s (assoc (base-state) :jobs {"j1" (job "j1" "A" "running")
                                     "j2" (job "j2" "A" "running")})]
    (testing "wide (>=120) uses [N jobs]"
      (is (= "[2 jobs]" (jobs/status-bar-fragment s 160)))
      (is (= "[2 jobs]" (jobs/status-bar-fragment s 120))))
    (testing "narrow (<120) uses [Nj]"
      (is (= "[2j]" (jobs/status-bar-fragment s 80)))
      (is (= "[2j]" (jobs/status-bar-fragment s 119))))))

(deftest panel-render-grouped-test
  (testing "rows grouped by chatLabel"
    (let [s (-> (base-state)
                (assoc :jobs {"j1" (job "j1" "Chat Alpha" "running" :summary "build")
                              "j2" (job "j2" "Chat Beta"  "completed" :summary "test")
                              "j3" (job "j3" "Chat Alpha" "failed"   :summary "lint" :exit 1)}))
          [s' _] (jobs/cmd-open-jobs-panel s)
          out    (jobs/render-jobs-panel-lines s')]
      (is (= :picking (:mode s')))
      (is (= :jobs (get-in s' [:picker :kind])))
      (is (str/includes? out "Chat Alpha"))
      (is (str/includes? out "Chat Beta"))
      (is (str/includes? out "build"))
      (is (str/includes? out "test"))
      (is (str/includes? out "lint"))
      (is (str/includes? out "exit:1")))))

(deftest empty-jobs-panel-test
  (testing "/jobs with no jobs surfaces system message, does not enter picking"
    (let [[s' _] (jobs/cmd-open-jobs-panel (base-state))]
      (is (= :ready (:mode s')))
      (is (some #(str/includes? (:text %) "No background jobs") (:items s'))))))

(deftest kill-confirm-flow-test
  (testing "d on row opens modal, y dispatches kill, modal closes"
    (let [kill-calls (atom [])
          s          (-> (base-state)
                         (assoc :jobs {"j1" (job "j1" "A" "running")}))
          [s1 _]     (jobs/cmd-open-jobs-panel s)
          [s2 _]     (jobs/handle-key s1 (key-msg "d"))]
      (is (= :confirm-kill (get-in s2 [:jobs-view :kind])))
      (is (= "j1" (get-in s2 [:jobs-view :job-id])))
      (with-redefs [protocol/jobs-kill! (fn [_ id cb]
                                          (swap! kill-calls conj id)
                                          (cb {:result {:killed true}}))]
        (let [[s3 cmd] (jobs/handle-key s2 (key-msg "y"))]
          (is (nil? (:jobs-view s3)))
          (when cmd ((:fn cmd)))
          (is (= ["j1"] @kill-calls)))))))

(deftest kill-cancel-test
  (testing "n on modal closes without dispatch"
    (let [kill-calls (atom [])
          s          (-> (base-state)
                         (assoc :jobs {"j1" (job "j1" "A" "running")}))
          [s1 _]     (jobs/cmd-open-jobs-panel s)
          [s2 _]     (jobs/handle-key s1 (key-msg "d"))]
      (with-redefs [protocol/jobs-kill! (fn [_ id _]
                                          (swap! kill-calls conj id))]
        (let [[s3 cmd] (jobs/handle-key s2 (key-msg "n"))]
          (is (nil? (:jobs-view s3)))
          (is (nil? cmd))
          (is (empty? @kill-calls))))))

  (testing "Escape on modal closes without dispatch"
    (let [kill-calls (atom [])
          s          (-> (base-state)
                         (assoc :jobs {"j1" (job "j1" "A" "running")}))
          [s1 _]     (jobs/cmd-open-jobs-panel s)
          [s2 _]     (jobs/handle-key s1 (key-msg "d"))]
      (with-redefs [protocol/jobs-kill! (fn [_ id _]
                                          (swap! kill-calls conj id))]
        (let [[s3 cmd] (jobs/handle-key s2 (key-msg :escape))]
          (is (nil? (:jobs-view s3)))
          (is (nil? cmd))
          (is (empty? @kill-calls)))))))

(deftest output-fetch-on-enter-test
  (testing "Enter on row dispatches jobs-read-output"
    (let [read-calls (atom [])
          s          (-> (base-state)
                         (assoc :jobs {"j1" (job "j1" "A" "running")}))
          [s1 _]     (jobs/cmd-open-jobs-panel s)]
      (with-redefs [protocol/jobs-read-output! (fn [_ id cb]
                                                 (swap! read-calls conj id)
                                                 (cb {:result {:lines  [{:stream "stdout" :text "hello"}]
                                                               :status "running"}}))]
        (let [[s2 cmd] (jobs/handle-key s1 (key-msg :enter))]
          (is (= :output (get-in s2 [:jobs-view :kind])))
          (is (= "j1" (get-in s2 [:jobs-view :job-id])))
          (when cmd ((:fn cmd)))
          (is (= ["j1"] @read-calls)))))))

(deftest output-popup-render-test
  (testing "stderr lines prefixed, stdout plain"
    (let [s (-> (base-state)
                (assoc :jobs {"j1" (job "j1" "A" "running")})
                (assoc :jobs-view {:kind   :output
                                   :job-id "j1"
                                   :data   {:lines    [{:stream "stdout" :text "ok"}
                                                       {:stream "stderr" :text "bad"}]
                                            :status   "failed"
                                            :exitCode 1}}))
          out (jobs/render-output-popup-lines s)]
      (is (str/includes? out "status=failed"))
      (is (str/includes? out "exit=1"))
      (is (str/includes? out "ok"))
      (is (str/includes? out "[stderr] bad"))))

  (testing "no lines shows placeholder"
    (let [s (-> (base-state)
                (assoc :jobs {"j1" (job "j1" "A" "running")})
                (assoc :jobs-view {:kind   :output
                                   :job-id "j1"
                                   :data   {:lines [] :status "completed" :exitCode 0}}))
          out (jobs/render-output-popup-lines s)]
      (is (str/includes? out "(no output)")))))

(deftest output-popup-escape-test
  (testing "Escape on output overlay returns to panel"
    (let [s      (-> (base-state)
                     (assoc :jobs {"j1" (job "j1" "A" "running")}))
          [s1 _] (jobs/cmd-open-jobs-panel s)
          s2     (assoc s1 :jobs-view {:kind :output :job-id "j1" :data {:lines [] :status "running"}})
          [s3 _] (jobs/handle-key s2 (key-msg :escape))]
      (is (nil? (:jobs-view s3)))
      (is (= :picking (:mode s3)))
      (is (= :jobs (get-in s3 [:picker :kind]))))))

(deftest panel-escape-test
  (testing "Escape on panel returns to :ready"
    (let [s      (-> (base-state)
                     (assoc :jobs {"j1" (job "j1" "A" "running")}))
          [s1 _] (jobs/cmd-open-jobs-panel s)
          [s2 _] (jobs/handle-key s1 (key-msg :escape))]
      (is (= :ready (:mode s2)))
      (is (nil? (:picker s2)))
      (is (nil? (:jobs-view s2))))))

(deftest commands-registration-test
  (testing "/jobs is registered"
    (is (contains? commands/command-registry "/jobs"))
    (let [{:keys [doc handler]} (get commands/command-registry "/jobs")]
      (is (string? doc))
      (is (seq doc))
      (is (fn? handler)))))
