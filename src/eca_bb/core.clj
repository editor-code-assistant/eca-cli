(ns eca-bb.core
  (:require [babashka.cli :as cli]
            [babashka.nrepl.server :as nrepl-server]
            [charm.program :as program]
            [eca-bb.state :as state]
            [eca-bb.view :as view]))

(def ^:private cli-spec
  {:trust     {:desc "Auto-approve all tool calls" :coerce :boolean}
   :workspace {:desc "Workspace path" :default (System/getProperty "user.dir")}
   :model     {:desc "Model to use"}
   :agent     {:desc "Agent to use"}
   :eca       {:desc "Path to ECA binary"}
   :nrepl     {:desc "Start nREPL server on given port" :coerce :int}})

(defn- redirect-stdio-to-file! [path]
  (let [fos (java.io.FileOutputStream. (java.io.File. path) true)
        ps  (java.io.PrintStream. fos true)
        pw  (java.io.PrintWriter. fos true)]
    (System/setOut ps)
    (System/setErr ps)
    (alter-var-root #'*out* (constantly pw))
    (alter-var-root #'*err* (constantly pw))))

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec cli-spec})]
    (when-let [port (:nrepl opts)]
      (println (str "Starting nREPL server on port " port "..."))
      (redirect-stdio-to-file! (str (System/getProperty "user.home") "/.cache/eca/eca-bb-nrepl.log"))
      (nrepl-server/start-server! {:host "localhost" :port port :quiet true}))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (print "\033[?25h")
                                 (flush))))
    (program/run {:init       (state/make-init opts)
                  :update     state/update-state
                  :view       view/view
                  :alt-screen true
                  :mouse      :normal
                  :fps        20})))
