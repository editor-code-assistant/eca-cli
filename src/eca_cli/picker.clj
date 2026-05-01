(ns eca-cli.picker
  "Picker overlay state + key dispatch (model / agent / session / command).
  Pure transformations on a :picker map under state."
  (:require [clojure.string :as str]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.protocol :as protocol]
            [eca-cli.sessions :as sessions]))

(defn printable-char?
  "True when msg is a keypress carrying a single printable character (no
  Ctrl / Alt modifiers). Lives here because picker, commands, and state
  all share this guard."
  [m]
  (and (msg/key-press? m)
       (string? (:key m))
       (= 1 (count (:key m)))
       (not (:ctrl m))
       (not (:alt m))))

(defn item-display [kind item]
  (case kind
    :session (first item)
    :command (str (first item) "  —  " (second item))
    item))

(defn open-picker [state kind]
  (let [items (if (= :model kind) (:available-models state) (:available-agents state))]
    (if (empty? items)
      state
      (-> state
          (assoc :mode :picking
                 :picker {:kind     kind
                          :list     (cl/item-list items :height 8)
                          :all      items
                          :filtered items
                          :query    ""})
          (update :input ti/reset)))))

(defn open-session-picker [state session-pairs]
  (let [labels (mapv first session-pairs)]
    (-> state
        (assoc :mode :picking
               :picker {:kind     :session
                        :list     (cl/item-list labels :height 8)
                        :all      session-pairs
                        :filtered session-pairs
                        :query    ""})
        (update :input ti/reset))))

(defn filter-picker [state ch]
  (let [query    (str (get-in state [:picker :query]) ch)
        kind     (get-in state [:picker :kind])
        all      (get-in state [:picker :all])
        filtered (filterv #(str/includes? (str/lower-case (item-display kind %))
                                          (str/lower-case query))
                          all)
        labels   (mapv #(item-display kind %) filtered)]
    (-> state
        (assoc-in [:picker :query] query)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items labels))))

(defn unfilter-picker [state]
  (let [query    (get-in state [:picker :query])
        new-q    (if (seq query) (subs query 0 (dec (count query))) "")
        kind     (get-in state [:picker :kind])
        all      (get-in state [:picker :all])
        filtered (if (seq new-q)
                   (filterv #(str/includes? (str/lower-case (item-display kind %))
                                            (str/lower-case new-q))
                            all)
                   all)
        labels   (mapv #(item-display kind %) filtered)]
    (-> state
        (assoc-in [:picker :query] new-q)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items labels))))

;; --- Selection handlers (model / agent / session) ---
;;
;; The :command kind is dispatched by state.clj's update-state directly,
;; because picker cannot depend on commands without creating a cycle.

(defn- select-model-or-agent [state kind]
  (let [list-comp (get-in state [:picker :list])
        selected  (cl/selected-item list-comp)]
    (if selected
      (do
        (if (= :model kind)
          (protocol/selected-model-changed! (:server state) selected)
          (protocol/selected-agent-changed! (:server state) selected))
        [(-> state
             (assoc :mode :ready)
             (assoc (if (= :model kind) :selected-model :selected-agent) selected)
             (cond-> (= :model kind) (assoc :selected-variant nil))
             (assoc-in [:opts (if (= :model kind) :model :agent)] selected)
             (dissoc :picker)
             (update :input ti/focus))
         nil])
      [state nil])))

(defn- select-session [state]
  (let [{:keys [list filtered]} (:picker state)
        idx                     (cl/selected-index list)
        [_display chat-id]      (when (and (some? idx) (< idx (count filtered)))
                                  (nth filtered idx))]
    (when chat-id
      (sessions/save-chat-id! (get-in state [:opts :workspace]) chat-id))
    [(-> state
         (assoc :mode :ready :items [] :chat-lines [] :scroll-offset 0)
         (assoc :chat-id (or chat-id (:chat-id state)))
         (dissoc :picker)
         (update :input ti/focus))
     (when chat-id (sessions/open-chat-cmd (:server state) chat-id))]))

(defn handle-key
  "Dispatch keypresses while :mode is :picking. Returns [new-state cmd-or-nil].
  Returns the original state unchanged for command-picker Enter — caller
  (state.clj) handles that selection to avoid a picker → commands cycle."
  [state msg]
  (let [kind (get-in state [:picker :kind])]
    (cond
      (and (msg/key-press? msg) (msg/key-match? msg :enter))
      (case kind
        (:model :agent) (select-model-or-agent state kind)
        :session        (select-session state)
        :command        [state nil] ; caller handles
        [state nil])

      (and (msg/key-press? msg) (msg/key-match? msg :escape))
      [(-> state (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)) nil]

      (and (msg/key-press? msg) (msg/key-match? msg :backspace))
      (if (and (= :command kind) (= "" (get-in state [:picker :query])))
        [(-> state (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)) nil]
        [(unfilter-picker state) nil])

      (printable-char? msg)
      [(filter-picker state (:key msg)) nil]

      :else
      (let [[new-list _] (cl/list-update (get-in state [:picker :list]) msg)]
        [(assoc-in state [:picker :list] new-list) nil]))))
