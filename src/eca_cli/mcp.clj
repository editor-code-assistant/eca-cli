(ns eca-cli.mcp
  "MCP server status: `tool/serverUpdated` handler, /mcp panel, status-bar slot,
  and `mcp/connectServer` dispatch. Owns the `:mcps` state slice — a map of
  server-name → server info kept in sync with the ECA server. No back-references
  to eca-cli.state.

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
            [charm.program :as program]
            [eca-cli.protocol :as protocol]))

;; --- ECA notification handler ---

(defn handle-tool-server-updated
  "Handles `tool/serverUpdated`. Non-MCP `:type` values are ignored. MCP servers
  are upserted into `:mcps` keyed by `:name` — subsequent updates replace prior
  entries rather than appending."
  [state params]
  (if (= "mcp" (:type params))
    (let [name   (:name params)
          entry  (-> params
                     (dissoc :type)
                     (update :tools     #(or % []))
                     (update :prompts   #(or % []))
                     (update :resources #(or % [])))
          state' (assoc-in state [:mcps name] entry)]
      [state' nil])
    [state nil]))

;; --- /mcp command + panel ---

(defn- panel-list [mcps]
  (mapv val (sort-by key mcps)))

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

(defn- status-emoji [status]
  (case status
    "running"       "🟢"
    "starting"      "🟡"
    "failed"        "🔴"
    "stopped"       "⚪"
    "disabled"      "⚫"
    "requires-auth" "🟠"
    "⚪"))

(defn- render-row [{:keys [name status tools]}]
  (let [base (str (status-emoji status) " " name " · " (count tools) " tools · " status)]
    (cond-> base
      (= "requires-auth" status) (str " [connect]")
      (= "failed" status)        (str " (check ~/.cache/eca/eca-cli.log)"))))

(defn render-mcp-panel-lines
  "Renders panel rows: one line per MCP server, sorted alphabetically by name."
  [state]
  (mapv render-row (panel-list (:mcps state))))

;; --- Status-bar fragment ---

(defn status-bar-fragment
  "Returns the status-bar MCP slot string, or nil when no MCPs are known.
  Wide (>=120 cols): `MCPs: n/m ✓` (or `⚠` when any non-running). Narrow: `M:n/m`."
  [state width]
  (let [mcps (:mcps state)]
    (when (seq mcps)
      (let [total   (count mcps)
            running (count (filter #(= "running" (:status (val %))) mcps))
            wide?   (>= width 120)
            sentinel (if (= running total) "✓" "⚠")]
        (if wide?
          (str "MCPs: " running "/" total " " sentinel)
          (str "M:" running "/" total))))))

;; --- connect-server dispatch ---

(defn connect-server!
  "Sends `mcp/connectServer` notification for the given server name. Pure cmd
  builder — returns [state cmd]."
  [state name]
  [state
   (program/cmd
     (fn []
       (protocol/mcp-connect-server! (:server state) name)
       nil))])

;; --- :picking :kind :mcp key dispatch ---

(defn- selected-entry [state]
  (let [{:keys [list filtered]} (:picker state)
        idx (cl/selected-index list)]
    (when (and (some? idx) (< idx (count filtered)))
      (nth filtered idx))))

(defn handle-key
  "Dispatches keys for the :mcp picker. Enter on requires-auth → connect;
  otherwise no-op. Escape and filter behaviours are handled by picker.clj."
  [state msg]
  (cond
    (and (msg/key-press? msg) (msg/key-match? msg :enter))
    (if-let [entry (selected-entry state)]
      (if (= "requires-auth" (:status entry))
        (let [[s' cmd] (connect-server! state (:name entry))]
          [(-> s' (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)) cmd])
        [state nil])
      [state nil])

    :else
    [state nil]))
