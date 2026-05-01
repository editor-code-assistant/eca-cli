# Phase 4: Command System

> **Pre-condition:** Phase 3 complete (`bb test` passes, `/sessions`, `/new`, session picker functional).

## Goal

A slash command system that is the primary extensibility seam for eca-cli. Typing `/` opens an interactive command picker. All built-in commands live in one registry. Adding a new command means adding one entry — no other code changes.

---

## What Phase 3 Already Did

- `/model`, `/agent`, `/new`, `/sessions` are dispatched by hardcoded string comparisons in the `update-state` Enter handler.
- `:picking` mode with charm list component handles interactive selection — reused for the command picker.
- The existing login state machine handles `status:"login"` from ECA responses — `/login` will re-enter this flow from a user-initiated path.

What is NOT done yet: a command registry, autocomplete-on-`/`, `/clear`, `/help`, `/quit`, `/login`, or unknown-command error feedback.

---

## What to Build

### 1. Command registry — `src/eca_cli/commands.clj`

New namespace. Exports a single map `registry`:

```clojure
{"/model"    {:doc "Open model picker"                  :handler open-model-picker}
 "/agent"    {:doc "Open agent picker"                  :handler open-agent-picker}
 "/new"      {:doc "Start a fresh chat"                 :handler new-chat}
 "/sessions" {:doc "Browse and resume previous chats"   :handler list-sessions}
 "/clear"    {:doc "Clear chat display (local only)"    :handler clear-chat}
 "/help"     {:doc "Show available commands"            :handler show-help}
 "/quit"     {:doc "Exit eca-cli"                        :handler quit}
 "/login"    {:doc "Manually trigger provider login"    :handler login}}
```

Each handler is a pure function `(fn [state] -> [new-state cmd-or-nil])`. The handlers for `/model`, `/agent`, `/new`, `/sessions` contain the logic currently hardcoded in `state.clj`; those hardcoded branches are deleted.

**Handler discipline:** handlers must not call protocol fns directly or side-effect outside the return tuple. All side effects go through the returned cmd thunk, consistent with the Elm architecture.

**Rebuild-lines convention:** handlers that modify `:items` do NOT call `rebuild-lines` themselves. The `update-state` command executor calls `rebuild-lines` unconditionally after every handler, so handlers just `update :items`:

```clojure
;; In update-state, after handler execution:
(let [[new-state cmd] (handler state)]
  [(-> new-state rebuild-lines (update :input #(-> % ti/reset ti/focus))) cmd])
```

This keeps handlers simple and ensures `:chat-lines` is never stale.

---

### 2. Command autocomplete — `:picking :command` mode

**Trigger:** the user types `/` as the first character in an empty `:ready` input. This is intercepted before `ti/text-input-update` runs — if mode is `:ready`, input is empty, and the printable char is `/`, enter `:picking :command` with an empty query instead of forwarding to the text input.

**Behaviour in `:picking :command`:**

| Key | Effect |
|-----|--------|
| Printable char | Append to `:query`, re-filter list |
| Backspace (non-empty query) | Remove last query char, re-filter |
| Backspace (empty query) | Return to `:ready`, input cleared |
| Up / Down | Move cursor in list |
| Enter | Execute selected command's handler → may open another picker or return `:ready` |
| Escape | Return to `:ready`, input cleared |

**Filtering:** case-insensitive substring match on command name AND doc string. Example: typing `chat` surfaces `/sessions` ("Browse and resume previous chats").

**Picker state:**
```clojure
{:kind     :command
 :query    ""
 :list     <charm-list>           ; display strings
 :all      [["/model" "Open model picker"] ...]
 :filtered [["/model" "Open model picker"] ...]}  ; subset of :all matching current query
```

`:filtered` starts equal to `:all` (empty query = no filter). Updated alongside `:list` on every filter/unfilter operation — consistent with the existing picker implementation in `open-picker` and `open-session-picker`.

Display format in the list: `"/command  —  doc string"`.

**Empty match:** if the filtered list is empty, Enter is a no-op. The user can backspace or Escape.

---

### 3. Direct dispatch (typed command + Enter)

Users who type a full command and press Enter without going through the picker are also served. The `:ready` Enter handler, when input starts with `/`, looks up the text in `commands/registry` and executes the handler. If not found, appends a system error item:

```
⚠ Unknown command: /foobar  (type /help to see available commands)
```

The existing hardcoded `/model`, `/agent`, `/new`, `/sessions` branches are removed — they now live as handler fns in `commands.clj`.

---

### 4. Built-in command: `/clear`

Clears the local chat display. Does not touch the ECA chat — the chat-id is preserved and the next send continues the same session on the server.

```clojure
(defn- clear-chat [state]
  [(assoc state :items [] :chat-lines [] :scroll-offset 0) nil])
```

---

### 5. Built-in command: `/help`

Appends a system item listing all registered commands and their docs.

```clojure
(defn- show-help [state]
  (let [lines (map (fn [[name {:keys [doc]}]] (str name "  —  " doc))
                   (sort-by key commands/registry))
        text  (str/join "\n" (into ["Available commands:"] lines))]
    [(update state :items conj {:type :system :text text}) nil]))
```

---

### 6. Built-in command: `/quit`

Returns the shutdown cmd, same as Ctrl+C.

```clojure
(defn- quit [state]
  [state (shutdown-cmd (:server state))])
```

Note: `shutdown-cmd` is currently defined in `state.clj`. Either require it from a shared location, or define the quit handler inline in `state.clj` and reference it from the registry. The simplest approach is to define all handlers in `state.clj` (as private fns) and build the registry map there, exporting it from `commands.clj` as a pure data structure constructed at load time. If circular dependency is a problem, inline the registry in `state.clj` and have `commands.clj` just re-export the names.

---

### 7. Built-in command: `/login`

Manually initiates the provider login flow. Useful when an API key expires mid-session.

**Implementation:** re-use the existing `start-login-cmd` in `state.clj`. That function already calls `protocol/providers-list!` (which exists at `protocol.clj:59`), picks the first unauthenticated/expired provider, calls `protocol/providers-login!`, and dispatches `:eca-login-action` — exactly the same flow triggered automatically when ECA returns `status:"login"`. The `/login` handler just calls it with a nil pending-message:

```clojure
(defn- login [state]
  [state (start-login-cmd (:server state) nil)])
```

No new protocol functions, no new message types, no provider picker needed. If all providers are currently authenticated, `start-login-cmd` dispatches `:eca-error` with "Login required but no unauthenticated provider found" — adequate feedback for Phase 4.

**No view changes needed** — the login UI is already rendered by the existing `:login` mode handler.

---

### 8. View: command picker label

Update the `label` dispatch in `render-picker` in `view.clj`:

```clojure
label (case kind
        :model   "model"
        :agent   "agent"
        :session "chat"
        :command "command"
        "item")
```

---

## State machine additions

```
:ready  --"/" in empty input-->  :picking (kind :command)
:picking (command)  --Enter-->   execute handler
                                   → :ready  (most commands)
                                   → :picking (kind :model/:agent/:session)  (picker-launching commands)
:picking (command)  --Esc-->     :ready, input cleared
:picking (command)  --BSpace (empty query)-->  :ready, input cleared

:ready  --Enter with "/cmd"-->   registry lookup → execute handler or show error

:ready  --"/login" Enter / picker-->  fire start-login-cmd → :eca-login-action dispatched
:eca-login-action handler → :login mode (ECA drives from here, same as auto-login)
```

---

## Tests

### Unit tests (`bb test`)

#### `test/eca_cli/commands_test.clj` (new file)

```clojure
(ns eca-cli.commands-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-cli.commands :as commands]))

(deftest registry-completeness-test
  (testing "all expected commands present"
    (doseq [cmd ["/model" "/agent" "/new" "/sessions"
                 "/clear" "/help" "/quit" "/login"]]
      (is (contains? commands/registry cmd)
          (str cmd " missing from registry"))))

  (testing "each entry has non-empty :doc string"
    (doseq [[name {:keys [doc]}] commands/registry]
      (is (string? doc) (str name " :doc must be a string"))
      (is (seq doc)     (str name " :doc must be non-empty"))))

  (testing "each entry has :handler fn"
    (doseq [[name {:keys [handler]}] commands/registry]
      (is (fn? handler) (str name " :handler must be a fn")))))
```

#### `state_test.clj` additions

```clojure
;; Autocomplete trigger
(deftest slash-opens-command-picker-test
  (testing "typing '/' in empty :ready input enters :picking :command"
    (let [s0 (assoc (base-state) :mode :ready)
          [s _] (state/update-state s0 (msg/key-press "/"))]
      (is (= :picking (:mode s)))
      (is (= :command (get-in s [:picker :kind])))
      (is (= "" (get-in s [:picker :query])))
      (is (= 8 (cl/item-count (get-in s [:picker :list]))))))

  (testing "typing non-'/' in :ready does not open picker"
    (let [s0 (assoc (base-state) :mode :ready)
          [s _] (state/update-state s0 (msg/key-press "h"))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))))

  (testing "typing '/' in non-empty input does not open picker"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (assoc :input (ti/set-value (ti/text-input) "hello")))
          [s _] (state/update-state s0 (msg/key-press "/"))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s))))))

;; Command picker filtering
(deftest command-picker-filter-test
  (testing "typing narrows by case-insensitive substring on name and doc"
    (let [s0 (assoc (base-state) :mode :ready)
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          total      (cl/item-count (get-in s-pick [:picker :list]))
          [s1 _]     (state/update-state s-pick (msg/key-press "m"))]
      (is (= "m" (get-in s1 [:picker :query])))
      (is (< (cl/item-count (get-in s1 [:picker :list])) total))
      (is (pos? (cl/item-count (get-in s1 [:picker :list]))))))

  (testing "backspace on non-empty query removes char, list grows"
    (let [s0 (assoc (base-state) :mode :ready)
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          [s1 _]     (state/update-state s-pick (msg/key-press "m"))
          [s2 _]     (state/update-state s1 (msg/key-press :backspace))]
      (is (= "" (get-in s2 [:picker :query])))
      (is (= :picking (:mode s2)))))

  (testing "backspace on empty query returns to :ready"
    (let [s0 (assoc (base-state) :mode :ready)
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          [s1 _]     (state/update-state s-pick (msg/key-press :backspace))]
      (is (= :ready (:mode s1)))
      (is (nil? (:picker s1))))))

;; Escape from command picker
(deftest command-picker-escape-test
  (testing "Escape returns to :ready with cleared input"
    (let [s0 (assoc (base-state) :mode :ready)
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          [s _]      (state/update-state s-pick (msg/key-press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))
      (is (= "" (str/trim (ti/value (:input s))))))))

;; /clear
(deftest slash-clear-test
  (testing "/clear entered directly clears items and scroll"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :items [{:type :user :text "hi"}]
                        :scroll-offset 5)
                 (assoc :input (ti/set-value (ti/text-input) "/clear")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (empty? (:items s)))
      (is (= 0 (:scroll-offset s)))))

  (testing "/clear via command picker clears items"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :items [{:type :user :text "hi"}]))
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          ;; filter to 'cl' to isolate /clear
          [s1 _]     (state/update-state s-pick (msg/key-press "c"))
          [s2 _]     (state/update-state s1 (msg/key-press "l"))
          [s3 _]     (state/update-state s2 (msg/key-press :enter))]
      (is (= :ready (:mode s3)))
      (is (nil? (:picker s3)))
      (is (empty? (:items s3))))))

;; /help
(deftest slash-help-test
  (testing "/help appends system item containing all command names"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (assoc :input (ti/set-value (ti/text-input) "/help")))
          [s _] (state/update-state s0 (msg/key-press :enter))
          sys   (last (filter #(= :system (:type %)) (:items s)))]
      (is (some? sys))
      (doseq [cmd ["/model" "/agent" "/new" "/sessions"
                   "/clear" "/help" "/quit" "/login"]]
        (is (str/includes? (:text sys) cmd)
            (str cmd " missing from /help output"))))))

;; /quit
(deftest slash-quit-test
  (testing "/quit returns non-nil shutdown cmd"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (assoc :input (ti/set-value (ti/text-input) "/quit")))
          [_ cmd] (state/update-state s0 (msg/key-press :enter))]
      (is (some? cmd)))))

;; Unknown command
(deftest unknown-command-test
  (testing "unrecognised /cmd appends system error containing command text"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready)
                 (assoc :input (ti/set-value (ti/text-input) "/foobarxyzzy")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (some #(and (= :system (:type %))
                      (str/includes? (:text %) "/foobarxyzzy"))
                (:items s))))))

;; /model via registry (regression: same behaviour as Phase 2 hardcoded path)
(deftest slash-model-via-registry-test
  (testing "/model via direct Enter still opens model picker"
    (let [s0 (-> (base-state)
                 (assoc :mode :ready
                        :available-models ["anthropic/claude-sonnet-4-6"])
                 (assoc :input (ti/set-value (ti/text-input) "/model")))
          [s _] (state/update-state s0 (msg/key-press :enter))]
      (is (= :picking (:mode s)))
      (is (= :model (get-in s [:picker :kind])))))

  (testing "/model via command picker opens model picker"
    (let [s0 (assoc (base-state) :mode :ready
                    :available-models ["anthropic/claude-sonnet-4-6"])
          [s-pick _] (state/update-state s0 (msg/key-press "/"))
          [s1 _]     (state/update-state s-pick (msg/key-press "m"))
          ;; navigate to /model if not already top
          [s2 _]     (state/update-state s1 (msg/key-press :enter))]
      (is (= :picking (:mode s2)))
      (is (= :model (get-in s2 [:picker :kind]))))))
```

#### `view_test.clj` additions

```clojure
(testing "command picker renders 'Select command' label"
  (let [s (assoc base-state
                 :mode :picking
                 :picker {:kind :command :query "m"
                          :list (cl/item-list ["/model  —  Open model picker"] :height 8)})
        rendered (view/view s)]
    (is (str/includes? rendered "Select command"))))

```

---

### Integration tests (`bb itest`)

Add to `test/eca_cli/integration_test.clj` under a Phase 4 section:

```clojure
;; ---------------------------------------------------------------------------
;; Phase 4 — command system
;; ---------------------------------------------------------------------------

(deftest phase4-command-picker-opens-test
  (start! itest-cmd)
  (try
    (testing "typing '/' opens command picker"
      (keys! "/")
      (let [s (wait-for! (has "Select command") 3000)]
        (is (str/includes? s "Select command"))))
    (testing "typing to filter narrows list — 'm' shows model, hides quit"
      (keys! "m")
      (Thread/sleep 200)
      (is (str/includes? (screen) "model"))
      (is (not (str/includes? (screen) "/quit"))))
    (testing "Escape closes picker, returns to SAFE"
      (keys! "Escape")
      (let [s (wait-for! (lacks "Select command") 3000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))

(deftest phase4-clear-command-test
  (start! itest-cmd)
  (try
    (testing "/clear removes previous chat content from display"
      (keys! "hello-clear-xyzzy" "Enter")
      (wait-for-ready!)
      (keys! "/clear" "Enter")
      (Thread/sleep 300)
      (is (not (str/includes? (screen) "hello-clear-xyzzy"))))
    (finally (kill!))))

(deftest phase4-help-command-test
  (start! itest-cmd)
  (try
    (testing "/help shows command listing in chat"
      (keys! "/help" "Enter")
      (Thread/sleep 300)
      (let [s (screen)]
        (is (str/includes? s "/model"))
        (is (str/includes? s "/quit"))))
    (finally (kill!))))

(deftest phase4-unknown-command-test
  (start! itest-cmd)
  (try
    (testing "unknown /cmd shows error containing command text"
      (keys! "/notacommandxyzzy" "Enter")
      (Thread/sleep 300)
      (is (str/includes? (screen) "notacommandxyzzy")))
    (finally (kill!))))

(deftest phase4-command-picker-executes-test
  (start! itest-cmd)
  (try
    (testing "selecting /new from command picker clears chat"
      (keys! "picker-exec-seed-xyzzy" "Enter")
      (wait-for-ready!)
      (keys! "/")
      (wait-for! (has "Select command") 3000)
      (keys! "new" "Enter")
      (Thread/sleep 500)
      (is (not (str/includes? (screen) "picker-exec-seed-xyzzy"))))
    (finally (kill!))))

(deftest phase4-backspace-exits-picker-test
  (start! itest-cmd)
  (try
    (testing "Backspace on empty query in command picker returns to :ready"
      (keys! "/")
      (wait-for! (has "Select command") 3000)
      (keys! "BSpace")
      (let [s (wait-for! (lacks "Select command") 3000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))
```

---

## Stopping Criteria

### Automated (`bb test`)

1. `bb test` passes — no regressions from phases 1–3.
2. `commands/registry` contains all 8 commands; each has non-empty `:doc` string and fn `:handler`.
3. Typing `/` in empty `:ready` input enters `:picking :command` with empty query and full command list (8 items).
4. Typing non-`/` or `/` in non-empty input does not open command picker.
5. Typing in `:picking :command` appends to query and filters list; backspace on non-empty query removes char.
6. Backspace on empty query in `:picking :command` returns to `:ready`, picker dissoc'd.
7. Escape from `:picking :command` returns to `:ready`, input cleared.
8. Enter in `:picking :command` executes highlighted command and returns to `:ready` (tested for `/clear`).
9. `/clear` (direct Enter) clears `:items` and `:scroll-offset`; `/clear` via picker does the same.
10. `/help` (direct Enter) appends a system item containing all 8 command names.
11. `/quit` (direct Enter) returns non-nil shutdown cmd.
12. Unknown `/foobarxyzzy` (direct Enter) appends a system error item containing the command text.
13. `/model` via direct Enter still opens model picker (regression from Phase 2 hardcoded path).
14. `/model` via command picker opens model picker.
15. Command picker renders "Select command" label in view.

### Integration (`bb itest`)

16. Typing `/` opens "Select command" overlay.
17. Typing `m` narrows list — "model" visible, "/quit" not visible.
18. Escape closes overlay, SAFE restored.
19. Backspace on empty query in picker closes overlay, SAFE restored.
20. `/clear` typed directly removes seeded chat message from display.
21. `/help` typed directly shows `/model` and `/quit` in chat.
22. Unknown command typed directly shows the command name in a chat error.
23. Selecting `/new` from command picker (via `/` → filter `new` → Enter) clears chat.

### Manual

24. `bb run` → type `/` → command picker appears with all 8 commands listed.
25. Type `se` → narrows to `/sessions`; Enter opens session picker normally.
26. Type `mo` → narrows to `/model`; Enter opens model picker; select model — status bar updates.
27. Type `/clear` + Enter → old messages gone; next message continues the same session (chat-id unchanged).
28. Type `/help` + Enter → formatted command list visible in chat.
29. Type `/quit` + Enter → eca-cli exits cleanly (exit code 0, no error output).
30. Type `/notacommand` + Enter → ⚠ error visible in chat, app remains responsive.
31. Type `/login` + Enter → ECA login flow begins for the first unauthenticated provider (device-code, input, or authorize action appears).
32. If all providers are authenticated, `/login` shows `⚠ Login required but no unauthenticated provider found`.

---

## Notes

- **Hardcoded Enter branches removed** — the `(= "/model" text)`, `(= "/agent" text)`, `(= "/new" text)`, `(= "/sessions" text)` branches in `update-state` are deleted. All command dispatch goes through `commands/registry`. Handler logic migrates to `commands.clj`.

- **Circular dependency resolution** — all handler fns are defined as private fns in `state.clj` (they need `open-picker`, `start-login-cmd`, `rebuild-lines`, etc. which all live there). The registry map is built in `state.clj` and bound to a `def`. `commands.clj` simply re-exports it: `(def registry state/command-registry)`. This means `commands.clj` requires `state.clj` (one-way, no cycle) and `state.clj` does NOT require `commands.clj` — the registry is defined directly in `state.clj`. The `commands` namespace exists purely so external consumers (tests, future plugins) can refer to `commands/registry` instead of `state/command-registry`.

- **`/login` reuses `start-login-cmd`** — `protocol/providers-list!` and `protocol/providers-login!` already exist (`protocol.clj:59,64`). `start-login-cmd` already orchestrates the full flow. The `/login` handler is one line. No new protocol code required.

- **`/model` with no models** — preserve Phase 2 behaviour: if `(:available-models state)` is empty, the `/model` handler shows `⚠ No models available` as a system item rather than opening an empty picker.

- **`/variant` not in this phase** — the registry makes adding it trivial later; no design changes needed.

- **Tab completion not implemented** — Up/Down navigation plus type-to-filter is sufficient. Tab is not intercepted.

- **`/clear` does not delete the server chat** — chat-id is preserved; the next message continues the same server-side session. This is intentional: `/clear` is a display reset, not a new-chat operation. Use `/new` to start fresh on the server.

- **Command picker with no match** — if query matches nothing, Enter is a no-op. The user can backspace or Escape. No "No results" indicator needed (the empty list speaks for itself via the charm list component).

- **`/help` output order** — sort alphabetically by command name so the output is stable and scannable.
