(ns eca-cli.transcript
  "Which chat items have gone to the terminal's history.

  Inline (the default, see `eca-cli.render`), a finished item is printed once
  above the live region, as Claude Code does, and scrolls into the
  terminal's own scrollback: from then on it can't change on screen. The
  state says how far that has gone:

  - `:committed`: how many leading `:items` are in the history. It only moves
    forward, over items that can no longer change (`item-final?`), in order:
    a tool call still running holds back everything after it.
  - `:epoch`: bumped whenever the items are replaced wholesale (a new chat,
    /clear, a chat the server replays): the history starts over.
  - `:history-removals`: cumulative removal indices within this epoch, each
    relative to the items after earlier removals. A renderer consumes new
    entries to rebase its printed count, which may lag behind :committed.

  Pure: functions of the state.")

(defn reset-items
  "Replace the chat's items wholesale: a new chat, /clear, a chat the server
  replays. The terminal's history starts over with them."
  [state items]
  (-> state
      (assoc :items items :committed 0 :history-removals [])
      (update :epoch (fnil inc 0))))

(defn remove-items
  "Drop matching items, rebase positional references, and record every removal
  for the renderer. Each ledger index is relative to the items after earlier
  removals, including removals of items not yet committed or printed."
  [state pred]
  (let [items (vec (:items state))
        committed (min (count items) (or (:committed state) 0))
        {:keys [kept indices removals gone]}
        (reduce-kv (fn [{:keys [kept] :as result} i item]
                     (if (pred item)
                       (cond-> (update result :removals conj (count kept))
                         (< i committed) (update :gone inc))
                       (-> result
                           (assoc-in [:indices i] (count kept))
                           (update :kept conj item))))
                   {:kept [] :indices {} :removals [] :gone 0}
                   items)]
    (if (empty? removals)
      state
      (cond-> (-> state
                  (assoc :items kept)
                  (update :history-removals (fnil into []) removals))
        (pos? gone) (assoc :committed (- committed gone))
        (some? (:subagent-chats state))
        (update :subagent-chats
                (fn [chats]
                  (into (empty chats)
                        (keep (fn [[chat-id i]]
                                (when-let [new-i (get indices i)] [chat-id new-i])))
                        chats)))
        (:focus-path state)
        (update :focus-path
                (fn [path]
                  (when-let [new-i (get indices (first path))]
                    (assoc (vec path) 0 new-i))))))))

(defn turn-running? [state]
  (some? (:turn-started-at state)))

(defn welcome-settled?
  "Whether the welcome has all its facts and initialization has finished.
  Early config can precede model discovery. Stop waiting when the server
  is gone or the user starts talking."
  [state]
  (boolean (or (and (empty? (:welcome-pending state))
                    (every? :done? (vals (:init-tasks state))))
               (= :disconnected (:mode state))
               (some #(= :user (:type %)) (:items state)))))

(defn item-final?
  "Whether `item` can no longer change on screen."
  [state item]
  (or (true? (:settled? item))
      (case (:type item)
        :welcome   (welcome-settled? state)
        :tool-call (contains? #{:called :rejected} (:state item))
        :thinking  (not= :thinking (:status item))
        :hook      (not= :running (:status item))
        :question  (boolean (or (contains? item :answer) (:cancelled? item)))
        true)))

(defn settle-turn
  "After confirmed turn completion, release orphan unfinished activity. Idle
  replay and local stop requests are not completion. Questions can outlive a
  turn, so they are never settled here; already-final rows are left unchanged."
  [state]
  (letfn [(settle [item]
            (cond-> item
              (and (#{:tool-call :thinking :hook} (:type item))
                   (not (item-final? state item)))
              (assoc :settled? true)
              (:sub-items item) (update :sub-items #(mapv settle %))))]
    (cond-> state
      (:items state) (update :items #(mapv settle %)))))

(defn- frozen-end
  "The index of the first item from `from` on that may still change."
  [state from]
  (let [items (:items state)
        n     (count items)]
    (loop [i from]
      (if (and (< i n) (item-final? state (nth items i)))
        (recur (inc i))
        i))))

(defn advance
  "Move `:committed` over the items that just became final. Never back: what
  is printed stays printed. Never past the items. The state is returned as
  is when nothing moves."
  [state]
  (let [committed (or (:committed state) 0)
        from      (min (count (:items state)) committed)
        to        (frozen-end state from)]
    (if (= to committed)
      state
      (assoc state :committed to))))
