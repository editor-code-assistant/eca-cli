(ns eca-cli.upgrade
  "Managed ECA server binary: resolve, download, verify, install, auto-update.

  Same contract as the editor plugins (eca-emacs, eca-vscode, eca-desktop):
  - follow the latest GitHub release of editor-code-assistant/eca, no pin;
  - per-platform native artifact; Linux x86_64 uses the *static* build so it
    also runs where generic glibc binaries can't (NixOS, musl distros);
  - sha256-verified against the digest GitHub publishes for the asset;
  - staged next to the destination, checked to actually run, then swapped in
    with an atomic rename, so a running server keeps its inode and an
    interrupted update never leaves a half-written binary behind;
  - installed version recorded in an `eca-version` marker; a missing marker
    (legacy or interrupted install) forces a reinstall;
  - when GitHub is unreachable, keep using the installed binary.

  The latest-release lookup is cached for an hour (eca-emacs' default
  `eca-server-releases-cache-ttl`) so frequent launches stay instant and well
  under GitHub's unauthenticated API rate limit."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [eca-cli.paths :as paths])
  (:import [java.security MessageDigest]))

(def releases-api-url
  "Newest release first, like the editor plugins' `releases[0]`."
  "https://api.github.com/repos/editor-code-assistant/eca/releases?per_page=1")

(def release-download-url "https://github.com/editor-code-assistant/eca/releases/download")

(def release-check-ttl-ms (* 60 60 1000))

(def ^:private stage-prefix ".eca-download-")

;; --- Platform ---

(defn platform-asset
  "Release asset filename for the given os-name and arch (JVM property values)."
  ([] (platform-asset (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([os-name arch]
   (let [os     (str/lower-case (str os-name))
         linux? (str/includes? os "linux")
         mac?   (str/includes? os "mac")
         amd64? (contains? #{"amd64" "x86_64"} arch)
         arm64? (contains? #{"aarch64" "arm64"} arch)]
     (cond
       (and linux? amd64?) "eca-native-static-linux-amd64.zip"
       (and linux? arm64?) "eca-native-linux-aarch64.zip"
       (and mac? arm64?)   "eca-native-macos-aarch64.zip"
       (and mac? amd64?)   "eca-native-macos-amd64.zip"
       :else (throw (ex-info (str "No prebuilt ECA server for " os-name " " arch
                                  "; install one yourself and pass --eca /path/to/eca")
                             {:os os-name :arch arch}))))))

;; --- Versions ---

(defn- version-parts [v]
  (mapv parse-long (re-seq #"\d+" (str v))))

(defn version-older?
  "True when version string `a` sorts strictly before `b` (numeric segments)."
  [a b]
  (let [pa  (version-parts a)
        pb  (version-parts b)
        n   (max (count pa) (count pb))
        pad #(into % (repeat (- n (count %)) 0))]
    (neg? (compare (pad pa) (pad pb)))))

(defn installed-version
  "Version recorded by the last successful managed install, or nil."
  []
  (let [f (paths/eca-version-file)]
    (when (fs/exists? f)
      (not-empty (str/trim (slurp f))))))

(defn binary-version
  "Run `binary --version` and return the version it reports. Throws ex-info
  carrying the process output when the binary can't run here (e.g. a glibc
  build on NixOS, which exits 127 with an explanation on stderr)."
  [binary]
  (let [p (try
            (proc/process [(str binary) "--version"] {:out :string :err :string})
            (catch Exception e
              (throw (ex-info (str "cannot execute " binary ": " (ex-message e)) {} e))))
        r (deref p 15000 ::timeout)]
    (when (= ::timeout r)
      (proc/destroy-tree p)
      (throw (ex-info (str binary " --version timed out") {})))
    (let [{:keys [exit out err]} r
          version (second (re-find #"eca (\S+)" (str out)))]
      (if (and (zero? exit) version)
        version
        (throw (ex-info (str binary " --version failed (exit " exit "): "
                             (str/trim (str err " " out)))
                        {:exit exit}))))))

;; --- Release lookup ---

(def ^:private http-client
  ;; No Accept-Encoding: assets must arrive byte-for-byte so the
  ;; Content-Length and sha256 checks hold.
  (delay (http/client {:follow-redirects :normal
                       :connect-timeout  10000
                       :request          {:headers {:accept     "*/*"
                                                    :user-agent "eca-cli"}}})))

(defn parse-sha256
  "Extract a lowercase sha256 hex digest from a GitHub asset `digest`
  (\"sha256:<hex>\") or a `<asset>.sha256` file (\"<hex>  <name>\")."
  [s]
  (some-> (re-find #"(?i)(?:^|sha256:|\s)([0-9a-f]{64})(?:\s|$)" (str/trim (str s)))
          second
          str/lower-case))

(defn parse-release
  "Normalise a GitHub release JSON map to {:version :assets {name {:url :sha256}}}."
  [release]
  {:version (:tag_name release)
   :assets  (into {}
                  (for [{:keys [name browser_download_url digest]} (:assets release)]
                    [name {:url    browser_download_url
                           :sha256 (parse-sha256 digest)}]))})

(defn fetch-latest-release
  "Ask GitHub for the newest ECA release. Throws on network/HTTP/payload errors."
  []
  (let [resp   (http/get releases-api-url {:client  @http-client
                                           :headers {"Accept" "application/vnd.github+json"}
                                           :timeout 15000
                                           :throw   false})
        status (:status resp)]
    (when-not (= 200 status)
      (throw (ex-info (str "GitHub releases API answered HTTP " status) {:status status})))
    (let [releases (json/parse-string (:body resp) true)
          release  (when (sequential? releases) (first releases))]
      (when-not (:tag_name release)
        (throw (ex-info "unexpected GitHub releases payload" {})))
      (parse-release release))))

(defn cached-release
  "The cached release when `cache` was written less than `ttl-ms` before `now`."
  [cache now ttl-ms]
  (let [{:keys [checked-at release]} (when (map? cache) cache)]
    (when (and (number? checked-at)
               (<= checked-at now)
               (< (- now checked-at) ttl-ms)
               (:version release))
      release)))

(defn- read-edn-file [f]
  (try
    (when (fs/exists? f) (edn/read-string (slurp (fs/file f))))
    (catch Exception _ nil)))

(defn- write-atomically!
  "Write `content` to `file` via a temp file + rename, so readers never see a
  partial write."
  [file content]
  (let [f   (fs/file file)
        tmp (fs/file (str f ".tmp-" (random-uuid)))]
    (fs/create-dirs (fs/parent f))
    (spit tmp content)
    (fs/move tmp f {:replace-existing true :atomic-move true})))

(defn latest-release
  "The newest release: from the TTL cache when fresh, else from GitHub (and
  re-cached). `:refresh? true` bypasses the cache. Throws when GitHub can't
  be reached and the cache is stale."
  ([] (latest-release {}))
  ([{:keys [refresh?]}]
   (let [now   (System/currentTimeMillis)
         cache (paths/release-check-file)]
     (or (when-not refresh?
           (cached-release (read-edn-file cache) now release-check-ttl-ms))
         (let [release (fetch-latest-release)]
           (try
             (write-atomically! cache (pr-str {:checked-at now :release release}))
             (catch Exception _ nil))
           release)))))

;; --- Install ---

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn download!
  "Stream `url` into `dest`, calling `on-progress` with
  {:downloaded n :total content-length-or-nil :done? bool}. Returns the sha256
  hex of the received bytes. Throws on HTTP errors and truncated bodies."
  [url dest on-progress]
  (let [resp   (http/get url {:client  @http-client
                              :as      :stream
                              :timeout 60000
                              :throw   false})
        status (:status resp)]
    (when-not (= 200 status)
      (some-> ^java.io.Closeable (:body resp) .close)
      (throw (ex-info (str "HTTP " status " downloading " url) {:url url :status status})))
    (let [total (some-> (get-in resp [:headers "content-length"]) parse-long)
          md    (MessageDigest/getInstance "SHA-256")
          buf   (byte-array (* 64 1024))]
      (with-open [in  ^java.io.InputStream (:body resp)
                  out (io/output-stream (fs/file dest))]
        (loop [n 0]
          (let [r (.read in buf)]
            (if (neg? r)
              (do (when (and total (not= n total))
                    (throw (ex-info (str "download truncated: got " n " of " total " bytes") {})))
                  (on-progress {:downloaded n :total total :done? true}))
              (do (.write out buf 0 r)
                  (.update md buf 0 r)
                  (on-progress {:downloaded (+ n r) :total total :done? false})
                  (recur (+ n r)))))))
      (hex (.digest md)))))

(defn fetch-published-sha256
  "Fallback for releases whose API payload lacks a digest: the `<asset>.sha256`
  file published next to every ECA artifact. nil when unavailable."
  [asset-url]
  (try
    (let [resp (http/get (str asset-url ".sha256") {:client @http-client :timeout 15000 :throw false})]
      (when (= 200 (:status resp))
        (parse-sha256 (:body resp))))
    (catch Exception _ nil)))

(defn- cleanup-stale-stages!
  "Remove staging dirs left by crashed installs. Only those older than an hour,
  so a concurrent eca-cli instance's in-flight install is left alone."
  [dir]
  (let [cutoff (- (System/currentTimeMillis) release-check-ttl-ms)]
    (doseq [p (try (fs/list-dir dir) (catch Exception _ []))]
      (when (and (str/starts-with? (str (fs/file-name p)) stage-prefix)
                 (< (.toMillis (fs/last-modified-time p)) cutoff))
        (try (fs/delete-tree p) (catch Exception _ nil))))))

(defn install!
  "Download, verify and atomically install `release` as the managed binary.
  Returns the installed version. `reporter` supplies optional `:log` (fn [msg])
  and `:on-progress` (see `download!`) callbacks."
  [{:keys [version assets]} {:keys [log on-progress]
                             :or   {log (fn [_]) on-progress (fn [_])}}]
  (let [asset-name (platform-asset)
        asset      (get assets asset-name)
        url        (or (:url asset) (str release-download-url "/" version "/" asset-name))
        dest       (fs/file (paths/eca-binary))
        dir        (fs/parent dest)]
    (fs/create-dirs dir)
    (cleanup-stale-stages! dir)
    (let [stage (fs/create-temp-dir {:dir dir :prefix stage-prefix})]
      (try
        (log (str "Downloading ECA server " version " (" asset-name ")..."))
        (let [zip      (fs/file stage asset-name)
              actual   (download! url zip on-progress)
              expected (or (:sha256 asset) (fetch-published-sha256 url))]
          (cond
            (nil? expected)
            (log "No checksum published for this release, skipping verification.")

            (not= expected actual)
            (throw (ex-info (str "checksum mismatch for " asset-name
                                 " (expected " expected ", got " actual ")")
                            {:expected expected :actual actual})))
          (fs/unzip zip stage {:replace-existing true})
          (let [staged (fs/file stage "eca")]
            (when-not (fs/exists? staged)
              (throw (ex-info (str asset-name " does not contain an `eca` binary") {})))
            (fs/set-posix-file-permissions staged "rwxr-xr-x")
            ;; Refuse to swap in a binary that can't even start on this machine.
            (binary-version staged)
            (fs/move staged dest {:replace-existing true :atomic-move true})
            (write-atomically! (paths/eca-version-file) version)
            (log (str "Installed ECA server " version " at " dest))
            version))
        (finally
          (try (fs/delete-tree stage) (catch Exception _ nil)))))))

;; --- Decision ---

(defn decide
  "Pure: what to do with the managed binary.
    :installed? managed binary exists
    :current    version from the marker file (nil when missing)
    :latest     newest release version (nil when it couldn't be fetched)
  Returns {:action :use} | {:action :install :reason kw} | {:action :error}."
  [{:keys [installed? current latest]}]
  (cond
    (and (not installed?) (nil? latest)) {:action :error}
    (not installed?)                     {:action :install :reason :missing}
    (nil? latest)                        {:action :use}
    (nil? current)                       {:action :install :reason :unknown-version}
    (version-older? current latest)      {:action :install :reason :outdated}
    :else                                {:action :use}))

(defn ensure-managed!
  "Make sure the managed ECA binary is installed and current, installing or
  updating it as needed. Returns {:path :version :notices}; `:notices` are
  user-facing warnings (e.g. offline, so the update check was skipped). Throws
  ex-info with a user-facing message only when no usable binary exists."
  ([] (ensure-managed! {}))
  ([{:keys [log] :or {log (fn [_])} :as reporter}]
   (let [binary                  (str (paths/eca-binary))
         installed?              (fs/exists? binary)
         current                 (installed-version)
         {:keys [release error]} (try {:release (latest-release)}
                                      (catch Exception e {:error (or (ex-message e) (str e))}))
         latest                  (:version release)
         {:keys [action]}        (decide {:installed? installed? :current current :latest latest})]
     (case action
       :use
       {:path    binary
        :version current
        :notices (if error
                   [(str "Couldn't check for ECA server updates (" error "), using installed version "
                         (or current "(unknown)") ".")]
                   [])}

       :install
       (try
         (when installed?
           (log (str "Updating ECA server " (or current "(unknown version)") " -> " latest)))
         {:path binary :version (install! release reporter) :notices []}
         (catch Exception e
           (if installed?
             {:path    binary
              :version current
              :notices [(str "ECA server update to " latest " failed (" (ex-message e)
                             "), using installed version " (or current "(unknown)") ".")]}
             (throw (ex-info (str "Could not install the ECA server: " (ex-message e)) {} e)))))

       :error
       (throw (ex-info (str "Could not download the ECA server (" error ").\n"
                            "Check your internet connection, or point eca-cli at an existing "
                            "binary with --eca /path/to/eca.")
                       {}))))))

;; --- Resolution ---

(defn resolve-binary!
  "Pick the ECA binary to run, in eca-emacs' order:
    1. an explicit path (`--eca`)
    2. `eca` on PATH (installed by the user, e.g. a package manager)
    3. the managed binary, auto-installed and auto-updated
  Returns {:path :source :notices}. Throws ex-info with a user-facing message
  when nothing usable can be found or installed."
  [{:keys [eca]} reporter]
  (cond
    eca
    (let [p (if (fs/exists? eca) (fs/absolutize eca) (fs/which eca))]
      (if (and p (fs/regular-file? p) (fs/executable? p))
        {:path (str p) :source :flag :notices []}
        (throw (ex-info (str "--eca " eca " is not an executable file.") {}))))

    (fs/which "eca")
    {:path (str (fs/which "eca")) :source :path :notices []}

    :else
    (assoc (ensure-managed! reporter) :source :managed)))

;; --- Console reporting (before the TUI starts, and for `bb upgrade-eca`) ---

(defn format-progress
  "\"12.3 / 28.9 MB  42%\", or \"12.3 MB\" when the total is unknown."
  [downloaded total]
  (let [mb #(format "%.1f" (/ (double %) 1048576.0))]
    (if (and total (pos? total))
      (format "%s / %s MB  %d%%" (mb downloaded) (mb total)
              (long (quot (* 100 downloaded) total)))
      (str (mb downloaded) " MB"))))

(defn console-reporter
  "Reporter printing to stderr. The progress line redraws in place (at most
  every 100ms) when attached to a terminal and is omitted otherwise."
  []
  (let [tty?       (some? (System/console))
        last-print (atom 0)]
    {:log         (fn [msg]
                    (binding [*out* *err*]
                      (println (str "eca-cli: " msg))))
     :on-progress (fn [{:keys [downloaded total done?]}]
                    (when tty?
                      (let [now (System/currentTimeMillis)]
                        (when (or done? (> (- now @last-print) 100))
                          (reset! last-print now)
                          (binding [*out* *err*]
                            (print (str "\r\033[2K  " (format-progress downloaded total)))
                            (when done? (println))
                            (flush))))))}))

;; --- bb upgrade-eca ---

(defn upgrade!
  "`bb upgrade-eca [--force]`: install the latest ECA server into the managed
  location (normally automatic on `bb run`; this forces a fresh check)."
  [& args]
  (let [{:keys [log] :as reporter} (console-reporter)
        force?                     (some #{"--force" "-f"} args)]
    (try
      (let [{:keys [version] :as release} (latest-release {:refresh? true})
            current                       (installed-version)]
        (if (and (not force?)
                 (fs/exists? (paths/eca-binary))
                 current
                 (not (version-older? current version)))
          (log (str "ECA server is up to date (" current ") at " (paths/eca-binary)))
          (install! release reporter))
        (when-let [on-path (fs/which "eca")]
          (log (str "Note: `eca` on your PATH (" on-path ") takes precedence over the managed binary."))))
      (catch Exception e
        (log (str "Upgrade failed: " (ex-message e)))
        (System/exit 1)))))
