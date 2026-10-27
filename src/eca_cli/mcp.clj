(ns eca-cli.mcp
  "MCP servers: `tool/serverUpdated` / `tool/serverRemoved` handlers, the /mcp
  panel and its actions (start, stop, connect, enable, disable, log out), the
  status-bar slot. Owns the `:mcps` state slice — a map of server-name →
  server info kept in sync with the ECA server. No back-references to
  eca-cli.state.

  :mcps shape — keyed by name (string):
    {:name string
     :status string  ; running | starting | stopped | failed | disabled | requires-auth
     :disabled boolean
     :hasAuth boolean
     :command string?  :args [string]?  :url string?
     :tools [tool]?  :prompts [prompt]?  :resources [resource]?}"
  (:require [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [clojure.string :as str]
            [eca-cli.paths :as paths]
            [eca-cli.protocol :as protocol]
            [eca-cli.theme :as theme]))

;; --- /mcp command + panel ---

(defn picker-open?
  "True when state is in the `:picking` mode with the `:mcp` picker kind. Used
  by mcp.clj (handler refresh, panel render) and state.clj (Enter dispatch) to
  share one definition of \"MCP picker is the active overlay\"."
  [state]
  (and (= :picking (:mode state))
       (= :mcp (get-in state [:picker :kind]))))

(defn- panel-list [mcps]
  (mapv val (sort-by key mcps)))

(defn- apply-query [entries query]
  (if (str/blank? query)
    entries
    (let [q (str/lower-case query)]
      (filterv #(str/includes? (str/lower-case (:name %)) q) entries))))

(defn- refresh-mcp-picker
  "Rebuild the open :mcp picker from `:mcps`, re-applying `:query` and preserving
  selection by server name when possible. Returns updated state. Caller must
  guard that the picker is open + `:kind :mcp`."
  [state]
  (let [{:keys [list filtered query]} (:picker state)
        prev-idx   (cl/selected-index list)
        prev-name  (when (and (some? prev-idx) (< prev-idx (count filtered)))
                     (:name (nth filtered prev-idx)))
        all        (panel-list (:mcps state))
        filtered'  (apply-query all query)
        labels     (mapv :name filtered')
        new-idx    (or (when prev-name
                         (some (fn [[i n]] (when (= n prev-name) i))
                               (map-indexed vector labels)))
                       0)
        new-list   (-> list (cl/set-items labels) (cl/select new-idx))]
    (-> state
        (assoc-in [:picker :all] all)
        (assoc-in [:picker :filtered] filtered')
        (assoc-in [:picker :list] new-list))))

;; --- ECA notification handler ---

(defn apply-server-update
  "Applies a `tool/serverUpdated` notification. Non-MCP `:type` values are ignored. MCP servers
  are upserted into `:mcps` keyed by `:name` — subsequent updates replace prior
  entries rather than appending. If the `/mcp` picker is currently open the
  picker's `:all`/`:filtered`/`:list` are refreshed in lockstep, preserving the
  current query and selection (by server name) where possible."
  [state params]
  (if (= "mcp" (:type params))
    (let [name   (:name params)
          entry  (-> params
                     (dissoc :type)
                     (update :tools     #(or % []))
                     (update :prompts   #(or % []))
                     (update :resources #(or % [])))
          state' (assoc-in state [:mcps name] entry)
          state' (if (picker-open? state')
                   (refresh-mcp-picker state')
                   state')]
      [state' nil])
    [state nil]))

(defn cmd-open-mcp-panel
  "Opens the `/mcp` panel. Empty `:mcps` shows a system message instead."
  [state]
  (if (empty? (:mcps state))
    [(-> state
         (update :items conj {:type :system :text "⚠ No MCP servers configured"}))
     nil]
    (let [entries (panel-list (:mcps state))]
      [(-> state
           (assoc :mode :picking
                  :picker {:kind     :mcp
                           :list     (cl/item-list (mapv :name entries) :height 8)
                           :all      entries
                           :filtered entries
                           :query    ""})
           (update :input ti/reset))
       nil])))

;; --- Render ---

(defn- status-role [status]
  (case status
    "running"                    :success
    ("starting" "requires-auth") :warning
    "failed"                     :error
    :subtle))

(defn- render-row [{:keys [name status tools]}]
  (str (theme/paint (status-role status) "●") " " name
       (theme/paint :muted (str " · " (count tools) " tools · " status))
       (case status
         "requires-auth" (theme/paint :accent " (needs login)")
         "failed"        (theme/paint :muted (str " (check " (paths/home-relative (paths/log-file)) ")"))
         nil)))

(defn render-mcp-panel-lines
  "Renders panel rows: one line per MCP server, sorted alphabetically by name.
  When the `/mcp` picker is open, rows come from the picker's `:filtered`
  entries so the display stays in lockstep with Enter's selection target."
  [state]
  (let [entries (if (picker-open? state)
                  (get-in state [:picker :filtered])
                  (panel-list (:mcps state)))]
    (mapv render-row entries)))

;; --- Status-bar fragment ---

(defn health
  "Overall MCP health role for the status line, by the worst enabled server:
  `:error` any failed, `:warning` any starting or waiting for auth,
  `:success` all running, `:muted` otherwise (stopped)."
  [mcps]
  (let [statuses (set (keep #(when-not (= "disabled" (:status %)) (:status %)) (vals mcps)))]
    (cond
      (statuses "failed")                                  :error
      (some statuses ["starting" "requires-auth"])         :warning
      (and (seq statuses) (= #{"running"} statuses))       :success
      :else                                                :muted)))

(defn status-bar-fragment
  "The status-line MCP slot, `● MCP running/enabled` with the dot coloured by
  `health`, or nil when no MCP servers are known."
  [state]
  (let [mcps (:mcps state)]
    (when (seq mcps)
      (let [enabled (remove #(= "disabled" (:status %)) (vals mcps))
            running (count (filter #(= "running" (:status %)) enabled))]
        (str (theme/paint (health mcps) "●") " "
             (theme/paint :muted (str "MCP " running "/" (count enabled))))))))

;; --- :picking :kind :mcp key dispatch ---

(defn- selected-entry [state]
  (let [{:keys [list filtered]} (:picker state)
        idx (cl/selected-index list)]
    (when (and (some? idx) (< idx (count filtered)))
      (nth filtered idx))))

(defn actions-for
  "What can be done with an MCP server in its state, as eca-emacs offers:
  [label action] pairs for `protocol/mcp-server-action!`. Enabling and
  disabling are saved in the ECA config, starting and stopping aren't. Log
  out forgets the server's OAuth login and restarts it."
  [{:keys [status disabled hasAuth]}]
  (let [disabled? (or disabled (= "disabled" status))]
    (vec (concat
           (case status
             "requires-auth"        [["Connect (log in)" :connect]]
             ("running" "starting") [["Stop" :stop]]
             [["Start" :start]])
           [(if disabled? ["Enable" :enable] ["Disable" :disable])]
           (when hasAuth [["Log out" :logout]])))))

(defn- close [state]
  (-> state (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)))

(defn handle-key
  "Enter in the /mcp panel: the server's actions (`actions-for`). Escape and
  filtering are picker.clj's."
  [state msg]
  (if-let [entry (and (msg/key-press? msg) (msg/key-match? msg :enter) (selected-entry state))]
    (let [actions (mapv (fn [[label action]] [label [action (:name entry)]]) (actions-for entry))]
      [(-> state
           (assoc :picker {:kind     :mcp-action
                           :query    ""
                           :server   (:name entry)
                           :list     (cl/item-list (mapv first actions) :height 8)
                           :all      actions
                           :filtered actions})
           (update :input ti/reset))
       nil])
    [state nil]))

(defn action-picker-open? [state]
  (and (= :picking (:mode state)) (= :mcp-action (get-in state [:picker :kind]))))

(defn handle-action-select
  "Enter on an MCP server action: sent; the server's tool/serverUpdated
  shows the result in the status line and the panel."
  [state]
  (let [{:keys [list filtered]} (:picker state)
        idx                     (cl/selected-index list)
        [label [action name]]   (when (and (some? idx) (< idx (count filtered))) (nth filtered idx))]
    (if action
      (do (protocol/mcp-server-action! (:server state) action name)
          [(-> (close state)
               (update :items conj {:type :system :level :info
                                    :text (str label ": " name)}))
           nil])
      [state nil])))

(defn apply-server-removed
  "`tool/serverRemoved`: the server is gone from the config."
  [state {:keys [name]}]
  (let [s (update state :mcps dissoc name)]
    [(if (picker-open? s) (refresh-mcp-picker s) s) nil]))
