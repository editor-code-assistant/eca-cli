(ns eca-cli.upgrade
  (:require [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def eca-version "0.130.0")

(defn platform-asset
  "Returns the release asset filename for the given os-name and arch."
  ([] (platform-asset (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([os-name arch]
   (let [os (str/lower-case os-name)]
     (cond
       (and (str/includes? os "linux")  (#{"amd64" "x86_64"} arch)) "eca-native-linux-amd64.zip"
       (and (str/includes? os "linux")  (= arch "aarch64"))         "eca-native-linux-aarch64.zip"
       (and (str/includes? os "mac os") (= arch "aarch64"))         "eca-native-macos-aarch64.zip"
       (and (str/includes? os "mac os") (#{"amd64" "x86_64"} arch)) "eca-native-macos-amd64.zip"
       :else (throw (ex-info (str "Unsupported platform: " os-name " " arch) {}))))))

(defn dest-path []
  (str (System/getProperty "user.home") "/.cache/eca/eca-cli/eca"))

(defn check-version
  "Returns a warning string if binary version doesn't match eca-version, else nil."
  [binary]
  (try
    (let [r   (deref (proc/process [binary "--version"] {:out :string :err :string}))
          out (str/trim (or (:out r) ""))
          v   (second (re-find #"eca (\S+)" out))]
      (when (and v (not= v eca-version))
        (str "⚠ ECA version mismatch: running " v ", expected " eca-version
             ". Run `bb upgrade-eca` to update.")))
    (catch Exception _ nil)))

(defn run! []
  (let [asset   (platform-asset)
        url     (str "https://github.com/editor-code-assistant/eca/releases/download/"
                     eca-version "/" asset)
        tmp-zip "/tmp/eca-download.zip"
        tmp-dir "/tmp/eca-extract"]
    (println (str "Downloading ECA " eca-version " (" asset ")..."))
    (let [r (deref (proc/process ["curl" "-fsSL" "-o" tmp-zip url]
                                 {:out :inherit :err :inherit}))]
      (when (not= 0 (:exit r))
        (throw (ex-info "Download failed" {}))))
    (io/make-parents (dest-path))
    (.mkdirs (java.io.File. tmp-dir))
    (deref (proc/process ["unzip" "-o" tmp-zip "-d" tmp-dir]
                         {:out :inherit :err :inherit}))
    (let [extracted (java.io.File. (str tmp-dir "/eca"))
          dest      (java.io.File. (dest-path))]
      (when-not (.exists extracted)
        (throw (ex-info (str "Expected binary not found after unzip: " (.getAbsolutePath extracted)) {})))
      (.setExecutable extracted true)
      (io/copy extracted dest)
      (.setExecutable dest true))
    (println (str "Installed to " (dest-path)))
    (let [r (deref (proc/process [(dest-path) "--version"]
                                 {:out :string :err :string}))]
      (println (str/trim (:out r))))))
