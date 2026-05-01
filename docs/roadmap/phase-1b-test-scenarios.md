# Phase 1b: Manual Test Scenarios

> Automated criteria (stopping criteria 1–6) are already passing. These scenarios cover manual criteria 7–11.

## Setup

The default model uses Anthropic which is already authenticated — login won't trigger naturally. Force an unauthenticated provider via `--model`:

| Flow | Command |
|---|---|
| API key (scenarios 7, 8, 10, 11) | `bb run --model openai/gpt-4o` |
| OAuth (scenario 9) | `bb run --model google/gemini-2.0-flash` |

No credential changes needed — do not touch `~/.cache/eca/db.transit.json`.

For all scenarios, you can tail the raw protocol log in a second terminal:
```
tail -f ~/.cache/eca/eca-cli.log
```

---

## Scenario 7 — Login UI triggers correctly (criterion 7)

```
bb run --model openai/gpt-4o
```

Type any message and press Enter.

**Expect:**
- Mode transitions to `:login` — input area changes to a login prompt
- Shows `🔐 Login required for openai.` with an `input` action asking for API key
- App does not hang; status bar remains responsive

---

## Scenario 8 — API key flow completes, original message answered (criterion 8)

Continuing from scenario 7.

Enter a valid OpenAI API key and press Enter.

**Expect:**
- `providers/loginInput` is sent; ECA responds with `{action: "done"}`
- Mode transitions to `:chatting`
- The original message from scenario 7 is re-sent automatically (no user action needed)
- OpenAI responds; response appears in chat
- Status bar shows token count and cost (e.g. `1234tok  $0.001`)

---

## Scenario 9 — OAuth flow (criterion 9)

```
bb run --model google/gemini-2.0-flash
```

Type any message and press Enter.

**Expect:**
- Login UI shows `authorize` or `device-code` action with a URL and/or code
- Complete auth in browser
- `providers/updated` notification fires automatically → mode transitions to `:chatting`
- Original message is re-sent; Gemini responds

*Skip if a Google account is not available.*

---

## Scenario 10 — Timeout, no hang, clean recovery (criterion 10)

Disconnect from the network, then:

```
bb run --model openai/gpt-4o
```

Send a message. ECA calls `providers/login`; with no network the call should time out after 10 seconds.

**Expect:**
- After ~10s: error system message in chat containing "timed out"
- Mode returns to `:ready`, input is focused
- Original message is **not** re-sent automatically
- App remains fully usable — next message sends a fresh `chat/prompt`

*If disconnecting is impractical: this code path is covered by `login-timeout-test` unit test. Mark as unit-tested only.*

---

## Scenario 11 — Escape cancels cleanly at each stage (criterion 11)

### 11a — Escape from `input` action

```
bb run --model openai/gpt-4o
```

Send a message to trigger login, then press Escape immediately when the API key prompt appears.

**Expect:**
- Mode returns to `:ready`, input focused
- `:login` is gone from state (no login prompt visible)
- `:pending-message` is discarded — the original message is not queued for re-send

**Verify no artifacts:** type a new message and send it. Confirm it goes out as a plain `chat/prompt` with no login side-effects (check log if unsure).

### 11b — Escape from `device-code` / `authorize` action

Repeat using `bb run --model google/gemini-2.0-flash` if OAuth tested in scenario 9.

Press Escape when the URL/code is shown. Same expectations as 11a.

---

## Pass criteria summary

| # | Criterion | How to verify |
|---|---|---|
| 7 | Login UI appears with correct action type | Scenario 7 |
| 8 | API key flow completes, original message answered, tokens shown | Scenario 8 |
| 9 | OAuth flow completes, session resumes | Scenario 9 |
| 10 | Timeout shows error, returns to `:ready`, no hang | Scenario 10 |
| 11 | Escape at any stage returns cleanly, next message is fresh | Scenario 11a + 11b |
