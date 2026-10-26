(ns eca-cli.terminal-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.terminal :as terminal]))

(deftest ena-acs-test
  (testing "screen/tmux terminfo draw lines via SO/SI and need G1 designated as DEC graphics"
    (is (= "\u001b(B\u001b)0" (terminal/ena-acs "screen-256color")))
    (is (= "\u001b(B\u001b)0" (terminal/ena-acs "tmux-256color"))))

  (testing "xterm-style terminfo switches G0 directly (ESC ( 0), nothing to enable"
    (is (nil? (terminal/ena-acs "xterm-256color"))))

  (testing "missing or dumb terminal types yield nil"
    (is (nil? (terminal/ena-acs nil)))
    (is (nil? (terminal/ena-acs "")))
    (is (nil? (terminal/ena-acs "dumb")))))
