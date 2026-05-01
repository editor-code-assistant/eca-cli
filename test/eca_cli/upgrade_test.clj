(ns eca-cli.upgrade-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.upgrade :as upgrade]))

(deftest platform-asset-test
  (testing "linux amd64"
    (is (= "eca-native-linux-amd64.zip" (upgrade/platform-asset "Linux" "amd64"))))

  (testing "linux x86_64 maps to amd64 asset"
    (is (= "eca-native-linux-amd64.zip" (upgrade/platform-asset "Linux" "x86_64"))))

  (testing "linux aarch64"
    (is (= "eca-native-linux-aarch64.zip" (upgrade/platform-asset "Linux" "aarch64"))))

  (testing "macos aarch64"
    (is (= "eca-native-macos-aarch64.zip" (upgrade/platform-asset "Mac OS X" "aarch64"))))

  (testing "macos amd64"
    (is (= "eca-native-macos-amd64.zip" (upgrade/platform-asset "Mac OS X" "amd64"))))

  (testing "macos x86_64 maps to amd64 asset"
    (is (= "eca-native-macos-amd64.zip" (upgrade/platform-asset "Mac OS X" "x86_64"))))

  (testing "unknown platform throws"
    (is (thrown? Exception (upgrade/platform-asset "Windows" "amd64")))))
