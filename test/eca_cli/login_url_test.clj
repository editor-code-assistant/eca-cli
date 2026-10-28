(ns eca-cli.login-url-test
  "A provider login's URL can be used: shown whole (an OAuth URL runs to 400
  characters, wider than any terminal, which would cut it), sent to the
  clipboard, opened in the browser when there's one; a device code copied."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [eca-cli.browser :as browser]
            [eca-cli.clipboard :as clipboard]
            [eca-cli.paths :as paths]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]))

(use-fixtures :each support/isolated-chats-file)

(def ^:private oauth-url
  "Anthropic's Claude Pro/Max authorize URL, as the server builds it (PKCE
  challenge and state made up, at their real lengths)."
  (str "https://claude.ai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e"
       "&response_type=code&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback"
       "&scope=org%3Acreate_api_key+user%3Aprofile+user%3Ainference"
       "&code_challenge=" (apply str (repeat 43 "C")) "&code_challenge_method=S256"
       "&state=" (apply str (repeat 72 "S"))))

(def ^:private authorize
  {:action "authorize" :url oauth-url
   :message "Complete authentication in your browser, then paste the authorization code"
   :fields [{:key "code" :label "Authorization code" :type "text"}]})

(defn- base [width]
  {:mode :ready :width width :height 40 :items [] :chat-lines [] :scroll-offset 0 :current-text ""
   :input (ti/focus (ti/text-input)) :opts {:workspace "/tmp/ws"} :init-tasks {} :mcps {} :trust false})

(defn- login-action
  "What the server's next login step does to `s`: [state cmd]."
  [s action]
  (state/update-state s {:type :eca-login-action :provider "anthropic" :action action}))

(defn- screen-lines [s] (map theme/strip-ansi (str/split-lines (view/view s))))

(defn- run [cmd] (when (and cmd (= :cmd (:type cmd)) (:fn cmd)) ((:fn cmd))))

(deftest the-whole-url-is-shown-test
  (doseq [width [60 80 120 200]]
    (testing (str width " columns")
      (with-redefs [browser/command (constantly nil)]
        (let [[s _]  (login-action (base width) authorize)
              lines  (screen-lines s)]
          (is (= :login (:mode s)))
          (is (every? #(<= (theme/width %) width) lines)
              "no line is wider than the terminal, which would cut it")
          (is (str/includes? (str/join lines) oauth-url)
              "the URL's rows, joined, are the whole URL")
          (is (some #(= "Enter Authorization code after authorizing:" %) lines)))))))

(deftest copied-and-opened-test
  (testing "authorize: the URL goes to the clipboard and to the browser"
    (let [opened (atom []) copied (atom [])]
      (with-redefs [browser/command             (fn [url] ["xdg-open" url])
                    browser/open!               (fn [url] (swap! opened conj url) true)
                    clipboard/copy-with-tool!   (fn [text] (swap! copied conj text) nil)]
        (let [[s cmd] (login-action (base 100) authorize)]
          (is (= (clipboard/osc52 oauth-url) (get-in s [:clipboard :osc])) "copied through the terminal (OSC 52)")
          (run cmd)
          (is (= [oauth-url] @opened))
          (is (= [oauth-url] @copied) "and with the platform's clipboard tool")
          (is (some #(str/includes? % "Opening your browser. The URL, also sent to your clipboard:")
                    (screen-lines s)))))))
  (testing "no browser to open (SSH, a console): it says to open the URL"
    (with-redefs [browser/command (constantly nil)]
      (let [[s _] (login-action (base 100) authorize)]
        (is (some #(str/includes? % "Open this URL in a browser, also sent to your clipboard:")
                  (screen-lines s))))))
  (testing "device code: the code is copied, the URL opened, as eca-emacs does"
    (let [opened (atom []) copied (atom [])]
      (with-redefs [browser/command           (fn [url] ["xdg-open" url])
                    browser/open!             (fn [url] (swap! opened conj url) true)
                    clipboard/copy-with-tool! (fn [text] (swap! copied conj text) nil)]
        (let [[s cmd] (login-action (base 80) {:action "device-code" :url "https://github.com/login/device"
                                                :code "ABCD-1234" :message "Enter the code at GitHub"})]
          (run cmd)
          (is (= ["https://github.com/login/device"] @opened))
          (is (= ["ABCD-1234"] @copied))
          (is (= (clipboard/osc52 "ABCD-1234") (get-in s [:clipboard :osc])))
          (is (some #(= "Code: ABCD-1234  (sent to your clipboard)" %) (screen-lines s)))
          (is (some #(= "https://github.com/login/device" %) (screen-lines s))))))))

(deftest other-steps-copy-nothing-test
  (testing "choosing a method or typing a key opens and copies nothing"
    (doseq [action [{:action "choose-method" :methods [{:key "max" :label "Claude Pro/Max"}]}
                    {:action "input" :fields [{:key "api-key" :label "API key" :type "secret"}]}]]
      (let [[s cmd] (login-action (base 80) action)]
        (is (nil? cmd))
        (is (nil? (:clipboard s)))))))

(deftest browser-command-test
  (when-not (str/starts-with? (str (System/getProperty "os.name")) "Mac")
    (testing "Linux: xdg-open in a graphical session only"
      (with-redefs [fs/which (fn [c] (str "/usr/bin/" c))]
        (with-redefs [paths/getenv {"DISPLAY" ":0"}]
          (is (= ["xdg-open" "https://x.y"] (browser/command "https://x.y"))))
        (with-redefs [paths/getenv {"WAYLAND_DISPLAY" "wayland-0"}]
          (is (= ["xdg-open" "https://x.y"] (browser/command "https://x.y"))))
        (with-redefs [paths/getenv {}]
          (is (nil? (browser/command "https://x.y")) "an SSH session or a console: no text browser on our terminal"))))
    (testing "no xdg-open: nothing to open"
      (with-redefs [fs/which (constantly nil) paths/getenv {"DISPLAY" ":0"}]
        (is (nil? (browser/command "https://x.y")))
        (is (false? (browser/open! "https://x.y")))))))
