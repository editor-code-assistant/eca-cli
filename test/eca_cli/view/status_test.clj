(ns eca-cli.view.status-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.theme :as theme]
            [eca-cli.view.status :as status]))

(deftest friendly-number-test
  (is (= "999" (status/friendly-number 999)))
  (is (= "52.9K" (status/friendly-number 52900)))
  (is (= "200K" (status/friendly-number 200000)))
  (is (= "1M" (status/friendly-number 1000000)))
  (is (= "1.5M" (status/friendly-number 1500000)))
  (is (nil? (status/friendly-number nil))))

(deftest allocate-test
  (testing "proportional, summing to the cells"
    (is (= [2 4 4] (status/allocate [200 400 400] 10))))
  (testing "every category with tokens gets a cell when there is room"
    (is (= [1 9] (status/allocate [1 1000] 10))))
  (testing "fewer cells than categories: the largest win"
    (is (= [0 1 1] (status/allocate [1 5 9] 2))))
  (testing "nothing to draw"
    (is (= [0 0] (status/allocate [0 0] 10)))
    (is (= [0 0] (status/allocate [5 5] 0)))))

(def ^:private usage
  {:sessionTokens 52900 :limit {:context 200000}
   :autoCompactPercentage 85
   :contextBreakdown {:categories [{:name "System prompt" :tokens 25000 :color "#61afef"}
                                   {:name "Conversation" :tokens 25000 :color "#98c379"}]
                      :usedTokens 50000 :contextLimit 200000 :freeColor "#5c6370"}})

(deftest context-bar-test
  (testing "used cells in category colours, free cells shaded, compaction marker"
    (let [bar (status/context-bar usage 12)]
      (is (= "███░░░░░░░│░" (theme/strip-ansi bar)))
      (is (str/includes? bar (theme/sgr {:fg "#61afef"})))
      (is (str/includes? bar (theme/sgr {:fg "#98c379"})))
      (is (str/includes? bar (str (theme/sgr {:fg "#5c6370"}) "░")))))
  (testing "a partial cell shows the remainder"
    (is (= "█▌░░░░░░░░" (theme/strip-ansi
                         (status/context-bar (-> usage (dissoc :autoCompactPercentage)
                                                 (assoc-in [:contextBreakdown :usedTokens] 30000))
                                             10)))))
  (testing "no breakdown, no bar"
    (is (nil? (status/context-bar {:sessionTokens 10} 10)))))

(deftest context-pct-test
  (is (= 25.0 (status/context-pct usage)))
  (is (nil? (status/context-pct {:sessionTokens 10 :limit {:context 100}}))))

(deftest quota-text-test
  (let [quota {:windows [{:id "5h" :label "5h" :usedPercent 42 :resetsAt 2000}
                         {:id "7d" :label "7d" :usedPercent 91.4 :resetsAt 9000}]}]
    (testing "windows with their usage, critical ones in the error colour"
      (let [text (status/quota-text quota 1000)]
        (is (= "5h 42% · 7d 91%" (theme/strip-ansi text)))
        (is (str/includes? text (str (theme/sgr :error) "7d 91%")))))
    (testing "a window past its reset shows a dash"
      (is (= "5h — · 7d 91%" (theme/strip-ansi (status/quota-text quota 5000)))))))

(defn- state [width & {:as kvs}]
  (merge {:width width :opts {:workspace "/home/u/proj"} :trust false
          :selected-model "anthropic/claude-sonnet-4-6" :selected-agent "code"
          :selected-variant "high" :chat-title "Fix things" :usage usage
          :mcps {"a" {:status "running"}} :init-tasks {} :jobs {}}
         kvs))

(deftest render-test
  (testing "always exactly as wide as the terminal"
    (doseq [w [200 160 120 100 80 60 40 20 8]]
      (is (= w (theme/width (status/render (state w) 0))) (str "width " w))))
  (testing "wide: everything, right side flush right"
    (let [line (theme/strip-ansi (status/render (state 160) 0))]
      (is (str/starts-with? line " code   anthropic/claude-sonnet-4-6  high  proj  Fix things"))
      (is (str/ends-with? line "25%  52.9K/200K  SAFE"))))
  (testing "narrow: the provider prefix goes before the model does"
    (let [line (theme/strip-ansi (status/render (state 40) 0))]
      (is (str/includes? line "claude-sonnet-4-6"))
      (is (not (str/includes? line "anthropic/")))
      (is (str/ends-with? line "SAFE"))))
  (testing "trust shows in bold red, and the line never ends with a space"
    (let [line (status/render (state 100 :trust true) 0)]
      (is (str/includes? line (str (theme/sgr (theme/merge-styles :error :bold)) "● TRUST")))
      (is (not (str/ends-with? (theme/strip-ansi line) " ")))))
  (testing "init progress while the server is still loading"
    (is (str/includes? (theme/strip-ansi (status/render (state 160 :init-tasks {"m" {:title "Loading models" :done? false}
                                                                                  "t" {:title "Tools" :done? true}})
                                                        0))
                       "⏳ 1/2 Loading models")))
  (testing "last turn time and speed"
    (is (str/includes? (theme/strip-ansi (status/render (state 200 :last-turn-ms 14200
                                                               :usage (assoc usage :messageOutputTps 48))
                                                        0))
                       "14s · 48 tok/s"))))

(deftest fit-test
  (let [s (fn [id prio w] {:id id :prio prio :w w :text (apply str (repeat w "x"))})]
    (testing "drops the highest :prio first"
      (is (= [[:a] [:c]]
             (mapv #(mapv :id %) (status/fit [[(s :a 1 10) (s :b 9 10)] [(s :c 2 10)]] 25)))))
    (testing "keeps the last segment even if too wide"
      (is (= [[:a] []] (mapv #(mapv :id %) (status/fit [[(s :a 1 50)] []] 10)))))))
