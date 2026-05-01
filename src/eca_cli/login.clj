(ns eca-cli.login
  "Provider login flow: cmd builders, notification handler, runtime + key dispatch.
  Owns the :login mode and the :eca-login-action / :eca-login-complete runtime
  events. No back-references to eca-cli.state."
  (:require [clojure.string :as str]
            [charm.program :as program]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.protocol :as protocol]
            [eca-cli.chat :as chat]
            [eca-cli.view :as view]))

;; --- charm/program cmd builders ---

(defn start-login-cmd [srv pending-message]
  (program/cmd
    (fn []
      (let [providers-result (promise)
            _                (protocol/providers-list! srv
                               (fn [r] (deliver providers-result (or (:result r) {}))))]
        (let [providers (-> (deref providers-result 10000 {:providers []}) :providers)
              provider  (first (filter #(contains? #{"unauthenticated" "expired"}
                                                   (get-in % [:auth :status]))
                                       providers))]
          (if-not provider
            {:type :eca-error :error "Login required but no unauthenticated provider found"}
            (let [login-result (promise)
                  _            (protocol/providers-login! srv (:id provider) nil
                                 (fn [r] (deliver login-result (or (:result r) (:error r)))))]
              {:type            :eca-login-action
               :provider        (:id provider)
               :action          (deref login-result 10000 nil)
               :pending-message pending-message})))))))

(defn choose-login-method-cmd [srv provider method]
  (program/cmd
    (fn []
      (let [result (promise)
            _      (protocol/providers-login! srv provider method
                     (fn [r] (deliver result (or (:result r) (:error r)))))]
        {:type     :eca-login-action
         :provider provider
         :action   (deref result 10000 nil)}))))

(defn submit-login-cmd [srv provider collected pending-message]
  (program/cmd
    (fn []
      (let [result (promise)
            _      (protocol/providers-login-input! srv provider collected
                     (fn [r] (deliver result (or (:result r) (:error r)))))]
        (let [r (deref result 10000 ::timeout)]
          (cond
            (= r ::timeout)        {:type :eca-error :error "Login timed out"}
            (= "done" (:action r)) {:type :eca-login-complete :pending-message pending-message}
            :else                  {:type :eca-error :error (str "Login failed: " r)}))))))

;; --- ECA notification handler (providers/updated) ---

(defn handle-providers-updated [state provider-status]
  (let [auth-status (get-in provider-status [:auth :status])
        provider-id (:id provider-status)]
    (if (and (= :login (:mode state))
             (contains? #{"authenticated" "expiring"} auth-status)
             (= provider-id (get-in state [:login :provider])))
      (let [pending   (:pending-message state)
            srv       (:server state)
            opts      (:opts state)
            new-state (-> state
                          (assoc :mode :chatting)
                          (dissoc :login)
                          (update :input ti/blur))]
        [new-state (when pending
                     (program/cmd (fn []
                                    (chat/send-chat-prompt! srv nil pending opts)
                                    nil)))])
      [state nil])))

;; --- Runtime msg handlers (called from state/update-state) ---

(defn handle-eca-login-action [state msg]
  (let [{:keys [provider action]} msg
        pending (or (:pending-message msg) (get-in state [:login :pending-message]))]
    (cond
      (nil? action)
      [(-> state
           (assoc :mode :ready)
           (update :input ti/focus)
           (update :items conj {:type :system :text "Login failed: timed out"})
           view/rebuild-lines)
       nil]

      (= "done" (:action action))
      (do
        (when pending
          (chat/send-chat-prompt! (:server state) nil pending (:opts state)))
        [(-> state (assoc :mode :chatting) (dissoc :login) (update :input ti/blur)) nil])

      :else
      (let [needs-input? (or (= "input" (:action action))
                             (and (= "authorize" (:action action))
                                  (seq (:fields action))))
            login-state  {:provider        provider
                          :action          action
                          :field-idx       0
                          :collected       {}
                          :pending-message pending}]
        [(-> state
             (assoc :mode :login :login login-state)
             (update :input #(if needs-input? (ti/focus %) (ti/blur %))))
         nil]))))

(defn handle-eca-login-complete [state msg]
  (let [pending (:pending-message msg)]
    (when pending
      (chat/send-chat-prompt! (:server state) nil pending (:opts state)))
    [(-> state (assoc :mode :chatting) (dissoc :login) (update :input ti/blur)) nil]))

;; --- :login mode key dispatch ---

(defn- choose-method-key? [state msg]
  (and (msg/key-press? msg)
       (= "choose-method" (get-in state [:login :action :action]))
       (re-matches #"[1-9]" (str (:key msg)))))

(defn- submit-input-key? [state msg]
  (and (msg/key-press? msg)
       (msg/key-match? msg :enter)
       (let [action-type (get-in state [:login :action :action])]
         (or (= "input" action-type)
             (and (= "authorize" action-type)
                  (seq (get-in state [:login :action :fields])))))))

(defn handle-key
  "Dispatch keypresses while :mode is :login. Caller guarantees :mode = :login."
  [state msg]
  (cond
    (choose-method-key? state msg)
    (let [idx     (dec (parse-long (str (:key msg))))
          methods (get-in state [:login :action :methods])
          method  (nth methods idx nil)]
      (if method
        [state (choose-login-method-cmd (:server state)
                                        (get-in state [:login :provider])
                                        (:key method))]
        [state nil]))

    (submit-input-key? state msg)
    (let [login     (:login state)
          fields    (get-in login [:action :fields])
          field     (nth fields (:field-idx login) nil)
          value     (str/trim (ti/value (:input state)))
          collected (assoc (:collected login) (:key field) value)
          next-idx  (inc (:field-idx login))]
      (if (< next-idx (count fields))
        [(-> state
             (update :login assoc :field-idx next-idx :collected collected)
             (update :input #(-> % ti/reset ti/focus)))
         nil]
        [(-> state (update :input #(-> % ti/reset ti/blur)))
         (submit-login-cmd (:server state)
                           (:provider login)
                           collected
                           (:pending-message login))]))

    (and (msg/key-press? msg) (msg/key-match? msg :escape))
    [(-> state
         (assoc :mode :ready)
         (dissoc :login :pending-message)
         (update :input ti/focus))
     nil]

    ;; Default: forward to text-input so users can type into login fields
    :else
    (let [[new-input cmd] (ti/text-input-update (:input state) msg)]
      [(assoc state :input new-input) cmd])))
