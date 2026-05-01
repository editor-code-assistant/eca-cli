# Phase 3: Session Continuity

> **Pre-condition:** Phase 2 complete (`bb test` passes, Ctrl+L model picker and `/agent` picker functional).

## Goal

Quit and come back to where you left off. Start fresh when you want to. Browse previous chats without restarting. Know which chat you're in at all times.

---

## What Phase 2 Already Did

- `chat-id` is stored in state and sent with every subsequent `chat/prompt`, maintaining continuity within a single process lifetime.
- `:picking` mode (with charm list component) handles interactive selection — reused here for the session picker.
- `send-request!` in `protocol.clj` handles request/response with callbacks — used for `chat/list` and `chat/open`.
- `welcomeMessage` from `config/updated` is already surfaced as a system item.

What is NOT done yet: persistence across restarts, `chat/opened`/`chat/cleared` notification handlers, `/new`, `/sessions`, or chat title in the status bar.

---

## What to Build

### 0. Own ECA binary — `bb upgrade-eca` task

eca-cli currently falls through the discovery chain to `~/.cache/nvim/eca/eca` (the Neovim plugin binary). That's fragile — users without Neovim get no binary, and eca-cli can't control which version it runs against.

**Target location:** `~/.cache/eca/eca-cli/eca` — eca-cli's exclusively managed binary.

**Pinned version:** stored as a constant in `src/eca_cli/upgrade.clj`:
```clojure
(def eca-version "0.130.0")
```

**Platform detection:**

| `(System/getProperty "os.name")` | `(System/getProperty "os.arch")` | Asset |
|---|---|---|
| `"Linux"` | `"amd64"` / `"x86_64"` | `eca-native-linux-amd64.zip` |
| `"Linux"` | `"aarch64"` | `eca-native-linux-aarch64.zip` |
| `"Mac OS X"` | `"aarch64"` | `eca-native-macos-aarch64.zip` |
| `"Mac OS X"` | `"amd64"` / `"x86_64"` | `eca-native-macos-amd64.zip` |

**Download flow in `upgrade.clj`:**
1. Build URL: `https://github.com/editor-code-assistant/eca/releases/download/{version}/{asset}`
2. `curl -fsSL -o /tmp/eca-download.zip <url>`
3. `unzip -o /tmp/eca-download.zip -d /tmp/eca-extract/`
4. `chmod +x` and move to `~/.cache/eca/eca-cli/eca`
5. Print version confirmation.

**New bb task:**
```clojure
upgrade-eca {:doc "Download and install the pinned ECA binary"
             :requires ([eca-cli.upgrade])
             :task (eca-cli.upgrade/run!)}
```

**Discovery order update in `server.clj`** — insert eca-cli's own binary as first fallback after `--eca` flag, before PATH and nvim:

```
1. --eca flag
2. ~/.cache/eca/eca-cli/eca        ← NEW (eca-cli managed)
3. which eca (PATH)
4. ~/.cache/nvim/eca/eca          (nvim plugin)
5. ~/Library/Caches/nvim/eca/eca  (nvim macOS)
6. ~/.emacs.d/eca/eca             (emacs)
```

**On startup version check:** In `make-init`, after resolving the binary path, run `<binary> --version`, parse the output, and if it doesn't match `eca-version`, emit a `:system` item in chat:
```
⚠ ECA version mismatch: running 0.128.2, expected 0.130.0. Run `bb upgrade-eca` to update.
```
This is a warning only — eca-cli still runs with whatever binary it found.

---

### 1. Chat-id persistence — `src/eca_cli/sessions.clj`

New file. Stores a map of workspace-path → chat-id in EDN:

```
~/.cache/eca/eca-cli-sessions.edn
{"/home/sam/project" "abc-123-def"
 "/home/sam/other"   "xyz-789-ghi"}
```

```clojure
(defn sessions-path []
  (str (System/getProperty "user.home") "/.cache/eca/eca-cli-sessions.edn"))

(defn load-chat-id
  "Returns persisted chat-id for workspace, or nil."
  [workspace])

(defn save-chat-id!
  "Saves chat-id for workspace. Passing nil removes the entry."
  [workspace chat-id])
```

**`load-chat-id`:** `slurp` the file, `clojure.edn/read-string`, `get` by workspace. Return nil on any error (file missing, parse error).

**`save-chat-id!`:** Read existing map (or `{}`), `assoc` or `dissoc`, `spit`. Create parent dirs if needed (`io/make-parents`). No locking — only one eca-cli process per workspace is expected.

**Wire in `make-init`:** After resolving opts, call `(sessions/load-chat-id workspace)` and include the result as `:chat-id` in `initial-state`. First `chat/prompt` will then include `chatId`, resuming the session server-side.

**Wire in `send-chat-prompt!` callback:** After storing `:chat-id` in state, also call `(sessions/save-chat-id! workspace chat-id)`. The callback runs on the reader thread — file I/O there is fine.

### 2. `chat/opened` notification handler

ECA sends `chat/opened` for:
- `chat/open` hydration replay (before the `chat/contentReceived` stream)
- `chat/fork` (Phase 9)

**`chat/opened` does NOT fire for ordinary new chats created via `chat/prompt`** — the protocol sequence diagram shows no `chat/opened` in the `chat/prompt` flow. The `chat-id` for a new chat arrives only via the `chat/prompt` response callback. As a consequence, `:chat-title` will be nil for newly started chats; it is only populated after a `/sessions` open or a fork.

Add to `handle-eca-notification` in `state.clj`:

```clojure
"chat/opened"
(let [{:keys [chatId title]} (:params m)]
  [(-> state
       (assoc :chat-id chatId)
       (assoc :chat-title title))
   nil])
```

New state field: `:chat-title nil`.

### 3. `chat/cleared` notification handler

ECA sends `chat/cleared` before replaying messages via `chat/open` (and after `chat/rollback` in Phase 9). It does **not** fire in response to `chat/delete` — the client clears its own UI in the `/new` handler directly. `chat/cleared` is purely a server-initiated notification telling the client to wipe items before a replay stream.

```clojure
"chat/cleared"
(let [clear-msgs? (get-in m [:params :messages])]
  [(cond-> state
     clear-msgs? (-> (assoc :items [])
                     (assoc :chat-lines [])
                     (assoc :scroll-offset 0)))
   nil])
```

### 4. Protocol: new request fns in `protocol.clj`

```clojure
(defn list-chats! [srv callback]
  (send-request! srv "chat/list" {:limit 20} callback))

(defn open-chat! [srv chat-id callback]
  (send-request! srv "chat/open" {:chatId chat-id} callback))

(defn delete-chat! [srv chat-id callback]
  (send-request! srv "chat/delete" {:chatId chat-id} callback))
```

### 5. `/new` command

Special-cased in the Enter handler in `:ready` (before the general "send to ECA" branch), like `/model` and `/agent`:

```
input text == "/new" + Enter in :ready:
```

1. Capture current `:chat-id`.
2. If no `:chat-id`: clear input, stay in `:ready` — already fresh.
3. If `:chat-id`:
   - Fire `delete-chat!` as a cmd (fire-and-forget; ignore response).
   - Call `(sessions/save-chat-id! workspace nil)` — remove from disk.
   - Clear local state: `:items []`, `:chat-lines []`, `:chat-id nil`, `:chat-title nil`, `:scroll-offset 0`.
   - Clear input, stay in `:ready`.

No confirmation prompt — immediate. The action is recoverable via `/sessions` (old chat still exists on the server until deleted asynchronously).

### 6. `/sessions` command — session picker

**Trigger:** input is `/sessions` + Enter in `:ready`.

**Flow:**

1. Fire `list-chats!` as a cmd. Return `[state cmd]` — state unchanged, ui shows input cleared.
2. The callback puts `{:type :chat-list-loaded :chats [...]}` on the queue.
3. On `:chat-list-loaded` in `handle-eca-tick`: enter `:picking` with `:kind :session`.

**Picker `:all` entries:** vector of `[display-string chat-id]` pairs:

```clojure
; display: "My Project Chat  •  2026-04-25  •  42 msgs"
; fall back to first 8 chars of id if no title
```

Build the charm list from the display strings only. On `cl/selected-item`, use index to retrieve the chat-id from `:all`.

**On Enter in session picker — note on existing picker handler:**
The current picker Enter handler in `update-state` handles `:model` and `:agent` by calling `selected-model-changed!` / `selected-agent-changed!`. The `:session` kind must be branched separately: instead of sending a selection notification, call `open-chat!`, clear local items, and set `:chat-id`. The existing handler becomes a `case` or `cond` on `(:kind picker)`.

**On Enter in session picker:**
- Get selected index from picker state.
- Fire `open-chat!` cmd with the selected chat-id.
- Clear `:items`, `:chat-lines`, `:scroll-offset` immediately (server is about to replay).
- Update `:chat-id` to the selected id.
- Dissoc `:picker`, return to `:ready`.
- The server will then emit `chat/cleared` → `chat/opened` → `chat/contentReceived` stream which populates the UI normally.

**Picker state for sessions (addition to existing `:picker` map):**
```clojure
{:kind    :session
 :list    <charm-list>          ; display strings
 :all     [["Title  •  date  •  N msgs" "chat-id"] ...]
 :query   ""}
```

Filtering: same case-insensitive substring match on the display string.

**If `chat/list` returns empty:** dispatch `:chat-list-loaded` with `[]`, picker opens with an empty list and a "No sessions" indicator, Escape returns to `:ready`.

### 7. Status bar: chat title

In `render-status-bar`, add chat title between the agent field and the SAFE indicator:

```
myproject  claude-sonnet-4-6  code  "My Project Chat"  SAFE
```

- Show when `:chat-title` is non-nil and non-empty.
- Truncate to 24 chars, append `…` if longer.
- Wrap in `"` chars or use a distinct visual separator.

---

## State machine additions

```
:ready  --/sessions + Enter-->  (fire list-chats!)
queue <-- chat/list response  -->  :chat-list-loaded
:ready  --:chat-list-loaded-->  :picking (kind :session)
:picking (session)  --Enter-->  :ready  (+ fire open-chat!)
:picking (session)  --Esc-->   :ready  (no change)
```

`:loading` is not a new mode. Between `/sessions` Enter and `:chat-list-loaded`, the app stays in `:ready` with the input cleared. A brief delay is acceptable.

---

## Tests

### Unit tests (`bb test`)

#### `test/eca_cli/sessions_test.clj` (new file)

```clojure
(deftest load-chat-id-missing-file-test
  (testing "returns nil when file does not exist"
    (with-redefs [sessions/sessions-path (fn [] "/tmp/no-such-eca-file.edn")]
      (is (nil? (sessions/load-chat-id "/some/workspace"))))))

(deftest load-chat-id-found-test
  (testing "returns stored chat-id for workspace"
    (let [f (java.io.File/createTempFile "sessions" ".edn")]
      (spit f (pr-str {"/workspace" "chat-abc"}))
      (with-redefs [sessions/sessions-path (fn [] (.getAbsolutePath f))]
        (is (= "chat-abc" (sessions/load-chat-id "/workspace")))))))

(deftest load-chat-id-missing-workspace-test
  (testing "returns nil when workspace not in file"
    (let [f (java.io.File/createTempFile "sessions" ".edn")]
      (spit f (pr-str {"/other" "chat-xyz"}))
      (with-redefs [sessions/sessions-path (fn [] (.getAbsolutePath f))]
        (is (nil? (sessions/load-chat-id "/workspace")))))))

(deftest save-and-load-round-trip-test
  (testing "save then load returns same value"
    (let [f (java.io.File/createTempFile "sessions" ".edn")]
      (.deleteOnExit f)
      (with-redefs [sessions/sessions-path (fn [] (.getAbsolutePath f))]
        (sessions/save-chat-id! "/workspace" "chat-round-trip")
        (is (= "chat-round-trip" (sessions/load-chat-id "/workspace")))))))

(deftest save-nil-removes-entry-test
  (testing "save nil removes the workspace entry"
    (let [f (java.io.File/createTempFile "sessions" ".edn")]
      (.deleteOnExit f)
      (with-redefs [sessions/sessions-path (fn [] (.getAbsolutePath f))]
        (sessions/save-chat-id! "/workspace" "chat-to-remove")
        (sessions/save-chat-id! "/workspace" nil)
        (is (nil? (sessions/load-chat-id "/workspace")))))))
```

#### `state-test.clj` additions

```clojure
(deftest chat-opened-handler-test
  (testing "chat/opened stores chat-id and title"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "chat/opened"
                   :params {:chatId "new-chat-123" :title "My Project Chat"}})]
      (is (= "new-chat-123" (:chat-id s)))
      (is (= "My Project Chat" (:chat-title s)))))

  (testing "chat/opened with no title stores nil"
    (let [[s _] (handle-eca-notification
                  (base-state)
                  {:method "chat/opened"
                   :params {:chatId "chat-no-title"}})]
      (is (= "chat-no-title" (:chat-id s)))
      (is (nil? (:chat-title s))))))

(deftest chat-cleared-handler-test
  (testing "chat/cleared with messages:true clears items and scroll"
    (let [s0 (assoc (base-state)
                    :items [{:type :user :text "hi"}]
                    :scroll-offset 5)
          [s _] (handle-eca-notification
                  s0
                  {:method "chat/cleared"
                   :params {:chatId "x" :messages true}})]
      (is (empty? (:items s)))
      (is (= 0 (:scroll-offset s)))))

  (testing "chat/cleared with messages:false leaves items intact"
    (let [s0 (assoc (base-state)
                    :items [{:type :user :text "hi"}])
          [s _] (handle-eca-notification
                  s0
                  {:method "chat/cleared"
                   :params {:chatId "x" :messages false}})]
      (is (= 1 (count (:items s)))))))

(deftest slash-new-clears-state-test
  (testing "/new with no chat-id is a no-op (already fresh)"
    (let [s0 (assoc (base-state) :mode :ready :chat-id nil)
          s0 (assoc s0 :input (ti/set-value (ti/text-input) "/new"))
          [s cmd] (state/update-state s0 (msg/key-press :enter))]
      (is (= :ready (:mode s)))
      (is (nil? cmd))))

  (testing "/new with chat-id clears state and returns delete cmd"
    (with-redefs [sessions/save-chat-id! (fn [& _] nil)]
      (let [s0 (assoc (base-state)
                      :mode :ready
                      :chat-id "old-chat"
                      :items [{:type :user :text "hello"}])
            s0 (assoc s0 :input (ti/set-value (ti/text-input) "/new"))
            [s cmd] (state/update-state s0 (msg/key-press :enter))]
        (is (= :ready (:mode s)))
        (is (nil? (:chat-id s)))
        (is (nil? (:chat-title s)))
        (is (empty? (:items s)))
        (is (some? cmd))))))

(deftest slash-sessions-fires-list-cmd-test
  (testing "/sessions in :ready fires chat/list cmd"
    (let [s0 (assoc (base-state) :mode :ready)
          s0 (assoc s0 :input (ti/set-value (ti/text-input) "/sessions"))
          [s cmd] (state/update-state s0 (msg/key-press :enter))]
      (is (= :ready (:mode s)))
      (is (some? cmd)))))

(deftest chat-list-loaded-enters-picking-test
  (testing ":chat-list-loaded with results enters :picking :session"
    (let [s0 (assoc (base-state) :mode :ready)
          chats [{:id "chat-abc" :title "Project A" :messageCount 10}
                 {:id "chat-def" :title "Project B" :messageCount 5}]
          [s _] (state/update-state s0 {:type :chat-list-loaded :chats chats})]
      (is (= :picking (:mode s)))
      (is (= :session (get-in s [:picker :kind])))
      (is (= 2 (count (get-in s [:picker :all]))))))

  (testing ":chat-list-loaded with empty list still enters :picking"
    (let [s0 (assoc (base-state) :mode :ready)
          [s _] (state/update-state s0 {:type :chat-list-loaded :chats []})]
      (is (= :picking (:mode s)))
      (is (= :session (get-in s [:picker :kind])))
      (is (empty? (get-in s [:picker :all]))))))

(deftest session-picker-enter-fires-open-cmd-test
  (testing "Enter in session picker returns :ready and fires open-chat cmd"
    (let [s0 (assoc (base-state) :mode :ready)
          chats [{:id "chat-abc" :title "My Chat" :messageCount 3}]
          [s-pick _] (state/update-state s0 {:type :chat-list-loaded :chats chats})
          [s cmd] (state/update-state s-pick (msg/key-press :enter))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s)))
      (is (some? cmd)))))

(deftest session-picker-escape-test
  (testing "Esc in session picker returns to :ready, no change"
    (let [s0 (assoc (base-state) :mode :ready)
          chats [{:id "chat-abc" :title "My Chat" :messageCount 3}]
          [s-pick _] (state/update-state s0 {:type :chat-list-loaded :chats chats})
          [s _] (state/update-state s-pick (msg/key-press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:picker s))))))
```

#### `view-test.clj` additions

```clojure
(testing "chat title shown when :chat-title set"
  (let [bar (render-status-bar (assoc base :chat-title "My Project Chat"))]
    (is (str/includes? bar "My Project Chat"))))

(testing "long title truncated to 24 chars with ellipsis"
  (let [bar (render-status-bar (assoc base :chat-title "A very long chat title that exceeds limits"))]
    (is (str/includes? bar "…"))
    (is (not (str/includes? bar "A very long chat title that exceeds")))))

(testing "no title shown when :chat-title nil"
  (let [bar (render-status-bar base)]
    (is (not (str/includes? bar "My Project")))))
```

### Integration tests (`bb itest`)

Add to `test/eca_cli/integration_test.clj` under a Phase 3 section:

```clojure
(deftest phase3-resume-test
  (start! "bb run")
  (try
    (let [msg "resume-marker-xyzzy"]
      (keys! msg "Enter")
      (wait-for! (has "SAFE") 30000)
      (kill!)
      (Thread/sleep 500)
      (start! "bb run")
      (let [s (wait-for! (has "SAFE") 15000)]
        (testing "previous message appears after restart (auto-resume)"
          (is (str/includes? s msg)))))
    (finally (kill!))))

(deftest phase3-new-command-test
  (start! "bb run")
  (try
    (let [old-msg "new-cmd-before-xyzzy"
          new-msg "new-cmd-after-xyzzy"]
      (keys! old-msg "Enter")
      (wait-for! (has "SAFE") 30000)
      (keys! "/new" "Enter")
      (Thread/sleep 500)
      (testing "/new clears old message from UI"
        (is (not (str/includes? (screen) old-msg))))
      (keys! new-msg "Enter")
      (let [s (wait-for! (has "SAFE") 30000)]
        (testing "new message works after /new"
          (is (str/includes? s new-msg)))))
    (finally (kill!))))

(deftest phase3-sessions-picker-test
  ;; Self-contained: sends a message first to ensure at least one chat exists.
  (start! "bb run")
  (try
    (keys! "sessions-seed-xyzzy" "Enter")
    (wait-for! (has "SAFE") 30000)
    (testing "/sessions opens session picker"
      (keys! "/sessions" "Enter")
      (let [s (wait-for! (has "Select chat") 8000)]
        (is (str/includes? s "Select chat"))))
    (testing "Escape from sessions picker returns to :ready"
      (keys! "Escape")
      (let [s (wait-for! (lacks "Select chat") 5000)]
        (is (str/includes? s "SAFE"))))
    (finally (kill!))))
```

---

## Stopping Criteria

### Automated (`bb test`)

1. `bb test` passes — no Phase 2 regressions.
2. `upgrade.clj` platform detection — correct asset name for linux-amd64, linux-aarch64, macos-aarch64, macos-amd64; throws on unknown platform.
3. Sessions persistence — 5 cases: load missing file, load found, load missing workspace, round-trip, save nil removes.
3. `chat/opened` stores `:chat-id` and `:chat-title`; no-title case stores nil.
4. `chat/cleared` with `messages:true` clears items + scroll; `messages:false` leaves items intact.
5. `/new` with no chat-id is no-op; with chat-id clears state and returns delete cmd.
6. `/sessions` in `:ready` returns non-nil cmd.
7. `:chat-list-loaded` with results enters `:picking :session` with correct item count; empty list still enters picker.
8. Session picker Enter returns `:ready`, fires open cmd, dissocs picker.
9. Session picker Escape returns `:ready`, no change.
10. Status bar shows `:chat-title` (truncated at 24 + `…`); nil shows nothing.

### Integration (`bb itest`)

11. App resumes previous chat after restart in same workspace — old message visible on screen.
12. `/new` clears UI; subsequent message works (app not broken after reset).
13. `/sessions` opens a picker with "Select chat" visible.

### Manual

13b. `bb upgrade-eca` downloads the binary to `~/.cache/eca/eca-cli/eca` and prints the version.
13c. `bb run` uses `~/.cache/eca/eca-cli/eca` (confirm via `--eca` flag or log output).
14. Select a session from `/sessions` picker → previous messages replay and appear in chat.
15. Chat title appears in status bar after a chat exchange is established.
16. `/new` after a long chat → clean start, no old messages visible, next message begins a new session.
17. Two workspaces: each has its own independent chat-id (check `eca-cli-sessions.edn`).

---

## Notes

- **No `--resume` flag** — auto-resume by default is the right call. Simpler UX, no flag to remember. `/new` is the escape hatch. The roadmap mentioned `--resume`; this phase drops it.
- **`chat/opened` fires for new chats too** — ECA emits it for every new chat creation, not just `chat/open` replays. The handler should not overwrite chat-id if it's already the same value (idempotent assoc is fine).
- **`chat/list` limit 20** — reasonable default. A `/sessions` picker with >20 items would need search anyway (which the filter handles).
- **Session picker uses index for id lookup** — `cl/selected-item` returns the display string; use `cl/selected-index` to get the index and look up `(nth (:all picker) idx)` for the full entry. `cl/selected-index` is confirmed present in charm 0.2.71.
- **`chat/list` error handling** — if the `list-chats!` callback receives an error, dispatch `:chat-list-loaded` with `:chats []` and append a `{:type :system :text "⚠ Could not load sessions"}` item to state. Don't leave the app stuck in `:ready` with no feedback.
- **`eca --version` output format** — the binary prints `eca X.Y.Z` on stdout (e.g. `eca 0.130.0`). Parse with `(second (re-find #"eca (\S+)" output))` and compare against `upgrade/eca-version`.
- **`:chat-title` is nil for new chats** — ECA does not emit `chat/opened` on `chat/prompt`. Title is only populated after a `/sessions` open or fork. The status bar omits the title field when nil; no placeholder needed.
- **`save-chat-id!` call location** — currently `send-chat-prompt!` callback is in `protocol.clj`. It receives the workspace via the opts map that's threaded through state. Pass workspace explicitly when building the callback in `state.clj`, or read it from the opts stored in state.
- **`chat/opened` title from `chat/prompt`** — for the normal first-message flow, ECA may not emit `chat/opened` explicitly; the chat-id comes from the `chat/prompt` response. The title only arrives via `chat/opened`. If ECA does emit `chat/opened` for new chats, the title will appear automatically. If not, `:chat-title` remains nil until `/sessions` selects a chat or `chat/open` is called.
