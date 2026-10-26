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
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

;; --- Status helpers ---

(defn- status-dot [status]
  (theme/paint (case status
                 "running"   :warning
                 "completed" :success
                 "failed"    :error
                 :subtle)
               "●"))

(defn- truncate-label [s n]
  (let [s (or s "")]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (- n 3))) "...")
      s)))

(def ^:private summary-max-len 80)

(defn- job-summary
  "Display label for a job: prefers :summary, falls back to :label then id,
  truncated to `summary-max-len`. `id-fallback` is supplied separately so
  callers that already destructured `:id` into a local can pass it directly."
  [job id-fallback]
  (truncate-label (or (:summary job) (:label job) id-fallback) summary-max-len))

(defn- jobs-vec
  "Stable seq of jobs ordered by chatLabel then startedAt."
  [state]
  (->> (vals (:jobs state))
       (sort-by (juxt #(or (:chatLabel %) "")
                      #(or (:startedAt %) "")))
       vec))

;; --- Status-bar fragment ---

(defn status-bar-fragment
  "Status-line slot for background jobs still running (`1 job`, `3 jobs`), nil
  when none are. Finished ones stay listed in /jobs."
  [state]
  (let [n (count (filter #(= "running" (:status %)) (vals (:jobs state))))]
    (when (pos? n)
      (theme/paint :muted (str n (if (= 1 n) " job" " jobs"))))))

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

(defn- read-output-cmd
  "Fires jobs/readOutput and returns immediately. The protocol callback (invoked
  on the reader thread when the server responds) puts an :eca-jobs-output
  runtime message onto the server queue, where handle-jobs-output picks it up.
  No blocking deref — the command-executor thread is not stalled and concurrent
  jobs/updated notifications continue to flow."
  [srv job-id]
  (program/cmd
    (fn []
      (let [queue (:queue srv)]
        (protocol/jobs-read-output!
          srv job-id
          (fn [r]
            (.put queue {:type   :eca-jobs-output
                         :job-id job-id
                         :data   (or (:result r)
                                     {:lines [] :status "unknown" :exitCode nil})})))
        nil))))

;; --- /jobs command — opens panel via shared :picker ---

(defn- panel-row-label
  "Plain one-line label for the panel's picker list (the panel renders its own
  styled rows)."
  [job]
  (str (job-summary job (:id job)) "  ·  " (or (:elapsed job) "")))

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
        header       (str (theme/paint :accent-bold "Background jobs")
                          (theme/paint :muted "  Enter: output  ·  d: kill  ·  Esc: close"))
        sep          (view/divider width)
        flat-idx     (atom -1)
        render-row   (fn [job]
                       (swap! flat-idx inc)
                       (let [selected? (= @flat-idx sel-idx)
                             summary   (job-summary job (:id job))
                             exit      (when (and (= "failed" (:status job)) (:exitCode job))
                                         (str "  exit:" (:exitCode job)))]
                         (str (if selected? (theme/paint :accent-bold "❯ ") "  ")
                              (status-dot (:status job)) " "
                              (theme/paint (when selected? :accent-bold) summary)
                              (theme/paint :muted (str "  " (or (:elapsed job) "")))
                              (some->> exit (theme/paint :error)))))
        max-hdr-w    60
        hdr-chrome   4 ;; "── " prefix + trailing space before fill
        render-group (fn [[chat-label jobs]]
                       (let [fill (max 0 (- (min max-hdr-w width) (count chat-label) hdr-chrome))
                             hdr  (str (theme/paint :border "── ")
                                       (theme/paint :muted chat-label)
                                       (theme/paint :border (str " " (apply str (repeat fill "─")))))
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
        label   (job-summary job job-id)
        header  (str (status-dot status) " " (theme/paint :bold label)
                     (theme/paint :muted (str "  ·  status=" status
                                              (when (some? exit) (str "  ·  exit=" exit)))))
        lines   (:lines data)
        body    (if (seq lines)
                  (mapv (fn [{:keys [stream text]}]
                          (if (= "stderr" stream)
                            (str (theme/paint :error "[stderr] ") text)
                            text))
                        lines)
                  [(theme/paint :muted "(no output)")])
        footer  (theme/paint :muted "Esc: back to panel")]
    (str/join "\n"
              (into [header (view/divider (:width state))]
                    (conj body (view/divider (:width state)) footer)))))

;; --- Render: kill confirm modal ---

(defn render-confirm-kill-lines
  "Returns the kill confirm modal view."
  [state]
  (let [{:keys [job-id]} (:jobs-view state)
        job     (get-in state [:jobs job-id])
        summary (job-summary job job-id)]
    (str (theme/paint (theme/merge-styles :warning :bold) "Kill ") summary "? "
         (theme/paint :muted "[") (theme/paint (theme/merge-styles :error :bold) "y")
         (theme/paint :muted "/") (theme/paint :bold "n") (theme/paint :muted "]"))))

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
