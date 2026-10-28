(ns eca-cli.selection
  "The model, agent and variant the next prompts use: chosen in a picker
  (/model, Alt+P / Ctrl+L, /agent, /variant) or cycled with a key, as other
  agents' TUIs do:

  - Shift+Tab: the next agent (code, plan…), as Claude Code reaches plan mode
    and OpenCode cycles agents.
  - Alt+T: the next variant (reasoning effort), none included; Alt+T toggles
    thinking in Claude Code (OpenCode's Ctrl+T is our task list).
  - Ctrl+P or F2 / Shift+F2: the next / previous recently picked model, as pi
    and OpenCode. Picked models are remembered across sessions.

  Changes apply from the next prompt, so they work while the agent works.
  The status line shows the result."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [eca-cli.paths :as paths]
            [eca-cli.protocol :as protocol]
            [eca-cli.view :as view]))

;; --- Recent models ---

(def max-recent-models
  "How many picked models Ctrl+P cycles through."
  5)

(defn load-recent-models
  "The models picked last, most recent first, from the state file."
  []
  (try
    (let [f (paths/recent-models-file)]
      (when (.exists f)
        (let [v (edn/read-string (slurp f))]
          (when (vector? v) (filterv string? v)))))
    (catch Exception _ nil)))

(defn- save-recent-models! [models]
  (try
    (let [f (paths/recent-models-file)]
      (io/make-parents f)
      (spit f (pr-str models)))
    (catch Exception _ nil)))

(defn remember-model
  "`model` was picked instead of `previous`: both first of the recent
  models (so Ctrl+P goes back), which are saved."
  [state model previous]
  (let [recent (->> (concat [model previous] (or (:recent-models state) (load-recent-models)))
                    (remove nil?)
                    distinct
                    (take max-recent-models)
                    vec)]
    (save-recent-models! recent)
    (assoc state :recent-models recent)))

;; --- Choosing ---

(defn choose
  "Make `value` the `kind` (:model, :agent or :variant, nil for none) of the
  next prompts. The server hears of a model or agent; a variant is sent with
  each prompt. A new model drops the variant: the server says which applies
  (`config/updated`). The user's pick ends the command line's (`:pins`)."
  [state kind value]
  (case kind
    :model   (do (protocol/selected-model-changed! (:server state) value)
                 (-> state
                     (assoc :selected-model value :selected-variant nil)
                     (assoc-in [:opts :model] value)
                     (update :opts dissoc :variant)
                     (update :pins (fnil disj #{}) :model :variant)))
    :agent   (do (protocol/selected-agent-changed! (:server state) value)
                 (-> state
                     (assoc :selected-agent value)
                     (assoc-in [:opts :agent] value)
                     (update :pins (fnil disj #{}) :agent)))
    :variant (-> state
                 (assoc :selected-variant value)
                 (assoc-in [:opts :variant] value)
                 (update :pins (fnil disj #{}) :variant))))

;; --- Cycling ---

(defn- next-in
  "The element after `current` in `xs` (`:backward`: before it), wrapping
  around; the first (last) when `current` isn't one of them."
  [xs current dir]
  (let [n (count xs)
        i (.indexOf ^java.util.List xs current)
        j (cond
            (neg? i)            (if (= :backward dir) (dec n) 0)
            (= :backward dir)   (dec i)
            :else               (inc i))]
    (when (pos? n) (nth xs (mod j n)))))

(defn- note [state text]
  (-> state
      (update :items conj {:type :system :level :info :text text})
      view/rebuild-lines))

(defn cycle-agent
  "Shift+Tab: the next agent."
  [state]
  (let [agents (vec (:available-agents state))]
    (if (< (count agents) 2)
      state
      (choose state :agent (next-in agents (:selected-agent state) :forward)))))

(defn cycle-variant
  "Alt+T: the model's next variant, after the last none (its defaults)."
  [state]
  (let [variants (vec (:available-variants state))]
    (if (empty? variants)
      (note state "This model has no variants.")
      (choose state :variant (next-in (into [nil] variants) (:selected-variant state) :forward)))))

(defn current-model [state]
  (or (:selected-model state) (:model state)))

(defn model-cycle
  "What Ctrl+P cycles through: the recent models the server still offers, in
  the order they were picked (cycling doesn't reorder them, so two models
  toggle), with the model in use first when it isn't one of them."
  [state]
  (let [available (set (:available-models state))
        current   (current-model state)
        recent    (filterv #(or (empty? available) (available %)) (distinct (:recent-models state)))]
    (cond
      (nil? current)              recent
      (some #{current} recent)    recent
      :else                       (into [current] recent))))

(defn cycle-model
  "Ctrl+P / F2 (`:forward`), Shift+F2 (`:backward`): the next or previous
  recent model."
  [state dir]
  (let [models (model-cycle state)]
    (if (< (count models) 2)
      (note state "No other recent model yet: pick one with Alt+P (or /model), then Ctrl+P switches between them.")
      (choose state :model (next-in models (current-model state) dir)))))
