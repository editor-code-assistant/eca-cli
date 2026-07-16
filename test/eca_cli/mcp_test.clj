(ns eca-cli.mcp-test
  (:require [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.commands :as commands]
            [eca-cli.mcp :as mcp]
            [eca-cli.picker :as picker]
            [eca-cli.protocol :as protocol]))

(defn- base-state []
  {:mode :ready
   :mcps {}
   :items []
   :input (ti/text-input)
   :width 160})

;; --- tool/serverUpdated handler ---

(deftest tool-server-updated-handler-test
  (testing "single mcp notification adds entry keyed by name"
    (let [[s _] (mcp/handle-tool-server-updated
                  (base-state)
                  {:type "mcp" :name "fs" :status "running"
                   :disabled false :hasAuth false
                   :tools [{:name "read"} {:name "write"}]})]
      (is (= #{"fs"} (set (keys (:mcps s)))))
      (is (= "running" (get-in s [:mcps "fs" :status])))
      (is (= 2 (count (get-in s [:mcps "fs" :tools]))))))

  (testing "non-mcp type is ignored"
    (let [[s _] (mcp/handle-tool-server-updated
                  (base-state)
                  {:type "other" :name "x" :status "running"})]
      (is (empty? (:mcps s)))))

  (testing "missing tools/prompts/resources are normalised to []"
    (let [[s _] (mcp/handle-tool-server-updated
                  (base-state)
                  {:type "mcp" :name "fs" :status "running"})]
      (is (= [] (get-in s [:mcps "fs" :tools])))
      (is (= [] (get-in s [:mcps "fs" :prompts])))
      (is (= [] (get-in s [:mcps "fs" :resources]))))))

(deftest tool-server-updated-update-not-duplicate-test
  (testing "subsequent notification with same name updates existing entry"
    (let [[s1 _] (mcp/handle-tool-server-updated
                   (base-state)
                   {:type "mcp" :name "fs" :status "starting" :tools []})
          [s2 _] (mcp/handle-tool-server-updated
                   s1
                   {:type "mcp" :name "fs" :status "running"
                    :tools [{:name "read"}]})]
      (is (= 1 (count (:mcps s2))))
      (is (= "running" (get-in s2 [:mcps "fs" :status])))
      (is (= 1 (count (get-in s2 [:mcps "fs" :tools])))))))

;; --- status-bar-fragment ---

(deftest status-bar-fragment-empty-test
  (testing "empty :mcps → nil (slot hidden)"
    (is (nil? (mcp/status-bar-fragment (base-state) 160)))
    (is (nil? (mcp/status-bar-fragment (base-state) 80)))))

(deftest status-bar-fragment-wide-test
  (let [state (assoc (base-state)
                     :mcps {"a" {:status "running"}
                            "b" {:status "running"}
                            "c" {:status "running"}
                            "d" {:status "failed"}})]
    (testing "width 160, 3/4 running → ⚠ sentinel"
      (is (= "MCPs: 3/4 ⚠" (mcp/status-bar-fragment state 160))))
    (testing "width 120 (boundary, wide path)"
      (is (= "MCPs: 3/4 ⚠" (mcp/status-bar-fragment state 120))))
    (testing "width 80 → compact, no sentinel"
      (is (= "M:3/4" (mcp/status-bar-fragment state 80))))
    (testing "width 100 (narrow)"
      (is (= "M:3/4" (mcp/status-bar-fragment state 100)))))

  (testing "all running, wide → ✓ sentinel"
    (let [state (assoc (base-state)
                       :mcps {"a" {:status "running"}
                              "b" {:status "running"}})]
      (is (= "MCPs: 2/2 ✓" (mcp/status-bar-fragment state 160)))
      (is (= "M:2/2" (mcp/status-bar-fragment state 80))))))

;; --- panel render ---

(deftest mcp-panel-render-test
  (testing "rows sorted by name, status text + tool count + emoji present"
    (let [state (assoc (base-state)
                       :mcps {"zeta" {:name "zeta" :status "running"
                                      :tools [{:name "z1"}]}
                              "alpha" {:name "alpha" :status "requires-auth"
                                       :tools []}
                              "bravo" {:name "bravo" :status "failed"
                                       :tools [{:name "b1"} {:name "b2"}]}})
          lines (mcp/render-mcp-panel-lines state)]
      (is (= 3 (count lines)))
      (is (str/starts-with? (nth lines 0) "🟠 alpha"))
      (is (str/includes? (nth lines 0) "[connect]"))
      (is (str/starts-with? (nth lines 1) "🔴 bravo"))
      (is (str/includes? (nth lines 1) "2 tools"))
      (is (str/includes? (nth lines 1) "check ~/.cache/eca/eca-cli.log"))
      (is (str/starts-with? (nth lines 2) "🟢 zeta"))
      (is (str/includes? (nth lines 2) "1 tools")))))

;; --- /mcp command + connect dispatch ---

(deftest mcp-connect-server-dispatch-test
  (testing "Enter on requires-auth row triggers mcp-connect-server!"
    (let [sent (atom [])
          state (assoc (base-state)
                       :mode :picking
                       :server :stub
                       :picker {:kind :mcp
                                :list (cl/item-list ["fs"] :height 8)
                                :all      [{:name "fs" :status "requires-auth"}]
                                :filtered [{:name "fs" :status "requires-auth"}]
                                :query ""})]
      (with-redefs [protocol/mcp-connect-server!
                    (fn [_srv name] (swap! sent conj name))]
        (let [[_ cmd] (mcp/handle-key state (msg/key-press :enter))]
          ;; cmd is a charm cmd; execute it to fire the notification
          (when cmd ((:fn cmd)))
          (is (= ["fs"] @sent))))))

  (testing "Enter on running row does NOT send notification"
    (let [sent (atom [])
          state (assoc (base-state)
                       :mode :picking
                       :server :stub
                       :picker {:kind :mcp
                                :list (cl/item-list ["fs"] :height 8)
                                :all      [{:name "fs" :status "running"}]
                                :filtered [{:name "fs" :status "running"}]
                                :query ""})]
      (with-redefs [protocol/mcp-connect-server!
                    (fn [_srv name] (swap! sent conj name))]
        (let [[_ cmd] (mcp/handle-key state (msg/key-press :enter))]
          (when cmd ((:fn cmd)))
          (is (empty? @sent)))))))

;; --- picker/render in lockstep ---

(defn- mcps-fixture []
  {"alpha" {:name "alpha" :status "running"       :tools []}
   "bravo" {:name "bravo" :status "requires-auth" :tools []}
   "delta" {:name "delta" :status "running"       :tools []}})

(deftest picker-renders-filtered-after-query-test
  (testing "panel rows reflect picker :filtered after a filter is typed, and
            Enter dispatches against that same filtered subset"
    (let [sent  (atom [])
          state (-> (base-state)
                    (assoc :server :stub :mcps (mcps-fixture)))
          [opened _] (mcp/cmd-open-mcp-panel state)
          filtered   (picker/filter-picker opened "b")
          lines      (mcp/render-mcp-panel-lines filtered)]
      (is (= 1 (count lines)))
      (is (str/starts-with? (first lines) "🟠 bravo"))
      (with-redefs [protocol/mcp-connect-server!
                    (fn [_srv n] (swap! sent conj n))]
        (let [[_ cmd] (mcp/handle-key filtered (msg/key-press :enter))]
          (when cmd ((:fn cmd)))
          (is (= ["bravo"] @sent)
              "Enter targets the one filtered (visible) row, not the original list"))))))

(deftest picker-refreshes-on-server-update-test
  (testing "tool/serverUpdated arriving while picker open refreshes :all/:filtered"
    (let [state    (-> (base-state) (assoc :mcps (mcps-fixture)))
          [opened _] (mcp/cmd-open-mcp-panel state)
          [updated _] (mcp/handle-tool-server-updated
                        opened
                        {:type "mcp" :name "bravo" :status "running"
                         :tools [{:name "t1"}]})]
      (is (= "running" (get-in updated [:mcps "bravo" :status])))
      (is (= "running" (->> updated :picker :all
                            (some #(when (= "bravo" (:name %)) (:status %))))))
      (is (= "running" (->> updated :picker :filtered
                            (some #(when (= "bravo" (:name %)) (:status %))))))
      (let [lines (mcp/render-mcp-panel-lines updated)]
        (is (= 3 (count lines)))
        (is (not (some #(str/includes? % "[connect]") lines))
            "row [connect] suffix gone after status transitions to running"))))

  (testing "with non-empty filter, update preserves :query and selection by name"
    (let [state      (-> (base-state) (assoc :mcps (mcps-fixture)))
          [opened _] (mcp/cmd-open-mcp-panel state)
          filtered   (picker/filter-picker opened "b")
          [updated _] (mcp/handle-tool-server-updated
                        filtered
                        {:type "mcp" :name "bravo" :status "running"
                         :tools [{:name "t1"}]})]
      (is (= "b" (get-in updated [:picker :query]))
          "query preserved across update")
      (is (= ["bravo"] (mapv :name (get-in updated [:picker :filtered])))
          "filter re-applied to new :mcps")
      (is (= 0 (cl/selected-index (get-in updated [:picker :list])))
          "cursor stays on bravo")))

  (testing "selection by name is preserved when a different server updates"
    (let [state      (-> (base-state) (assoc :mcps (mcps-fixture)))
          [opened _] (mcp/cmd-open-mcp-panel state)
          ;; user moves cursor to delta (index 2)
          on-delta   (update-in opened [:picker :list] cl/select 2)
          [updated _] (mcp/handle-tool-server-updated
                        on-delta
                        {:type "mcp" :name "bravo" :status "running"
                         :tools []})
          idx        (cl/selected-index (get-in updated [:picker :list]))]
      (is (= "delta" (:name (nth (get-in updated [:picker :filtered]) idx)))
          "cursor follows the previously selected server name")))

  (testing "update while picker closed does not synthesise picker keys"
    (let [state      (-> (base-state) (assoc :mcps (mcps-fixture)))
          [updated _] (mcp/handle-tool-server-updated
                        state
                        {:type "mcp" :name "bravo" :status "running" :tools []})]
      (is (nil? (:picker updated)))
      (is (= :ready (:mode updated))))))

;; --- /mcp registration ---

(deftest commands-registration-test
  (testing "/mcp exists in command-registry with handler + doc"
    (is (contains? commands/command-registry "/mcp"))
    (let [entry (get commands/command-registry "/mcp")]
      (is (string? (:doc entry)))
      (is (seq (:doc entry)))
      (is (fn? (:handler entry))))))
