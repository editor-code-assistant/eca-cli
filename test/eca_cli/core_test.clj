(ns eca-cli.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.core :as core]
            [eca-cli.version :as version]))

(deftest parse-args-start-test
  (testing "no args starts the TUI in the current directory"
    (is (= {:opts {:workspace (System/getProperty "user.dir")}}
           (core/parse-args []))))

  (testing "options are passed through"
    (is (= {:workspace "/tmp/p" :model "m" :agent "a" :trust true :eca "/bin/eca" :nrepl 7888}
           (:opts (core/parse-args ["--workspace" "/tmp/p" "--model" "m" "--agent" "a"
                                    "--trust" "--eca" "/bin/eca" "--nrepl" "7888"]))))))

(deftest parse-args-info-test
  (testing "--version prints the version, exits 0"
    (is (= {:exit 0 :message (str "eca-cli " version/version)}
           (core/parse-args ["--version"]))))

  (testing "--help and -h print usage listing every option, exit 0"
    (doseq [flag ["--help" "-h"]]
      (let [{:keys [exit message]} (core/parse-args [flag])]
        (is (zero? exit))
        (is (str/includes? message "Usage: eca-cli [options]"))
        (doseq [opt ["--workspace" "--model" "--agent" "--trust" "--eca" "--nrepl" "--version" "--help"]]
          (is (str/includes? message opt) opt))))))

(deftest parse-args-error-test
  (testing "invalid input exits 1 with a hint instead of being ignored"
    (doseq [[args expected] [[["--modle" "x"] "Unknown option: --modle"]
                             [["--nrepl" "abc"] "--nrepl"]
                             [["--nrepl"] "Missing value for option --nrepl"]
                             [["my-project"] "unexpected argument: my-project"]]]
      (let [{:keys [exit message opts]} (core/parse-args args)]
        (is (= 1 exit) (pr-str args))
        (is (nil? opts))
        (is (str/starts-with? message "eca-cli: "))
        (is (str/includes? message expected) message)
        (is (str/includes? message "eca-cli --help") message)))))
