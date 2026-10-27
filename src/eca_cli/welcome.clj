(ns eca-cli.welcome
  "The welcome screen opening a new chat (drawn by `eca-cli.view.welcome`):
  a `:welcome` item at the top of the chat, built from what the state knows
  and refreshed as the rest arrives after startup: the server's version, the
  model the server picks, the recent chats."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [charm.program :as program]
            [eca-cli.paths :as paths]
            [eca-cli.protocol :as protocol]
            [eca-cli.upgrade :as upgrade]
            [eca-cli.version :as version]
            [eca-cli.view :as view]))

(defn item
  "The welcome item for `state`, from what's known so far."
  [state]
  (let [workspace (get-in state [:opts :workspace])]
    (cond-> {:type        :welcome
             :cli-version version/version
             :agents-md?  (boolean (and workspace (.exists (io/file workspace "AGENTS.md"))))}
      workspace                      (assoc :workspace (paths/home-relative workspace))
      (:server-version state)        (assoc :server-version (:server-version state))
      (:selected-model state)        (assoc :model (:selected-model state))
      (:selected-variant state)      (assoc :variant (:selected-variant state))
      (:selected-agent state)        (assoc :agent (:selected-agent state))
      (some? (:recent-chats state))  (assoc :recent (:recent-chats state)))))

(defn fresh
  "A new chat's screen: the welcome alone."
  [state]
  (-> state (assoc :items [(item state)]) view/rebuild-lines))

(defn refresh
  "Bring the welcome at the top of the chat up to date, when it's there."
  [state]
  (if (= :welcome (:type (first (:items state))))
    (-> state (assoc-in [:items 0] (item state)) view/rebuild-lines)
    state))

;; --- What arrives after startup ---

(defn ago
  "How long ago `then` was (epoch ms), roughly: `5m ago`, `2h ago`, `3d ago`."
  [then now]
  (let [s (quot (max 0 (- now then)) 1000)]
    (cond
      (< s 60)             "just now"
      (< s 3600)           (str (quot s 60) "m ago")
      (< s 86400)          (str (quot s 3600) "h ago")
      (< s (* 86400 30))   (str (quot s 86400) "d ago")
      (< s (* 86400 365))  (str (quot s (* 86400 30)) "mo ago")
      :else                (str (quot s (* 86400 365)) "y ago"))))

(defn recent-chats
  "The chats worth resuming in a `chat/list` answer (latest first): the ones
  with messages, with their age."
  [chats now]
  (->> chats
       (filter #(pos? (or (:messageCount %) 0)))
       (take 3)
       (mapv (fn [{:keys [id title updatedAt createdAt]}]
               (cond-> {:id id :title title}
                 (or updatedAt createdAt) (assoc :ago (ago (or updatedAt createdAt) now)))))))

(defn recent-chats-cmd [srv]
  (program/cmd
    (fn []
      (let [p (promise)]
        (protocol/list-chats! srv (fn [r] (deliver p (get-in r [:result :chats]))))
        {:type  :recent-chats-loaded
         :chats (some-> (deref p 10000 nil) (recent-chats (System/currentTimeMillis)))}))))

(defn handle-recent-chats
  "`chats` nil: the server didn't answer, there's no recent section."
  [state chats]
  (if (nil? chats) state (refresh (assoc state :recent-chats chats))))

(defn server-version-cmd
  "The ECA binary's version from `eca --version`, as eca-emacs asks it, when
  the managed install's marker doesn't tell."
  [binary]
  (program/cmd
    (fn []
      {:type    :server-version
       :version (try (upgrade/binary-version binary) (catch Exception _ nil))})))

(defn handle-server-version [state version]
  (if version (refresh (assoc state :server-version version)) state))

(defn custom-message?
  "Whether the server's welcome message is the user's own: its default
  (`# Welcome to ECA!`, tips written for editors) is what the welcome box
  replaces."
  [text]
  (and (not (str/blank? text))
       (not (str/starts-with? (str/trim text) "# Welcome to ECA!"))))
