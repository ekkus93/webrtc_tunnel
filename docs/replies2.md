# replies2.md — Responses to Claude Code Questions

These replies address the questions and implementation risks raised in `responses2(20).md` about the Android WebRTC emulator data-plane spec/TODO.

## Summary decisions

1. **Yes: this pass is fail-fast + observable + deterministic, not the final root-cause fix.**
2. **Yes: add a black-hole-answer E2E test.** Unit tests alone are not enough for the probe-failure path.
3. **Honor `android_ice_mode` on all platforms.** Keep the name for compatibility/history, but define the semantics as cross-platform diagnostic ICE-mode controls.
4. **Use a device-agnostic Android mode-injection mechanism.** Prefer debug intent extra or system property over patching app-private config with `run-as`.
5. **Implement the probe as a cancel-safe readiness step.** It must not race with the initial stream `OPEN`, but it should still be interruptible by ICE/session failure.
6. **Avoid reconnect hot loops.** A failed probe should fail the current session/request cleanly and obey the normal reconnect/backoff rules.

---

## 1. Framing: yes, this is not the final answer-office fix

Claude Code is correct: this pass should be framed as:

> Make the Android WebRTC data plane deterministic, observable, and fail-fast under the Docker/emulator harness.

It should **not** claim to fix the original `answer-office` data-plane failure while that host is down and no physical remote target is available.

The real `answer-office` failure will likely still manifest as:

```text
data channel open
probe sent
no Pong received
probe timeout
session torn down cleanly
clear error returned/logged
```

That is a successful result for this pass. The old behavior was worse: the tunnel could appear connected while the browser/client hung with zero bytes.

Update the spec language to explicitly say:

- This pass adds a **detector and guardrail** for post-DCEP data-plane failure.
- It does **not** prove or repair the remote NAT/firewall/Android vnet interaction.
- The later root-cause pass needs `answer-office` back online or another physical-device remote target.

---

## 2. Test coverage: yes, add the black-hole-answer E2E

I agree with Claude Code’s concern. The Docker/emulator matrix mostly validates the happy path:

```text
data channel open -> Ping -> Pong -> bridge traffic
```

That is necessary but not sufficient. The most important new behavior is the failure path:

```text
data channel open -> Ping sent -> no Pong -> timeout -> teardown -> no hang
```

So add a deliberate black-hole-answer mode to the E2E test harness.

### Required behavior

Add a test-only/debug-only answer mode that:

1. Completes signaling, ICE, DTLS, SCTP, and data-channel open normally.
2. Starts the answer-side tunnel/multiplex loop normally enough to receive frames.
3. When it receives `TunnelFrameType::Ping`, it logs the event but deliberately does **not** send `Pong`.
4. Keeps the data channel open long enough for the offer-side probe timeout to fire.
5. Does not affect production behavior unless the debug flag/env var is explicitly set.

Suggested control surface:

```bash
P2P_TUNNEL_DEBUG_DROP_PING=1
```

or a CLI flag:

```bash
p2p-answer --debug-drop-ping
```

Use whichever style matches the existing CLI/config patterns better. It must be unavailable or clearly marked as debug/test behavior.

### Black-hole E2E acceptance criteria

Add a test row/script that starts the answer with the drop-Ping behavior and runs the Android emulator offer/client.

The test should assert:

- Data channel reaches open.
- Offer logs `data-plane probe sent`.
- Answer logs that it received and intentionally dropped Ping.
- Offer logs `data-plane probe timeout`.
- The session/peer connection is torn down.
- The local client does not hang indefinitely.
- The offer returns to its steady/listening state.
- No real stream `OPEN` frame is sent after the failed probe.
- The test completes within a bounded time, for example `probe_timeout + small_margin`, not the old long user-visible hang.

This should be part of the matrix, not just a unit test.

Unit tests are still useful for nonce matching, timeout handling, malformed Pong handling, and stream-id rules, but the E2E is what proves the Android/Docker harness handles teardown and return-to-listening correctly.

---

## 3. `android_ice_mode`: honor it on all platforms

Claude Code is right that the earlier wording was contradictory. Resolve it this way:

> `android_ice_mode` is honored on all platforms, despite the historical name.

Do **not** ignore it on desktop. It should be treated as a cross-platform diagnostic knob that controls whether the WebRTC setup uses the native/default ICE path or the explicit vnet fallback path.

Keep the existing name for now to reduce churn, but add a comment like:

```text
android_ice_mode is historical naming. The setting is honored on all platforms so tests can exercise native/vnet behavior outside Android too.
```

### Exact semantics

Define the modes as:

#### `native`

- Never call `SettingEngine::set_vnet`.
- Use the normal/default WebRTC setting engine.
- If native gathering fails or no usable candidate is produced, fail loudly through the normal open/connect timeout path.
- Log clearly:

```text
ice_mode=native set_vnet=false
```

#### `vnet`

- Force explicit vnet construction.
- If the interface list or fallback network cannot be constructed, return a configuration/startup error.
- Do **not** silently fall back to native mode.
- Log clearly:

```text
ice_mode=vnet set_vnet=true
```

#### `auto`

For this pass, `auto` may preserve the current production behavior, but it must become observable:

- Try the normal/native path first when possible.
- Use the current vnet fallback only where the existing code would have used it.
- Log the selected path.
- No silent fallback: every mode decision must be logged.

Suggested log shape:

```text
ice_mode=auto selected_path=native set_vnet=false reason=interface_enumeration_ok
```

or:

```text
ice_mode=auto selected_path=vnet set_vnet=true reason=interface_enumeration_failed
```

This allows desktop integration tests and Android emulator tests to exercise all three modes predictably.

---

## 4. Android mode injection: use debug intent extra or system property, not `run-as` config patching

I agree with the SELinux warning. Do not depend on writing app-private config files with `run-as`.

Use a device-agnostic injection method that works on both emulator and physical devices.

Preferred options, in order:

### Option A — Debug-only intent extra

The debug/test Activity or service start path accepts an extra such as:

```text
p2p.android_ice_mode=native
```

or:

```text
p2p.android_ice_mode=vnet
```

The app then writes that value into the generated config before starting the tunnel.

This is the cleanest option if the E2E scripts already drive the app through intents.

### Option B — Debug-only Android system property

The debug build reads a property such as:

```bash
adb shell setprop debug.p2p.android_ice_mode native
```

The app reads it during config generation/startup and applies it.

This is also acceptable, but make sure the property is cleared between matrix rows.

### Avoid

Avoid this as the primary mechanism:

```bash
adb shell run-as <package> sh -c 'sed -i ... app-private-config.toml'
```

It may work on the emulator but is not robust on physical Samsung devices. It also couples tests to internal file paths and timing.

### Timing rule

Whichever method is used, the override must be applied:

```text
after wizard/config generation
before Start
```

If the wizard is re-run, it may regenerate `android_ice_mode = "auto"`, so the override must be reapplied.

---

## 5. Probe implementation: cancel-safe, single consumer of data-channel events

Claude Code is right to flag the blocking-await issue.

The probe must preserve this invariant:

> No stream `OPEN` is sent until the Ping/Pong readiness probe succeeds.

But the probe should still be cancel-safe with respect to ICE/session failure.

Recommended shape:

```rust
tokio::select! {
    probe_result = run_data_plane_probe(&data_channel, timeout) => {
        probe_result?;
    }
    ice_result = wait_for_terminal_ice_failure_or_shutdown(...) => {
        return Err(ice_result.into());
    }
}
```

Important: do **not** create two concurrent consumers of `DataChannelHandle::next_event()`.

If `run_data_plane_probe()` consumes data-channel events while waiting for `Pong`, then `run_multiplex_offer()` must not be running yet. The order should be:

```text
wait for data channel open
run Ping/Pong readiness probe
only after probe success: start run_multiplex_offer / send real stream OPEN
```

If ICE/session events are handled by a separate channel or session-state watch, race the probe against that. If they are only available from the same data-channel event stream, keep the simpler bounded blocking probe and document the limitation. A 5-second maximum delay is acceptable, but the preferred implementation is cancel-safe if the architecture allows it.

---

## 6. Reconnect/retry cadence: no tight loop

After a probe failure:

1. Tear down the peer connection/session.
2. Fail the current local client/request with a clear error.
3. Return to the offer service’s normal steady/listening state.
4. Apply existing reconnect/backoff rules before attempting another negotiation.
5. Do not immediately renegotiate in a tight loop with no new client demand or no backoff.

Expected behavior for a retrying browser/client:

```text
client request arrives
negotiate
data channel opens
probe timeout
request fails cleanly
backoff applies
subsequent retry repeats only after normal retry cadence
```

Add an acceptance check that the debug black-hole test does not produce an unbounded loop like:

```text
negotiate -> probe fail -> negotiate -> probe fail -> negotiate -> ...
```

The spec should define the intended minimum behavior:

- Fail-fast per session/request is good.
- Hot-loop renegotiation is not acceptable.
- Existing `enable_auto_reconnect` behavior must not bypass backoff after probe failure.

---

## 7. Adjust the TODO around existing frame validation

Claude Code verified that several low-level pieces already exist:

- `TunnelFrame::ping(payload)` and `TunnelFrame::pong(payload)` already exist.
- Ping/Pong already use stream id 0.
- The codec already rejects stream frames on stream id 0.
- The codec already rejects Ping/Pong on nonzero stream id.
- The answer already replies to Ping with Pong.
- The offer currently ignores Pong.

So the TODO should not imply these validations need to be invented from scratch.

Change the relevant TODO item from:

```text
Add stream-id validation tests for Ping/Pong and reserved stream 0.
```

to:

```text
Verify existing frame/stream-id validation and add only missing tests:
- Ping/Pong must remain stream id 0.
- Open/Data/Close/Error must remain forbidden on stream id 0.
- Ping/Pong with nonzero stream id must remain rejected.
- Existing tests should be extended only where coverage is missing.
```

This reduces the chance Claude Code rewrites working code unnecessarily.

---

## 8. Native matrix row expected failure

For the Android emulator, expect `native` mode may fail before the new probe is reached.

That failure path is still useful.

Update matrix expectations:

```text
ANDROID_ICE_MODE=native
expected behavior on emulator: may gather no usable candidates
expected failure point: pre-open/open timeout
not expected failure point: probe timeout
required assertion: set_vnet was not used
```

So the native row should not be judged as a failed implementation simply because the data channel never opens. The point is to verify the mode switch and the no-vnet path.

---

## 9. Config template note

Claude Code’s note is correct: if `[webrtc]` and `[tunnel]` are in a shared `STATIC_TLS_WEBRTC_TUNNEL_SECTIONS` constant, update that shared constant rather than duplicating edits across template builders.

Acceptance criteria:

- Generated offer config includes `android_ice_mode = "auto"`.
- Generated default config template includes `android_ice_mode = "auto"`.
- The value can be overridden by the debug test injection mechanism.
- Re-running the wizard resets to the default unless the test override is applied again.

---

## 10. Validate `data_plane_probe_timeout_ms`

Serde defaults are not validation.

Add explicit validation:

```text
data_plane_probe_timeout_ms must be > 0
```

Recommended bounds:

```text
minimum: 100 ms
default: 5000 ms
maximum: optional, but 60000 ms is reasonable if a max is desired
```

If the project already has a config-validation layer, put it there. If not, validate where `TunnelConfig` is loaded into the offer runtime.

Invalid config should fail startup/config load clearly, not silently use the default.

---

## 11. Probe latency

Yes, the probe adds one RTT to every successful session.

That is acceptable. The tradeoff is worth it because it prevents a much worse UX: a tunnel that appears connected while the client hangs with zero bytes.

Add a short note to the spec:

```text
The readiness probe is mandatory and adds one application-level data-channel RTT before real stream bridging starts. This is intentional.
```

---

## 12. Probe/answer-loop race

The race is benign as described.

The offer may send Ping shortly after data-channel open, before the answer-side multiplex loop is actively reading. Because the channel is reliable and ordered, the Ping should be buffered and delivered once the answer reads.

Add a comment near the probe call:

```text
The answer may not have entered its multiplex read loop yet when this Ping is sent. This is acceptable because the data channel is reliable/ordered; the probe timeout covers the case where the frame is not delivered.
```

Do not add sleeps to “fix” this. A sleep would hide timing bugs and slow the hot path.

---

## 13. `rand_core` dependency

Add `rand_core` to `p2p-tunnel` only if the probe implementation actually generates the nonce in that crate.

If the nonce is generated in `p2p-daemon` or another crate that already has a suitable dependency, do not add a redundant dependency.

Nonce requirements are simple:

- random enough for log correlation and stale-Pong rejection;
- 8 to 16 bytes is enough;
- do not log full nonce if logs are meant to stay redacted;
- compare exact payload bytes in Pong.

---

## 14. Acceptance checklist updates

Add these items to the TODO acceptance section:

### Happy path

- Android emulator + Docker answer succeeds in `auto`.
- Probe succeeds before first stream `OPEN`.
- Logs show data-channel open, probe sent, probe acked, then bridge starts.
- Existing E2E behavior is not regressed.

### Forced vnet path

- `ANDROID_ICE_MODE=vnet` forces `set_vnet=true`.
- Logs prove vnet was selected intentionally.
- If vnet setup fails, startup fails loudly.

### Native path

- `ANDROID_ICE_MODE=native` forces `set_vnet=false`.
- On emulator, failure before data-channel open is acceptable.
- The test must assert the failure is explicit and bounded.

### Black-hole path

- Debug answer drops Ping.
- Offer times out the probe.
- Peer/session is torn down.
- Local client fails without hanging.
- Offer returns to steady/listening state.
- No stream `OPEN` is sent after failed probe.
- No tight reconnect loop occurs.

---

## 15. Final instructions for Claude Code

Please update the spec/TODO according to these decisions before implementing:

1. Clarify that this pass is not a final fix for the original `answer-office` issue.
2. Add black-hole-answer E2E coverage.
3. Define `android_ice_mode` as honored on all platforms.
4. Use debug intent extra or system property for Android test-mode injection.
5. Implement the readiness probe before `run_multiplex_offer()` / before real stream `OPEN`.
6. Make the probe cancel-safe if feasible without introducing multiple data-channel event consumers.
7. Validate `data_plane_probe_timeout_ms > 0`.
8. Avoid any silent fallback between `native`, `vnet`, and `auto`.
9. Preserve existing frame validation; extend tests only where coverage is missing.
10. Ensure probe failure tears down cleanly and does not create a reconnect hot loop.

The plan is otherwise approved. The main addition is the black-hole-answer E2E, because it is the only local test that exercises the headline fail-fast behavior.
