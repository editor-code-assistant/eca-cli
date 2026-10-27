(ns eca-cli.demo
  "Records demo.gif, the README's demo (`bb demo`): eca-cli against a
  scripted stand-in for the ECA server (build/demo/fake_eca.clj), in a demo
  project under a temporary HOME, so the recording shows nobody's account,
  models or code. tmux runs it and takes the keystrokes; frames are captured
  from the pane whenever it changes, drawn to PNG by headless Chrome and
  assembled by ffmpeg.

  Needs tmux, Chrome (or Chromium) and ffmpeg."
  (:require [babashka.classpath :as classpath]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [eca-cli.dist :as dist]))

(def cols 100)
(def rows 30)

;; --- The demo project ---

(def ^:private project-files
  {"src/my_app/http.clj"
   (str "(ns my-app.http\n"
        "  (:require [babashka.http-client :as http]))\n"
        "\n"
        "(defn fetch\n"
        "  \"GETs `url`, returning the response body.\"\n"
        "  [url]\n"
        "  (:body (http/get url {:timeout 5000})))\n")
   "src/my_app/core.clj"
   (str "(ns my-app.core\n"
        "  (:require [my-app.http :as http]))\n"
        "\n"
        "(defn -main [& _]\n"
        "  (println (http/fetch \"https://example.com/status\")))\n")
   "test/my_app/http_test.clj"
   (str "(ns my-app.http-test\n"
        "  (:require [clojure.test :refer [deftest is]]\n"
        "            [my-app.http :as http]))\n")
   "deps.edn"  "{:paths [\"src\"]}\n"
   "README.md" "# my-app\n"
   "AGENTS.md" "# my-app\n\nA small Clojure service.\n"})

(defn- setup!
  "A temporary HOME holding the demo project, ~/dev/my-app; the stand-in
  server; eca-cli's version, as a release stamps it."
  []
  (let [root (str (fs/create-temp-dir {:prefix "eca-cli-demo"}))
        home (str root "/home")
        ws   (str home "/dev/my-app")
        res  (str root "/res")
        eca  (str root "/eca")]
    (doseq [[path content] project-files]
      (fs/create-dirs (fs/parent (fs/path ws path)))
      (spit (str ws "/" path) content))
    (fs/create-dirs res)
    (spit (str res "/ECA_CLI_VERSION") (dist/release-version))
    ;; Run from outside the repo: bb would pick up its bb.edn.
    (spit eca (str "#!/bin/sh\ncd \"" root "\" && exec bb \""
                   (fs/absolutize "build/demo/fake_eca.clj") "\" \"$@\"\n"))
    (fs/set-posix-file-permissions eca "rwxr-xr-x")
    {:root root :home home :workspace ws :res res :eca eca}))

;; --- tmux ---

(def ^:private socket "eca-cli-demo")

(defn- tmux [& args]
  (:out (p/sh (into ["tmux" "-L" socket "-f" "/dev/null"] args))))

(defn- pane [] (tmux "capture-pane" "-p" "-e" "-t" "demo"))

(defn- screen-text [] (tmux "capture-pane" "-p" "-t" "demo"))

(defn- launch! [{:keys [root home workspace res eca]}]
  (tmux "kill-server")
  ;; eca-cli's files (chats, log) go under the demo's HOME, not the user's.
  (tmux "new-session" "-d" "-s" "demo" "-x" (str cols) "-y" (str rows) "-c" root
        (str "env HOME='" home "' XDG_STATE_HOME='" home "/.local/state' "
             "XDG_CACHE_HOME='" home "/.cache' XDG_CONFIG_HOME='" home "/.config' "
             "TERM=xterm-256color COLORTERM=truecolor "
             "bb -cp '" res ":" (classpath/get-classpath) "' -m eca-cli.core "
             "--workspace '" workspace "' --eca '" eca "'"))
  (tmux "set" "-t" "demo" "status" "off"))

(defn- wait-for
  "Until the screen shows `re` (a regex), or `ms` pass."
  ([re] (wait-for re 30000))
  ([re ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (cond
         (re-find re (screen-text))               true
         (> (System/currentTimeMillis) deadline)  (throw (ex-info (str "Timed out waiting for " re) {}))
         :else                                    (do (Thread/sleep 50) (recur)))))))

(defn- keys! [& ks] (apply tmux "send-keys" "-t" "demo" ks))

(defn- type!
  "`text` typed at `ms` per character."
  [text ms]
  (doseq [ch text]
    (keys! "-l" (str ch))
    (Thread/sleep ms)))

;; --- The storyboard ---

(defn- play!
  "What the demo shows: the welcome, a prompt with a file added as context,
  the agent reading it and asking to edit it, the diff approved, its summary."
  []
  (wait-for #"Tips for getting started" 60000)
  (Thread/sleep 2800)
  (type! "Add retries with backoff to fetch in " 45)
  (Thread/sleep 300)
  (keys! "@")
  (wait-for #"Add context")
  (Thread/sleep 900)
  (type! "http" 140)
  (Thread/sleep 1100)
  (keys! "Enter")
  (Thread/sleep 900)
  (keys! "Enter")
  (wait-for #"\[y\] approve")
  (Thread/sleep 3200)
  (keys! "y")
  (wait-for #"Want me to add a test")
  (Thread/sleep 4000))

(defn- record
  "Frames as [ms-since-start screen], a new one whenever the screen changes,
  while `play!` runs."
  []
  (let [frames (atom [])
        done   (promise)
        t0     (System/currentTimeMillis)
        ;; From the welcome on (not while connecting).
        grab   (future
                 (loop [last-screen nil]
                   (when-not (realized? done)
                     (let [s       (pane)
                           started (or last-screen (when (str/includes? s "Tips for getting started") ""))]
                       (when (and started (not= s last-screen))
                         (swap! frames conj [(- (System/currentTimeMillis) t0) s]))
                       (Thread/sleep 60)
                       (recur (when started s))))))]
    (try
      (play!)
      (finally
        (deliver done true)
        @grab))
    @frames))

;; --- Frames to HTML (a terminal screen, the dark scheme of the README) ---

(def ^:private scheme
  {:fg "#cdd6f4" :bg "#1e1e2e"
   :base ["#45475a" "#f38ba8" "#a6e3a1" "#f9e2af" "#89b4fa" "#f5c2e7" "#94e2d5" "#bac2de"
          "#585b70" "#f38ba8" "#a6e3a1" "#f9e2af" "#89b4fa" "#f5c2e7" "#94e2d5" "#a6adc8"]})

(defn- xterm256 [n]
  (cond
    (< n 16)  (nth (:base scheme) n)
    (< n 232) (let [n  (- n 16)
                    lv [0 95 135 175 215 255]]
                (format "#%02x%02x%02x" (nth lv (quot n 36)) (nth lv (mod (quot n 6) 6)) (nth lv (mod n 6))))
    :else     (let [v (+ 8 (* 10 (- n 232)))] (format "#%02x%02x%02x" v v v))))

(def ^:private acs
  ;; tmux hands back box lines in the DEC special graphics charset.
  {\q "─" \x "│" \l "┌" \k "┐" \m "└" \j "┘" \t "├" \u "┤" \w "┬" \v "┴" \n "┼" \a "▒" \0 "█" \h "░"})

(defn- sgr [st params]
  (loop [st st ps (if (empty? params) [0] params)]
    (if-let [[p & more] (seq ps)]
      (cond
        (= p 0)          (recur {} more)
        (= p 1)          (recur (assoc st :bold true) more)
        (= p 2)          (recur (assoc st :faint true) more)
        (= p 3)          (recur (assoc st :italic true) more)
        (= p 4)          (recur (assoc st :underline true) more)
        (= p 7)          (recur (assoc st :reverse true) more)
        (= p 9)          (recur (assoc st :strike true) more)
        (= p 22)         (recur (dissoc st :bold :faint) more)
        (= p 23)         (recur (dissoc st :italic) more)
        (= p 24)         (recur (dissoc st :underline) more)
        (= p 27)         (recur (dissoc st :reverse) more)
        (= p 29)         (recur (dissoc st :strike) more)
        (<= 30 p 37)     (recur (assoc st :fg (xterm256 (- p 30))) more)
        (<= 90 p 97)     (recur (assoc st :fg (xterm256 (+ 8 (- p 90)))) more)
        (<= 40 p 47)     (recur (assoc st :bg (xterm256 (- p 40))) more)
        (<= 100 p 107)   (recur (assoc st :bg (xterm256 (+ 8 (- p 100)))) more)
        (= p 39)         (recur (dissoc st :fg) more)
        (= p 49)         (recur (dissoc st :bg) more)
        (and (#{38 48} p) (= 5 (first more)))
        (recur (assoc st (if (= 38 p) :fg :bg) (xterm256 (second more))) (drop 2 more))
        (and (#{38 48} p) (= 2 (first more)))
        (let [[_ r g b] more]
          (recur (assoc st (if (= 38 p) :fg :bg) (format "#%02x%02x%02x" r g b)) (drop 4 more)))
        :else            (recur st more))
      st)))

(defn- css [{:keys [fg bg bold faint italic underline reverse strike]}]
  (let [[fg bg] (if reverse [(or bg (:bg scheme)) (or fg (:fg scheme))] [fg bg])]
    (str (when fg (str "color:" fg ";"))
         (when bg (str "background:" bg ";"))
         (when bold "font-weight:bold;")
         (when faint "opacity:.6;")
         (when italic "font-style:italic;")
         (when (or underline strike)
           (str "text-decoration:" (str/join " " (remove nil? [(when underline "underline") (when strike "line-through")])) ";")))))

(defn- esc [s] (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- line->html
  "One captured line as cells of their width; [html state] (tmux carries
  styles and the charset over lines)."
  [line [st0 acs0]]
  (let [sb (StringBuilder.) n (count line)]
    (loop [i 0 st st0 acs? acs0]
      (if (>= i n)
        [(str sb) [st acs?]]
        (let [c (.charAt ^String line i)]
          (cond
            (and (= \u001b c) (< (inc i) n) (= \[ (.charAt ^String line (inc i))))
            (let [end    (loop [j (+ i 2)] (if (or (>= j n) (Character/isLetter (.charAt ^String line j))) j (recur (inc j))))
                  params (->> (str/split (subs line (+ i 2) (min end n)) #";") (remove str/blank?) (mapv parse-long))]
              (recur (inc end) (if (and (< end n) (= \m (.charAt ^String line end))) (sgr st params) st) acs?))

            (and (= \u001b c) (< (+ i 2) n) (#{\( \)} (.charAt ^String line (inc i))))
            (recur (+ i 3) st (if (= \( (.charAt ^String line (inc i))) (= \0 (.charAt ^String line (+ i 2))) acs?))

            (= \u000e c) (recur (inc i) st true)
            (= \u000f c) (recur (inc i) st false)
            (= \u001b c) (recur (+ i 2) st acs?)

            :else
            (let [cp-end (+ i (Character/charCount (.codePointAt ^String line (int i))))
                  raw    (subs line i cp-end)
                  ch     (if acs? (get acs c raw) raw)
                  w      (max 1 (.columnLength (org.jline.utils.AttributedString. ^String ch)))]
              (.append sb (str "<span style=\"display:inline-block;width:" w "ch;" (css st) "\">" (esc ch) "</span>"))
              (recur cp-end st acs?))))))))

(defn- screen->html [screen]
  (str "<!doctype html><html><head><meta charset='utf-8'><style>"
       "body{margin:0;background:" (:bg scheme) ";}"
       "pre{margin:0;padding:14px 16px;font-family:'DejaVu Sans Mono',monospace;font-size:14px;"
       "line-height:17px;color:" (:fg scheme) ";white-space:pre;}"
       "</style></head><body><pre>"
       (str/join "\n" (first (reduce (fn [[out state] line]
                                       (let [[html state'] (line->html line state)]
                                         [(conj out html) state']))
                                     [[] [{} false]]
                                     (str/split-lines screen))))
       "</pre></body></html>"))

;; --- Frames to GIF ---

(defn- chrome []
  (or (some fs/which ["google-chrome" "chromium" "chromium-browser"])
      (throw (ex-info "Chrome or Chromium is needed to draw the frames." {}))))

(def ^:private frame-w (+ 32 (long (Math/ceil (* cols 8.43)))))
(def ^:private frame-h (+ 28 (* rows 17)))

(defn- draw!
  "PNGs of `screens` in `dir`, a few Chrome processes at a time."
  [screens dir]
  (let [browser (str (chrome))]
    (doseq [batch (partition-all 4 (map-indexed vector screens))]
      (->> batch
           (mapv (fn [[i screen]]
                   (let [html (str dir "/" (format "f%04d" i) ".html")]
                     (spit html (screen->html screen))
                     (p/process [browser "--headless=new" "--disable-gpu" "--no-sandbox" "--hide-scrollbars"
                                 (str "--user-data-dir=" dir "/chrome-" (mod i 4))
                                 (str "--window-size=" frame-w "," frame-h)
                                 (str "--screenshot=" dir "/" (format "f%04d" i) ".png")
                                 (str "file://" html)]
                                {:out :string :err :string}))))
           (run! deref)))))

(defn- gif!
  "`frames` ([ms screen]) drawn and assembled into `out`, each frame shown
  until the next one; the last held a while."
  [frames out]
  (let [dir   (str (fs/create-temp-dir {:prefix "eca-cli-demo-frames"}))
        times (map first frames)
        holds (concat (map (fn [a b] (max 40 (- b a))) times (rest times)) [3000])
        list  (str dir "/frames.txt")]
    (draw! (map second frames) dir)
    (spit list (str (str/join (map-indexed (fn [i ms] (format "file 'f%04d.png'\nduration %.3f\n" i (/ ms 1000.0))) holds))
                    (format "file 'f%04d.png'\n" (dec (count frames)))))
    (let [{:keys [exit err]} (p/sh ["ffmpeg" "-y" "-loglevel" "error" "-f" "concat" "-safe" "0" "-i" list
                                    "-vf" "split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle"
                                    "-loop" "0" out])]
      (when-not (zero? exit) (throw (ex-info (str "ffmpeg failed: " err) {}))))
    (fs/delete-tree dir)))

(defn record!
  "Record demo.gif (or the path given) from the storyboard in `play!`."
  [args]
  (let [out    (or (first args) "demo.gif")
        demo   (setup!)]
    (try
      (launch! demo)
      (let [frames (record)]
        (println (count frames) "frames, drawing them…")
        (gif! frames out)
        (println "Wrote" out (str "(" (quot (fs/size out) 1024) " KB)")))
      (finally
        (keys! "C-c")
        (Thread/sleep 300)
        (keys! "C-c")
        (Thread/sleep 1000)
        (tmux "kill-server")
        (fs/delete-tree (:root demo))))))
