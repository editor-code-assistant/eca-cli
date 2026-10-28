(ns eca-cli.program
  "charm.clj's `run` with two changes:

  - The event loop. charm handles one message per 10 ms tick and renders
    after each, so input is capped at ~100 keys a second: pasting a stack
    trace took half a minute. This loop wakes as soon as a message arrives,
    handles everything queued, then renders once.
  - The renderer (`eca-cli.render`): inline, with the chat in the
    terminal's history, or full screen, frame by frame.

  - The input loop knows Shift+Tab and can pause, for Ctrl+G's editor
    (see `external`).

  Adapted from `charm.program/run` (de.timokramer/charm.clj 0.2.71, MIT
  License); its command execution and resize check are reused as they are.
  Revisit when bumping charm."
  (:require [charm.input.handler :as input]
            [charm.input.keymap :as km]
            [charm.message :as msg]
            [charm.program :as program]
            [charm.terminal :as term]
            [clojure.core.async :as a]
            [eca-cli.render :as render])
  (:import [org.jline.keymap KeyMap]
           [org.jline.terminal Attributes Terminal]
           [org.jline.utils Signals]))

(def ^:private execute-cmd!      @#'program/execute-cmd!)
(def ^:private check-window-size! @#'program/check-window-size!)

(def ^:private read-timeout-ms 100)

(defn- keymap
  "charm's keymap, plus Shift+Tab (CSI Z, terminfo's `kcbt`), which charm
  doesn't know: it would arrive as an :unknown key."
  [terminal]
  (doto ^KeyMap (km/create-keymap terminal)
    (.bind {:type :tab :shift true} "[Z")))

(defn- event->msg
  "A charm input event as a message, as `charm.program`'s input loop does."
  [event]
  (case (:type event)
    :mouse (let [raw-button (:button event)
                 wheel      (case (int raw-button)
                              4 :wheel-up 5 :wheel-down 6 :wheel-left 7 :wheel-right nil)]
             (msg/mouse (or wheel (:action event))
                        (if wheel :none (case (int raw-button) 0 :left 1 :middle 2 :right :none))
                        (:x event) (:y event)
                        :ctrl (:ctrl event) :alt (:alt event) :shift (:shift event)))
    :focus (msg/focus)
    :blur  (msg/blur)
    (msg/key-press (if (= :runes (:type event)) (:runes event) (:type event))
                   :ctrl (:ctrl event) :alt (:alt event) :shift (:shift event))))

(defn- start-input-loop!
  "Read the terminal's input into `msg-chan` until `running?` turns false;
  nothing is read while `paused?` (another program has the terminal).
  Returns the thread."
  [^Terminal terminal msg-chan running? paused?]
  (let [km     (keymap terminal)
        thread (Thread.
                 (fn []
                   (while @running?
                     (try
                       (if @paused?
                         (Thread/sleep 20)
                         (when-let [event (input/read-event terminal :timeout-ms read-timeout-ms :keymap km)]
                           (a/put! msg-chan (event->msg event))))
                       (catch InterruptedException _
                         (reset! running? false))
                       (catch Exception _
                         ;; Read errors while shutting down.
                         nil)))))]
    (.setDaemon thread true)
    (.start thread)
    thread))

(def ^:private max-batch
  "Messages handled before rendering anyway, so a flood can't freeze the screen."
  1000)

(defn- bracketed-paste!
  "Turn the terminal's bracketed paste on or off: pasted text then comes
  between :paste-start and :paste-end keys, so its newlines can stay text
  instead of acting as Enter (see `eca-cli.state`)."
  [^org.jline.terminal.Terminal terminal on?]
  (doto (.writer terminal)
    (.write (if on? "\u001b[?2004h" "\u001b[?2004l"))
    (.flush)))

(defn external
  "A message for a cmd to return: run `run` (no arguments) with the terminal
  handed over to it (an editor), then deliver `(done result)` as a message.
  `run`'s result, or `{:error message}` if it throws."
  [run done]
  {:type ::external :run run :done done})

(defn- run-external!
  "Run an `external` message's fn with the terminal back in its normal
  state: input not read, cooked mode, no bracketed paste, nothing drawn.
  Then everything is as it was and the next frame draws afresh."
  [{:keys [terminal renderer paused? original-attrs]} m]
  (reset! paused? true)
  ;; The input loop finishes the read it's in.
  (Thread/sleep (+ read-timeout-ms 30))
  (let [raw (term/get-attributes terminal)]
    (try
      (render/suspend! renderer)
      (bracketed-paste! terminal false)
      (term/set-attributes terminal original-attrs)
      ((:run m))
      (catch Throwable e
        {:error (or (ex-message e) (str e))})
      (finally
        (doseq [restore [(fn [] (term/set-attributes terminal raw))
                         (fn [] (bracketed-paste! terminal true))
                         (fn [] (render/resume! renderer))]]
          (try (restore) (catch Throwable _ nil)))
        (reset! paused? false)))))

(defn- handle!
  "Apply message `m`; false when it asks to quit."
  [m state renderer msg-chan update ctx]
  (cond
    (msg/quit? m)        false
    (= :error (:type m)) (throw (:error m))

    (= ::external (:type m))
    (let [result (run-external! ctx m)]
      (a/put! msg-chan ((:done m) result))
      true)

    :else
    (do (when (= :window-size (:type m))
          (render/resize! renderer (:width m) (:height m) (System/currentTimeMillis)))
        (let [[new-state cmd] (update @state m)]
          (reset! state new-state)
          (execute-cmd! cmd msg-chan))
        true)))

(defn run
  "Like `charm.program/run`, with `:frame` (state -> what `eca-cli.render`
  draws, see `eca-cli.view/frame`) for `:view`. Options `:init :update
  :frame`; returns the final state."
  [{:keys [init update frame]}]
  (let [terminal                   (term/create-terminal)
        ^Attributes original-attrs (term/enter-raw-mode terminal)
        renderer                   (render/create terminal)
        msg-chan                   (a/chan 256)
        running?                   (atom true)
        paused?                    (atom false)
        last-size                  (atom {:width 0 :height 0})
        init-result                (if (fn? init) (init) [init nil])
        [initial-state init-cmd]   (if (vector? init-result) init-result [init-result nil])
        state                      (atom initial-state)
        ctx                        {:terminal terminal :renderer renderer :paused? paused?
                                    :original-attrs original-attrs}
        draw!                      #(render/frame! renderer (frame @state) (System/currentTimeMillis))]
    (try
      (render/start! renderer)
      (bracketed-paste! terminal true)
      (Signals/register "WINCH" (reify Runnable
                                  (run [_] (check-window-size! terminal msg-chan last-size))))
      (Signals/register "INT" (reify Runnable
                                (run [_] (a/put! msg-chan (msg/key-press "c" :ctrl true)))))
      (check-window-size! terminal msg-chan last-size)

      (let [^Thread input-thread (start-input-loop! terminal msg-chan running? paused?)]
        (execute-cmd! init-cmd msg-chan)
        (draw!)
        (loop []
          (when @running?
            ;; Wake on the first message (at most 10 ms idle), take all the
            ;; queued ones, render once. Idle, a frame put off (a repaint
            ;; waiting for a resize to settle) is drawn when due.
            (if-let [m (a/alt!! msg-chan ([m] m) (a/timeout 10) ([_] nil))]
              (do (loop [m m, n 1]
                    (if (handle! m state renderer msg-chan update ctx)
                      (when-let [next-m (and (< n max-batch) (a/poll! msg-chan))]
                        (recur next-m (inc n)))
                      (reset! running? false)))
                  (when @running? (draw!)))
              (when (render/due? renderer (System/currentTimeMillis))
                (draw!)))
            (recur)))
        (.interrupt input-thread))
      @state

      (finally
        (reset! running? false)
        (a/close! msg-chan)
        ;; Each cleanup is independent: even a broken output stream must
        ;; not keep the terminal in raw mode or leave its cursor hidden.
        (doseq [restore [(fn [] (bracketed-paste! terminal false))
                         (fn [] (render/finish! renderer (frame @state) (System/currentTimeMillis)))
                         (fn [] (render/stop! renderer))
                         (fn [] (term/set-attributes terminal original-attrs))
                         (fn [] (term/close terminal))]]
          (try (restore) (catch Throwable _ nil)))))))
