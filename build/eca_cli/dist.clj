(ns eca-cli.dist
  "Release artifacts, built by `bb dist` (CI runs it on every push to main).

  A self-contained executable is a babashka binary with the eca-cli uberjar
  appended: at startup bb finds the jar glued to itself and runs its -main
  (https://github.com/babashka/babashka/wiki/Self-contained-executable), so
  users need neither bb nor a JVM. Building is plain file plumbing, which
  lets one machine produce every platform; CI then smoke-tests each binary
  on its own OS.

  Output, in dist/:
    eca-cli-<platform>.tar.gz  the `eca-cli` executable
    eca-cli.jar                the uberjar, runs anywhere with `bb eca-cli.jar`
    <file>.sha256              checksums, `sha256sum -c` compatible"
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [eca-cli.upgrade :as upgrade]
            [eca-cli.version :as version])
  (:import [java.security MessageDigest]
           [java.util.zip ZipEntry ZipInputStream ZipOutputStream]))

(def version-line
  "Release line. Every push to main ships `<line>.<commit count>`; bump it by
  hand to start a new line (e.g. \"0.2\" or \"1.0\")."
  "0.1")

(def platform-flavours
  "eca-cli platform -> babashka release flavour. Linux gets bb's static
  builds: fully static (musl) on x86_64, so it runs on any distro, NixOS and
  Alpine included; arm64 is only mostly static (needs glibc), and the only
  arm64 Linux build bb publishes."
  {"linux-amd64"   "linux-amd64-static"
   "linux-aarch64" "linux-aarch64-static"
   "macos-amd64"   "macos-amd64"
   "macos-aarch64" "macos-aarch64"})

(def target-dir "target")
(def dist-dir "dist")

;; --- Version ---

(defn- git [& args]
  (let [{:keys [exit out err]} (p/sh (into ["git"] args))]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed: " (str/trim err)) {})))
    (str/trim out)))

(defn release-version
  "`<version-line>.<number of commits up to HEAD>`: grows with every push to
  main and is reproducible from the commit alone."
  []
  (when (= "true" (git "rev-parse" "--is-shallow-repository"))
    (throw (ex-info (str "Shallow clone: the release version counts commits, so it needs the "
                         "full history (git fetch --unshallow, or fetch-depth: 0 in CI).")
                    {})))
  (str version-line "." (git "rev-list" "--count" "HEAD")))

;; --- Files ---

(defn file-sha256 [f]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (fs/file f))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn write-sha256!
  "Write `<f>.sha256` as `<hex>  <file name>`."
  [f]
  (spit (str f ".sha256") (str (file-sha256 f) "  " (fs/file-name f) "\n")))

(defn stamp-version!
  "Copy jar `in` to `out`, (re)placing the version resource with `version`."
  [in out version]
  (with-open [zin  (ZipInputStream. (io/input-stream (fs/file in)))
              zout (ZipOutputStream. (io/output-stream (fs/file out)))]
    (loop []
      (when-let [entry (.getNextEntry zin)]
        (when-not (= version/resource-name (.getName entry))
          (.putNextEntry zout (ZipEntry. (.getName entry)))
          (io/copy zin zout)
          (.closeEntry zout))
        (recur)))
    (.putNextEntry zout (ZipEntry. version/resource-name))
    (.write zout (.getBytes (str version "\n") "UTF-8"))
    (.closeEntry zout))
  out)

(defn assemble-executable!
  "Write the babashka binary followed by the uberjar to `out`, executable."
  [bb jar out]
  (fs/create-dirs (fs/parent out))
  (with-open [o (io/output-stream (fs/file out))]
    (io/copy (fs/file bb) o)
    (io/copy (fs/file jar) o))
  (fs/set-posix-file-permissions out "rwxr-xr-x")
  out)

;; --- Build steps ---

(defn- build-uberjar!
  "`bb uberjar` from bb.edn's :paths and :deps (tests aren't on them), with
  `version` stamped in."
  [version out]
  (let [raw (fs/path target-dir "eca-cli-unstamped.jar")]
    (fs/create-dirs target-dir)
    (fs/delete-if-exists raw)
    (p/shell "bb" "uberjar" (str raw) "-m" "eca-cli.core")
    (stamp-version! raw out version)))

(defn bb-archive-name [bb-version flavour]
  (str "babashka-" bb-version "-" flavour ".tar.gz"))

(defn bb-archive-url [bb-version flavour]
  (str "https://github.com/babashka/babashka/releases/download/v" bb-version "/"
       (bb-archive-name bb-version flavour)))

(defn- fetch-bb-archive!
  "Babashka's release archive for `flavour`, downloaded once into target/ and
  verified against the .sha256 published next to it."
  [bb-version flavour]
  (let [url     (bb-archive-url bb-version flavour)
        archive (fs/path target-dir "bb" (bb-archive-name bb-version flavour))]
    (when-not (fs/exists? archive)
      (let [part (fs/path (str archive ".part"))]
        (fs/create-dirs (fs/parent archive))
        (println "Downloading" url)
        (let [actual   (upgrade/download! url part (fn [_]))
              expected (upgrade/fetch-published-sha256 url)]
          (when-not (= expected actual)
            (fs/delete-if-exists part)
            (throw (ex-info (str "Checksum mismatch for " url
                                 " (expected " (or expected "none published") ", got " actual ")")
                            {})))
          (fs/move part archive {:replace-existing true}))))
    archive))

(defn- tar-owner-flags
  "Archive entries as root:root, not the build user: GNU tar run as root (e.g.
  `sudo tar -x` into /usr/local/bin) restores the recorded owner. Only GNU tar
  (CI) is handled; other tars archive as-is."
  []
  (if (str/includes? (:out (p/sh ["tar" "--version"])) "GNU tar")
    ["--owner=0" "--group=0" "--numeric-owner"]
    []))

(defn- build-platform!
  "dist/eca-cli-<platform>.tar.gz holding the self-contained `eca-cli`."
  [bb-version jar platform]
  (let [archive (fetch-bb-archive! bb-version (platform-flavours platform))
        stage   (fs/path target-dir "stage" platform)
        out     (fs/path dist-dir (str "eca-cli-" platform ".tar.gz"))]
    (fs/delete-tree stage)
    (fs/create-dirs stage)
    (p/shell "tar" "-xzf" (str archive) "-C" (str stage) "bb")
    (assemble-executable! (fs/path stage "bb") jar (fs/path stage "eca-cli"))
    (fs/delete (fs/path stage "bb"))
    (apply p/shell "tar" (concat ["-czf" (str out)] (tar-owner-flags) ["-C" (str stage) "eca-cli"]))
    (write-sha256! out)
    out))

;; --- bb dist ---

(def ^:private dist-spec
  ;; :string, or babashka.cli would turn a version like "0.2" into a number.
  {:version    {:desc "Version to stamp (default: `bb release-version`)" :ref "<version>" :coerce :string}
   :platforms  {:desc   (str "Comma-separated subset of " (str/join "," (sort (keys platform-flavours))))
                :ref    "<list>"
                :coerce :string}
   :bb-version {:desc   "Babashka release to embed (default: the one running this task)"
                :ref    "<version>"
                :coerce :string}})

(defn dist!
  "`bb dist [--version <v>] [--platforms <list>] [--bb-version <v>]`"
  [args]
  (let [opts       (cli/parse-opts args {:spec dist-spec :restrict true})
        version    (or (:version opts) (release-version))
        bb-version (or (:bb-version opts) (System/getProperty "babashka.version"))
        platforms  (if-let [ps (:platforms opts)]
                     (str/split ps #",")
                     (sort (keys platform-flavours)))
        jar        (fs/path dist-dir "eca-cli.jar")]
    (when-let [unknown (seq (remove platform-flavours platforms))]
      (throw (ex-info (str "Unknown platform(s): " (str/join ", " unknown)) {})))
    (fs/delete-tree dist-dir)
    (fs/create-dirs dist-dir)
    (println "Building eca-cli" version "on babashka" bb-version)
    (build-uberjar! version jar)
    (write-sha256! jar)
    (doseq [platform platforms]
      (build-platform! bb-version jar platform))
    (doseq [f (sort (map str (fs/list-dir dist-dir)))]
      (println " " f))))
