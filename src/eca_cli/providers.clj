(ns eca-cli.providers
  "/providers, like eca-emacs's providers panel: the providers the server
  knows, how each is logged in, its models and subscription quota. Enter
  offers to log in to one (the usual login flow) or out of it
  (`providers/logout`)."
  (:require [clojure.string :as str]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.program :as program]
            [eca-cli.login :as login]
            [eca-cli.protocol :as protocol]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]
            [eca-cli.view.status :as status]))

(def picker-kinds #{:providers :provider-action})

(def ^:private with-credentials
  "Statuses of a provider that has credentials (eca-emacs's `is-authed`)."
  #{"authenticated" "expiring" "expired" "local"})

(defn list-cmd [srv]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/providers-list! srv (fn [r] (deliver p (get-in r [:result :providers]))))
        {:type :providers-loaded :providers (deref p 10000 nil)}))))

(defn cmd-open-providers [state]
  [state (list-cmd (:server state))])

(defn- note [state text]
  (-> state
      (update :items conj {:type :system :level :info :text text})
      view/rebuild-lines))

;; --- The panel's rows ---

(defn- status-text
  "As eca-emacs labels them: `✓ configured` is a key from the config or the
  environment, `✓ logged in` a login."
  [{:keys [status source]}]
  (case status
    ("authenticated" "expiring") (if (#{"config" "env"} source) "✓ configured" "✓ logged in")
    "expired"         "✗ login expired"
    "local"           "local"
    "not-running"     "not running"
    "unauthenticated" "✗ not logged in"
    (str status)))

(defn- how-text
  "`OAuth (login)`, `API key (env: OPENAI_API_KEY)`, `Manual (login)`…"
  [{:keys [status type mode source envVar]}]
  (when (#{"authenticated" "expiring" "expired"} status)
    (str/join " " (remove nil? [(cond mode             (str/capitalize mode)
                                      (= "oauth" type) "OAuth"
                                      type             "API key")
                                (case source
                                  "env"              (str "(env" (when envVar (str ": " envVar)) ")")
                                  ("config" "login") (str "(" source ")")
                                  nil)]))))

(defn- expiry-text [expires-at now-secs]
  (when (number? expires-at)
    (let [left (- (long expires-at) now-secs)]
      (when (pos? left)
        (cond
          (<= left 3600)  (str "expires in " (max 1 (quot left 60)) "m")
          (<= left 86400) (str "expires in " (quot left 3600) "h")
          :else           (str "expires in " (quot left 86400) "d"))))))

(defn row-label
  "`Label (id)  •  ✓ logged in · OAuth (login) · expires in 3h · 12 models · 5h 42%`."
  ([provider] (row-label provider (quot (System/currentTimeMillis) 1000)))
  ([{:keys [id label auth models quota configured]} now-secs]
   (let [n (count models)]
     (str (or label id) (when (and label (not= label id)) (str " (" id ")"))
          "  •  "
          (str/join " · " (remove str/blank?
                                  [(status-text auth)
                                   (how-text auth)
                                   (expiry-text (:expiresAt auth) now-secs)
                                   (when (or configured (pos? n))
                                     (str n " model" (when (not= 1 n) "s")))
                                   ;; Picker labels are plain: the picker paints them.
                                   (some-> quota (status/quota-text now-secs) theme/strip-ansi)]))))))

(defn- sort-key
  "eca-emacs's order: logged in, local, configured, the rest."
  [{:keys [id auth configured]}]
  [(cond
     (#{"authenticated" "expiring" "expired"} (:status auth)) 0
     (= "local" (:status auth))                               1
     configured                                               2
     :else                                                    3)
   (str id)])

(defn- open [state kind items & {:as extra}]
  (-> state
      (assoc :mode :picking
             :picker (merge {:kind kind :query "" :all items :filtered items
                             :list (cl/item-list (mapv first items) :height 8)}
                            extra))
      (update :input ti/reset)))

(defn- close [state]
  (-> state (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)))

(defn handle-loaded
  "`providers/list` answered: the panel (or why there isn't one)."
  [state providers]
  (cond
    (nil? providers)   (note state "⚠ Could not list the providers.")
    (empty? providers) (note state "No providers configured.")
    :else (open state :providers (mapv (fn [p] [(row-label p) p]) (sort-by sort-key providers)))))

;; --- Actions ---

(defn- oauth-login?
  "A login other than typing an API key (eca-emacs: Login vs Add Key)."
  [methods]
  (some #(not (#{"manual" "api-key"} (:key %))) methods))

(defn actions-for
  "What Enter offers for a provider, as eca-emacs's panel: log in (or add a
  key) when it has a login and no credentials, log in again when the login
  expired or is about to, log out of a login. A key from the config or the
  environment isn't something to log out of: it would still be there."
  [{:keys [id auth login]}]
  (let [{:keys [status source]} auth
        methods                 (seq (:methods login))
        creds?                  (contains? with-credentials status)]
    (cond-> []
      (and methods (not creds?))
      (conj [(if (oauth-login? methods) "Log in" "Add API key") [:login id]])

      (and methods (#{"expiring" "expired"} status) (= "login" source))
      (conj ["Log in again" [:login id]])

      (and creds? (= "login" source))
      (conj ["Log out" [:logout id]]))))

(defn- no-actions-note [{:keys [id auth]}]
  (let [{:keys [status source envVar]} auth]
    (cond
      (= "config" source)               (str id " uses the API key in the ECA config.")
      (= "env" source)                  (str id " uses an API key from the environment"
                                             (when envVar (str " (" envVar ")")) ".")
      (#{"local" "not-running"} status) (str id " runs locally: there's no login.")
      :else                             (str id " has no login: set its key in the ECA config."))))

(defn- logout-cmd [srv provider]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/providers-logout! srv provider (fn [r] (deliver p r)))
        (let [r (deref p 10000 nil)]
          {:type     :provider-logged-out
           :provider provider
           :error    (cond
                       (nil? r)    "timed out"
                       (:error r)  (or (get-in r [:error :message]) (pr-str (:error r))))})))))

(defn handle-logged-out
  "`providers/logout` answered."
  [state {:keys [provider error]}]
  (note state (if error
                (str "⚠ Could not log out of " provider ": " error)
                (str "Logged out of " provider "."))))

(defn handle-select
  "Enter on a provider (its actions) or on an action."
  [state]
  (let [{:keys [kind list filtered]} (:picker state)
        idx       (cl/selected-index list)
        [_ value] (when (and (some? idx) (< idx (count filtered))) (nth filtered idx))]
    (cond
      (nil? value) [state nil]

      (= :providers kind)
      (let [actions (actions-for value)]
        (if (seq actions)
          [(open state :provider-action actions :provider (or (:label value) (:id value))) nil]
          [(-> state close (note (no-actions-note value))) nil]))

      :else
      (let [[action provider] value]
        [(close state)
         (case action
           :login  (login/login-provider-cmd (:server state) provider)
           :logout (logout-cmd (:server state) provider))]))))
