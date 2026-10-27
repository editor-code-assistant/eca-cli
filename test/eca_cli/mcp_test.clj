(ns eca-cli.mcp-test
  (:require [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.commands :as commands]
            [eca-cli.mcp :as mcp]
            [eca-cli.paths :as paths]
            [eca-cli.picker :as picker]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.theme :as theme]))

(defn- base-state []
  {:mode :ready
   :mcps {}
   :items []
   :input (ti/text-input)
   :width 160})

(defn- mcps-fixture []
  {"alpha" {:name "alpha" :status "running"       :tools []}
   "bravo" {:name "bravo" :status "requires-auth" :tools []}
   "delta" {:name "delta" :status "running"       :tools []}})

;; --- tool/serverUpdated handler ---

(deftest tool-server-updated-handler-test
  (testing "single mcp notification adds entry keyed by name"
    (let [[s _] (mcp/apply-server-update
                  (base-state)
                  {:type "mcp" :name "fs" :status "running"
                   :disabled false :hasAuth false
                   :tools [{:name "read"} {:name "write"}]})]
      (is (= #{"fs"} (set (keys (:mcps s)))))
      (is (= "running" (get-in s [:mcps "fs" :status])))
      (is (= 2 (count (get-in s [:mcps "fs" :tools]))))))

  (testing "non-mcp type is ignored"
    (let [[s _] (mcp/apply-server-update
                  (base-state)
                  {:type "other" :name "x" :status "running"})]
      (is (empty? (:mcps s)))))

  (testing "missing tools/prompts/resources are normalised to []"
    (let [[s _] (mcp/apply-server-update
                  (base-state)
                  {:type "mcp" :name "fs" :status "running"})]
      (is (= [] (get-in s [:mcps "fs" :tools])))
      (is (= [] (get-in s [:mcps "fs" :prompts])))
      (is (= [] (get-in s [:mcps "fs" :resources]))))))

(deftest tool-server-updated-update-not-duplicate-test
  (testing "subsequent notification with same name updates existing entry"
    (let [[s1 _] (mcp/apply-server-update
                   (base-state)
                   {:type "mcp" :name "fs" :status "starting" :tools []})
          [s2 _] (mcp/apply-server-update
                   s1
                   {:type "mcp" :name "fs" :status "running"
                    :tools [{:name "read"}]})]
      (is (= 1 (count (:mcps s2))))
      (is (= "running" (get-in s2 [:mcps "fs" :status])))
      (is (= 1 (count (get-in s2 [:mcps "fs" :tools])))))))

;; --- status-bar-fragment ---

(deftest status-bar-fragment-empty-test
  (testing "empty :mcps → nil (slot hidden)"
    (is (nil? (mcp/status-bar-fragment (base-state))))))

(deftest status-bar-fragment-test
  (testing "running/enabled, the dot coloured by the worst state"
    (let [frag (mcp/status-bar-fragment
                 (assoc (base-state) :mcps {"a" {:status "running"} "b" {:status "running"}
                                            "c" {:status "running"} "d" {:status "failed"}}))]
      (is (= "● MCP 3/4" (theme/strip-ansi frag)))
      (is (str/starts-with? frag (theme/sgr :error)))))
  (testing "all running → success"
    (is (str/starts-with? (mcp/status-bar-fragment
                            (assoc (base-state) :mcps {"a" {:status "running"}}))
                          (theme/sgr :success))))
  (testing "disabled servers are left out of the count"
    (is (= "● MCP 1/1" (theme/strip-ansi (mcp/status-bar-fragment
                                           (assoc (base-state) :mcps {"a" {:status "running"}
                                                                      "b" {:status "disabled"}})))))))

(deftest health-test
  (is (= :error   (mcp/health {"a" {:status "failed"} "b" {:status "starting"}})))
  (is (= :warning (mcp/health {"a" {:status "running"} "b" {:status "requires-auth"}})))
  (is (= :warning (mcp/health {"a" {:status "starting"}})))
  (is (= :success (mcp/health {"a" {:status "running"} "b" {:status "disabled"}})))
  (is (= :muted   (mcp/health {"a" {:status "stopped"}})))
  (is (= :muted   (mcp/health {"a" {:status "disabled"}}))))

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
          styled (mcp/render-mcp-panel-lines state)
          lines  (mapv theme/strip-ansi styled)]
      (is (= 3 (count lines)))
      (is (str/starts-with? (nth lines 0) "● alpha"))
      (is (str/includes? (nth lines 0) "(needs login)"))
      (is (str/starts-with? (nth lines 1) "● bravo"))
      (is (str/includes? (nth lines 1) "2 tools"))
      (is (str/includes? (nth lines 1) (str "check " (paths/home-relative (paths/log-file))))
          "points at the server log eca-cli writes")
      (is (str/starts-with? (nth lines 2) "● zeta"))
      (is (str/includes? (nth lines 2) "1 tools"))
      (testing "the status dot carries the colour"
        (is (str/starts-with? (nth styled 0) (theme/sgr :warning)))
        (is (str/starts-with? (nth styled 1) (theme/sgr :error)))
        (is (str/starts-with? (nth styled 2) (theme/sgr :success)))))))

;; --- /mcp command + server actions ---

(defn- panel-on [entry]
  (assoc (base-state)
         :mode :picking
         :server :stub
         :picker {:kind     :mcp
                  :list     (cl/item-list [(:name entry)] :height 8)
                  :all      [entry]
                  :filtered [entry]
                  :query    ""}))

(defn- action-labels [state]
  (mapv first (get-in state [:picker :all])))

(deftest mcp-actions-for-test
  (testing "by state, as eca-emacs offers them"
    (is (= [["Connect (log in)" :connect] ["Disable" :disable]]
           (mcp/actions-for {:status "requires-auth"})))
    (is (= [["Stop" :stop] ["Disable" :disable]]
           (mcp/actions-for {:status "running"})))
    (is (= [["Stop" :stop] ["Disable" :disable]]
           (mcp/actions-for {:status "starting"})))
    (is (= [["Start" :start] ["Disable" :disable]]
           (mcp/actions-for {:status "stopped"})))
    (is (= [["Start" :start] ["Disable" :disable]]
           (mcp/actions-for {:status "failed"}))))
  (testing "a disabled server can be enabled (or started once)"
    (is (= [["Start" :start] ["Enable" :enable]]
           (mcp/actions-for {:status "disabled" :disabled true})))
    (is (= [["Stop" :stop] ["Enable" :enable]]
           (mcp/actions-for {:status "running" :disabled true}))
        "started by hand while disabled in the config"))
  (testing "a server with an OAuth login can be logged out of"
    (is (= [["Stop" :stop] ["Disable" :disable] ["Log out" :logout]]
           (mcp/actions-for {:status "running" :hasAuth true})))))

(deftest mcp-enter-opens-actions-test
  (testing "Enter on a server opens its actions; nothing is sent yet"
    (let [sent    (atom [])]
      (with-redefs [protocol/mcp-server-action! (fn [_srv action name] (swap! sent conj [action name]))]
        (let [[s cmd] (mcp/handle-key (panel-on {:name "fs" :status "requires-auth"}) (msg/key-press :enter))]
          (is (nil? cmd))
          (is (= :picking (:mode s)))
          (is (= :mcp-action (get-in s [:picker :kind])))
          (is (= "fs" (get-in s [:picker :server])))
          (is (= ["Connect (log in)" "Disable"] (action-labels s)))
          (is (empty? @sent))))))

  (testing "a running server: stop / disable"
    (let [[s _] (mcp/handle-key (panel-on {:name "fs" :status "running"}) (msg/key-press :enter))]
      (is (= ["Stop" "Disable"] (action-labels s)))))

  (testing "keys other than Enter are left to the picker"
    (let [st    (panel-on {:name "fs" :status "running"})
          [s _] (mcp/handle-key st (msg/key-press "x"))]
      (is (= st s)))))

(deftest mcp-action-select-test
  (testing "Enter on an action sends it for that server and closes the panel"
    (let [sent (atom [])]
      (with-redefs [protocol/mcp-server-action! (fn [_srv action name] (swap! sent conj [action name]))]
        (let [[opened _] (mcp/handle-key (panel-on {:name "fs" :status "requires-auth"}) (msg/key-press :enter))
              _          (is (mcp/action-picker-open? opened))
              [s cmd]    (mcp/handle-action-select opened)]
          (is (nil? cmd))
          (is (= [[:connect "fs"]] @sent))
          (is (= :ready (:mode s)))
          (is (nil? (:picker s)))
          (is (= "Connect (log in): fs" (:text (peek (:items s)))))))))

  (testing "the selected action is the one sent"
    (let [sent (atom [])]
      (with-redefs [protocol/mcp-server-action! (fn [_srv action name] (swap! sent conj [action name]))]
        (let [[opened _] (mcp/handle-key (panel-on {:name "fs" :status "running"}) (msg/key-press :enter))
              moved      (update-in opened [:picker :list] cl/select 1)]
          (mcp/handle-action-select moved)
          (is (= [[:disable "fs"]] @sent))))))

  (testing "an action through the state dispatcher shows its note"
    (with-redefs [protocol/mcp-server-action! (fn [_ _ _])]
      (let [[opened _] (mcp/handle-key (panel-on {:name "fs" :status "running"}) (msg/key-press :enter))
            [s _]      (state/update-state (assoc opened :chat-lines [] :scroll-offset 0 :height 24)
                                           (msg/key-press :enter))]
        (is (some #(str/includes? (theme/strip-ansi %) "Stop: fs") (:chat-lines s)))))))

(deftest mcp-server-action-protocol-test
  (testing "each action is its mcp/* notification, with the server's name"
    (let [sent (atom [])]
      (with-redefs [protocol/send-notification! (fn [_srv method params] (swap! sent conj [method params]))]
        (doseq [a [:start :stop :enable :disable :logout :connect]]
          (protocol/mcp-server-action! :srv a "fs"))
        (is (= [["mcp/startServer" {:name "fs"}] ["mcp/stopServer" {:name "fs"}]
                ["mcp/enableServer" {:name "fs"}] ["mcp/disableServer" {:name "fs"}]
                ["mcp/logoutServer" {:name "fs"}] ["mcp/connectServer" {:name "fs"}]]
               @sent))))))

(deftest tool-server-removed-test
  (testing "tool/serverRemoved drops the server"
    (let [[s _] (mcp/apply-server-removed (assoc (base-state) :mcps (mcps-fixture)) {:name "bravo"})]
      (is (= #{"alpha" "delta"} (set (keys (:mcps s)))))))

  (testing "with the panel open, its rows follow"
    (let [[opened _] (mcp/cmd-open-mcp-panel (assoc (base-state) :mcps (mcps-fixture)))
          [s _]      (mcp/apply-server-removed opened {:name "bravo"})]
      (is (= ["alpha" "delta"] (mapv :name (get-in s [:picker :filtered]))))
      (is (= 2 (count (mcp/render-mcp-panel-lines s))))))

  (testing "through the notification dispatcher"
    (let [[s _] (state/update-state (assoc (base-state) :mcps (mcps-fixture) :server nil)
                                    {:type :eca-tick
                                     :msgs [{:method "tool/serverRemoved" :params {:name "alpha"}}]})]
      (is (= #{"bravo" "delta"} (set (keys (:mcps s))))))))

;; --- picker/render in lockstep ---

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
      (is (str/starts-with? (theme/strip-ansi (first lines)) "● bravo"))
      (with-redefs [protocol/mcp-server-action!
                    (fn [_srv action n] (swap! sent conj [action n]))]
        (let [[actions _] (mcp/handle-key filtered (msg/key-press :enter))]
          (is (= "bravo" (get-in actions [:picker :server]))
              "Enter targets the one filtered (visible) row, not the original list")
          (mcp/handle-action-select actions)
          (is (= [[:connect "bravo"]] @sent)))))))

(deftest picker-filters-by-name-only-test
  (testing "typing filters :mcp rows by :name, not by status/tool text"
    (let [state      (-> (base-state) (assoc :server :stub :mcps (mcps-fixture)))
          [opened _] (mcp/cmd-open-mcp-panel state)]
      (testing "a shared status substring ('running') matches no rows"
        (let [f (picker/filter-picker opened "running")]
          (is (= [] (mapv :name (get-in f [:picker :filtered]))))
          (is (= [] (mcp/render-mcp-panel-lines f)))))
      (testing "'auth' (only in bravo's status) matches no rows"
        (let [f (picker/filter-picker opened "auth")]
          (is (= [] (mapv :name (get-in f [:picker :filtered]))))))
      (testing "a name substring matches by name"
        (let [f (picker/filter-picker opened "elt")]
          (is (= ["delta"] (mapv :name (get-in f [:picker :filtered]))))))
      (testing "picker :list items stay name strings after a keystroke"
        (let [f (picker/filter-picker opened "a")]
          (is (every? string? (cl/items (get-in f [:picker :list])))
              "labels remain names, not serialized entry maps"))))))

(deftest picker-refreshes-on-server-update-test
  (testing "tool/serverUpdated arriving while picker open refreshes :all/:filtered"
    (let [state    (-> (base-state) (assoc :mcps (mcps-fixture)))
          [opened _] (mcp/cmd-open-mcp-panel state)
          [updated _] (mcp/apply-server-update
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
        (is (not (some #(str/includes? % "(needs login)") lines))
            "row (needs login) suffix gone after status transitions to running"))))

  (testing "with non-empty filter, update preserves :query and selection by name"
    (let [state      (-> (base-state) (assoc :mcps (mcps-fixture)))
          [opened _] (mcp/cmd-open-mcp-panel state)
          filtered   (picker/filter-picker opened "b")
          [updated _] (mcp/apply-server-update
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
          [updated _] (mcp/apply-server-update
                        on-delta
                        {:type "mcp" :name "bravo" :status "running"
                         :tools []})
          idx        (cl/selected-index (get-in updated [:picker :list]))]
      (is (= "delta" (:name (nth (get-in updated [:picker :filtered]) idx)))
          "cursor follows the previously selected server name")))

  (testing "update while picker closed does not synthesise picker keys"
    (let [state      (-> (base-state) (assoc :mcps (mcps-fixture)))
          [updated _] (mcp/apply-server-update
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
