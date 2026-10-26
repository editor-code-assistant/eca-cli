(ns eca-cli.dist-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [eca-cli.dist :as dist]
            [eca-cli.upgrade :as upgrade]
            [eca-cli.version :as version])
  (:import [java.util.zip ZipEntry ZipInputStream ZipOutputStream]))

(defn- write-zip! [f entries]
  (with-open [zout (ZipOutputStream. (io/output-stream (fs/file f)))]
    (doseq [[name content] entries]
      (.putNextEntry zout (ZipEntry. ^String name))
      (.write zout (.getBytes ^String content "UTF-8"))
      (.closeEntry zout))))

(defn- read-zip [f]
  (with-open [zin (ZipInputStream. (io/input-stream (fs/file f)))]
    (loop [acc []]
      (if-let [entry (.getNextEntry zin)]
        ;; Not slurp: it would close the stream after the first entry.
        (let [content (java.io.ByteArrayOutputStream.)]
          (io/copy zin content)
          (recur (conj acc [(.getName entry) (.toString content "UTF-8")])))
        acc))))

(deftest stamp-version-test
  (fs/with-temp-dir [dir {}]
    (let [in  (fs/path dir "in.jar")
          out (fs/path dir "out.jar")]
      (write-zip! in [["META-INF/MANIFEST.MF" "Main-Class: eca_cli.core\n"]
                      ["eca_cli/core.clj" "(ns eca-cli.core)"]
                      [version/resource-name "0.1.1\n"]])
      (dist/stamp-version! in out "0.1.42")
      (is (= [["META-INF/MANIFEST.MF" "Main-Class: eca_cli.core\n"]
              ["eca_cli/core.clj" "(ns eca-cli.core)"]
              [version/resource-name "0.1.42\n"]]
             (read-zip out))
          "keeps every entry in order, replaces a stale version"))))

(deftest assemble-executable-test
  (fs/with-temp-dir [dir {}]
    (let [bb  (fs/path dir "bb")
          jar (fs/path dir "app.jar")
          exe (fs/path dir "out" "eca-cli")]
      (spit (fs/file bb) "BINARY")
      (spit (fs/file jar) "JAR")
      (dist/assemble-executable! bb jar exe)
      (is (= "BINARYJAR" (slurp (fs/file exe))) "the jar is appended to the binary")
      (is (fs/executable? exe)))))

(deftest sha256-test
  (fs/with-temp-dir [dir {}]
    (let [f (fs/path dir "eca-cli-linux-amd64.tar.gz")]
      (spit (fs/file f) "abc")
      (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
             (dist/file-sha256 f)))
      (testing "the .sha256 file works with `sha256sum -c` and our own parser"
        (dist/write-sha256! f)
        (let [content (slurp (str f ".sha256"))]
          (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  eca-cli-linux-amd64.tar.gz\n"
                 content))
          (is (= (dist/file-sha256 f) (upgrade/parse-sha256 content))))))))

(deftest platforms-test
  (testing "x86_64 Linux embeds the fully static (musl) babashka, so it runs on NixOS and musl distros"
    (is (= "linux-amd64-static" (dist/platform-flavours "linux-amd64"))))
  (is (= #{"linux-amd64" "linux-aarch64" "macos-amd64" "macos-aarch64"}
         (set (keys dist/platform-flavours))))
  (is (= "https://github.com/babashka/babashka/releases/download/v1.13.228/babashka-1.13.228-macos-aarch64.tar.gz"
         (dist/bb-archive-url "1.13.228" "macos-aarch64"))))
