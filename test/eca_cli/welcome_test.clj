(ns eca-cli.welcome-test
  "The welcome screen: its rendering at different widths, and the item kept
  up to date as the server's version, model and recent chats arrive."
  (:require [charm.components.text-input :as ti]
            [charm.message :as msg]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eca-cli.test-support :as support]
            [eca-cli.chats :as chats]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.theme :as theme]
            [eca-cli.view.welcome :as view]
            [eca-cli.welcome :as welcome]))

(use-fixtures :each support/isolated-chats-file)

(def ^:private returning
  {:type :welcome :cli-version "0.1.99" :server-version "0.130.1"
   :model "anthropic/claude-sonnet-4-5" :variant "high" :agent "code"
   :workspace "~/dev/my-project" :agents-md? true
   :recent [{:id "c1" :title "Fix the flaky login test" :ago "2h ago"}
            {:id "c2" :title "Add a dark theme" :ago "1d ago"}]})

(def ^:private newcomer
  (assoc returning :recent [] :agents-md? false :server-version nil :variant nil))

(def ^:private logo-top
  "The logo's top row, the top cube's peak."
  "⢀⣠⣴⠟⠻⣦⣄⡀")

(defn- plain [lines] (mapv theme/strip-ansi lines))

(defn- text [lines] (str/join "\n" (plain lines)))

;; --- Rendering ---

(deftest logo-test
  (let [logo (view/logo-lines)]
    (is (= 8 (count logo)))
    (is (every? #(= view/logo-width (theme/width %)) logo))
    (testing "braille, empty cells as plain spaces"
      (is (re-find #"[\u2801-\u28FF]" (str/join logo)))
      (is (not (str/includes? (str/join logo) "\u2800"))))
    (testing "the prompt in the text's colour, the cubes in the accent"
      (is (str/includes? (nth logo 3) (str (theme/sgr :bold) "⠛⠶⣦⡄" theme/reset)))
      (is (str/includes? (nth logo 4) (str (theme/sgr :bold) "⠶⠛⠋⠁" theme/reset)))
      (is (str/includes? (str/join logo) (theme/sgr :accent))))))

(deftest wide-welcome-test
  (let [lines (view/render returning 120)]
    (testing "a box as wide as it gets (110), every line the same width"
      (is (= 10 (count lines)))
      (is (every? #(= 110 (theme/width %)) lines)))
    (testing "the versions on the top border, like Claude Code's title"
      (is (str/starts-with? (first (plain lines)) "╭── eca-cli 0.1.99 · server 0.130.1 ─")))
    (testing "logo, greeting, model, agent, workspace"
      (is (str/includes? (text lines) logo-top))
      (is (str/includes? (text lines) "Welcome back!"))
      (is (str/includes? (text lines) "anthropic/claude-sonnet-4-5 · high"))
      (is (str/includes? (text lines) "code agent"))
      (is (str/includes? (text lines) "~/dev/my-project")))
    (testing "tips, then the recent chats with their age"
      (is (str/includes? (text lines) "Tips for getting started"))
      (is (str/includes? (text lines) "/  commands, skills and prompts"))
      (is (re-find #"Fix the flaky login test +2h ago" (text lines)))
      (is (str/includes? (text lines) "/chats to resume one")))))

(deftest newcomer-welcome-test
  (let [t (text (view/render newcomer 100))]
    (is (str/includes? t "Welcome to ECA!"))
    (is (str/includes? t "╭── eca-cli 0.1.99 ─") "no server version yet: just eca-cli's")
    (is (str/includes? t "/init  create an AGENTS.md") "no AGENTS.md: /init first")
    (is (str/includes? t "#      insert a path for the agent"))
    (is (str/includes? t "No recent chats yet"))))

(deftest narrower-welcome-test
  (testing "80 columns: the tips and the last chat fold under the logo"
    (let [lines (view/render returning 80)]
      (is (every? #(= 80 (theme/width %)) lines))
      (is (str/includes? (text lines) logo-top))
      (is (str/includes? (text lines) "/ commands  @ context  # paths"))
      (is (str/includes? (text lines) "Last: Fix the flaky login test  2h ago · /chats"))))
  (testing "40 columns: no room for the logo"
    (let [lines (view/render returning 40)]
      (is (every? #(= 40 (theme/width %)) lines))
      (is (not (str/includes? (text lines) logo-top)))
      (is (str/includes? (text lines) "Welcome back!"))))
  (testing "nothing ever runs past the width"
    (doseq [w (range 24 130 7)]
      (is (every? #(<= (theme/width %) w) (view/render returning w)) (str "width " w)))))

(deftest no-color-test
  (binding [theme/*color?* false]
    (let [lines (view/render returning 120)]
      (is (not (re-find #"\u001b\[(?:[0-9;]*;)?3[0-9]" (str/join lines))) "no colours")
      (is (str/includes? (text lines) "Welcome back!")))))

;; --- The item ---

(deftest item-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "eca-cli-ws" (make-array java.nio.file.attribute.FileAttribute 0)))
        st  {:opts {:workspace (str dir)} :server-version "0.130.1"
             :selected-model "openai/gpt-5" :selected-agent "plan" :selected-variant "low"}]
    (try
      (is (= {:type :welcome :cli-version (:cli-version (welcome/item st)) :agents-md? false
              :workspace (str dir) :server-version "0.130.1"
              :model "openai/gpt-5" :agent "plan" :variant "low"}
             (welcome/item st)))
      (is (nil? (:recent (welcome/item st))) "recent chats not asked yet")
      (spit (io/file dir "AGENTS.md") "# Agents")
      (is (:agents-md? (welcome/item st)) "the /init tip goes once there's an AGENTS.md")
      (finally (run! io/delete-file (reverse (file-seq dir)))))))

(deftest ago-test
  (let [now 1000000000]
    (is (= "just now" (welcome/ago (- now 30000) now)))
    (is (= "5m ago" (welcome/ago (- now (* 5 60000)) now)))
    (is (= "2h ago" (welcome/ago (- now (* 2 3600000)) now)))
    (is (= "3d ago" (welcome/ago (- now (* 3 86400000)) now)))))

(deftest recent-chats-test
  (let [now 1000000000]
    (is (= [{:id "a" :title "Fix it" :ago "2h ago"} {:id "c" :title nil}]
           (welcome/recent-chats [{:id "a" :title "Fix it" :messageCount 4 :updatedAt (- now 7200000)}
                                  {:id "b" :title "Empty" :messageCount 0 :updatedAt now}
                                  {:id "c" :messageCount 1}]
                                 now))
        "chats with messages, latest first as the server sorts them")
    (is (= 3 (count (welcome/recent-chats (repeat 5 {:id "x" :messageCount 1}) now))))))

(deftest custom-message-test
  (is (not (welcome/custom-message? "# Welcome to ECA!\n\nType `/` to see all commands")))
  (is (not (welcome/custom-message? "  ")))
  (is (welcome/custom-message? "Hi team! Remember: tests first.")))

;; --- Kept up to date ---

(defn- base-state []
  (-> {:mode :ready :chat-id nil :items [] :current-text "" :tool-calls {}
       :approval-queue [] :questions [] :pending-contexts [] :init-tasks {}
       :input (ti/text-input) :input-history [] :chat-lines [] :scroll-offset 0
       :width 120 :height 30 :mcps {} :server nil :opts {:workspace "/tmp/test"}}
      welcome/fresh))

(defn- shown [state] (text (:chat-lines state)))

(deftest arrives-after-startup-test
  (testing "the server's version (asked of the binary)"
    (let [[s _] (state/update-state (base-state) {:type :server-version :version "0.130.1"})]
      (is (str/includes? (shown s) "eca-cli")
          "on screen")
      (is (str/includes? (shown s) "server 0.130.1"))
      (is (= s (first (state/update-state s {:type :server-version :version nil})))
          "no answer: nothing changes")))

  (testing "the recent chats"
    (let [[s _] (state/update-state (base-state) {:type :recent-chats-loaded
                                                  :chats [{:id "c1" :title "Fix the flaky login test" :ago "2h ago"}]})]
      (is (str/includes? (shown s) "Welcome back!"))
      (is (str/includes? (shown s) "Fix the flaky login test"))))

  (testing "the model, agent and variant the server picks (config/updated)"
    (let [[s _] (state/update-state (base-state)
                                    {:type :eca-tick
                                     :msgs [{:method "config/updated"
                                             :params {:chat {:selectModel "anthropic/claude-sonnet-4-5"
                                                             :selectAgent "code" :selectVariant "high"
                                                             :welcomeMessage "# Welcome to ECA!\n\nType `/`"}}}]})]
      (is (str/includes? (shown s) "anthropic/claude-sonnet-4-5 · high"))
      (is (str/includes? (shown s) "code agent"))
      (is (= [:welcome] (mapv :type (:items s))) "the server's default welcome text isn't repeated")))

  (testing "a welcome message of the user's own follows the box"
    (let [[s _] (state/update-state (base-state)
                                    {:type :eca-tick
                                     :msgs [{:method "config/updated"
                                             :params {:chat {:welcomeMessage "Hi team! Tests first."}}}]})]
      (is (= [:welcome :assistant-text] (mapv :type (:items s))))
      (is (str/includes? (shown s) "Hi team! Tests first.")))))

(deftest only-on-a-new-chat-test
  (testing "a chat the server replays (resumed, rolled back) shows its history instead"
    (let [[s _] (state/update-state (assoc (base-state) :chat-id "c1")
                                    {:type :eca-tick :msgs [{:method "chat/cleared" :params {:chatId "c1" :messages true}}]})]
      (is (not-any? #(= :welcome (:type %)) (:items s)))))

  (testing "/new opens on a fresh one, without the chat it deleted"
    (with-redefs [protocol/delete-chat! (fn [& _])
                  chats/save-chat-id!   (fn [& _])]
      (let [s0    (-> (base-state)
                      (assoc :chat-id "c1" :recent-chats [{:id "c1" :title "This one" :ago "just now"}
                                                          {:id "c2" :title "Older one" :ago "1d ago"}])
                      (update :items conj {:type :user :text "hello"})
                      (assoc :input (ti/set-value (ti/text-input) "/new")))
            [s _] (state/update-state s0 (msg/key-press :enter))]
        (is (= [:welcome] (mapv :type (:items s))))
        (is (= ["c2"] (mapv :id (:recent-chats s))))
        (is (str/includes? (shown s) "Older one"))
        (is (not (str/includes? (shown s) "This one"))))))

  (testing "it isn't a block to focus"
    (let [[s _] (state/update-state (base-state) (msg/key-press :tab))]
      (is (nil? (:focus-path s))))))
