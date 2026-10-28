(ns eca-cli.editor
  "Ctrl+G: write the prompt in a real editor ($VISUAL, else $EDITOR, else
  vi), as Claude Code, Codex and pi do. The draft goes to a temp file, the
  editor gets the terminal (`eca-cli.program/external`), and what was saved
  comes back to the input."
  (:require [babashka.fs :as fs]
            [charm.components.text-input :as ti]
            [charm.program :as program]
            [clojure.string :as str]
            [eca-cli.paths :as paths]
            [eca-cli.program :as eca-program]
            [eca-cli.view :as view]))

(defn editor-command
  "The editor to run, as a shell command: it may carry arguments
  (`code --wait`). Without $VISUAL or $EDITOR, vi, or nano where there's no
  vi (NixOS)."
  []
  (or (not-empty (str/trim (str (paths/getenv "VISUAL"))))
      (not-empty (str/trim (str (paths/getenv "EDITOR"))))
      (some #(when (fs/which %) %) ["vi" "nano"])
      "vi"))

(defn edit!
  "Run the editor on `text`, waiting for it: `{:text …}` with what was saved,
  or `{:error …}`."
  [text]
  (let [f (doto (java.io.File/createTempFile "eca-cli-prompt" ".md") (.deleteOnExit))]
    (try
      (spit f (str text))
      (let [editor (editor-command)
            exit   (-> (ProcessBuilder. ^java.util.List ["sh" "-c" (str editor " \"$1\"") "sh" (str f)])
                       .inheritIO
                       .start
                       .waitFor)]
        (if (zero? exit)
          {:text (slurp f)}
          {:error (str editor " exited with " exit ": the prompt is unchanged.")}))
      (finally (.delete f)))))

(defn edit-cmd
  "Edit `text` in the editor; the result arrives as `:editor-done`."
  [text]
  (program/cmd
    (fn [] (eca-program/external #(edit! text) #(assoc % :type :editor-done)))))

(defn handle-done
  "The editor closed: what was saved replaces the prompt (editors end the
  file with a newline, dropped). While a question is being answered, it's
  the draft kept for afterwards."
  [state {:keys [text error]}]
  (if error
    (-> state
        (update :items conj {:type :system :text (str "⚠ " error)})
        view/rebuild-lines)
    (let [text (str/replace (str text) #"\s+\z" "")]
      (if (= :asking (:mode state))
        (assoc state :input-draft text)
        (update state :input #(-> % (ti/set-value text) (assoc :pos (count text)) ti/focus))))))
