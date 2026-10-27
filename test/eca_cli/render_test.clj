(ns eca-cli.render-test
  "Planner and isolated terminal regressions: append-only history, native
  scrollback across resizes, alternate-screen geometry, and failure cleanup."
  (:require [charm.terminal :as term]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.render :as render])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.regex Pattern]
           [org.jline.terminal Size Terminal TerminalBuilder]))

(defn- history [epoch committed total]
  {:epoch epoch :committed committed :total total :removals []
   :lines (fn [from to] (mapv #(str "item" %) (range from to)))})

(defn- inline-frame [epoch committed & [total]]
  {:fullscreen? false :content "live\nprompt" :history (history epoch committed (or total committed))})

(def ^:private env {:width 80 :height 24 :now 10000 :resized-at nil})

(def ^:private live [:live ["live" "prompt"]])

(defn- shown [& {:as m}]
  (merge {:screen :inline :epoch 1 :printed 2 :removals-seen 0 :inline-width 80 :live-rows 2} m))

(deftest first-frame-test
  (testing "starts where the cursor is: nothing cleared"
    (let [{:keys [steps shown]} (render/plan {} (inline-frame 1 0) env)]
      (is (= [live] steps))
      (is (= {:screen :inline :epoch 1 :printed 0 :removals-seen 0 :inline-width 80 :live-rows 2} shown))))
  (testing "what's already final is printed first"
    (is (= [[:print ["item0" "item1"]] live] (:steps (render/plan {} (inline-frame 1 2) env))))))

(deftest append-test
  (testing "newly final items go to the history, once"
    (let [{:keys [steps shown]} (render/plan (shown) (inline-frame 1 4) env)]
      (is (= [[:print ["item2" "item3"]] live] steps))
      (is (= 4 (:printed shown)))))
  (testing "nothing new: the live region alone"
    (is (= [live] (:steps (render/plan (shown) (inline-frame 1 2) env)))))
  (testing "items removed from the state stay printed; the boundary follows"
    (let [frame (assoc-in (inline-frame 1 1) [:history :removals] [1])
          {:keys [steps shown]} (render/plan (shown) frame env)]
      (is (= [live] steps))
      (is (= 1 (:printed shown)))
      (is (= 1 (:removals-seen shown))))))

(deftest removal-and-append-test
  (testing "remove a printed flag and append in the same render batch"
    (let [frame (assoc-in (inline-frame 1 2) [:history :removals] [1])
          {:keys [steps shown]} (render/plan (shown) frame env)]
      (is (= [[:print ["item1"]] live] steps) "equal counts must not hide the appended item")
      (is (= [2 1] [(:printed shown) (:removals-seen shown)]))
      (is (= [live] (:steps (render/plan shown frame env))) "consume each removal once")))
  (testing "removing an unprinted item does not move the actual printed boundary"
    (doseq [idx [2 3]]
      (let [frame (assoc-in (inline-frame 1 3) [:history :removals] [idx])
            {:keys [steps shown]} (render/plan (shown) frame env)]
        (is (= [[:print ["item2"]] live] steps) (str "removed index " idx))
        (is (= [3 1] [(:printed shown) (:removals-seen shown)])))))
  (testing "indices are sequential, not indices into the original vector"
    (let [frame (assoc-in (inline-frame 1 3) [:history :removals] [1 1])
          {:keys [steps shown]} (render/plan (shown :printed 4) frame env)]
      (is (= [[:print ["item2"]] live] steps))
      (is (= 2 (:removals-seen shown)))))
  (testing "already consumed removals do not rebase the boundary again"
    (let [frame (assoc-in (inline-frame 1 3) [:history :removals] [0 1])]
      (is (= [[:print ["item1" "item2"]] live]
             (:steps (render/plan (shown :removals-seen 1) frame env))))))
  (testing "removals remain pending while the alternate screen is open"
    (let [frame (assoc-in (inline-frame 1 2) [:history :removals] [1])
          opened (:shown (render/plan (shown) (assoc frame :fullscreen? true) env))]
      (is (= [2 0] [(:printed opened) (:removals-seen opened)]))
      (is (= [[:leave-fullscreen] [:print ["item1"]] live]
             (:steps (render/plan opened frame env)))))))

(deftest epoch-test
  (testing "a new epoch appends a blank and a new section without clearing history"
    (let [{:keys [steps shown]} (render/plan (shown) (inline-frame 2 3) env)]
      (is (= [[:print ["" "item0" "item1" "item2"]] live] steps))
      (is (= [2 3 0] [(:epoch shown) (:printed shown) (:removals-seen shown)]))))
  (testing "even an empty new chat erases the old live region and starts a section"
    (is (= [[:print [""]] live]
           (:steps (render/plan (shown) (inline-frame 2 0) env)))))
  (testing "a new epoch does not inherit the old removal cursor"
    (let [frame (assoc-in (inline-frame 2 1) [:history :removals] [0])
          {:keys [steps shown]} (render/plan (shown :removals-seen 5) frame env)]
      (is (= [[:print ["" "item0"]] live] steps))
      (is (= [2 1 1] [(:epoch shown) (:printed shown) (:removals-seen shown)])))))

(deftest resize-debounce-test
  (doseq [[width height] [[60 24] [120 24] [80 12]]]
    (testing (str "all geometry changes debounce: " width "x" height)
      (let [before (shown)
            resized (assoc env :width width :height height :resized-at 9950)
            early (render/plan before (inline-frame 1 3) resized)
            settled (render/plan (:shown early) (inline-frame 1 3) (assoc resized :now 10100))]
        (is (= [] (:steps early)))
        (is (= before (:shown early)) "pending history is not consumed during debounce")
        (is (= 10100 (:retry-at early)))
        (is (= [[:print ["item2"]] live] (:steps settled)))
        (is (= width (:inline-width (:shown settled))))
        (is (nil? (:retry-at settled))))))
  (testing "epoch changes wait too, without consuming removals or changing epochs"
    (let [before (shown :removals-seen 2)
          early (render/plan before (inline-frame 2 1) (assoc env :resized-at 9999))]
      (is (= [] (:steps early)))
      (is (= before (:shown early)))))
  (testing "ordinary settled resizing only redraws live rows"
    (doseq [width [60 120]]
      (is (= [live] (:steps (render/plan (shown) (inline-frame 1 2)
                                        (assoc env :width width :resized-at 9800))))))))

(deftest reanchor-plan-test
  (testing "damage clears only the visible region and never reprints committed items"
    (let [damaged (assoc env :reanchor? true)]
      (is (= [[:reanchor] live] (:steps (render/plan (shown) (inline-frame 1 2) damaged))))
      (is (= [[:reanchor] [:print ["item2"]] live]
             (:steps (render/plan (shown) (inline-frame 1 3) damaged))))
      (is (= [[:leave-fullscreen] [:reanchor] live]
             (:steps (render/plan (shown :screen :fullscreen) (inline-frame 1 2) damaged))))))
  (testing "the live region never outgrows the screen"
    (let [tall {:fullscreen? false :content (str/join "\n" (map #(str "l" %) (range 40)))
                :history (history 1 2 2)}
          [[_ lines]] (:steps (render/plan (shown) tall env))]
      (is (= 24 (count lines)))
      (is (= "l39" (peek lines)) "keep the bottom of the frame"))))

(deftest fullscreen-test
  (let [fs {:fullscreen? true :content "chat\nprompt" :history (history 1 2 2)}]
    (testing "into the alternate screen"
      (let [{:keys [steps shown]} (render/plan (shown) fs env)]
        (is (= [[:enter-fullscreen] [:fullscreen ["chat" "prompt"]]] steps))
        (is (= :fullscreen (:screen shown)))
        (is (= 2 (:printed shown)) "what's printed inline is remembered")
        (testing "then frame by frame"
          (is (= [[:fullscreen ["chat" "prompt"]]] (:steps (render/plan shown fs env)))))
        (testing "back inline: the main screen is as it was; what became final meanwhile is printed"
          (is (= [[:leave-fullscreen] [:print ["item2"]] live]
                 (:steps (render/plan shown (inline-frame 1 3) env)))))))
    (testing "--fullscreen: the alternate screen from the start"
      (is (= [[:enter-fullscreen] [:fullscreen ["chat" "prompt"]]]
             (:steps (render/plan {} (assoc fs :history nil) env)))))))

(deftest final-frame-test
  (testing "inline: the rest of the chat, the reply streaming in too, and no live region"
    (is (= [[:print ["item2" "item3" "item4"]]]
           (:steps (render/plan (shown) (assoc (inline-frame 1 3 5) :final? true) env)))))
  (testing "all printed already: the live region still goes"
    (is (= [[:print []]] (:steps (render/plan (shown) (assoc (inline-frame 1 2) :final? true) env)))))
  (testing "from the transcript: back to the main screen first"
    (is (= [[:leave-fullscreen] [:print ["item2"]]]
           (:steps (render/plan (shown :screen :fullscreen)
                                (assoc (inline-frame 1 3) :final? true :fullscreen? true) env)))))
  (testing "no waiting or duplicate history on exit during resize"
    (let [frame (assoc (inline-frame 1 2) :final? true)
          resized (assoc env :width 60 :resized-at 9990)]
      (is (= [[:print []]] (:steps (render/plan (shown) frame resized))))
      (is (= [[:reanchor] [:print []]]
             (:steps (render/plan (shown) frame (assoc resized :reanchor? true)))))))
  (testing "--fullscreen: the alternate screen goes, nothing is printed"
    (is (= [[:leave-fullscreen]]
           (:steps (render/plan {:screen :fullscreen}
                                {:fullscreen? true :content "x" :history nil :final? true} env))))))

(deftest content-lines-test
  (is (= [" "] (render/content-lines "" 24)) "never nothing")
  (is (= ["c" "d"] (render/content-lines "a\nb\nc\nd" 2)))
  (is (= ["a" "b"] (render/content-lines "a\nb" 0)) "unknown height: all"))

(deftest fullscreen-resize-plan-test
  (let [frame (assoc (inline-frame 1 2) :fullscreen? true)
        opened (:shown (render/plan (shown) frame env))]
    (is (= [80 24] (:full-size opened)))
    (doseq [[width height] [[60 24] [120 24] [80 12]]]
      (let [resized (assoc env :width width :height height :resized-at 9950)
            early (render/plan opened frame resized)
            settled (render/plan opened frame (assoc resized :now 10100))]
        (is (= [] (:steps early)))
        (is (= opened (:shown early)))
        (is (= [[:reset-fullscreen] [:fullscreen ["live" "prompt"]]] (:steps settled)))
        (is (= [width height] (:full-size (:shown settled))))
        (is (= [[:fullscreen ["live" "prompt"]]]
               (:steps (render/plan (:shown settled) frame (assoc resized :now 10200)))))))
    (testing "leaving the transcript also waits for resize, unless exiting"
      (let [resized (assoc env :width 60 :resized-at 9990)]
        (is (= [] (:steps (render/plan opened (inline-frame 1 2) resized))))
        (is (= [[:leave-fullscreen] [:print []]]
               (:steps (render/plan opened (assoc frame :final? true) resized))))))))

(deftest reflow-rows-test
  (doseq [[label lines old-width width expected]
          [["no live rows" [] 80 40 0]
           ["short rows remain independent" ["a" "b" "c"] 80 10 3]
           ["exact width has no extra wrap row" ["1234567890"] 10 10 1]
           ["narrowing wraps each hard line" ["1234567890" "abcdefghij"] 10 5 4]
           ["widening retains hard boundaries" ["abcd" "efgh"] 4 20 2]
           ["intentional empty rows survive" ["a" "" ""] 80 40 3]
           ["only previously drawn columns count" ["abcdefgh"] 4 2 2]
           ["ANSI styles do not occupy columns" ["\u001b[31mabcdefgh\u001b[0m"] 8 4 2]
           ["wide characters use display columns" ["界界"] 4 2 2]]]
    (testing label
      (is (= expected (render/reflow-rows lines old-width width))))))

(deftest attributed-hard-lines-test
  (is (= ["a\n" "b\n" "\n"]
         (mapv str (#'render/attributed ["a" "b" ""] 80)))
      "the shared inline/fullscreen conversion preserves hard-line boundaries")
  (is (= ["abcd\n"] (mapv str (#'render/attributed ["abcdefgh"] 4)))
      "truncate before appending the delimiter"))

;; Only memory streams: never attach these tests to the user's terminal.
(defn- with-terminal [width height f]
  (let [out (ByteArrayOutputStream.)]
    (with-open [terminal (-> (TerminalBuilder/builder)
                             (.system false)
                             (.streams (ByteArrayInputStream. (byte-array 0)) out)
                             (.type "xterm-256color")
                             (.build))]
      (.setSize terminal (Size. width height))
      (f terminal out))))

(defn- output-text [^ByteArrayOutputStream out]
  (.toString out "UTF-8"))

(defn- occurrences [text fragment]
  (count (re-seq (re-pattern (Pattern/quote fragment)) text)))

(defn- resize-terminal! [^Terminal terminal renderer width height now]
  (.setSize terminal (Size. width height))
  (render/resize! renderer width height now))

(deftest output-preserves-history-test
  (with-terminal 80 24
    (fn [terminal out]
      (let [renderer (render/create terminal)
            frame (inline-frame 1 2)]
        (render/frame! renderer frame 0)
        (is (not (str/includes? (output-text out) "\u001b[2J")) "first draw does not clear")
        (is (not (str/includes? (output-text out) "\u001b[H")) "first draw does not home")
        (let [initial (output-text out)]
          (resize-terminal! terminal renderer 60 24 10)
          (render/frame! renderer frame 159)
          (is (= initial (output-text out)) "debounce emits no terminal bytes")
          (is (false? (render/due? renderer 159)))
          (is (true? (render/due? renderer 160)))
          (render/frame! renderer frame 160)
          (is (nil? (render/due? renderer 161))))
        (resize-terminal! terminal renderer 100 24 170)
        (render/frame! renderer frame 320)
        (is (not (str/includes? (output-text out) "\u001b[2J")) "ordinary resize does not clear")
        (let [next-chat (assoc-in (inline-frame 2 1) [:history :lines]
                                  (fn [_ _] ["new-section-marker"]))]
          (render/frame! renderer next-chat 330)
          (is (re-find #"\r+\nnew-section-marker\r+\n" (output-text out)))
          (is (not (str/includes? (output-text out) "\u001b[2J")) "new epoch does not clear")
          (render/frame! renderer (assoc next-chat :fullscreen? true) 340)
          (render/frame! renderer next-chat 350)
          ;; Finish bypasses debounce, and a repeated finish must not print
          ;; already committed history a second time.
          (resize-terminal! terminal renderer 90 24 360)
          (render/finish! renderer next-chat 361)
          (render/finish! renderer next-chat 362)
          (render/stop! renderer))
        (let [output (output-text out)]
          (doseq [marker ["item0" "item1" "new-section-marker"]]
            (is (= 1 (occurrences output marker)) (str marker " is printed once")))
          (is (not (str/includes? output "\u001b[3J"))))))))

(deftest final-output-prints-only-uncommitted-test
  (with-terminal 80 24
    (fn [terminal out]
      (let [renderer (render/create terminal)
            frame (inline-frame 1 2 3)]
        (render/frame! renderer frame 0)
        (render/frame! renderer (assoc frame :fullscreen? true) 1)
        (render/finish! renderer (assoc frame :fullscreen? true) 2)
        (render/finish! renderer frame 3)
        (doseq [marker ["item0" "item1" "item2"]]
          (is (= 1 (occurrences (output-text out) marker))))
        (is (false? (:alt? @renderer)))
        (is (= 3 (get-in @renderer [:shown :printed])))
        (is (= [] (:inline-lines @renderer)))
        (is (not (str/includes? (output-text out) "\u001b[3J")))))))

(deftest fullscreen-resize-output-test
  (with-terminal 20 5
    (fn [terminal out]
      (let [renderer (render/create terminal)
            frame {:fullscreen? true :content "a\nb\nc\nd\ne"}]
        (render/frame! renderer frame 0)
        (doseq [[width at] [[10 10] [20 200]]]
          (let [old-display (:full @renderer)]
            (resize-terminal! terminal renderer width 5 at)
            (.reset ^ByteArrayOutputStream out)
            (render/frame! renderer frame (+ at 149))
            (is (= "" (output-text out)))
            (is (identical? old-display (:full @renderer)))
            (render/frame! renderer frame (+ at 150))
            (is (not (identical? old-display (:full @renderer))))
            (is (str/includes? (output-text out) "\u001b[2J"))
            (is (str/includes? (output-text out) "\u001b[H"))
            (is (not (str/includes? (output-text out) "\n"))
                "fullscreen Display moves with cursor capabilities, not scrolling newlines")
            (is (not (str/includes? (output-text out) "\u001b[?1049h")) "reset, not re-entry")
            (is (not (str/includes? (output-text out) "\u001b[3J")))))))))

(deftest sticky-reanchor-roundtrip-test
  (doseq [hidden? [false true]]
    (testing (str "main screen hidden: " hidden?)
      (with-terminal 40 4
        (fn [terminal out]
          (let [renderer (render/create terminal)
                frame (assoc (inline-frame 1 2) :content
                             (str/join "\n" (repeat 3 (apply str (repeat 20 "x")))))
                fullscreen (assoc frame :fullscreen? true :content "transcript\nprompt")]
            (render/frame! renderer frame 0)
            (when hidden? (render/frame! renderer fullscreen 1))
            (resize-terminal! terminal renderer 10 4 100)
            (is (true? (:reanchor? @renderer)))
            (resize-terminal! terminal renderer 40 4 110)
            (is (true? (:reanchor? @renderer)) "widening cannot restore the lost origin")
            (.reset ^ByteArrayOutputStream out)
            (render/frame! renderer (if hidden? fullscreen frame) 259)
            (is (= "" (output-text out)))
            (is (true? (:reanchor? @renderer)))
            (when hidden?
              (render/frame! renderer fullscreen 260)
              (is (true? (:reanchor? @renderer)) "fullscreen drawing does not consume main-screen damage")
              (is (not (str/includes? (output-text out) "\u001b[2J"))))
            (render/frame! renderer frame 261)
            (is (false? (:reanchor? @renderer)))
            (is (= 2 (get-in @renderer [:shown :printed])))
            (is (= 1 (occurrences (output-text out) "\u001b[2J")))
            (is (not (str/includes? (output-text out) "item0")))
            (is (not (str/includes? (output-text out) "item1")))
            (is (not (str/includes? (output-text out) "\u001b[3J")))
            (render/frame! renderer frame 262)
            (is (= 1 (occurrences (output-text out) "\u001b[2J")) "reanchor happens only once")))))))

(deftest shrinking-live-region-keeps-cursor-in-new-content-test
  (with-terminal 20 5
    (fn [terminal out]
      (let [renderer (render/create terminal)]
        (render/frame! renderer {:content "a\nb\nc\nd\ne"} 0)
        (render/frame! renderer {:content "short"} 1)
        (is (= ["short"] (:inline-lines @renderer)))
        (resize-terminal! terminal renderer 30 5 10)
        (.reset ^ByteArrayOutputStream out)
        (render/frame! renderer {:content "CHANGED"} 160)
        (let [output (output-text out)]
          (is (str/includes? output "CHANGED"))
          (is (not (re-find #"\u001b\[[0-9]*[AB]" output))
              "after shrinking, resize must not invent a row above/below the new cursor")
          (is (not (str/includes? output "\n"))))))))

(deftest failed-frame-does-not-commit-test
  (with-terminal 80 24
    (fn [terminal out]
      (let [renderer (render/create terminal)
            frame (inline-frame 1 2)]
        (render/frame! renderer frame 0)
        (swap! renderer assoc :reanchor? true)
        (.reset ^ByteArrayOutputStream out)
        (let [before (:shown @renderer)]
          (with-redefs-fn {#'render/step! (fn [_ _] (throw (ex-info "draw failed" {})))}
            #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"draw failed"
                                  (render/frame! renderer (inline-frame 1 3) 1))))
          (is (= before (:shown @renderer))))
        (is (true? (:reanchor? @renderer)) "a failed recovery remains owed")
        (is (str/ends-with? (output-text out) "\u001b[?2026l") "failed output ends synchronized mode")
        (render/frame! renderer (inline-frame 1 3) 2)
        (is (= 3 (get-in @renderer [:shown :printed])))
        (is (false? (:reanchor? @renderer)))
        (is (= 1 (occurrences (output-text out) "item2")))))))

(deftest partial-alt-entry-still-cleans-up-test
  (with-terminal 80 24
    (fn [terminal out]
      (let [renderer (render/create terminal)
            frame (inline-frame 1 2)]
        (render/frame! renderer frame 0)
        (let [before (:shown @renderer)]
          (with-redefs [term/clear-screen (fn [_] (throw (ex-info "clear failed" {})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"clear failed"
                                 (render/frame! renderer (assoc frame :fullscreen? true) 1))))
          (is (= before (:shown @renderer)) "planned fullscreen state was not committed"))
        (is (true? (:alt? @renderer)) "terminal did enter the alternate screen")
        (.reset ^ByteArrayOutputStream out)
        (render/stop! renderer)
        (let [output (output-text out)]
          (is (str/includes? output "\u001b[?1006l"))
          (is (str/includes? output "\u001b[?1000l"))
          (is (str/includes? output "\u001b[?1049l"))
          (is (re-find #"\u001b\[\?(?:[0-9]+;)*25h" output))
          (is (not (str/includes? output "\u001b[3J"))))))))

(deftest stop-restores-independently-test
  (doseq [alt? [false true]]
    (let [events (atom [])
          renderer (atom {:terminal nil :alt? alt? :shown {:screen :inline}})]
      (with-redefs-fn {#'render/write! (fn [_ text]
                                       (swap! events conj [:write text])
                                       (throw (ex-info "output failed" {})))
                      #'term/exit-alt-screen (fn [_]
                                               (swap! events conj :exit-alt)
                                               (throw (AssertionError. "exit failed")))
                      #'term/show-cursor (fn [_] (swap! events conj :show-cursor))}
        #(render/stop! renderer))
      (is (= (cond-> [[:write "\u001b[?2026l\u001b[?1006l\u001b[?1000l"]]
               alt? (conj :exit-alt)
               true (conj :show-cursor))
             @events)))))
