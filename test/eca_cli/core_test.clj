(ns eca-cli.core-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
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

(deftest parse-args-fullscreen-test
  (testing "inline is the default; --fullscreen is a boolean flag"
    (is (not (get-in (core/parse-args []) [:opts :fullscreen])))
    (let [{:keys [opts exit]} (core/parse-args ["--fullscreen"])]
      (is (nil? exit))
      (is (true? (:fullscreen opts)))
      (is (= (System/getProperty "user.dir") (:workspace opts)))))
  (testing "the flag composes with other options without consuming their values"
    (doseq [args [["--fullscreen" "--workspace" "/tmp/p" "--model" "m"]
                 ["--workspace" "/tmp/p" "--model" "m" "--fullscreen"]]]
      (is (= {:workspace "/tmp/p" :model "m" :fullscreen true}
             (:opts (core/parse-args args)))))))

(deftest parse-args-info-test
  (testing "--version prints the version, exits 0"
    (is (= {:exit 0 :message (str "eca-cli " version/version)}
           (core/parse-args ["--version"]))))

  (testing "--help and -h print usage listing every option, exit 0"
    (doseq [flag ["--help" "-h"]]
      (let [{:keys [exit message]} (core/parse-args [flag])]
        (is (zero? exit))
        (is (str/includes? message "Usage: eca-cli [options] [prompt]"))
        (doseq [opt ["--workspace" "--model" "--agent" "--trust" "--fullscreen" "--eca" "--nrepl" "--version" "--help"
                     "--print" "--output-format" "--continue" "--file" "--variant" "--title"
                     "--output-last-message" "--ephemeral" "--list-models" "--list-chats" "--json"
                     "--config-file" "--log-level" "--verbose"]]
          (is (str/includes? message opt) opt))))))

(deftest parse-args-error-test
  (testing "invalid input exits 2 with a hint instead of being ignored"
    (doseq [[args expected] [[["--modle" "x"] "Unknown option: --modle"]
                             [["-x"] "Unknown option: -x"]
                             [["--nrepl" "abc"] "--nrepl"]
                             [["--nrepl"] "Missing value for option --nrepl"]
                             [["--model" "--trust"] "Missing value for option --model"]
                             [["-f"] "Missing value for option -f"]
                             [["--output-format" "yaml"] "--output-format must be text, json or stream-json"]
                             [["--log-level" "loud"] "--log-level must be"]
                             [["--list-models" "--list-chats"] "can't be used together"]
                             [["--json"] "--json goes with --list-models or --list-chats"]]]
      (let [{:keys [exit message opts]} (core/parse-args args)]
        (is (= 2 exit) (pr-str args))
        (is (nil? opts))
        (is (str/starts-with? message "eca-cli: "))
        (is (str/includes? message expected) message)
        (is (str/includes? message "eca-cli --help") message)))))

(deftest parse-args-prompt-test
  (testing "the words left over are the prompt"
    (is (= "explain this project" (:prompt (:opts (core/parse-args ["explain this project"])))))
    (is (= "explain this project" (:prompt (:opts (core/parse-args ["explain" "this" "project"]))))))
  (testing "options go before or after it"
    (doseq [args [["-p" "fix the build" "--model" "m"]
                  ["fix the build" "-p" "--model" "m"]
                  ["--model=m" "-p" "fix the build"]]]
      (let [{:keys [prompt print model]} (:opts (core/parse-args args))]
        (is (= "fix the build" prompt) (pr-str args))
        (is (true? print))
        (is (= "m" model)))))
  (testing "-f takes one value each time, so the prompt can follow it"
    (let [{:keys [file prompt]} (:opts (core/parse-args ["-f" "a.clj" "-f" "b.clj" "explain them"]))]
      (is (= ["a.clj" "b.clj"] file))
      (is (= "explain them" prompt))))
  (testing "short options group, the last one taking a value"
    (let [{:keys [print continue output-last-message prompt]} (:opts (core/parse-args ["-pco" "out.txt" "go on"]))]
      (is (true? print))
      (is (true? continue))
      (is (= "out.txt" output-last-message))
      (is (= "go on" prompt))))
  (testing "after --, everything is the prompt"
    (is (= "-p is not a flag here" (:prompt (:opts (core/parse-args ["--" "-p" "is" "not" "a" "flag" "here"]))))))
  (testing "no prompt, no :prompt"
    (is (not (contains? (:opts (core/parse-args ["-p"])) :prompt)))))

(deftest parse-args-print-options-test
  (is (= {:workspace (System/getProperty "user.dir")
          :print true :output-format "stream-json" :continue true :variant "high"
          :title "Nightly review" :ephemeral true :log-level "debug" :verbose true
          :config-file "ci.json" :prompt "review"}
         (:opts (core/parse-args ["-p" "--output-format" "stream-json" "-c" "--variant" "high"
                                  "--title" "Nightly review" "--ephemeral" "--log-level" "debug"
                                  "--verbose" "--config-file" "ci.json" "review"]))))
  (is (= {:workspace (System/getProperty "user.dir") :list-models true :json true}
         (:opts (core/parse-args ["--list-models" "--json"])))))

(deftest print-mode-problem-test
  (testing "print-only options in the TUI"
    (is (= "--output-format needs print mode (-p)" (core/print-mode-problem {:output-format "json"} false)))
    (is (= "--ephemeral needs print mode (-p)" (core/print-mode-problem {:ephemeral true} false)))
    (is (= "--output-last-message needs print mode (-p)" (core/print-mode-problem {:output-last-message "f"} false))))
  (testing "the TUI's options in print mode"
    (is (some? (core/print-mode-problem {:fullscreen true} true))))
  (testing "fine otherwise"
    (is (nil? (core/print-mode-problem {:output-format "json" :ephemeral true} true)))
    (is (nil? (core/print-mode-problem {:fullscreen true :continue true} false)))))

(deftest server-args-test
  (is (= [] (core/server-args {})))
  (is (= ["--log-level" "debug" "--verbose" "--config-file" "/c.json"]
         (core/server-args {:log-level "debug" :verbose true :config-file "/c.json"}))))

(deftest file-contexts-test
  (let [dir (str (fs/create-temp-dir))
        f   (str (fs/create-file (fs/path dir "a.clj")))]
    (is (= [{:type "file" :path (str (fs/canonicalize f))}
            {:type "directory" :path (str (fs/canonicalize dir))}]
           (core/file-contexts [f dir])))
    (is (= {:error "no such file or directory: nope.clj"} (core/file-contexts ["nope.clj"])))
    (is (= [] (core/file-contexts nil)))))
