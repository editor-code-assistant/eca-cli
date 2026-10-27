(ns eca-cli.history
  "Chat history surgery and navigation, following eca-emacs:

  - /rollback: back to before one of your messages, dropping the messages,
    the file changes tools made since, or both (`chat/rollback`); a dropped
    message comes back to the input.
  - /flag <text>: a named checkpoint (`chat/addFlag`), drawn as a rule
    across the chat. It follows the latest message or content the protocol
    gives an id: your latest message, or a later tool call or thought (the
    agent's replies have none, so a flag can't follow one).
  - /flags: jump to a flag, fork a new chat from it (`chat/fork`), or
    remove it (`chat/removeFlag`).
  - /timeline: jump to one of your messages or a flag.

  Each opens a picker; `handle-select` takes the choice (state.clj routes
  Enter here for `picker-kinds`)."
  (:require [clojure.string :as str]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [eca-cli.protocol :as protocol]
            [eca-cli.view :as view]))

(def picker-kinds #{:rollback :rollback-type :flags :flag-action :timeline})

(defn- one-line
  "`s` on one line, cut to `n` characters."
  [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- note [state text]
  (-> state
      (update :items conj {:type :system :level :info :text text})
      view/rebuild-lines))

(defn- open
  "Open a picker of `[label value]` items, newest first."
  [state kind items]
  (-> state
      (assoc :mode :picking
             :picker {:kind     kind
                      :query    ""
                      :list     (cl/item-list (mapv first items) :height 8)
                      :all      items
                      :filtered items})
      (update :input ti/reset)))

(defn- close [state]
  (-> state (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)))

(defn- indexed
  "Chat items of `types` as `{:idx … item…}`, newest first."
  [state types]
  (->> (:items state)
       (keep-indexed (fn [i item] (when (types (:type item)) (assoc item :idx i))))
       reverse
       vec))

(defn scroll-to-item
  "Scroll so chat item `idx` starts at the top of the chat area (or as far as
  scrolling goes)."
  [state idx]
  (if-let [[start _] (get (:chat-line-spans state) idx)]
    (let [total   (count (:chat-lines state))
          visible (max 1 (- (:height state) 5))]
      (assoc state :scroll-offset (max 0 (min (- total visible) (- total start visible)))))
    state))

;; --- Commands ---

(defn cmd-rollback [state]
  (let [msgs (filter :content-id (indexed state #{:user}))]
    (if (empty? msgs)
      [(note state "Nothing to roll back to yet.") nil]
      [(open state :rollback (mapv (fn [m] [(one-line (:text m) 100) m]) msgs)) nil])))

(defn- latest-id
  "The id of the chat's latest message or content a flag can follow: a
  message of yours, a tool call, a thought, a hook."
  [state]
  (some (fn [item]
          (or (:content-id item)
              (when (#{:tool-call :thinking :hook} (:type item)) (:id item))))
        (rseq (vec (:items state)))))

(defn cmd-flag [state args]
  (let [id (latest-id state)]
    (cond
      (str/blank? args)
      [(note state "Usage: /flag <text> flags your latest message (or the agent's latest tool call); /flags lists them.") nil]

      (or (nil? (:chat-id state)) (nil? id))
      [(note state "Nothing to flag yet.") nil]

      :else
      (do (protocol/add-flag! (:server state) (:chat-id state) id (str/trim args))
          [state nil]))))

(defn cmd-flags [state]
  (let [flags (indexed state #{:flag})]
    (if (empty? flags)
      [(note state "No flags yet: /flag <text> adds one.") nil]
      [(open state :flags (mapv (fn [f] [(one-line (:text f) 100) f]) flags)) nil])))

(defn cmd-timeline [state]
  (let [points (indexed state #{:user :flag})]
    (if (empty? points)
      [(note state "Nothing to jump to yet.") nil]
      [(open state :timeline (mapv (fn [p] [(str (if (= :flag (:type p)) "⚑ " "┃ ") (one-line (:text p) 100)) p])
                                   points))
       nil])))

;; --- Picker choices ---

(def ^:private rollback-types
  [["Messages and the changes tools made" ["messages" "tools"]]
   ["Only the messages"                   ["messages"]]
   ["Only the changes tools made"         ["tools"]]])

(defn- rollback! [state message include]
  (protocol/rollback-chat! (:server state) (:chat-id state) (:content-id message) include)
  (cond-> (close state)
    ;; The dropped message comes back to the input, before any draft.
    (some #{"messages"} include)
    (update :input #(ti/set-value % (str/join "\n" (remove str/blank? [(str/trim (str (:text message)))
                                                                       (ti/value %)]))))))

(defn handle-select
  "Enter on an item of one of `picker-kinds`."
  [state]
  (let [{:keys [kind list filtered rollback-message]} (:picker state)
        idx           (cl/selected-index list)
        [_ value]     (when (and (some? idx) (< idx (count filtered))) (nth filtered idx))]
    (if (nil? value)
      [state nil]
      (case kind
        :rollback
        [(-> state
             (open :rollback-type rollback-types)
             (assoc-in [:picker :rollback-message] value))
         nil]

        :rollback-type
        [(rollback! state rollback-message value) nil]

        :flags
        [(open state :flag-action [["Jump to it"                [:jump value]]
                                   ["Fork a new chat from here" [:fork value]]
                                   ["Remove it"                 [:remove value]]])
         nil]

        :flag-action
        (let [[action flag] value]
          (case action
            :jump   [(-> state close (scroll-to-item (:idx flag))) nil]
            :fork   (do (protocol/fork-chat! (:server state) (:chat-id state) (:content-id flag))
                        [(close state) nil])
            :remove (do (protocol/remove-flag! (:server state) (:chat-id state) (:content-id flag))
                        [(-> state
                             close
                             (update :items (fn [items] (vec (remove #(and (= :flag (:type %))
                                                                           (= (:content-id flag) (:content-id %)))
                                                                     items))))
                             view/rebuild-lines)
                         nil])))

        :timeline
        [(-> state close (scroll-to-item (:idx value))) nil]))))
