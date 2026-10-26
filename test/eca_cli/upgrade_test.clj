(ns eca-cli.upgrade-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.paths :as paths]
            [eca-cli.upgrade :as upgrade])
  (:import [java.security MessageDigest]))

;; --- Platform ---

(deftest platform-asset-test
  (testing "linux x86_64 uses the static build (runs on NixOS / musl too)"
    (is (= "eca-native-static-linux-amd64.zip" (upgrade/platform-asset "Linux" "amd64")))
    (is (= "eca-native-static-linux-amd64.zip" (upgrade/platform-asset "Linux" "x86_64"))))

  (testing "linux arm64"
    (is (= "eca-native-linux-aarch64.zip" (upgrade/platform-asset "Linux" "aarch64")))
    (is (= "eca-native-linux-aarch64.zip" (upgrade/platform-asset "Linux" "arm64"))))

  (testing "macos"
    (is (= "eca-native-macos-aarch64.zip" (upgrade/platform-asset "Mac OS X" "aarch64")))
    (is (= "eca-native-macos-amd64.zip" (upgrade/platform-asset "Mac OS X" "amd64")))
    (is (= "eca-native-macos-amd64.zip" (upgrade/platform-asset "Mac OS X" "x86_64"))))

  (testing "unsupported platform throws, pointing at --eca"
    (let [e (try (upgrade/platform-asset "Windows 11" "amd64") nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (str/includes? (ex-message e) "--eca")))))

;; --- Versions ---

(deftest version-older-test
  (is (upgrade/version-older? "0.130.0" "0.167.0"))
  (is (upgrade/version-older? "0.165.1" "0.166.0"))
  (testing "numeric, not lexicographic"
    (is (upgrade/version-older? "0.99.0" "0.100.0"))
    (is (not (upgrade/version-older? "0.100.0" "0.99.0"))))
  (testing "equal or newer is not older"
    (is (not (upgrade/version-older? "0.167.0" "0.167.0")))
    (is (not (upgrade/version-older? "0.167.0" "0.166.9")))
    (is (not (upgrade/version-older? "1.0" "1.0.0")))))

;; --- Release parsing / cache ---

(def ^:private sha "156a75b0f0ae41a260b16cd4a80be8b8cc2ca8b3c1b13a66090316de70b70c41")

(deftest parse-sha256-test
  (is (= sha (upgrade/parse-sha256 (str "sha256:" sha))) "GitHub asset digest")
  (is (= sha (upgrade/parse-sha256 (str sha "\n"))) ".sha256 file, bare digest")
  (is (= sha (upgrade/parse-sha256 (str sha "  eca-native-static-linux-amd64.zip\n"))) "sha256sum format")
  (is (= sha (upgrade/parse-sha256 (str/upper-case sha))) "normalised to lowercase")
  (is (nil? (upgrade/parse-sha256 nil)))
  (is (nil? (upgrade/parse-sha256 "sha256:abc")))
  (is (nil? (upgrade/parse-sha256 (str sha "0"))) "65 hex chars is not a sha256"))

(deftest parse-release-test
  (let [r (upgrade/parse-release
            {:tag_name "0.167.0"
             :body     "changelog..."
             :assets   [{:name                 "eca-native-static-linux-amd64.zip"
                         :browser_download_url "https://x/eca-native-static-linux-amd64.zip"
                         :digest               (str "sha256:" sha)}
                        {:name                 "eca.jar"
                         :browser_download_url "https://x/eca.jar"}]})]
    (is (= "0.167.0" (:version r)))
    (is (= {:url "https://x/eca-native-static-linux-amd64.zip" :sha256 sha}
           (get-in r [:assets "eca-native-static-linux-amd64.zip"])))
    (is (= {:url "https://x/eca.jar" :sha256 nil} (get-in r [:assets "eca.jar"])))))

(deftest cached-release-test
  (let [release {:version "0.167.0" :assets {}}
        ttl     1000]
    (is (= release (upgrade/cached-release {:checked-at 5000 :release release} 5500 ttl)) "fresh")
    (is (nil? (upgrade/cached-release {:checked-at 5000 :release release} 6000 ttl)) "expired")
    (is (nil? (upgrade/cached-release {:checked-at 9000 :release release} 5000 ttl)) "written in the future (clock skew)")
    (is (nil? (upgrade/cached-release {:checked-at 5000 :release {}} 5500 ttl)) "no version")
    (is (nil? (upgrade/cached-release nil 5500 ttl)))
    (is (nil? (upgrade/cached-release "garbage" 5500 ttl)))))

;; --- Decision ---

(deftest decide-test
  (testing "nothing installed"
    (is (= {:action :install :reason :missing}
           (upgrade/decide {:installed? false :current nil :latest "0.167.0"})))
    (is (= {:action :error}
           (upgrade/decide {:installed? false :current nil :latest nil}))
        "and GitHub unreachable"))
  (testing "installed"
    (is (= {:action :use} (upgrade/decide {:installed? true :current "0.167.0" :latest "0.167.0"})))
    (is (= {:action :use} (upgrade/decide {:installed? true :current "0.168.0" :latest "0.167.0"}))
        "never downgrades")
    (is (= {:action :install :reason :outdated}
           (upgrade/decide {:installed? true :current "0.130.0" :latest "0.167.0"})))
    (is (= {:action :install :reason :unknown-version}
           (upgrade/decide {:installed? true :current nil :latest "0.167.0"}))
        "legacy / interrupted install without a version marker")
    (is (= {:action :use} (upgrade/decide {:installed? true :current "0.130.0" :latest nil}))
        "offline: keep the installed binary")))

(deftest format-progress-test
  (is (= "14.0 / 28.0 MB  50%" (upgrade/format-progress (* 14 1048576) (* 28 1048576))))
  (is (= "0.0 / 28.0 MB  0%" (upgrade/format-progress 0 (* 28 1048576))))
  (is (= "3.5 MB" (upgrade/format-progress (* 3.5 1048576) nil))))

;; --- Install (hermetic: fake release zip, isolated XDG_CACHE_HOME) ---

(defn- sha256-of [f]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (fs/read-all-bytes f))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- fake-release-zip
  "Zip containing an `eca` shell script that prints `version-line` and exits `exit-code`."
  [dir version-line exit-code]
  (let [src (fs/file dir "src")
        bin (fs/file src "eca")
        zip (fs/file dir "release.zip")]
    (fs/create-dirs src)
    (spit bin (str "#!/bin/sh\necho '" version-line "'\nexit " exit-code "\n"))
    (fs/zip zip [bin] {:root (str src)})
    zip))

(defn- with-isolated-cache* [f]
  (let [tmp (fs/create-temp-dir {:prefix "eca-cli-upgrade-test-"})]
    (try
      (with-redefs [paths/getenv (fn [k] (when (= k "XDG_CACHE_HOME") (str tmp)))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- fake-download [zip]
  (fn [_url dest on-progress]
    (fs/copy zip dest {:replace-existing true})
    (on-progress {:downloaded 1 :total 1 :done? true})
    (sha256-of zip)))

(defn- release-for [zip version]
  {:version version
   :assets  {(upgrade/platform-asset) {:url "https://example.invalid/eca.zip" :sha256 (sha256-of zip)}}})

(defn- stage-dirs [dir]
  (filter #(str/starts-with? (str (fs/file-name %)) ".eca-download-") (fs/list-dir dir)))

(deftest install-test
  (with-isolated-cache*
    (fn [tmp]
      (let [zip (fake-release-zip tmp "eca 9.9.9" 0)]
        (with-redefs [upgrade/download! (fake-download zip)]
          (is (= "9.9.9" (upgrade/install! (release-for zip "9.9.9") {})))
          (testing "binary installed, executable, and runnable"
            (is (fs/executable? (paths/eca-binary)))
            (is (= "9.9.9" (upgrade/binary-version (paths/eca-binary)))))
          (testing "version marker written"
            (is (= "9.9.9" (upgrade/installed-version))))
          (testing "staging dir cleaned up"
            (is (empty? (stage-dirs (paths/eca-binary-dir))))))))))

(deftest install-rejects-bad-artifacts-test
  (with-isolated-cache*
    (fn [tmp]
      (let [good (fake-release-zip (fs/create-dirs (fs/path tmp "good")) "eca 1.0.0" 0)]
        (with-redefs [upgrade/download! (fake-download good)]
          (upgrade/install! (release-for good "1.0.0") {}))

        (testing "checksum mismatch: refused, previous install untouched"
          (let [other (fake-release-zip (fs/create-dirs (fs/path tmp "other")) "eca 2.0.0" 0)]
            (with-redefs [upgrade/download! (fake-download other)]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checksum mismatch"
                                    (upgrade/install! (release-for good "2.0.0") {}))))
            (is (= "1.0.0" (upgrade/installed-version)))
            (is (= "1.0.0" (upgrade/binary-version (paths/eca-binary))))
            (is (empty? (stage-dirs (paths/eca-binary-dir))))))

        (testing "binary that can't run here (e.g. glibc build on NixOS): refused"
          (let [broken (fake-release-zip (fs/create-dirs (fs/path tmp "broken"))
                                         "Could not start dynamically linked executable" 127)]
            (with-redefs [upgrade/download! (fake-download broken)]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exit 127"
                                    (upgrade/install! (release-for broken "3.0.0") {}))))
            (is (= "1.0.0" (upgrade/installed-version)))
            (is (= "1.0.0" (upgrade/binary-version (paths/eca-binary))))))))))

;; --- ensure-managed! ---

(defn- install-fake! [tmp version]
  (let [zip (fake-release-zip (fs/create-dirs (fs/path tmp (str "v" version))) (str "eca " version) 0)]
    (with-redefs [upgrade/download! (fake-download zip)]
      (upgrade/install! (release-for zip version) {}))))

(deftest ensure-managed-test
  (testing "fresh machine: downloads the latest release"
    (with-isolated-cache*
      (fn [tmp]
        (let [zip (fake-release-zip tmp "eca 0.167.0" 0)]
          (with-redefs [upgrade/latest-release (constantly (release-for zip "0.167.0"))
                        upgrade/download!      (fake-download zip)]
            (let [r (upgrade/ensure-managed!)]
              (is (= "0.167.0" (:version r)))
              (is (= (str (paths/eca-binary)) (:path r)))
              (is (empty? (:notices r)))))))))

  (testing "up to date: no download"
    (with-isolated-cache*
      (fn [tmp]
        (install-fake! tmp "0.167.0")
        (with-redefs [upgrade/latest-release (constantly {:version "0.167.0" :assets {}})
                      upgrade/download!      (fn [& _] (throw (ex-info "should not download" {})))]
          (is (= {:path (str (paths/eca-binary)) :version "0.167.0" :notices []}
                 (upgrade/ensure-managed!)))))))

  (testing "outdated: updates"
    (with-isolated-cache*
      (fn [tmp]
        (install-fake! tmp "0.130.0")
        (let [zip (fake-release-zip (fs/create-dirs (fs/path tmp "new")) "eca 0.167.0" 0)]
          (with-redefs [upgrade/latest-release (constantly (release-for zip "0.167.0"))
                        upgrade/download!      (fake-download zip)]
            (is (= "0.167.0" (:version (upgrade/ensure-managed!))))
            (is (= "0.167.0" (upgrade/binary-version (paths/eca-binary)))))))))

  (testing "offline with an installed binary: use it, with a notice"
    (with-isolated-cache*
      (fn [tmp]
        (install-fake! tmp "0.130.0")
        (with-redefs [upgrade/latest-release (fn [& _] (throw (ex-info "connect timed out" {})))]
          (let [r (upgrade/ensure-managed!)]
            (is (= "0.130.0" (:version r)))
            (is (= 1 (count (:notices r))))
            (is (str/includes? (first (:notices r)) "connect timed out")))))))

  (testing "update fails with an installed binary: keep it, with a notice"
    (with-isolated-cache*
      (fn [tmp]
        (install-fake! tmp "0.130.0")
        (with-redefs [upgrade/latest-release (constantly {:version "0.167.0" :assets {}})
                      upgrade/download!      (fn [& _] (throw (ex-info "HTTP 502" {})))]
          (let [r (upgrade/ensure-managed!)]
            (is (= "0.130.0" (:version r)))
            (is (str/includes? (first (:notices r)) "HTTP 502")))))))

  (testing "offline with nothing installed: actionable error"
    (with-isolated-cache*
      (fn [_]
        (with-redefs [upgrade/latest-release (fn [& _] (throw (ex-info "UnknownHostException" {})))]
          (let [e (try (upgrade/ensure-managed!) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e))
            (is (str/includes? (ex-message e) "UnknownHostException"))
            (is (str/includes? (ex-message e) "--eca"))))))))

;; --- resolve-binary! ---

(deftest resolve-binary-test
  (testing "--eca pointing at an executable is used as-is"
    (with-isolated-cache*
      (fn [tmp]
        (let [bin (fs/file tmp "my-eca")]
          (spit bin "#!/bin/sh\necho 'eca 1.2.3'\n")
          (fs/set-posix-file-permissions bin "rwxr-xr-x")
          (is (= {:path (str bin) :source :flag :notices []}
                 (upgrade/resolve-binary! {:eca (str bin)} {})))))))

  (testing "--eca pointing at nothing usable is an error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an executable"
                          (upgrade/resolve-binary! {:eca "/definitely/not/here/eca"} {}))))

  (testing "no --eca and no `eca` on PATH: managed binary"
    (with-redefs [fs/which                (constantly nil)
                  upgrade/ensure-managed! (constantly {:path "/managed/eca" :version "1" :notices []})]
      (is (= {:path "/managed/eca" :version "1" :notices [] :source :managed}
             (upgrade/resolve-binary! {} {}))))))
