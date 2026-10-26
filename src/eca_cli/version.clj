(ns eca-cli.version
  "eca-cli's own version. `bb dist` stamps it into the release jar as the
  ECA_CLI_VERSION resource; running from a checkout (`bb run`) reports \"dev\"."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def resource-name "ECA_CLI_VERSION")

(def version
  (or (some-> (io/resource resource-name) slurp str/trim not-empty)
      "dev"))
