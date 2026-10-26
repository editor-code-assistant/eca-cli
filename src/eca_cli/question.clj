(ns eca-cli.question
  "`chat/askQuestion`: the server asks the user something — the `ask_user`
  tool, the choices of the server's /login — and waits for the answer.

  Questions queue in :questions; the first one is asked in :asking mode,
  taking over the input area (`view/render-question`): ↑/↓ and Enter pick an
  option, typing answers freely when the question allows it, Esc cancels.
  Each question and its answer also stay in the chat as a :question item."
  (:require [clojure.string :as str]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.chat :as chat]
            [eca-cli.protocol :as protocol]
            [eca-cli.view :as view]))

(defn- option
  "An option as `{:label :description}`, whatever the server sent."
  [opt]
  (cond
    (map? opt)    {:label (str (or (:label opt) "")) :description (:description opt)}
    (string? opt) {:label opt}
    :else         {:label (str opt)}))

(defn- asked-by?
  "Whether chat item `item` records question `q`: its own :question item, or
  the tool call that asked it (`ask_user`), which then shows the answer."
  [q item]
  (case (:type item)
    :question  (= (:request-id q) (:request-id item))
    :tool-call (and (:tool-call-id q) (= (:tool-call-id q) (:id item)))
    false))

(defn handle-ask-question
  "Server request `chat/askQuestion` (answered later, see `answer`). Asked
  right away unless the user is approving a tool call or answering another
  question: then it waits its turn."
  [state request]
  (let [{:keys [chatId question options toolCallId allowFreeform]} (:params request)
        q (cond-> {:request-id      (:id request)
                   :chat-id         chatId
                   :question        (str question)
                   :options         (if (sequential? options) (mapv option options) [])
                   ;; The protocol's default.
                   :allow-freeform? (not (false? allowFreeform))}
            toolCallId (assoc :tool-call-id toolCallId))]
    (-> state
        (update :questions (fnil conj []) q)
        ;; The tool call that asks already says what (`Q: …`) and will show
        ;; the answer; otherwise (login, a sub-agent's call) the question
        ;; gets an item of its own.
        (cond-> (not (some #(asked-by? q %) (:items state)))
          (update :items conj {:type :question :request-id (:id request) :question (:question q)}))
        (cond-> (#{:ready :chatting} (:mode state)) chat/enter-asking)
        view/rebuild-lines)))

(defn- answer
  "Answer the question being asked with `response` and record it in the
  chat (`shown`: `{:answer text}` or `{:cancelled? true}`); then ask the next
  one, or go back."
  [state response shown]
  (let [[q & more] (:questions state)]
    (protocol/send-response! (:server state) (:request-id q) response)
    (-> state
        (assoc :questions (vec more) :question-cursor 0)
        (update :items (fn [items] (mapv #(if (asked-by? q %) (merge % shown) %) items)))
        (update :input ti/reset)
        chat/settle-mode
        view/rebuild-lines)))

(defn- pick [state label]
  (answer state {:answer label :cancelled false} {:answer label}))

(defn handle-key
  "Keys in :asking mode."
  [state msg]
  (let [{:keys [options allow-freeform?]} (first (:questions state))
        n      (count options)
        typed  (str/trim (str (ti/value (:input state))))
        press? #(and (msg/key-press? msg) (msg/key-match? msg %))
        digit  (when (and (msg/key-press? msg) (string? (:key msg)) (re-matches #"[1-9]" (:key msg)))
                 (parse-long (:key msg)))]
    (cond
      (press? :escape)
      [(answer state {:answer nil :cancelled true} {:cancelled? true}) nil]

      (and (press? :up) (pos? n))
      [(update state :question-cursor #(mod (dec (or % 0)) n)) nil]

      (and (press? :down) (pos? n))
      [(update state :question-cursor #(mod (inc (or % 0)) n)) nil]

      (press? :enter)
      (cond
        (and allow-freeform? (seq typed)) [(pick state typed) nil]
        (pos? n)                          [(pick state (:label (nth options (min (dec n) (:question-cursor state 0))))) nil]
        :else                             [state nil])

      ;; Digits pick only when there's no typing to do: they'd start an answer.
      (and digit (not allow-freeform?) (<= digit n))
      [(pick state (:label (nth options (dec digit)))) nil]

      allow-freeform?
      (let [[input cmd] (ti/text-input-update (:input state) msg)]
        [(assoc state :input input) cmd])

      :else [state nil])))
