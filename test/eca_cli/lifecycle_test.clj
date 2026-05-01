(ns eca-cli.lifecycle-test
  "Tests for the shutdown lifecycle: ECA `shutdown` request → `exit`
  notification → server process kill. Verifies both the protocol-level
  sequence and the higher-level wiring through `commands/cmd-quit` and
  `state/update-state`'s Ctrl+C path produce identical observable
  behaviour."
  (:require [clojure.test :refer [deftest is testing]]
            [charm.message :as msg]
            [eca-cli.commands :as commands]
            [eca-cli.protocol :as protocol]
            [eca-cli.server :as server]
            [eca-cli.state :as state]))

(defn- run-sequence-cmd!
  "Walk a charm :sequence cmd, invoking each inner :cmd's :fn in order.
  Used by tests to drive cmd-quit / shutdown-cmd's side effects without
  spinning up the full charm event loop."
  [cmd]
  (when (= :sequence (:type cmd))
    (doseq [inner (:cmds cmd)]
      (when (= :cmd (:type inner))
        ((:fn inner))))))

;; --- protocol/shutdown! sequence ---

(deftest shutdown-sequence-order-test
  (testing "protocol/shutdown! sends `shutdown` request, then `exit` notification"
    (let [calls (atom [])
          fake-srv {:queue nil}]
      (with-redefs [protocol/send-request!     (fn [_srv method _params cb]
                                                 (swap! calls conj [:request method])
                                                 (cb {:result {}}))
                    protocol/send-notification! (fn [_srv method _params]
                                                  (swap! calls conj [:notification method]))]
        (protocol/shutdown! fake-srv)
        (is (= [[:request "shutdown"]
                [:notification "exit"]]
               @calls))))))

;; NOTE: the timeout-resilience case (server hangs → exit still fires) isn't
;; covered by an automated test because protocol/shutdown!'s 5s deref timeout
;; is hardcoded and `with-redefs` of clojure.core/deref recurses. The
;; behaviour is enforced by code structure: `(deref done 5000 nil)` returns
;; nil on timeout, and the `(send-notification! srv "exit" {})` line that
;; follows is unconditional. Verified visually; a deeper test would need
;; protocol/shutdown! to take an injectable timeout.

;; --- cmd-quit composition order ---

(deftest cmd-quit-runs-shutdown-then-server-shutdown-test
  (testing "commands/cmd-quit runs protocol/shutdown! before server/shutdown!"
    (let [calls (atom [])
          fake-srv {:queue nil}
          state    {:server fake-srv}]
      (with-redefs [protocol/shutdown! (fn [_srv] (swap! calls conj :protocol-shutdown))
                    server/shutdown!   (fn [_srv] (swap! calls conj :server-shutdown))]
        (let [[_state cmd] (commands/cmd-quit state)]
          (run-sequence-cmd! cmd)
          (is (= [:protocol-shutdown :server-shutdown] @calls)))))))

(deftest cmd-quit-survives-protocol-shutdown-exception-test
  (testing "if protocol/shutdown! throws, server/shutdown! still runs"
    (let [calls (atom [])
          fake-srv {:queue nil}
          state    {:server fake-srv}]
      (with-redefs [protocol/shutdown! (fn [_srv]
                                         (swap! calls conj :protocol-shutdown-attempted)
                                         (throw (Exception. "ECA already gone")))
                    server/shutdown!   (fn [_srv] (swap! calls conj :server-shutdown))]
        (let [[_state cmd] (commands/cmd-quit state)]
          (run-sequence-cmd! cmd)
          (is (= [:protocol-shutdown-attempted :server-shutdown] @calls)
              "Server kill must run even when protocol shutdown explodes"))))))

;; --- Ctrl+C and /quit equivalence ---

(deftest ctrl-c-and-cmd-quit-equivalent-test
  (testing "Ctrl+C in :ready and commands/cmd-quit produce equivalent shutdown side effects"
    (let [ctrl-c-calls (atom [])
          quit-calls   (atom [])
          fake-srv     {:queue nil}
          base-state   {:mode  :ready
                        :server fake-srv}]
      ;; Ctrl+C path — drives state/update-state and runs the resulting cmd
      (with-redefs [protocol/shutdown! (fn [_srv] (swap! ctrl-c-calls conj :protocol-shutdown))
                    server/shutdown!   (fn [_srv] (swap! ctrl-c-calls conj :server-shutdown))]
        (let [[_state cmd] (state/update-state base-state (msg/key-press "c" :ctrl true))]
          (run-sequence-cmd! cmd)))

      ;; /quit path — drives commands/cmd-quit directly
      (with-redefs [protocol/shutdown! (fn [_srv] (swap! quit-calls conj :protocol-shutdown))
                    server/shutdown!   (fn [_srv] (swap! quit-calls conj :server-shutdown))]
        (let [[_state cmd] (commands/cmd-quit base-state)]
          (run-sequence-cmd! cmd)))

      (is (= @ctrl-c-calls @quit-calls)
          "Ctrl+C and /quit must invoke identical shutdown side effects in the same order")
      (is (= [:protocol-shutdown :server-shutdown] @ctrl-c-calls)))))
