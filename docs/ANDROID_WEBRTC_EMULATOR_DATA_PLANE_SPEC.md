# Android WebRTC Emulator Data-Plane Hardening Spec

## 1. Purpose

This spec defines the next implementation pass for the Android side of the WebRTC tunnel app after the investigation documented in `docs/ANDROID_WEBRTC_DATA_PLANE_ISSUE.md`.

The immediate goal is **not** to solve every real-world NAT traversal case. The immediate goal is to make the Android offer data plane deterministic, observable, and fail-fast using the current **Docker + Android emulator** development rig.

The work must preserve the current project constraint: **TURN is out of scope and must not be implemented in this pass.**

`answer-office` is currently down and must not be required for this implementation or acceptance testing.

**Framing (read this first).** This pass adds a **detector and guardrail** for post-DCEP
data-plane failure; it does **not** prove or repair the original remote
NAT/firewall/Android-vnet interaction documented in
`docs/ANDROID_WEBRTC_DATA_PLANE_ISSUE.md`. In the real `answer-office` scenario the
expected (and *successful for this pass*) outcome is:

```text
data channel open -> probe sent -> no Pong -> probe timeout -> session torn down cleanly -> clear error
```

That is strictly better than the old behavior (the tunnel could appear connected while the
client hung at zero bytes), but it is **not a fix**. A later root-cause pass needs
`answer-office` back online (or another physical-device remote target). The explicit ICE
modes (§7) and probe diagnostics (§8) introduced here are the **test lever** that root-cause
work will use (e.g. force `vnet`/`native` and observe). The emulator/Docker matrix here
cannot reproduce the original failure (see §5 and the issue note), so it cannot prove the
fix either.

## 2. Problem Summary

The documented failure mode is:

1. Android offer accepts a local TCP client.
2. WebRTC negotiation completes.
3. ICE reaches `connected`.
4. DTLS and SCTP complete.
5. DCEP opens the reliable/ordered data channel.
6. Application tunnel traffic does not complete a round trip.
7. The local TCP client receives 0 bytes or hangs.

The important distinction is that **data-channel open is not proof that the tunnel data plane is usable**. The app currently moves into the bridge path too early.

The current Android-specific `SettingEngine::set_vnet(Net::Ifs(...))` fallback is also too implicit. It exists to work around Android interface-enumeration restrictions, but it is hard to test because it is selected automatically and silently except for logs. This pass must make that behavior explicit, configurable, and visible in diagnostics.

## 3. Current Code Facts

Relevant current code paths:

- Issue note: `docs/ANDROID_WEBRTC_DATA_PLANE_ISSUE.md`
- WebRTC setting engine: `crates/p2p-webrtc/src/lib.rs`
  - `build_setting_engine()` currently applies the `set_vnet(Net::Ifs(...))` fallback when OS interface enumeration does not return a usable IPv4 address.
  - `fallback_net()` injects an interface named `p2p-fallback` with the address from `primary_local_ipv4()`.
  - `primary_local_ipv4()` discovers the source IPv4 through a UDP socket connected to `8.8.8.8:80`.
- Tunnel frame support: `crates/p2p-tunnel/src/frame.rs`
  - `TunnelFrame::ping(payload)` exists.
  - `TunnelFrame::pong(payload)` exists.
  - `TunnelFrameType::Ping` and `TunnelFrameType::Pong` are already valid session-control frames on stream id 0.
- Answer-side multiplex loop: `crates/p2p-tunnel/src/multiplex/answer.rs`
  - Already replies to `Ping` with `Pong`.
- Offer-side multiplex loop: `crates/p2p-tunnel/src/multiplex/offer.rs`
  - Currently ignores `Pong`.
  - Currently sends the initial stream `OPEN` as soon as `run_multiplex_offer()` starts.
- Offer session state machine: `crates/p2p-daemon/src/offer/session/mod.rs`
  - Starts the tunnel bridge once `data_channel.is_open()` is true.
  - Has a timeout for the first data-channel open.
  - Does not currently require a tunnel-level data-plane probe before declaring the bridge usable.
- Android config generation: `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigTemplates.kt`
  - Generates the Android offer TOML.
- Existing emulator/Docker scripts:
  - `tests/e2e/android_tunnel_e2e.sh`
  - `tests/e2e/android_tunnel_debug.sh`
  - `tests/e2e/lib/android_wizard.sh`

## 4. Goals

### G1. Add an application data-plane readiness gate

After the WebRTC data channel opens, the offer must send a tunnel-level `Ping` frame and require the matching `Pong` before the local TCP stream is bridged.

Healthy sequence:

```text
ICE connected
Data channel open
Offer sends tunnel Ping nonce=N
Answer replies with tunnel Pong nonce=N
Offer logs probe success
Offer sends stream OPEN
Answer logs received OPEN
Answer connects target TCP
Offer receives OPEN ack
TCP bytes flow
```

Failure sequence:

```text
ICE connected
Data channel open
Offer sends tunnel Ping nonce=N
No matching Pong before timeout
Offer closes/tears down session
Daemon returns to waiting/listening state
Local TCP client is closed instead of hanging forever
Logs clearly say data-plane probe failed
```

### G2. Make Android ICE fallback mode explicit

Add a config-driven Android ICE mode with these values:

- `auto`
- `native`
- `vnet`

The selected mode must be logged and made available to diagnostics/status where practical.

The existing implicit Android fallback behavior must become an explicit `auto` decision. If `native` is selected, the code must not quietly fall back to `vnet`. If `vnet` is selected, the code must force the current fallback behavior or fail loudly if it cannot construct the fallback interface.

### G3. Improve emulator/Docker E2E coverage

The development test target is the Android emulator plus Dockerized answer. Add a repeatable matrix that exercises the new Android ICE modes and the tunnel data-plane probe.

The matrix must not require `answer-office`.

### G4. Improve logs and diagnostics

The implementation must add redacted, high-signal logs for:

- Android ICE mode requested.
- Android ICE mode actually selected.
- Whether OS interface enumeration worked.
- Whether `set_vnet` fallback was applied.
- Fallback local IPv4 discovery success/failure.
- Data channel open.
- Data-plane probe sent.
- Data-plane probe matched.
- Data-plane probe timed out.
- Unexpected pre-probe stream frames.
- Writer send failures.

Do not log payload bytes, private keys, SDP, secrets, or unredacted candidates unless the existing explicit debug/redaction rules allow it.

## 5. Non-Goals

Do not implement TURN.

Do not remove the current STUN-only constraint.

Do not require `answer-office` for validation.

Do not claim that the emulator/Docker matrix proves the original physical-phone/coworking-NAT failure is fixed. The emulator network is useful for deterministic development, but it is not equivalent to a physical Android phone behind arbitrary Wi-Fi/cellular NAT.

Do not replace the entire WebRTC stack in this pass.

Do not implement a full Android `ConnectivityManager` / `LinkProperties` native interface provider in this pass unless all P0/P1 tasks are already complete. That can be a later real-device hardening pass.

Do not add silent fallback behavior. Every fallback or degraded path must be logged and testable.

## 6. Config Changes

### 6.1 `WebRtcConfig.android_ice_mode`

Add this field to `p2p_core::WebRtcConfig`:

```rust
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AndroidIceMode {
    Auto,
    Native,
    Vnet,
}
```

```rust
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WebRtcConfig {
    pub stun_urls: Vec<String>,
    pub enable_trickle_ice: bool,
    pub enable_ice_restart: bool,
    #[serde(default = "default_android_ice_mode")]
    pub android_ice_mode: AndroidIceMode,
}

pub const fn default_android_ice_mode() -> AndroidIceMode {
    AndroidIceMode::Auto
}
```

Existing config files without `android_ice_mode` must continue to parse and must default to `auto`.

Generated Android configs should include the field explicitly:

```toml
[webrtc]
stun_urls = ["stun:stun.l.google.com:19302"]
enable_trickle_ice = true
enable_ice_restart = true
android_ice_mode = "auto"
```

### 6.2 `TunnelConfig.data_plane_probe_timeout_ms`

Add this field to `p2p_core::TunnelConfig`:

```rust
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TunnelConfig {
    pub read_chunk_size: usize,
    pub local_eof_grace_ms: u64,
    pub remote_eof_grace_ms: u64,
    #[serde(default = "default_data_plane_probe_timeout_ms")]
    pub data_plane_probe_timeout_ms: u64,
}

pub const fn default_data_plane_probe_timeout_ms() -> u64 {
    5000
}
```

Existing configs must continue to parse.

**Range validation (serde does not do this).** `#[serde(default)]` handles a *missing*
field, not an *invalid value* — `data_plane_probe_timeout_ms = 0` parses fine and would mean
a zero-length probe deadline (instant false failure). Add an explicit validation hook
wherever `TunnelConfig` is validated that **rejects `0`** and enforces sane bounds:
minimum **100 ms**, default **5000 ms**, maximum on the order of **60000 ms** (~60 s).
Reject out-of-range values with a clear config error; do not clamp silently.

Generated Android configs and test configs should include:

```toml
[tunnel]
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250
data_plane_probe_timeout_ms = 5000
```

Do not add a user-facing option to disable the probe in this pass. The probe is a required correctness gate.

## 7. Android ICE Mode Semantics

### 7.1 Platform behavior

**`android_ice_mode` is honored on ALL platforms, despite the historical name.** The
current vnet fallback is already selected at *runtime* by interface-enumeration success,
not by `#[cfg(target_os = "android")]`, so this is just a cross-platform diagnostic knob.
Do **not** ignore it on desktop — desktop integration tests must be able to force
`native`/`vnet` too. Add a short comment in code to that effect:

```text
android_ice_mode is historical naming. The setting is honored on all platforms so tests can
exercise native/vnet behavior outside Android too.
```

The three modes (same on every platform):

- `auto` (default; preserves current production behavior, now observable):
  - Run OS interface enumeration.
  - If enumeration yields at least one non-loopback IPv4 address, use native/default `SettingEngine`.
  - If enumeration fails or yields no usable address, apply the existing fallback `Net::Ifs` behavior.
  - **No silent fallback:** always log the requested mode and the selected decision + reason, e.g.
    `ice_mode=auto selected_path=native set_vnet=false reason=interface_enumeration_ok` or
    `ice_mode=auto selected_path=vnet set_vnet=true reason=interface_enumeration_failed`.
- `native`:
  - Always use the default/native `SettingEngine`; never call `set_vnet`.
  - If interface enumeration fails or no usable candidate is produced, do **not** fall back —
    fail loudly through the normal open/connect timeout path.
  - On the Android emulator (Android 11+) this mode may gather no candidates and fail before
    the data channel opens; that is acceptable as long as it fails loudly and predictably and
    `set_vnet` was not used. Log `ice_mode=native set_vnet=false`.
- `vnet`:
  - Force `Net::Ifs` fallback construction even if OS enumeration would have worked.
  - If the interface list / fallback local IPv4 cannot be constructed, return a
    configuration/startup error. Do **not** silently fall back to native. Log
    `ice_mode=vnet set_vnet=true`.

Note: the Android **emulator runs real Android 11+**, so all three modes are exercisable
there; honoring the modes on desktop additionally lets desktop integration tests
(`real_broker_tunnel.rs`, `two_node_daemon.rs`) exercise `native`/`vnet` predictably.

### 7.2 Function shape

Change this:

```rust
fn build_setting_engine() -> SettingEngine
```

to something like this:

```rust
fn build_setting_engine(config: &WebRtcConfig) -> Result<SettingEngine, WebRtcError>
```

Then call it from `WebRtcPeer::new(config)`:

```rust
let setting_engine = build_setting_engine(config)?;
let api = APIBuilder::new()
    .with_media_engine(media_engine)
    .with_setting_engine(setting_engine)
    .build();
```

This is required because `vnet` mode must be able to fail loudly if it cannot construct the requested fallback.

### 7.3 Diagnostic return structure

Internally, use a small diagnostic struct so logs/tests can inspect the decision:

```rust
#[derive(Clone, Debug, Eq, PartialEq)]
struct SettingEngineDecision {
    requested_mode: AndroidIceMode,
    selected_mode: SelectedIceMode,
    os_enumeration_worked: bool,
    fallback_ipv4_found: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SelectedIceMode {
    Native,
    VnetFallback,
}
```

This struct does not need to be public unless useful for tests. It should drive consistent logging.

## 8. Data-Plane Probe Design

### 8.1 Probe API

Add a new function to `p2p-tunnel`, probably in `crates/p2p-tunnel/src/multiplex/mod.rs` or a new `probe.rs` module:

```rust
pub async fn probe_data_plane(
    data_channel: &DataChannelHandle,
    timeout: Duration,
) -> Result<(), TunnelError>
```

This function must:

1. Generate a probe nonce.
2. Encode a `TunnelFrame::ping(nonce)`.
3. Send it directly over the data channel.
4. Wait for a matching `TunnelFrame::pong(nonce)`.
5. Return success only on exact nonce match.
6. Return a distinct timeout error if no matching Pong arrives before the deadline.
7. Return a distinct closed-channel error if the data channel closes.
8. Reply to inbound `Ping` frames with `Pong` while waiting.
9. Ignore or warn on mismatched `Pong` frames.
10. Treat inbound stream frames before the probe completes as protocol errors unless there is a strong reason to ignore them.

### 8.2 Nonce generation

Generate a 16-byte nonce with `OsRng`. `rand_core` is already a **workspace** dependency
(used by `p2p-core/src/ids.rs`), so this is only adding it to `p2p-tunnel`'s own
`Cargo.toml` (`rand_core = { workspace = true }`), not introducing a new third-party dep.

Do not use a predictable constant nonce except in unit tests.

Log only a short redacted nonce id if needed, such as the first 4 bytes hex-encoded. Do not log arbitrary payload bytes.

### 8.3 Error type

Add explicit `TunnelError` variants:

```rust
#[error("data-plane probe timed out after {0:?}")]
DataPlaneProbeTimeout(std::time::Duration),

#[error("data-plane probe failed: {0}")]
DataPlaneProbeFailed(String),
```

Use the timeout variant for timeout specifically so the daemon/UI/logs can distinguish it from general tunnel failure.

### 8.4 Offer session integration

In `crates/p2p-daemon/src/offer/session/mod.rs`, do not mark the session `TunnelOpen` and do not start the bridge merely because `data_channel.is_open()` returns true.

The healthy transition should be:

```text
Negotiating / ConnectingDataChannel
  -> data channel open
  -> probe_data_plane(...)
  -> probe success
  -> TunnelOpen
  -> run_multiplex_offer(...)
```

Implementation option:

- When `data_channel.is_open()` is true and the bridge is not active, run the probe first.
- Only after probe success:
  - write status `TunnelOpen`,
  - set `bridge_ever_active = true`,
  - set `session.bridge_state = BridgeSessionState::Active`,
  - start `run_multiplex_offer(...)`.
- On probe failure:
  - log warning with session id, remote peer id, timeout, and error type,
  - return an error from the session,
  - cleanup the peer connection,
  - let the daemon return to listening/waiting state.

**Probe must NOT block ICE handling (cancel-safe).** The probe must run as a **cancel-safe
`tokio::select!` arm that races against ICE `Failed`/`Disconnected`/`Closed`** — not a bare
blocking `await` inside the bridge-start block. If the probe were a blocking await, the
session loop would stop processing ICE-state/signaling events for up to
`data_plane_probe_timeout_ms` (default 5 s), so a mid-probe ICE disconnect would only be
handled after the probe finishes. Racing the probe against ICE teardown bounds this and lets
ICE failure abort the probe immediately.

**Ordering constraint is separate from cancel-safety.** "Do not run the probe concurrently
with the initial stream `OPEN`; the probe must happen before `OPEN`" constrains *OPEN
ordering only*. It does not require the probe to block ICE handling. Concretely: there must
be exactly **one consumer** of `DataChannelHandle::next_event()` — the probe owns it until
it succeeds/fails, then hands off to `run_multiplex_offer`. The probe and the first real
`OPEN` are never in flight at the same time, but the probe *is* concurrent with ICE-state
handling.

**Benign probe-vs-answer-start race (do not "fix" it).** The offer may send `Ping` before
the answer's `run_multiplex_answer` has started reading. SCTP is reliable + ordered, so the
buffered `Ping` is delivered once the answer starts reading — no loss inside the timeout
window. Add a one-line code comment so a future reader does not mistake this for a bug.

### 8.4a Reconnect / retry cadence on a persistently-broken data plane

After a probe failure → teardown → steady state, with `enable_auto_reconnect = true` and a
**persistently** broken data plane, a retrying local client (e.g. a browser reload loop)
must **not** drive a tight `negotiate → probe-fail → negotiate → probe-fail` hot loop.

Important: the offer negotiates **lazily per local client connection**, so the existing
`enable_auto_reconnect` backoff (which governs *reconnect / ICE-restart*) does **not** by
itself cover a *fresh* client-triggered negotiation that keeps failing the probe. To bound
this, add a small **per-remote-peer probe-failure cooldown**: after a probe failure to a
given remote peer, suppress re-negotiation to that peer for a short window (suggest a few
seconds, exponential up to a cap) so repeated local connects fail fast against the cooldown
instead of re-running the full negotiate+probe each time. Fail-fast **per request** stays
the behavior; the cooldown only prevents the hot loop. Define and log the cadence.

### 8.5 Answer behavior

The answer side already replies to `Ping`. Keep that behavior and add/verify logs:

```text
answer received tunnel PING; sending PONG
```

The log must not include payload bytes.

## 9. Emulator/Docker Test Requirements

### 9.1 Update existing E2E script behavior

Update `tests/e2e/android_tunnel_e2e.sh` to support:

```bash
ANSWER_NET=host|bridge
ANDROID_ICE_MODE=auto|native|vnet
```

If `ANSWER_NET=bridge`, the script must run the answer container behind Docker bridge networking and configure the answer target host appropriately, matching the behavior already available in `tests/e2e/android_tunnel_debug.sh`.

If `ANDROID_ICE_MODE` is set, the generated Android offer config must contain that mode before the tunnel service starts.

**Preferred mechanism (device-agnostic): debug-build intent-extra or system property.** The
debug build reads a test-only signal — e.g. `debug.p2p.android_ice_mode` system property, or
an intent extra on the service-start intent — and writes the selected mode into the
generated config itself. This works on **both** the emulator and **physical** devices, which
matters because the e2e/debug scripts now drive physical devices too
(`tests/e2e/lib/android_wizard.sh`).

**Do NOT rely on `run-as` config patching as the primary mechanism.** Patching the
app-private config file with `run-as` works on the emulator (permissive) but **fails on a
physical Samsung A54** — Android 16 **SELinux blocks `run-as` writes** to app-private files
(reads work, writes do not). It may remain a documented emulator-only fallback, but the
intent/property route is the one that must work everywhere.

**Timing:** any config override must be applied **after** the wizard completes and **before**
Start. The app does not regenerate config on a plain Start, so a post-wizard override sticks;
but **re-running the wizard regenerates `android_ice_mode = "auto"`**, overwriting the
override. Inject after the wizard, never before.

Do not implement this as an environment variable that Rust reads only on desktop; the Android app process must actually receive and use the selected mode.

### 9.2 Add matrix script

Add:

```text
tests/e2e/android_tunnel_matrix.sh
```

Required rows:

```bash
ANDROID_ICE_MODE=auto  ANSWER_NET=host   tests/e2e/android_tunnel_e2e.sh
ANDROID_ICE_MODE=auto  ANSWER_NET=bridge tests/e2e/android_tunnel_e2e.sh
ANDROID_ICE_MODE=vnet  ANSWER_NET=host   tests/e2e/android_tunnel_e2e.sh
ANDROID_ICE_MODE=vnet  ANSWER_NET=bridge tests/e2e/android_tunnel_e2e.sh
```

Optional diagnostic row:

```bash
ANDROID_ICE_MODE=native ANSWER_NET=host tests/e2e/android_tunnel_e2e.sh
```

The `native` row may be allowed to fail on emulator/Android 11+ if it fails with a clear expected error and no fallback was used. The matrix script should report it as `EXPECTED_FAIL` unless `EXPECT_NATIVE_ICE_PASS=1` is set.

Accuracy note for the `native` row: on the emulator, `native` gathers **no** candidates (no
interface enumeration → no host *and* no srflx), so the **data channel never opens** and the
row fails via the **first-data-channel-open timeout (~30 s)**, *not* the probe timeout
(~5 s). The `EXPECTED_FAIL` handling should key on that path/latency, and must assert that
`set_vnet` was **not** used (i.e. `ice_mode=native set_vnet=false`).

### 9.2a Add a black-hole-answer E2E (exercises the probe FAILURE path)

The emulator/Docker matrix above only exercises the probe's **happy path**: the prior
investigation proved that **no local network shape breaks the data plane** (same-LAN host,
same-LAN Docker bridge/NAT, and even cellular→home all deliver bytes — see the failure
matrix in `docs/ANDROID_WEBRTC_DATA_PLANE_ISSUE.md`). So every matrix row has a working data
plane and the probe always succeeds. The matrix therefore validates "the probe does not
break working sessions" but **cannot** validate the headline new behavior — "the probe fails
fast and tears down on a broken data plane."

Add a deliberate **black-hole answer** to cover the failure path end-to-end:

- Add a **debug-only** flag to `p2p-answer` that opens the WebRTC data channel normally but
  **silently drops inbound `Ping`** (never replies `Pong`) — e.g. `--debug-drop-ping`
  (CLI) gated by an env var such as `P2P_TUNNEL_DEBUG_DROP_PING=1`. It must be debug/test
  only and must not affect normal operation.
- Add a matrix/E2E row that runs the Android offer against this black-hole answer and asserts:
  1. the data channel opens,
  2. the offer sends `Ping` and receives **no** `Pong`,
  3. the probe **times out at ~`data_plane_probe_timeout_ms`** (not the ~30 s open timeout),
  4. the session tears down cleanly and the daemon returns to listening/waiting,
  5. the local TCP client does **not** hang — it sees a prompt connection refusal/close,
  6. there is **no** reconnect hot loop (per §8.4a).

This is the only matrix path that actually runs the fail-fast teardown end-to-end; unit
tests cover the probe logic, but this proves the whole-session behavior.

### 9.3 Required log assertions

The E2E script must assert that logs contain evidence of the new probe:

Offer-side expected log fragments:

```text
data-plane probe sent
data-plane probe acknowledged
```

Answer-side expected log fragments:

```text
received tunnel PING
sending tunnel PONG
```

Where possible, the script should also print the selected Android ICE mode from logs or app diagnostics.

## 10. Android UI / Diagnostics Requirements

This pass does not require a full UI settings screen for Android ICE mode.

However, diagnostics/logs exposed through the existing Logs screen and native recent-log bridge should include:

- requested Android ICE mode,
- selected Android ICE path,
- probe sent,
- probe success/failure.

If a data-plane probe fails, the user-visible status should not remain misleadingly stuck at `TunnelOpen`. It should return to a waiting/listening state after cleanup, and logs should clearly state the failure.

## 11. Validation Requirements

Run at minimum:

```bash
cargo fmt --all
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cd android && ./gradlew --no-daemon testDebugUnitTest
cd android && ./gradlew --no-daemon assembleDebug
```

Run emulator/Docker E2E:

```bash
tests/e2e/android_tunnel_e2e.sh
ANSWER_NET=bridge tests/e2e/android_tunnel_e2e.sh
tests/e2e/android_tunnel_matrix.sh
```

Run debug rig manually if a row fails:

```bash
tests/e2e/android_tunnel_debug.sh
ANSWER_NET=bridge tests/e2e/android_tunnel_debug.sh
```

## 12. Acceptance Criteria

Implementation is accepted when:

1. Existing config files without new fields still parse.
2. Generated Android configs include explicit `android_ice_mode = "auto"` and `data_plane_probe_timeout_ms = 5000`.
3. Android ICE `auto`, `native`, and `vnet` modes are accepted by config validation.
4. `native` mode never calls `set_vnet`.
5. `vnet` mode either applies `set_vnet` or fails loudly.
6. `auto` mode logs its decision and preserves current successful emulator behavior.
7. Offer sends a tunnel-level Ping after data-channel open and before the initial stream OPEN.
8. Offer requires a matching Pong before starting `run_multiplex_offer()`.
9. A probe timeout tears down the session and returns the daemon to listening/waiting state.
10. The local TCP client no longer hangs indefinitely on a post-DCEP data-plane stall.
11. E2E logs show probe sent and probe acknowledged in healthy emulator/Docker runs.
12. The matrix script runs auto/vnet rows for host and bridge Docker answer modes.
13. No TURN support is added.
14. No `answer-office` dependency is added to tests.
15. No new silent fallback path is introduced.
16. A **black-hole-answer** E2E (§9.2a) exercises the probe-failure path end-to-end: data
    channel opens, no Pong, probe times out at ~`data_plane_probe_timeout_ms`, clean
    teardown, return to listening, local client does not hang.
17. The probe is **cancel-safe** — it races ICE failure (§8.4) and never blocks ICE-state
    handling for the probe duration; there is exactly one consumer of `next_event()`.
18. A **persistently-broken** data plane does not produce a reconnect hot loop; a
    per-remote-peer probe-failure cooldown bounds re-negotiation (§8.4a).
19. `data_plane_probe_timeout_ms = 0` (and out-of-range values) is rejected by config
    validation (§6.2); valid range enforced.
20. `android_ice_mode` is honored on **all** platforms (desktop integration tests can force
    `native`/`vnet`), not gated/ignored off Android (§7.1).
21. This pass is documented as a detector/guardrail + test lever, **not** a fix for the
    real `answer-office` data-plane failure (§1 framing).

## 13. Future Work Explicitly Not in This Pass

Later, after `answer-office` or a physical-device remote test target is available again, consider:

- Android `ConnectivityManager` / `LinkProperties` based interface discovery.
- Binding the Android process to the selected active `Network` before starting WebRTC sockets.
- Packet capture on both peers to identify whether offer DATA or answer SACKs are dropped.
- Filtering Docker/private host candidates on public answer nodes.
- Conservative data-channel payload sizing / SCTP MTU hardening.
- Upgrading `webrtc-rs` after the current behavior is well-instrumented.

Do not mix those future items into this implementation pass unless explicitly requested.
