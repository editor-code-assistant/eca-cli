# Phase 1b: Login Hardening

> **Credentials required.** This phase verifies the login flow end-to-end against a real provider. Complete [Phase 1a](phase-1a-reliable-core.md) first.

## Goal

The login flow implemented in Phase 1a works correctly against a real provider under all conditions: successful auth, timeout, cancellation mid-flow, and token expiry during a session.

---

## Pre-conditions

- Phase 1a is complete (`bb test` passes, all manual criteria met)
- At least one provider credential available (API key or OAuth account)
- At least one provider in ECA's config is **unauthenticated** at startup (to trigger the login flow naturally)

---

## Task 6: Login Flow End-to-End Verification

### 6a: Trigger flow from chat

Send a message. ECA returns `status: 'login'`. Verify:

- The app transitions to `:login` mode without hanging
- `start-login-cmd` fires: `providers/list` is called, the unauthenticated provider is identified, `providers/login` is called
- The correct action type is displayed for the provider (e.g. `input` for API key, `authorize` for OAuth)
- The status bar shows `🔐` or a login indicator so it's clear the app is waiting for auth

### 6b: API key flow (`input` action)

- Enter the API key into the text input and press Enter
- `providers/loginInput` is sent with the collected key
- ECA responds with `{action: "done"}` → `:eca-login-complete` message is queued
- App transitions to `:chatting`, re-sends the original pending message
- ECA responds normally; tokens/cost appear in the status bar

### 6c: OAuth / device code flow (`authorize` or `device-code` action)

- The URL and/or device code are clearly visible in the input area
- Completing auth in the browser triggers `providers/updated` from ECA
- `handle-providers-updated` detects the matching provider becoming `"authenticated"`
- App transitions to `:chatting`, re-sends the original pending message

### 6d: Timeout

- Disconnect from the network (or use a bogus provider key) so `providers/login` never completes
- After 10 seconds, the app shows an error system message and returns to `:ready`
- The pending message is **not** re-sent; the input is ready for the user to try again
- `pending-requests` does not accumulate orphaned callbacks across repeated timeouts

### 6e: Cancellation mid-flow

For each action type that the provider supports:

- Enter `:login` mode, then press Escape
- App returns to `:ready` immediately
- `:login` is removed from state
- The original pending message is discarded (not re-sent on next chat)
- A subsequent new message sends a fresh `chat/prompt` with no login side-effects

### 6f: Token expiry during session

- Begin a session, authenticate, send several messages successfully
- Let the token expire (or revoke it manually via the provider's dashboard)
- Send another message: ECA should return `status: 'login'` again
- Verify the login flow re-triggers correctly and the session resumes after re-auth

---

## Additional Tests

These test the edge cases not covered by Phase 1a's unit tests. They still use mocked ECA responses — no real network needed.

```clojure
(deftest login-timeout-test
  (testing "nil action from start-login-cmd → error message + :ready, input focused"
    (let [[s _] (state/update-state
                  (assoc (base-state) :mode :chatting)
                  {:type :eca-login-action
                   :provider "anthropic"
                   :action nil
                   :pending-message "original"})]
      (is (= :ready (:mode s)))
      (is (nil? (:login s)))
      (is (some #(and (= :system (:type %))
                      (clojure.string/includes? (:text %) "timed out"))
               (:items s))))))

(deftest login-cancel-cleans-state-test
  (testing "Escape in :login mode clears :login, returns to :ready"
    (let [login-state {:provider "anthropic"
                       :action {:action "device-code"
                                :url "https://example.com"
                                :code "ABCD"
                                :message "Enter code"}
                       :field-idx 0 :collected {}
                       :pending-message "original question"}
          s0 (assoc (base-state) :mode :login :login login-state)
          [s _] (state/update-state s0 (msg/key-press :escape))]
      (is (= :ready (:mode s)))
      (is (nil? (:login s)))
      (is (nil? (:pending-message s))))))

(deftest login-re-trigger-test
  (testing "second login status after successful session starts fresh login flow"
    (let [s0       (assoc (base-state)
                          :mode :chatting
                          :chat-id "existing-chat"
                          :pending-message "second question")
          [s cmd]  (handle-eca-tick s0 [{:type :eca-prompt-response
                                          :chat-id "existing-chat"
                                          :model "claude-sonnet-4-6"
                                          :status "login"}])]
      (is (= "existing-chat" (:chat-id s)))
      (is (some? cmd)))))

(deftest providers-updated-wrong-mode-test
  (testing "providers/updated in :chatting mode is a no-op"
    (let [s0    (assoc (base-state) :mode :chatting)
          [s _] (handle-eca-notification
                  s0
                  {:method "providers/updated"
                   :params {:id "anthropic" :auth {:status "authenticated"}}})]
      (is (= :chatting (:mode s)))
      (is (= s0 s)))))

(deftest submit-login-multi-field-test
  (testing "multi-field input: first enter advances field, second submits"
    (let [login-state {:provider "anthropic"
                       :action {:action "input"
                                :fields [{:key "api-key" :label "API Key" :type "secret"}
                                         {:key "org-id" :label "Org ID" :type "text"}]}
                       :field-idx 0 :collected {}
                       :pending-message "hi"}
          s0 (-> (base-state)
                 (assoc :mode :login :login login-state)
                 (assoc :input (ti/set-value (ti/text-input) "sk-abc123")))
          ;; First Enter: advance to field 1
          [s1 cmd1] (state/update-state s0 (msg/key-press :enter))
          _ (is (nil? cmd1))
          _ (is (= 1 (get-in s1 [:login :field-idx])))
          _ (is (= "sk-abc123" (get-in s1 [:login :collected "api-key"])))
          ;; Second Enter: submit both fields
          s1' (assoc s1 :input (ti/set-value (:input s1) "my-org"))
          [s2 cmd2] (state/update-state s1' (msg/key-press :enter))]
      (is (some? cmd2))
      (is (= {:api-key "sk-abc123" :org-id "my-org"}
             (get-in s1 [:login :collected]))))))
```

> **Note:** `ti/set-value` may not exist — check the `charm.components.text-input` API and use the appropriate function to pre-populate the input for testing.

---

## Stopping Criteria

Phase 1b is complete when **all** of the following are true.

### Automated (verified by `bb test`)

1. `bb test` still passes with zero failures (Phase 1a regression check).
2. Login timeout test passes — nil action produces error message, state returns to `:ready`.
3. Cancel test passes — Escape in `:login` clears `:login` from state and discards pending message.
4. Re-trigger test passes — second `status: 'login'` on an established session starts a new login cmd.
5. Wrong-mode `providers/updated` test passes — no-op outside `:login` mode.
6. Multi-field input test passes — field advancement and final submission work correctly.

### Manual (verified by running the app with real credentials)

7. Sending a message with an unauthenticated provider triggers the login UI and shows the correct action type for that provider.
8. Completing an API key login flow results in the original message being answered by the model; token/cost data appears in the status bar.
9. Completing an OAuth login flow (if a supported provider is available) results in the same outcome.
10. Letting the login timeout (10s, no network / bad key) shows an error and returns to `:ready` without hanging.
11. Cancelling a login with Escape at any stage returns cleanly to `:ready`; the next message sent starts a fresh prompt with no login artefacts.
