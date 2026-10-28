(ns eca-cli.picker
  "Picker overlay state + key dispatch (model / agent / chat / command).
  Pure transformations on a :picker map under state."
  (:require [clojure.string :as str]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.chats :as chats]
            [eca-cli.selection :as selection]
            [eca-cli.transcript :as transcript]))

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

(defn- relative-to
  "`path` relative to the directory `root` when it's inside it, else as is."
  [path root]
  (let [prefix (when root (if (str/ends-with? root "/") root (str root "/")))]
    (if (and prefix (str/starts-with? path prefix) (< (count prefix) (count path)))
      (subs path (count prefix))
      path)))

(defn at-label
  "How a context reads in the prompt after `@` (or `#`), and is matched
  there when sending (see `eca-cli.chat`): a file's path, relative to the
  workspace `root` when it's in it, as eca-emacs shows them; a directory's
  with a `/`; an MCP resource's `server:name`. A plain string is a file path."
  ([ctx] (at-label ctx nil))
  ([ctx root]
   (if (string? ctx)
     ctx
     (case (:type ctx)
       "file"        (relative-to (str (:path ctx)) root)
       "directory"   (let [p (relative-to (str (:path ctx)) root)]
                       (if (str/ends-with? p "/") p (str p "/")))
       "mcpResource" (str (:server ctx) ":" (str/replace (str (:name ctx)) #"\s+" "-"))
       "web"         (str (:url ctx))
       (str (:type ctx))))))

(defn item-display [kind item]
  (case kind
    (:chat :rollback :rollback-type :flags :flag-action :timeline
     :providers :provider-action :mcp-action) (first item)
    :command (str (first item) "  —  " (second item))
    ;; A path, or a server context labelled when it arrived: [label context].
    :at-file (if (vector? item) (first item) (at-label item))
    :mcp (:name item)
    ;; A prompt, on one line.
    :history (str/trim (str/replace (str item) #"\s+" " "))
    item))

(defn filter-items
  "Items whose label contains `query` (case-insensitive), the earliest match
  first: typing `context` puts `/context` before a command that only
  mentions context in its description. Ties keep their order."
  [kind items query]
  (let [q (str/lower-case (str query))]
    (if (= "" q)
      (vec items)
      (->> items
           (keep (fn [item]
                   (when-let [i (str/index-of (str/lower-case (item-display kind item)) q)]
                     [i item])))
           (sort-by first)
           (mapv second)))))

(def no-variant
  "The variant picker's entry for none (the model's defaults), as in eca-emacs."
  "-")

(defn open-picker
  "The model, agent or variant picker. What's typed stays in the input for
  afterwards: it opens mid-sentence too (Alt+P), even while the agent works."
  [state kind]
  (let [items (case kind
                :model   (:available-models state)
                :agent   (:available-agents state)
                :variant (into [no-variant] (sort (:available-variants state))))]
    (if (empty? items)
      state
      (assoc state
             :mode :picking
             :picker {:kind     kind
                      :list     (cl/item-list items :height 8)
                      :all      items
                      :filtered items
                      :query    ""}))))

(defn open-chat-picker [state chat-pairs]
  (let [labels (mapv first chat-pairs)]
    (-> state
        (assoc :mode :picking
               :picker {:kind     :chat
                        :list     (cl/item-list labels :height 8)
                        :all      chat-pairs
                        :filtered chat-pairs
                        :query    ""})
        (update :input ti/reset))))

(defn open-at-file-picker
  "Opens the context picker with an empty list: `@` adds a context (files,
  directories, MCP resources; `chat/queryContext`), `hash?` the `#` picker,
  which inserts a file's path for the LLM to read (`chat/queryFiles`), as in
  eca-emacs. Items arrive asynchronously and are spliced in by
  update-at-file-results."
  ([state] (open-at-file-picker state false))
  ([state hash?]
   (assoc state
          :mode :picking
          :picker {:kind     :at-file
                   :hash?    hash?
                   :list     (cl/item-list [] :height 8)
                   :all      []
                   :filtered []
                   :query    ""})))

(defn update-at-file-results
  "Splice the server's items into the open picker, keeping the query typed
  since: file paths, or contexts (labelled here, relative to the
  workspace). `from`, the query and picker the items answer, drops a late
  answer to an older query (the user typed on)."
  ([state items] (update-at-file-results state items nil))
  ([state items from]
   (let [picker (:picker state)]
     (if (and (= :at-file (:kind picker))
              (or (nil? from)
                  (and (= (:query from) (:query picker))
                       (= (boolean (:hash? from)) (boolean (:hash? picker))))))
       (let [root     (get-in state [:opts :workspace])
             items    (mapv #(if (map? %) [(at-label % root) %] %) items)
             filtered (filter-items :at-file items (:query picker))]
         (-> state
             (assoc-in [:picker :all] items)
             (assoc-in [:picker :filtered] filtered)
             (update-in [:picker :list] cl/set-items (mapv #(item-display :at-file %) filtered))))
       state))))

(defn filter-picker [state ch]
  (let [query    (str (get-in state [:picker :query]) ch)
        kind     (get-in state [:picker :kind])
        filtered (filter-items kind (get-in state [:picker :all]) query)
        labels   (mapv #(item-display kind %) filtered)]
    (-> state
        (assoc-in [:picker :query] query)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items labels))))

(defn unfilter-picker [state]
  (let [query    (get-in state [:picker :query])
        new-q    (if (seq query) (subs query 0 (dec (count query))) "")
        kind     (get-in state [:picker :kind])
        filtered (filter-items kind (get-in state [:picker :all]) new-q)
        labels   (mapv #(item-display kind %) filtered)]
    (-> state
        (assoc-in [:picker :query] new-q)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items labels))))

;; --- Selection handlers (model / agent / chat) ---
;;
;; The :command kind is dispatched by state.clj's update-state directly,
;; because picker cannot depend on commands without creating a cycle.

(defn- select-model-or-agent
  "Enter in the model, agent or variant picker. `:mode :ready` hands over
  to `eca-cli.chat/after-picker` (the turn may still run)."
  [state kind]
  (let [list-comp (get-in state [:picker :list])
        selected  (cl/selected-item list-comp)
        value     (when-not (and (= :variant kind) (= no-variant selected)) selected)]
    (if selected
      [(-> state
           (selection/choose kind value)
           (cond-> (= :model kind) (selection/remember-model value (selection/current-model state)))
           (assoc :mode :ready)
           (dissoc :picker)
           (update :input ti/focus))
       nil]
      [state nil])))

(defn open-history-picker
  "Ctrl+R: the prompts sent before, newest first, to search by typing."
  [state]
  (let [items (vec (distinct (rseq (vec (:input-history state)))))]
    (if (empty? items)
      state
      (assoc state
             :mode :picking
             :picker {:kind     :history
                      :list     (cl/item-list (mapv #(item-display :history %) items) :height 8)
                      :all      items
                      :filtered items
                      :query    ""}))))

(defn- select-history
  "Enter in the history picker: the prompt back in the input, to edit or send."
  [state]
  (let [{:keys [list filtered]} (:picker state)
        idx                     (cl/selected-index list)
        text                    (when (and (some? idx) (< idx (count filtered))) (nth filtered idx))]
    [(-> state
         (cond-> text (update :input #(-> % (ti/set-value text) (assoc :pos (count text)))))
         (assoc :mode :ready)
         (dissoc :picker)
         (update :input ti/focus))
     nil]))

(defn- select-chat [state]
  (let [{:keys [list filtered]} (:picker state)
        idx                     (cl/selected-index list)
        [_display chat-id]      (when (and (some? idx) (< idx (count filtered)))
                                  (nth filtered idx))]
    (when chat-id
      (chats/save-chat-id! (get-in state [:opts :workspace]) chat-id))
    [(-> state
         (transcript/reset-items [])
         (assoc :mode :ready :chat-lines [] :scroll-offset 0)
         (assoc :chat-id (or chat-id (:chat-id state)))
         (dissoc :picker)
         (update :input ti/focus))
     (when chat-id (chats/open-chat-cmd (:server state) chat-id))]))

(defn- whitespace? [ch]
  (or (= \space ch) (= \newline ch) (= \tab ch)))

(defn- insert-at-tag
  "Insert `<prefix><path>` (`@` or `#`) into the text-input at the current
  cursor position, always leaving exactly one space after the tag (and a
  leading space when the char before the cursor is non-whitespace). Cursor
  ends past that trailing space, so text typed next never extends the token —
  keeping it matchable by `contexts-for-text` at send time."
  [input prefix path]
  (let [value    (ti/value input)
        pos      (min (count value) (max 0 (or (ti/position input) (count value))))
        before   (subs value 0 pos)
        after    (subs value pos)
        prev-ch  (when (pos? (count before)) (.charAt ^String before (dec (count before))))
        next-ch  (when (pos? (count after))  (.charAt ^String after 0))
        lead-sp? (and prev-ch (not (whitespace? prev-ch)))
        next-ws? (and next-ch (whitespace? next-ch))
        insert   (str (when lead-sp? " ") prefix path (when-not next-ws? " "))
        cursor   (+ pos (count insert) (if next-ws? 1 0))]
    (-> input
        (ti/set-value (str before insert after))
        (assoc :pos cursor))))

(defn- select-at-file
  "`@`: the context is added to the next prompt, `@label` marks it in the
  text. `#`: just `#label` in the text, sent as the file's full path (see
  `eca-cli.chat/expand-refs`)."
  [state]
  (let [{:keys [list filtered hash?]} (:picker state)
        idx                           (cl/selected-index list)
        item                          (when (and (some? idx) (< idx (count filtered)))
                                        (nth filtered idx))
        [label ctx]                   (cond
                                        (string? item) [item {:type "file" :path item}]
                                        (vector? item) item
                                        :else          [(at-label item) item])]
    (if item
      [(-> state
           (update :input #(-> % (insert-at-tag (if hash? "#" "@") label) ti/focus))
           (cond-> (not hash?) (update :pending-contexts (fnil conj []) ctx))
           (assoc :mode :ready)
           (dissoc :picker))
       nil]
      [(-> state (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)) nil])))

(defn- space? [msg]
  (and (msg/key-press? msg) (not (:ctrl msg)) (not (:alt msg))
       (contains? #{" " :space} (:key msg))))

(defn leave-command-picker
  "The `/` picker gives way to the input, holding `/` and what was typed to
  filter it, then `rest`: a command is typed with its arguments (`/rename My
  chat`), the picker only helping to find its name."
  [state rest]
  (let [text (str "/" (get-in state [:picker :query]) rest)]
    (-> state
        (assoc :mode :ready)
        (dissoc :picker)
        (update :input #(-> % (ti/set-value text) (assoc :pos (count text)) ti/focus)))))

(defn handle-key
  "Dispatch keypresses while :mode is :picking. Returns [new-state cmd-or-nil].
  Returns the original state unchanged for command-picker Enter — caller
  (state.clj) handles that selection to avoid a picker → commands cycle."
  [state msg]
  (let [kind (get-in state [:picker :kind])]
    (cond
      ;; A space after a command's name: its arguments follow, in the input.
      (and (= :command kind) (space? msg))
      [(leave-command-picker state " ") nil]

      (and (msg/key-press? msg) (msg/key-match? msg :enter))
      (case kind
        (:model :agent :variant) (select-model-or-agent state kind)
        :history        (select-history state)
        :chat           (select-chat state)
        :at-file        (select-at-file state)
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
