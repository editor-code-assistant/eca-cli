(ns eca-cli.jobs
  "Background jobs panel: state slice, `jobs/updated` handler, `/jobs` command,
  picker panel + output popup + kill confirm modal. Owns the `:picker` arm
  with `:kind :jobs` and the `:jobs-view` overlay under it. No back-references
  to eca-cli.state."
  (:require [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [charm.program :as program]
            [clojure.string :as str]
            [eca-cli.protocol :as protocol]
            [eca-cli.view :as view]))

;; --- Status helpers ---

(defn- status-emoji [status]
  (case status
    "running"   "🟡"
    "completed" "✅"
    "failed"    "🔴"
    "killed"    "⚫"
    "⚫"))

(defn- truncate-label [s n]
  (let [s (or s "")]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (- n 3))) "...")
      s)))

(defn- jobs-vec
  "Stable seq of jobs ordered by chatLabel then startedAt."
  [state]
  (->> (vals (:jobs state))
       (sort-by (juxt #(or (:chatLabel %) "")
                      #(or (:startedAt %) "")))
       vec))

;; --- Status-bar fragment ---

(defn status-bar-fragment
  "Compact `[N jobs]` (>=120 cols) or `[Nj]` (<120). Returns nil when no jobs."
  [state width]
  (let [n (count (:jobs state))]
    (when (pos? n)
      (if (>= width 120)
        (str "[" n " jobs]")
        (str "[" n "j]")))))

;; --- ECA notification handler ---

(defn handle-jobs-updated
  "Replaces the entire :jobs map keyed by id from the server-supplied list."
  [state params]
  (let [jobs   (or (:jobs params) [])
        by-id  (into {} (map (fn [j] [(:id j) j])) jobs)]
    [(assoc state :jobs by-id) nil]))

;; --- Protocol cmd builders ---

(defn- kill-cmd [srv job-id]
  (program/cmd
    (fn []
      (protocol/jobs-kill! srv job-id (fn [_] nil))
      nil)))

(defn- read-output-cmd [srv job-id]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/jobs-read-output! srv job-id
                                    (fn [r] (deliver p (or (:result r) {}))))
        {:type   :eca-jobs-output
         :job-id job-id
         :data   (deref p 10000 {:lines [] :status "unknown" :exitCode nil})}))))

;; --- /jobs command — opens panel via shared :picker ---

(defn- panel-row-label
  "Compact one-line label used as the picker row text and for confirm modal."
  [job]
  (let [emoji   (status-emoji (:status job))
        summary (truncate-label (or (:summary job) (:label job) (:id job)) 80)
        elapsed (or (:elapsed job) "")]
    (str emoji " " summary "  ·  " elapsed)))

(defn cmd-open-jobs-panel
  "Open the jobs panel as a :picker with :kind :jobs. Empty :jobs surfaces a
  system message — no panel opened."
  [state]
  (let [jobs (jobs-vec state)]
    (if (empty? jobs)
      [(-> state
           (update :items conj {:type :system :text "No background jobs"})
           view/rebuild-lines)
       nil]
      (let [labels (mapv panel-row-label jobs)]
        [(-> state
             (assoc :mode :picking
                    :picker {:kind     :jobs
                             :list     (cl/item-list labels :height 8)
                             :all      jobs
                             :filtered jobs
                             :query    ""})
             (update :input ti/reset))
         nil]))))

;; --- Render: jobs panel (chat-grouped list) ---

(defn render-jobs-panel-lines
  "Returns a single rendered string for the panel: grouped rows by chatLabel,
  delegated to from view/render-picker when (= :jobs (get-in state [:picker :kind]))."
  [state]
  (let [width        (:width state)
        filtered     (get-in state [:picker :filtered])
        list-comp    (get-in state [:picker :list])
        sel-idx      (cl/selected-index list-comp)
        groups       (->> filtered
                          (group-by #(or (:chatLabel %) "Unknown Chat"))
                          (sort-by key))
        header       "Background Jobs  (Enter: output  ·  d: kill  ·  Esc: close)"
        sep          (view/divider width)
        flat-idx     (atom -1)
        render-row   (fn [job]
                       (swap! flat-idx inc)
                       (let [marker  (if (= @flat-idx sel-idx) "▸ " "  ")
                             emoji   (status-emoji (:status job))
                             summary (truncate-label (or (:summary job) (:label job) (:id job)) 80)
                             elapsed (or (:elapsed job) "")
                             exit    (when (and (= "failed" (:status job)) (:exitCode job))
                                       (str "  exit:" (:exitCode job)))]
                         (str marker emoji " " summary "  " elapsed (or exit ""))))
        render-group (fn [[chat-label jobs]]
                       (let [hdr  (str "── " chat-label " "
                                       (apply str (repeat (max 0 (- (min 60 width) (count chat-label) 4)) "─")))
                             rows (mapv render-row jobs)]
                         (str/join "\n" (into [hdr] rows))))]
    (str/join "\n"
              (into [header sep]
                    (interpose "" (mapv render-group groups))))))

;; --- Render: output popup ---

(defn render-output-popup-lines
  "Returns the output popup view: header + buffered lines (stderr highlighted)."
  [state]
  (let [{:keys [job-id data]} (:jobs-view state)
        job     (get-in state [:jobs job-id])
        status  (or (:status data) (:status job) "unknown")
        exit    (:exitCode data)
        label   (truncate-label (or (:summary job) (:label job) job-id) 80)
        header  (str label "  ·  status=" status
                     (when (some? exit) (str "  ·  exit=" exit)))
        lines   (:lines data)
        body    (if (seq lines)
                  (mapv (fn [{:keys [stream text]}]
                          (if (= "stderr" stream)
                            (str "[stderr] " text)
                            text))
                        lines)
                  ["(no output)"])
        footer  "Esc: back to panel"]
    (str/join "\n"
              (into [header (view/divider (:width state))]
                    (conj body (view/divider (:width state)) footer)))))

;; --- Render: kill confirm modal ---

(defn render-confirm-kill-lines
  "Returns the kill confirm modal view."
  [state]
  (let [{:keys [job-id]} (:jobs-view state)
        job     (get-in state [:jobs job-id])
        summary (truncate-label (or (:summary job) (:label job) job-id) 80)]
    (str "Kill " summary "? [y/n]")))

;; --- Key dispatch ---

(defn- selected-job [state]
  (let [list-comp (get-in state [:picker :list])
        filtered  (get-in state [:picker :filtered])
        idx       (cl/selected-index list-comp)]
    (when (and (some? idx) (< idx (count filtered)))
      (nth filtered idx))))

(defn- close-overlay [state]
  (-> state (dissoc :jobs-view)))

(defn- close-panel [state]
  (-> state
      (assoc :mode :ready)
      (dissoc :picker :jobs-view)
      (update :input ti/focus)))

(defn- handle-output-key [state msg]
  (cond
    (and (msg/key-press? msg) (msg/key-match? msg :escape))
    [(close-overlay state) nil]

    :else [state nil]))

(defn- handle-confirm-key [state msg]
  (cond
    (and (msg/key-press? msg) (msg/key-match? msg "y"))
    (let [job-id (get-in state [:jobs-view :job-id])]
      [(close-overlay state) (kill-cmd (:server state) job-id)])

    (and (msg/key-press? msg) (or (msg/key-match? msg "n")
                                  (msg/key-match? msg :escape)))
    [(close-overlay state) nil]

    :else [state nil]))

(defn- handle-panel-key [state msg]
  (cond
    (and (msg/key-press? msg) (msg/key-match? msg :enter))
    (if-let [job (selected-job state)]
      [(assoc state :jobs-view {:kind :output :job-id (:id job) :data nil})
       (read-output-cmd (:server state) (:id job))]
      [state nil])

    (and (msg/key-press? msg) (msg/key-match? msg "d"))
    (if-let [job (selected-job state)]
      [(assoc state :jobs-view {:kind :confirm-kill :job-id (:id job)}) nil]
      [state nil])

    (and (msg/key-press? msg) (msg/key-match? msg :escape))
    [(close-panel state) nil]

    :else
    (let [[new-list _] (cl/list-update (get-in state [:picker :list]) msg)]
      [(assoc-in state [:picker :list] new-list) nil])))

(defn handle-key
  "Dispatch keys while in the jobs panel (`:picking + :kind :jobs`).
  Sub-overlays (output popup, kill confirm) consume keys first."
  [state msg]
  (case (get-in state [:jobs-view :kind])
    :output       (handle-output-key state msg)
    :confirm-kill (handle-confirm-key state msg)
    (handle-panel-key state msg)))

;; --- Runtime msg handler ---

(defn handle-jobs-output
  "Runtime event from read-output-cmd. Attaches fetched data to the overlay
  if it is still open for the same job-id."
  [state msg]
  (let [{:keys [job-id data]} msg]
    (if (and (= :output (get-in state [:jobs-view :kind]))
             (= job-id (get-in state [:jobs-view :job-id])))
      [(assoc-in state [:jobs-view :data] data) nil]
      [state nil])))
