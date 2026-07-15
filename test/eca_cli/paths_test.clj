(ns eca-cli.paths-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.paths :as paths]))

(defn- stub-env [m]
  (fn [k] (get m k)))

(deftest cache-home-with-env-test
  (testing "honours XDG_CACHE_HOME when set"
    (with-redefs [paths/getenv (stub-env {"XDG_CACHE_HOME" "/tmp/c"})]
      (is (= "/tmp/c" (str (paths/cache-home))))))
  (testing "falls back to ~/.cache when unset"
    (with-redefs [paths/getenv (stub-env {})]
      (is (= (str (System/getProperty "user.home") "/.cache")
             (str (paths/cache-home))))))
  (testing "empty string is treated as unset"
    (with-redefs [paths/getenv (stub-env {"XDG_CACHE_HOME" ""})]
      (is (= (str (System/getProperty "user.home") "/.cache")
             (str (paths/cache-home)))))))

(deftest state-home-with-env-test
  (testing "honours XDG_STATE_HOME when set"
    (with-redefs [paths/getenv (stub-env {"XDG_STATE_HOME" "/tmp/s"})]
      (is (= "/tmp/s" (str (paths/state-home))))))
  (testing "falls back to ~/.local/state when unset"
    (with-redefs [paths/getenv (stub-env {})]
      (is (= (str (System/getProperty "user.home") "/.local/state")
             (str (paths/state-home)))))))

(deftest config-home-with-env-test
  (testing "honours XDG_CONFIG_HOME when set"
    (with-redefs [paths/getenv (stub-env {"XDG_CONFIG_HOME" "/tmp/cfg"})]
      (is (= "/tmp/cfg" (str (paths/config-home))))))
  (testing "falls back to ~/.config when unset"
    (with-redefs [paths/getenv (stub-env {})]
      (is (= (str (System/getProperty "user.home") "/.config")
             (str (paths/config-home)))))))

(deftest eca-cache-dir-composition-test
  (with-redefs [paths/getenv (stub-env {"XDG_CACHE_HOME" "/tmp/c"})]
    (is (= "/tmp/c/eca" (str (paths/eca-cache-dir))))))

(deftest eca-state-dir-composition-test
  (with-redefs [paths/getenv (stub-env {"XDG_STATE_HOME" "/tmp/s"})]
    (is (= "/tmp/s/eca" (str (paths/eca-state-dir))))))

(deftest chats-file-under-state-test
  (with-redefs [paths/getenv (stub-env {"XDG_STATE_HOME" "/tmp/s"})]
    (is (= "/tmp/s/eca/eca-cli-chats.edn" (str (paths/chats-file))))))

(deftest log-file-under-state-test
  (with-redefs [paths/getenv (stub-env {"XDG_STATE_HOME" "/tmp/s"})]
    (is (= "/tmp/s/eca/eca-cli.log" (str (paths/log-file))))
    (is (= "/tmp/s/eca/eca-cli-nrepl.log" (str (paths/nrepl-log-file))))))

(deftest eca-binary-under-cache-test
  (with-redefs [paths/getenv (stub-env {"XDG_CACHE_HOME" "/tmp/c"})]
    (is (= "/tmp/c/eca/eca-cli/eca" (str (paths/eca-binary))))))
