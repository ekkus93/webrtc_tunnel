# Responses to ANDROID_WEBRTC_EMULATOR_DATA_PLANE SPEC + TODO — Questions & Issues

Review of `docs/ANDROID_WEBRTC_EMULATOR_DATA_PLANE_SPEC.md` and
`docs/ANDROID_WEBRTC_EMULATOR_DATA_PLANE_TODO.md`, cross-checked against the actual
source and against findings from the prior controlled-rig investigation
(`docs/ANDROID_WEBRTC_DATA_PLANE_ISSUE.md`, `memory.md`). No code written.

**Overall:** Solid, honestly-scoped pass. The two thrusts — a post-DCEP application
**data-plane readiness probe (Ping/Pong gate)** and an **explicit Android ICE mode
(auto/native/vnet)** — are the right "make it deterministic, observable, fail-fast" moves.
The spec's "Current Code Facts" (§3) are accurate, and the design builds on infrastructure
that already exists (verified below), which de-risks it. The notes below are
clarifications, one important test-coverage gap, and a few implementation risks — none
blocking.

---

## 0. Verified code facts (de-risks the plan; some TODO items are already done)

- `crates/p2p-tunnel/src/multiplex/answer.rs:230` — the answer **already** replies to
  `Ping` with `Pong(frame.payload)`. So the probe's happy path works today.
- `crates/p2p-tunnel/src/frame.rs:61–66` — `TunnelFrame::ping(payload)` and `pong(payload)`
  exist and are pinned to **stream id 0**.
- `crates/p2p-tunnel/src/frame.rs:150–156` — the codec **already enforces** the stream-id
  rules the TODO asks for: stream frames (`Open`/`Data`/`Close`/`Error`) with stream id 0
  are rejected, and `Ping`/`Pong` with a nonzero stream id return
  `TunnelError::SessionControlStreamId`. There is already a test
  `reject_reserved_stream_id_for_stream_frames`. **TODO P0 "frame tests" is mostly
  verify/extend, not new validation.**
- `crates/p2p-tunnel/src/multiplex/offer.rs:227` — the offer already ignores `Pong`.
- `crates/p2p-webrtc/src/lib.rs` — `DataChannelHandle` exposes `send()`, `next_event()`,
  `is_open()`; the probe can be built on these.
- `rand_core` is already a workspace dependency (used in `p2p-core/src/ids.rs`); "add
  rand_core to p2p-tunnel" is just adding it to that crate's `Cargo.toml`.
- The "first data-channel-open timeout" referenced in §3 was added in the prior session
  (commit `092dbb8`); it guards **pre-open** stalls. The new probe guards **post-open**
  stalls — the spec correctly distinguishes them.

The probe is also a **correct detector** for the real failure: the actual bug is
offer→answer SCTP data being dropped, so the offer's `Ping` never reaches the answer → no
`Pong` → probe times out. Good.

---

## 1. Confirm the intent: this pass fails *fast*, it does not *fix* the bug

In the real answer-office failure, the probe will **time out → tear down**; the tunnel
still won't pass traffic — it just fails cleanly in ~5 s (with a clear log) instead of
hanging at 0 bytes. The spec states this (§1, §5), but please confirm alignment:

> The value of this pass is **UX + observability + a test lever** (the explicit
> `vnet`/`native` modes + diagnostics are what a later root-cause pass will use to confirm
> "vnet is the culprit"). It is **not** a fix for the answer-office data-plane failure,
> which stays blocked until that host (or a physical-device remote target) is available.

**Question 1:** Is that the intended framing?

---

## 2. (Most important) The emulator/Docker matrix only exercises the probe's HAPPY path

The prior investigation proved that **no local network shape breaks the A54's data plane**:
same-LAN `--network host`, same-LAN Docker **bridge** (NAT), and even **A54 on cellular →
home Docker answer** all deliver bytes (see the failure matrix in
`ANDROID_WEBRTC_DATA_PLANE_ISSUE.md`). Therefore **every matrix row will have a working
data plane → the probe always succeeds.**

Consequence: the matrix validates "the probe does not break working sessions," but it
**cannot** validate the headline new behavior — "the probe correctly **fails fast and
tears down** on a broken data plane." That path is only covered by the TODO's unit tests.

**Strong recommendation:** add a deliberate **black-hole answer** mode to the E2E — an
answer that opens the WebRTC data channel but **never replies to `Ping`** (e.g. a debug
flag on `p2p-answer`, or a tiny stand-in answer) — so the fail-fast teardown + return to
listening + non-hanging local client are actually exercised end-to-end. Otherwise the most
important new behavior is never run in the matrix.

**Question 2:** Do you want the black-hole-answer E2E added, or is unit-test coverage of
the probe-failure path acceptable for this pass?

---

## 3. (Genuine ambiguity) "Android ICE mode" platform semantics are contradictory

Today the vnet fallback is selected at **runtime by interface-enumeration success**, NOT by
`#[cfg(target_os = "android")]` (confirmed: there is no `android` cfg gating in
`p2p-webrtc` or `p2p-core`). So `android_ice_mode` is effectively **cross-platform config**.

§7.1 contradicts itself:
- "On non-Android: accept but **ignore** `android_ice_mode`; log that it is ignored."
  → implies the mode is gated/ignored off-Android.
- "`vnet`: force `Net::Ifs` fallback **even if OS enumeration would have worked**."
  → only meaningful if the mode is **honored regardless of platform**.

**Question 3:** Is the mode **honored on all platforms** (pure config; emulator *and*
desktop obey it) or **Android-only** (ignored elsewhere)?

Recommendation: **honored on all platforms.** It is the simplest, most testable, and
removes the contradiction. The name `android_ice_mode` can stay (historical). Note: the
Android **emulator runs real Android 11+**, so all three modes are exercisable there
regardless of this decision — but desktop `p2p-offer`/`p2p-answer` (used by other e2e and
integration tests, e.g. `real_broker_tunnel.rs`, `two_node_daemon.rs`) need a defined
answer for what `native`/`vnet` do on desktop.

---

## 4. (Risk) Injecting `android_ice_mode` from the E2E has a SELinux caveat

The Android app always generates `android_ice_mode = "auto"` (per §6.1), so forcing
`native`/`vnet` requires overriding the generated config. Of the options §9.1 lists:

- **"Patch app-private config" works on the emulator** (permissive `run-as`) but **fails on
  a physical Samsung A54** — Android 16 SELinux **blocks `run-as` writes** to app-private
  files. (We hit exactly this wall in the prior session; reads work, writes do not.)
- The e2e/debug scripts now also drive **physical devices** (recent fixes in
  `lib/android_wizard.sh`), so the mode-injection mechanism should ideally be
  **device-agnostic**.

**Recommendation:** prefer the **test-only intent-extra / system-property** route (the
debug build reads it and writes it into the generated config) — works on emulator *and*
physical. Also: any config patch must happen **after** the wizard and **before** Start
(the app does not regenerate config on a plain Start, so a post-wizard patch sticks; but
re-running the wizard regenerates `"auto"`).

---

## 5. (Implementation choice) Where the probe runs in the offer session loop

`crates/p2p-daemon/src/offer/session/mod.rs` drives a `tokio::select!` loop. If the probe
is a **blocking `await`** inside the bridge-start block, the loop will not process
ICE-state / signaling events for up to the probe timeout (default 5 s). A mid-probe ICE
disconnect would then only be handled after the probe completes/times out.

Minor (the timeout bounds it), but worth a decision: run the probe as a **cancel-safe
select arm racing against ICE-failed/disconnected**, vs a blocking await. §8.4's "do not
run the probe concurrently with the initial stream OPEN" constrains OPEN ordering, not ICE
handling — so a select-arm probe is allowed.

---

## 6. (Behavior) Reconnect / retry cadence on a persistently-broken data plane

After a probe failure → teardown → steady state, with `enable_auto_reconnect = true` and a
persistently-broken data plane, confirm the next local client does **not** trigger a tight
`negotiate → probe-fail → negotiate → probe-fail` loop. A retrying browser would otherwise
hit repeated ~5–35 s failures. Fail-fast **per request** is fine; just ensure backoff
applies and there is no hot loop. (Define the intended cadence in the spec.)

---

## 7. Smaller accuracy notes

- **`native` matrix row latency:** on the emulator, `native` gathers **no** candidates (no
  enumeration → no host *and* no srflx), so the data channel never opens and the row fails
  via the **first-open timeout (~30 s)**, not the probe timeout. The matrix's
  `EXPECTED_FAIL` handling should key on that path/latency, and should assert `set_vnet`
  was not used (as §9.2 already requires).
- **Config templates are shared:** `[webrtc]` and `[tunnel]` live in the
  `STATIC_TLS_WEBRTC_TUNNEL_SECTIONS` constant in `ConfigTemplates.kt`, so one edit updates
  **both** `buildOfferConfig` and `buildDefaultConfigTemplate`. Good — just be aware both
  use it.
- **`data_plane_probe_timeout_ms = 0`:** serde `default` handles *parsing*, not *range*.
  Rejecting 0 needs an explicit validation hook (wherever `TunnelConfig` is validated).
- **Probe adds one RTT (worst case the full timeout) to every session setup.** For working
  sessions this is a small added latency; acceptable, but note it (the probe is a mandatory
  gate on the hot path).
- **Probe vs answer-loop start race (benign):** the offer creates the data channel and may
  send `Ping` before the answer's `run_multiplex_answer` is reading. SCTP is
  reliable+ordered, so the `Ping` is buffered and delivered when the answer starts reading
  — no loss within the 5 s window. Worth a one-line comment so nobody "fixes" it later.

---

## 8. Points of agreement

- Not implementing TURN; keeping STUN-only; not requiring `answer-office` — correct and
  consistent with the project constraints and the current (down) state of answer-office.
- Making the implicit `set_vnet` fallback **explicit + observable + fail-loud** is the
  right call; the implicit auto-selection is exactly what made the original failure hard to
  test.
- Redacted, high-signal logging at the decision points and probe milestones — aligned with
  the existing redaction posture.
- `build_setting_engine(&WebRtcConfig) -> Result<...>` (so `vnet` can fail loudly) — sound.

---

## 9. Two questions back (summary)

1. **Framing:** Confirm this pass is "deterministic + fail-fast + observable + a test lever
   for the later root-cause work," and explicitly **not** a fix for the answer-office
   data-plane failure (blocked until that host returns). (§1 above.)
2. **Test coverage:** Add a **black-hole-answer E2E** to actually exercise the probe's
   fail-fast/teardown path (since no local network reproduces the real data-plane break),
   or is unit-test coverage of the probe-failure path sufficient? (§2 above.)

Plus one decision needed before implementation: **§3** — is `android_ice_mode` honored on
all platforms or Android-only? — and a recommended approach for **§4** (device-agnostic
mode injection).
