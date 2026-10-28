(ns eca-cli.test-support
  "Fixtures shared by the test namespaces."
  (:require [clojure.java.io :as io]
            [eca-cli.paths :as paths]))

(defn isolated-chats-file
  "`:each` fixture: remembered chat ids and recent models go to temp files,
  never to the user's real ones (opening, forking or deleting a chat, or
  picking a model, writes them)."
  [t]
  (let [f      (doto (java.io.File/createTempFile "eca-cli-chats" ".edn") (.deleteOnExit))
        models (io/file (str f ".models"))
        none   (io/file (str f ".absent"))]
    (try
      (with-redefs [paths/chats-file           (constantly f)
                    paths/recent-models-file   (constantly models)
                    paths/legacy-chats-file    (constantly none)
                    paths/legacy-sessions-file (constantly none)]
        (t))
      (finally (.delete models)))))
