(ns eca-cli.extras-test
  "Variant picker, /rename, chat/statusChanged, metadata titles, images,
  the approval clock, the event loop's message handling."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-cli.chat :as chat]
            [eca-cli.program]
            [eca-cli.protocol :as protocol]
            [eca-cli.state :as state]
            [eca-cli.test-support :as support]
            [eca-cli.theme :as theme]
            [eca-cli.view :as view]
            [eca-cli.view.blocks :as blocks]))

(def ^:private sent (atom []))

(defn- capture [t]
  (reset! sent [])
  (with-redefs [protocol/chat-prompt! (fn [_ params _] (swap! sent conj [:prompt params]))
                protocol/update-chat! (fn [_ chat-id fields] (swap! sent conj [:update chat-id fields]))]
    (t)))

(use-fixtures :each support/isolated-chats-file capture)

(defn- base [& {:as extra}]
  (merge {:mode :ready :chat-id "c1" :items [] :current-text "" :tool-calls {} :questions []
          :approval-queue [] :input (ti/focus (ti/text-input)) :input-history [] :width 80 :height 24
          :chat-lines [] :scroll-offset 0 :opts {:workspace "/tmp/ws"} :mcps {} :init-tasks {}
          :server-commands [] :available-variants ["medium" "high" "low"]}
         extra))

(defn- press [s & keys]
  (reduce (fn [s k] (first (state/update-state s (msg/key-press k)))) s keys))

(defn- run [s text]
  (first (state/update-state (update s :input ti/set-value text) (msg/key-press :enter))))

(deftest variant-test
  (testing "/variant lists none (-) and the model's variants"
    (let [s (run (base) "/variant")]
      (is (= :variant (get-in s [:picker :kind])))
      (is (= ["-" "high" "low" "medium"] (get-in s [:picker :all])))
      (testing "the picked one is sent with each prompt"
        (let [s2 (press s :down :enter)]
          (is (= "high" (:selected-variant s2)))
          (run s2 "hi")
          (is (= "high" (:variant (second (peek @sent)))))))
      (testing "- means none"
        (reset! sent [])
        (let [s2 (press (assoc s :selected-variant "high") :enter)]
          (is (nil? (:selected-variant s2)))
          (run s2 "hi")
          (is (not (contains? (second (peek @sent)) :variant)))))))
  (testing "a model without variants says so"
    (let [s (run (base :available-variants []) "/variant")]
      (is (= :ready (:mode s)))
      (is (= "This model has no variants." (:text (peek (:items s)))))))
  (testing "the server's suggested variant is what's sent"
    (let [[s _] (chat/handle-config-updated (base) {:params {:chat {:selectVariant "low"}}})]
      (is (= "low" (get-in s [:opts :variant]))))))

(deftest rename-test
  (testing "/rename asks the server, whose metadata content updates the title"
    (let [s (run (base) "/rename API notes")]
      (is (= [[:update "c1" {:title "API notes"}]] @sent))
      (let [s2 (first (state/update-state s {:type :eca-tick
                                             :msgs [{:method "chat/contentReceived"
                                                     :params {:chatId "c1" :role "system"
                                                              :content {:type "metadata" :title "API notes"}}}]}))]
        (is (= "API notes" (:chat-title s2))))))
  (testing "nothing to rename before the first message"
    (reset! sent [])
    (run (base :chat-id nil) "/rename x")
    (is (= [] @sent))))

(deftest status-changed-test
  (let [status (fn [s chat-id st] (chat/handle-status-changed s {:chatId chat-id :status st}))]
    (testing "running: a turn the server runs shows as running"
      (let [s (status (base) "c1" "running")]
        (is (= :chatting (:mode s)))
        (is (number? (:turn-started-at s)))))
    (testing "stopping, until idle"
      (let [s (status (base) "c1" "stopping")]
        (is (:stopping? s))
        (is (str/includes? (theme/strip-ansi (view/view s)) "◌ Stopping…"))
        (is (not (:stopping? (status s "c1" "idle"))))))
    (testing "another chat's status is not this one's"
      (is (= :ready (:mode (status (base) "other" "running")))))))

(deftest image-test
  (let [png  (.encodeToString (java.util.Base64/getEncoder) (byte-array (range 0 120)))
        item (chat/save-image {:mediaType "image/png" :base64 png})]
    (testing "saved to a file named after its bytes"
      (is (= :image (:type item)))
      (is (= 120 (:bytes item)))
      (is (str/ends-with? (:path item) ".png"))
      (is (= 120 (.length (java.io.File. (:path item))))))
    (testing "rendered with kind, size and path"
      (let [lines (map theme/strip-ansi (blocks/render-item-lines item 120))]
        (is (= "▣ image  png · 120 B" (first lines)))
        (is (str/includes? (second lines) (:path item)))))
    (testing "bad data: says so instead of failing"
      (is (:error (chat/save-image {:mediaType "image/png" :base64 "%%%"}))))))

(deftest approval-clock-test
  (is (= "▲ Waiting for your approval · 1m 5s"
         (theme/strip-ansi (view/approval-gutter {:pending-approval {:tool-call-id "t"} :turn-started-at 0}
                                                 65000)))))

(deftest bracketed-paste-test
  (let [paste (fn [s text] (reduce (fn [s ch] (first (state/update-state s (msg/key-press (if (= ch \newline) :enter (str ch))))))
                                   (first (state/update-state s (msg/key-press :paste-start)))
                                   text))
        end   #(first (state/update-state % (msg/key-press :paste-end)))]
    (testing "a pasted multi-line text stays in the input: its newlines aren't Enter"
      (let [s (-> (base) (paste "first line\nsecond line") end)]
        (is (= "first line\nsecond line" (ti/value (:input s))))
        (is (= [] @sent) "nothing sent")
        (is (= :ready (:mode s)))
        (testing "then Enter sends it all"
          (run s "")
          (let [s2 (first (state/update-state s (msg/key-press :enter)))]
            (is (= "first line\nsecond line" (:message (second (peek @sent)))))
            (is (= :chatting (:mode s2)))))))
    (testing "a pasted leading / or @ is text, not a picker"
      (let [s (-> (base) (paste "/home/me/x and @y") end)]
        (is (= "/home/me/x and @y" (ti/value (:input s))))
        (is (nil? (:picker s)))))
    (testing "a paste whose end marker got lost stops counting after a while"
      (reset! sent [])
      (let [s (-> (base) (assoc :pasting-since 0) (update :input ti/set-value "hi"))]
        (state/update-state s (msg/key-press :enter))
        (is (= "hi" (:message (second (peek @sent)))))))))

(deftest input-wrapping-test
  (let [shown #(mapv str/trimr (str/split-lines (theme/strip-ansi (view/view (base :input %)))))
        input #(-> (ti/text-input) ti/focus (ti/set-value %))]
    (testing "newlines show as rows, continuation rows indented"
      (let [lines (shown (input "first\nsecond"))]
        (is (some #{"> first"} lines))
        (is (some #{"  second"} lines))))
    (testing "a long line wraps at the width instead of running off the screen"
      (let [lines (shown (input (apply str (repeat 200 "x"))))]
        (is (every? #(<= (theme/width %) 80) lines))
        (is (some #(str/starts-with? % "> xxx") lines))
        (is (some #(str/starts-with? % "  xxx") lines))))
    (testing "a long text shows the rows around the cursor, and the screen still fits"
      (let [text  (input (str/join "\n" (map #(str "row " %) (range 30))))
            lines (shown text)]
        (is (some #{"  row 29"} lines))
        (is (not-any? #{"> row 0"} lines))
        (is (< (count lines) 24) "inline: only what it needs")
        (is (= 24 (count (str/split-lines (view/view (base :input text :opts {:workspace "/tmp/ws" :fullscreen true})))))
            "full screen: exactly the screen")))))

(deftest event-loop-handle-test
  (let [handle! @#'eca-cli.program/handle!
        state   (atom {:n 0})
        update  (fn [s _] [(update s :n inc) nil])]
    (testing "a message updates the state; quitting stops"
      (is (true? (handle! (msg/key-press "a") state nil nil update)))
      (is (= 1 (:n @state)))
      (is (false? (handle! (msg/quit) state nil nil update))))))
