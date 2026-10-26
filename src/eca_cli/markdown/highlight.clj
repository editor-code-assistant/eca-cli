(ns eca-cli.markdown.highlight
  "Lightweight syntax highlighting for fenced code blocks.

  Not a parser: a per-line scanner driven by a small table per language
  family (comment and string syntax, keywords, constants). Block comments and
  multi-line strings carry over to the next line. Unknown languages come back
  unstyled. Output is one vector of segments per line, `{:text s :style
  role-or-nil}`, for the markdown renderer to wrap and paint."
  (:require [clojure.string :as str]))

(def ^:private lisp-keywords
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol" "defrecord"
    "deftype" "defonce" "definterface" "fn" "fn*" "let" "letfn" "if" "if-let" "if-not"
    "if-some" "when" "when-let" "when-not" "when-some" "when-first" "cond" "condp" "case"
    "do" "loop" "recur" "ns" "require" "import" "use" "try" "catch" "finally" "throw"
    "quote" "var" "binding" "doseq" "dotimes" "for" "while" "->" "->>" "some->" "some->>"
    "cond->" "cond->>" "as->" "doto" "new" "set!" "and" "or" "not" "lambda" "define"
    "let*" "progn" "setq" "defun" "defvar" "defcustom" "reify" "proxy" "extend-protocol"
    "extend-type" "comment"})

(def ^:private c-keywords
  #{"if" "else" "for" "while" "do" "return" "function" "const" "let" "var" "class" "new"
    "delete" "import" "export" "from" "as" "async" "await" "try" "catch" "finally" "throw"
    "throws" "switch" "case" "default" "break" "continue" "extends" "implements"
    "interface" "type" "enum" "public" "private" "protected" "static" "final" "abstract"
    "void" "int" "long" "short" "byte" "boolean" "bool" "char" "float" "double" "struct"
    "impl" "fn" "pub" "use" "mod" "match" "loop" "where" "trait" "package" "func" "defer"
    "go" "chan" "select" "range" "map" "in" "of" "instanceof" "typeof" "yield" "super"
    "override" "virtual" "namespace" "using" "template" "typename" "unsafe" "mut" "ref"
    "dyn" "crate" "extern" "sizeof" "goto" "volatile" "signed" "unsigned" "auto" "inline"
    "constexpr" "readonly" "declare" "module" "val" "when" "object" "companion" "data"
    "sealed" "suspend" "string" "number" "any" "unknown" "never"})

(def ^:private python-keywords
  #{"def" "class" "if" "elif" "else" "for" "while" "return" "import" "from" "as" "with"
    "try" "except" "finally" "raise" "lambda" "yield" "pass" "break" "continue" "in" "is"
    "not" "and" "or" "global" "nonlocal" "async" "await" "assert" "del" "match" "case"})

(def ^:private shell-keywords
  #{"if" "then" "else" "elif" "fi" "for" "while" "until" "do" "done" "case" "esac"
    "function" "in" "select" "return" "export" "local" "readonly" "declare" "unset"
    "source" "alias" "set" "shift" "exit"})

(def ^:private sql-keywords
  #{"select" "from" "where" "and" "or" "not" "insert" "into" "values" "update" "set"
    "delete" "create" "table" "index" "view" "drop" "alter" "join" "left" "right" "inner"
    "outer" "full" "on" "as" "group" "by" "order" "having" "limit" "offset" "union" "all"
    "distinct" "case" "when" "then" "else" "end" "is" "null" "in" "exists" "between"
    "like" "primary" "key" "foreign" "references" "default" "begin" "commit" "rollback"
    "with" "returning" "asc" "desc" "count" "sum" "avg" "min" "max"})

(def ^:private families
  {:lisp   {:line-comment ";" :strings "\"" :keyword-prefix \:
            :keywords lisp-keywords :constants #{"true" "false" "nil"}
            :ident-chars "-_*+!?<>=/.$%&'#"}
   :c      {:line-comment "//" :block-comment ["/*" "*/"] :strings "\"'`"
            :keywords c-keywords :types? true
            :constants #{"true" "false" "null" "nil" "undefined" "NaN" "this" "self"
                         "None" "Some" "Ok" "Err" "iota"}}
   :python {:line-comment "#" :strings "\"'" :triple-quotes? true
            :keywords python-keywords :types? true
            :constants #{"True" "False" "None" "self" "cls"}}
   :shell  {:line-comment "#" :strings "\"'" :variables? true
            :keywords shell-keywords :constants #{"true" "false"}}
   :conf   {:line-comment "#" :strings "\"'" :keys? true :ident-chars "-_$."
            :constants #{"true" "false" "null" "yes" "no" "on" "off" "~"}}
   :json   {:strings "\"" :keys? true :constants #{"true" "false" "null"}}
   :sql    {:line-comment "--" :strings "'\"" :keywords sql-keywords
            :case-insensitive? true :constants #{"true" "false"}}
   :diff   {:diff? true}})

(def ^:private lang->family
  (into {}
        (for [[family langs] {:lisp   ["clojure" "clj" "cljs" "cljc" "edn" "bb" "babashka"
                                       "lisp" "elisp" "emacs-lisp" "scheme" "racket" "fennel"
                                       "janet" "hy"]
                              :c      ["javascript" "js" "jsx" "mjs" "cjs" "typescript" "ts"
                                       "tsx" "java" "kotlin" "kt" "go" "golang" "rust" "rs"
                                       "c" "h" "cpp" "c++" "cc" "hpp" "cs" "csharp" "swift"
                                       "scala" "dart" "php" "groovy" "zig" "solidity"]
                              :python ["python" "py" "python3" "ruby" "rb" "elixir" "ex"
                                       "exs" "r" "julia" "nim" "crystal"]
                              :shell  ["sh" "bash" "zsh" "shell" "console" "fish" "ksh"
                                       "powershell" "ps1" "dockerfile" "docker" "make"
                                       "makefile"]
                              :conf   ["yaml" "yml" "toml" "ini" "conf" "cfg" "properties"
                                       "env" "dotenv" "nix" "hcl" "terraform" "tf"]
                              :json   ["json" "jsonc" "json5" "geojson"]
                              :sql    ["sql" "psql" "postgres" "postgresql" "mysql" "sqlite"
                                       "plsql"]
                              :diff   ["diff" "patch" "udiff"]}
              lang langs]
          [lang family])))

(defn family-of
  "The family table for a fence's info string (`clojure`, `ts title=x`, …)."
  [info]
  (some-> info str/trim (str/split #"[\s,{]" 2) first str/lower-case lang->family families))

(defn- ident-char? [fam ^Character c]
  (or (Character/isLetterOrDigit c) (= \_ c)
      (some? (str/index-of (or (:ident-chars fam) "$") c))))

(defn- string-end
  "Index just past the closing `delim` starting the scan at `i`, honouring
  backslash escapes, or nil when the string runs past the end of the line."
  [^String line i ^String delim]
  (loop [j i]
    (cond
      (>= j (count line)) nil
      (= \\ (.charAt line j)) (recur (+ j 2))
      (.startsWith line delim j) (+ j (count delim))
      :else (recur (inc j)))))

(defn- classify-word
  "Role for an identifier, or nil. `key-position?`: it starts the line (after
  indentation or a `- ` list marker) and a `:`/`=` follows it."
  [fam word key-position?]
  (let [w (if (:case-insensitive? fam) (str/lower-case word) word)]
    (cond
      (contains? (:constants fam) word)                  :syn-constant
      (contains? (:keywords fam) w)                      :syn-keyword
      (and (:keys? fam) key-position?)                   :syn-type
      (and (:types? fam)
           (Character/isUpperCase (.charAt ^String word 0))
           (some #(Character/isLowerCase ^Character %) word)) :syn-type
      :else nil)))

(defn- scan-line
  "[segments next-state] for one line. State is nil, or the open construct
  carried from the previous line: {:in :comment|:string :delim s}."
  [fam ^String line state]
  (let [n    (count line)
        segs (java.util.ArrayList.)
        emit (fn [style from to]
               (when (< from to) (.add segs {:text (subs line from to) :style style})))]
    (loop [i 0, plain 0, state state]
      (if (>= i n)
        (do (emit nil plain n) [(vec segs) state])
        (let [c (.charAt line i)
              bc (:block-comment fam)]
          (cond
            ;; inside a block comment / string: `plain` marks where it opened
            ;; (its delimiter, or the line start when carried over)
            (= :comment (:in state))
            (let [end (str/index-of line (:delim state) i)
                  to  (if end (+ end (count (:delim state))) n)]
              (emit :syn-comment plain to)
              (recur to to (when-not end state)))

            (= :string (:in state))
            (let [end (string-end line i (:delim state))
                  to  (or end n)]
              (emit :syn-string plain to)
              (recur to to (when-not end state)))

            (and (:line-comment fam) (.startsWith line ^String (:line-comment fam) i)
                 (or (not= "#" (:line-comment fam)) (zero? i)
                     (Character/isWhitespace (.charAt line (dec i)))))
            (do (emit nil plain i) (emit :syn-comment i n) (recur n n state))

            (and bc (.startsWith line ^String (first bc) i))
            (do (emit nil plain i)
                (recur (+ i (count (first bc))) i {:in :comment :delim (second bc)}))

            (and (:triple-quotes? fam) (or (.startsWith line "\"\"\"" i) (.startsWith line "'''" i)))
            (do (emit nil plain i)
                (recur (+ i 3) i {:in :string :delim (subs line i (+ i 3))}))

            (some? (str/index-of (or (:strings fam) "") c))
            (do (emit nil plain i)
                (recur (inc i) i {:in :string :delim (str c)}))

            (and (:variables? fam) (= \$ c) (< (inc i) n)
                 (let [d (.charAt line (inc i))] (or (ident-char? fam d) (= \{ d))))
            (let [end (if (= \{ (.charAt line (inc i)))
                        (or (some-> (str/index-of line "}" i) inc) n)
                        (loop [j (inc i)] (if (and (< j n) (ident-char? fam (.charAt line j))) (recur (inc j)) j)))]
              (emit nil plain i) (emit :syn-constant i end) (recur end end state))

            (and (Character/isDigit c)
                 (or (zero? i) (not (ident-char? fam (.charAt line (dec i))))))
            (let [end (loop [j i] (if (and (< j n) (let [d (.charAt line j)]
                                                     (or (Character/isLetterOrDigit d) (#{\. \_} d))))
                                    (recur (inc j)) j))]
              (emit nil plain i) (emit :syn-number i end) (recur end end state))

            (and (:keyword-prefix fam) (= (:keyword-prefix fam) c) (< (inc i) n)
                 (ident-char? fam (.charAt line (inc i)))
                 (or (zero? i) (not (ident-char? fam (.charAt line (dec i))))))
            (let [end (loop [j (inc i)] (if (and (< j n) (ident-char? fam (.charAt line j))) (recur (inc j)) j))]
              (emit nil plain i) (emit :syn-constant i end) (recur end end state))

            (and (ident-char? fam c) (or (zero? i) (not (ident-char? fam (.charAt line (dec i))))))
            (let [end   (loop [j i] (if (and (< j n) (ident-char? fam (.charAt line j))) (recur (inc j)) j))
                  word  (subs line i end)
                  key?  (and (:keys? fam)
                             (re-matches #"[\s-]*" (subs line 0 i))
                             (re-find #"^\s*[:=]" (subs line end)))
                  style (classify-word fam word key?)]
              (if style
                (do (emit nil plain i) (emit style i end) (recur end end state))
                (recur end plain state)))

            :else (recur (inc i) plain state)))))))

(defn- diff-line [line]
  [{:text line
    :style (cond
             (str/starts-with? line "@@")  :diff-hunk
             (str/starts-with? line "+++") :bold
             (str/starts-with? line "---") :bold
             (str/starts-with? line "+")   :diff-add
             (str/starts-with? line "-")   :diff-del
             :else nil)}])

(defn highlight
  "Segments per line for `lines` of code in the language named by `info`."
  [info lines]
  (let [fam (family-of info)]
    (cond
      (nil? fam)    (mapv (fn [l] [{:text l :style nil}]) lines)
      (:diff? fam)  (mapv diff-line lines)
      :else
      (loop [[line & more] lines, state nil, out []]
        (if (nil? line)
          out
          (let [[segs state'] (scan-line fam line state)]
            (recur more state' (conj out segs))))))))
