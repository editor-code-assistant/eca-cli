(ns eca-cli.test-support
  "Fixtures shared by the test namespaces."
  (:require [clojure.java.io :as io]
            [eca-cli.paths :as paths]))

(defn isolated-chats-file
  "`:each` fixture: remembered chat ids go to a temp file, never to the
  user's real chats file (opening, forking or deleting a chat writes it)."
  [t]
  (let [f    (doto (java.io.File/createTempFile "eca-cli-chats" ".edn") (.deleteOnExit))
        none (io/file (str f ".absent"))]
    (with-redefs [paths/chats-file           (constantly f)
                  paths/legacy-chats-file    (constantly none)
                  paths/legacy-sessions-file (constantly none)]
      (t))))
