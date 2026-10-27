(ns eca-cli.program
  "charm.clj's `run` with one change to the event loop. charm handles one
  message per 10 ms tick and renders after each, so input is capped at ~100
  keys a second: pasting a stack trace took half a minute. This loop wakes
  as soon as a message arrives, handles everything queued, then renders
  once.

  Adapted from `charm.program/run` (de.timokramer/charm.clj 0.2.71, MIT
  License); its input loop, command execution and resize check are reused
  as they are. Revisit when bumping charm."
  (:require [charm.message :as msg]
            [charm.program :as program]
            [charm.render.core :as render]
            [charm.terminal :as term]
            [clojure.core.async :as a])
  (:import [org.jline.terminal Attributes]
           [org.jline.utils Signals]))

(def ^:private start-input-loop! @#'program/start-input-loop!)
(def ^:private execute-cmd!      @#'program/execute-cmd!)
(def ^:private check-window-size! @#'program/check-window-size!)

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

(defn- handle!
  "Apply message `m`; false when it asks to quit."
  [m state renderer msg-chan update]
  (cond
    (msg/quit? m)        false
    (= :error (:type m)) (throw (:error m))
    :else
    (do (when (= :window-size (:type m))
          (render/update-size! renderer (:width m) (:height m)))
        (let [[new-state cmd] (update @state m)]
          (reset! state new-state)
          (execute-cmd! cmd msg-chan))
        true)))

(defn run
  "Like `charm.program/run`: options `:init :update :view :alt-screen
  :mouse :focus-reporting :fps :hide-cursor`; returns the final state."
  [{:keys [init update view] :as opts}]
  (let [{:keys [alt-screen mouse focus-reporting fps hide-cursor]}
        (merge {:alt-screen false :mouse nil :focus-reporting false :fps 60 :hide-cursor true} opts)

        terminal                   (term/create-terminal)
        ^Attributes original-attrs (term/enter-raw-mode terminal)
        renderer                   (render/create-renderer terminal :fps fps :alt-screen alt-screen
                                                           :hide-cursor hide-cursor)
        msg-chan                   (a/chan 256)
        running?                   (atom true)
        last-size                  (atom {:width 0 :height 0})
        init-result                (if (fn? init) (init) [init nil])
        [initial-state init-cmd]   (if (vector? init-result) init-result [init-result nil])
        state                      (atom initial-state)]
    (try
      (render/start! renderer)
      (bracketed-paste! terminal true)
      (when mouse (render/enable-mouse! renderer mouse))
      (when focus-reporting (render/enable-focus-reporting! renderer))
      (Signals/register "WINCH" (reify Runnable
                                  (run [_] (check-window-size! terminal msg-chan last-size))))
      (Signals/register "INT" (reify Runnable
                                (run [_] (a/put! msg-chan (msg/key-press "c" :ctrl true)))))
      (check-window-size! terminal msg-chan last-size)

      (let [^Thread input-thread (start-input-loop! terminal msg-chan running?)]
        (execute-cmd! init-cmd msg-chan)
        (render/render! renderer (view @state))
        (loop []
          (when @running?
            ;; Wake on the first message (at most 10 ms idle), take all the
            ;; queued ones, render once.
            (when-let [m (a/alt!! msg-chan ([m] m) (a/timeout 10) ([_] nil))]
              (loop [m m, n 1]
                (if (handle! m state renderer msg-chan update)
                  (when-let [next-m (and (< n max-batch) (a/poll! msg-chan))]
                    (recur next-m (inc n)))
                  (reset! running? false)))
              (when @running?
                (render/render! renderer (view @state))))
            (recur)))
        (.interrupt input-thread))
      @state

      (finally
        (reset! running? false)
        (a/close! msg-chan)
        (bracketed-paste! terminal false)
        (render/disable-mouse! renderer)
        (when focus-reporting (render/disable-focus-reporting! renderer))
        (render/stop! renderer)
        (term/set-attributes terminal original-attrs)
        (term/close terminal)))))
