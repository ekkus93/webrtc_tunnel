# Android ↔ p2p-answer Data-Channel Debugging and Transport Hardening TODO

This TODO implements `ANDROID_P2P_ANSWER_DATACHANNEL_DEBUG_SPEC.md`.

Each task has a priority:

- **P0** — Blocking diagnostic/correctness item. Required before making a reliable root-cause claim.
- **P1** — High-priority implementation/test item. Should be done in this pass unless P0 evidence proves it unnecessary.
- **P2** — Useful hardening/follow-up item. Do after P0/P1 if still relevant.
- **P3** — Optional experiment/cleanup. Do only if earlier work does not explain the bug.

---

## Phase 1 — Establish evidence before changing transport

> **Already established (do not re-run unless correlating with new instrumentation):**
> `toybox nc` reproduces the failure (0 bytes) — T4.1 done; Android local TCP accept
> fires — T4.2 substantially proven; Android uses the fallback net while the laptop does
> not — Phase 3 fallback usage answered; laptop-vs-phone selected pair compared (same
> srflx↔srflx, opposite result) — T1.3 done once; SCTP asymmetry observed. The remaining
> P0 gap is **packet localization**: phone-side and answer-side capture.

### T1.0 — Capture phone-side UDP traffic (NEW — highest leverage)

**Priority:** P0

**Files / tools:**

- rooted phone `tcpdump`, or PCAPdroid (no-root, VPN-based)
- Android app logs

**Tasks:**

- [ ] Capture Android app traffic during the failing local-forward request (root `tcpdump` or PCAPdroid).
- [ ] Filter for the selected answer srflx address/port if known.
- [ ] Confirm whether the phone emits UDP packets after DCEP/data-channel open when offer-side SCTP DATA/SACK should be sent.
- [ ] Correlate phone capture with Android logs and (if available) answer-side capture.

**Interpretation:**

- Phone emits UDP but answer host never sees it → Wi-Fi/router/NAT/transit.
- Phone emits no UDP after the stall → Android/`webrtc-rs` socket/vnet/SCTP send path.
- Phone emits and answer sees incoming → answer-side WebRTC/SCTP/demux.

**Acceptance criteria:**

- [ ] We know whether the phone actually sends the stalled SCTP DATA/SACK onto the network.
- [ ] If neither root nor PCAPdroid is available, explicitly mark "phone actually sent UDP" as **unproven** and proceed with the minimal echo test.

### T1.1 — Capture Android traffic on answer host

**Priority:** P0 **if answer-office host access exists; otherwise BLOCKED-BY-ACCESS (not failed)**

**Files / tools:**

- answer host shell
- `tcpdump`
- reproduction notes

**Tasks:**

- [ ] Identify Android public IP for the failing run.
- [ ] Start broad host capture:

  ```bash
  sudo tcpdump -ni any 'host <ANDROID_PUBLIC_IP> and udp' -w android-webrtc-any.pcap
  ```

- [ ] Reproduce Android browser request through local forward.
- [ ] Save app logs, answer logs, and capture file together with a timestamp.
- [ ] Record selected candidate pair/ports if logs expose them.
- [ ] Repeat with port-specific capture if selected ports are known:

  ```bash
  sudo tcpdump -ni any 'host <ANDROID_PUBLIC_IP> and (udp port <PORT1> or udp port <PORT2>)' -w android-webrtc-selected-ports.pcap
  ```

**Acceptance criteria:**

- [ ] Capture exists for at least one failing Android run.
- [ ] Capture time window covers ICE/DTLS/SCTP/data-channel-open and failed HTTP request.
- [ ] Logs and capture can be correlated by timestamp.

---

### T1.2 — Capture Docker/container path if answer runs in Docker

**Priority:** P0 **if answer-office host access exists; otherwise BLOCKED-BY-ACCESS**

**Files / tools:**

- answer host shell
- Docker host
- `tcpdump`

**Tasks:**

- [ ] Determine whether `p2p-answer` is running in Docker.
- [ ] Capture on Docker bridge:

  ```bash
  sudo tcpdump -ni docker0 'host <ANDROID_PUBLIC_IP> and udp' -w android-webrtc-docker0.pcap
  ```

- [ ] If practical, capture inside the container namespace.
- [ ] Reproduce the same Android failure.
- [ ] Compare host `any` capture vs `docker0` / container capture.

**Acceptance criteria:**

- [ ] We know whether Android UDP packets reach the host only, Docker bridge, and/or container.
- [ ] Docker involvement is classified as likely, unlikely, or still unknown.

---

### T1.3 — Capture known-working laptop comparison

**Priority:** P0 — **ALREADY RUN ONCE** (selected pairs compared, same srflx↔srflx,
opposite result; see SPEC "Established evidence"). Re-run only to correlate with new
instrumentation or a fresh capture.

**Files / tools:**

- working laptop offer
- answer host shell
- `tcpdump`

**Tasks:**

- [ ] Run the same forward against the same answer server from a known-working laptop offer.
- [ ] Capture:

  ```bash
  sudo tcpdump -ni any 'host <LAPTOP_PUBLIC_IP> and udp' -w laptop-webrtc-any.pcap
  ```

- [ ] Save laptop offer logs and answer logs.
- [ ] Compare packet flow after data-channel open against Android failure.

**Acceptance criteria:**

- [ ] Known-working capture exists.
- [ ] Android failing capture can be compared against laptop successful capture.
- [ ] Differences are summarized in notes.

---

### T1.4 — Write a short diagnostic conclusion from captures

**Priority:** P0

**Files:**

- `memory.md` or new diagnostic note under `docs/`

**Tasks:**

- [ ] Summarize whether packets stop before host, before container, inside WebRTC, inside tunnel mux, or at target TCP.
- [ ] Include exact capture filenames.
- [ ] Include relevant timestamps.
- [ ] Include selected candidate pair if known.
- [ ] State what evidence is still missing.

**Acceptance criteria:**

- [ ] A future developer can read the note and understand what was proven.
- [ ] No speculative root cause is presented as fact.

---

## Phase 2 — Add tunnel-frame instrumentation

### T2.1 — Add offer-side frame send/receive logs

**Priority:** P0

**Files to inspect:**

- `crates/p2p-tunnel/src/multiplex/offer.rs`
- related offer-side tunnel/mux files
- Android/mobile log plumbing if needed

**Tasks:**

- [ ] Log local TCP accept with stream ID and local port.
- [ ] Log outgoing `Open` frame with stream ID, forward ID, and encoded length.
- [ ] Log outgoing `Data` frame with stream ID and encoded length.
- [ ] Log outgoing `Close` or error frame with stream ID and reason code/message if available.
- [ ] Log incoming answer/control/data frames with stream ID and encoded length.
- [ ] Log local TCP write-back length.
- [ ] Log data channel send failures.
- [ ] Do not log payload contents.
- [ ] Redact peer IDs if full IDs are sensitive.

**Acceptance criteria:**

- [ ] Android failing run logs show whether offer sent `Open`.
- [ ] Android failing run logs show whether offer sent request `Data`.
- [ ] Android failing run logs show whether offer received response `Data`.
- [ ] Logs contain lengths/IDs but no payload bodies/secrets.

---

### T2.2 — Audit, complete, and surface answer-side frame logs

**Priority:** P0

> **Note:** several answer-side logs already exist (at DEBUG) in
> `crates/p2p-tunnel/src/multiplex/answer.rs` — `unknown_forward`,
> `target connect failed`, `ignoring DATA for unknown/opening stream`. The work is to
> **audit/complete/surface** them (add missing positive-path logs, ensure they're
> enabled/captured during a run, ensure correlation IDs), not add everything from
> scratch. On Android these are invisible until the tracing filter is widened, so the
> capture/log-plumbing matters as much as the lines.

**Files to inspect:**

- `crates/p2p-tunnel/src/multiplex/answer.rs`
- answer daemon files under `crates/p2p-daemon`
- `p2p-answer` binary/logging setup

**Tasks:**

- [ ] Log received `Open` frame with stream ID and forward ID.
- [ ] Log forward lookup success/failure.
- [ ] Log target TCP connect attempt.
- [ ] Log target TCP connect success/failure.
- [ ] Log received `Data` frame with stream ID and length.
- [ ] Log bytes written to target TCP.
- [ ] Log bytes read from target TCP.
- [ ] Log outgoing response `Data` frame length.
- [ ] Log data channel send failures.
- [ ] Log stream close/error reason.
- [ ] Do not log payload contents.

**Acceptance criteria:**

- [ ] Answer logs prove whether `Open` arrives.
- [ ] Answer logs prove whether request `Data` arrives.
- [ ] Answer logs prove whether target TCP receives request bytes.
- [ ] Answer logs prove whether target TCP response bytes are read and sent back.

---

### T2.3 — Add frame-level correlation IDs where missing

**Priority:** P1

**Files to inspect:**

- offer/answer multiplexing files
- logging helpers

**Tasks:**

- [ ] Ensure stream ID appears in every log for a stream.
- [ ] Ensure forward ID appears in open/lookup logs.
- [ ] Include redacted local/remote peer IDs if helpful.
- [ ] Include monotonic elapsed time from stream open where practical.

**Acceptance criteria:**

- [ ] One stream can be traced across offer logs, answer logs, and packet capture time windows.

---

## Phase 3 — Instrument WebRTC network path

### T3.1 — Log Android fallback network path usage

**Priority:** P0 — **substantially answered** (Android uses the `set_vnet`/`Net::Ifs`
fallback; laptop does not). Remaining work: make these logs **permanent + flag-gated**
(see T-instrumentation), not temporary.

**Files to inspect:**

- `crates/p2p-webrtc/src/lib.rs`
- any network/interface helper files

**Tasks:**

- [ ] Log whether OS interface enumeration works.
- [ ] Log whether fallback net / virtual net is used.
- [ ] Log fallback interface name if used.
- [ ] Log fallback selected local IPv4 if used.
- [ ] Log whether `set_vnet(Some(...))` is called.
- [ ] Ensure logs are available in Android diagnostics/log screen.

**Acceptance criteria:**

- [ ] Android run proves whether fallback net is used.
- [ ] Laptop run proves whether fallback net is not used or also used.
- [ ] The difference is recorded.

---

### T3.2 — Log candidates and selected candidate pair

**Priority:** P0

**Files to inspect:**

- `crates/p2p-webrtc`
- WebRTC setup/peer connection wrapper

**Tasks:**

- [ ] Log candidate gathering start/end.
- [ ] Log local candidates with type/protocol/address/port, redacting as needed.
- [ ] Log remote candidates with type/protocol/address/port, redacting as needed.
- [ ] Log selected candidate pair if WebRTC.rs exposes it.
- [ ] If selected pair is not exposed, log enough ICE state/candidate events to infer it from packet capture.
- [ ] Log ICE state transitions.

**Acceptance criteria:**

- [ ] Failing Android run has candidate logs.
- [ ] Working laptop run has candidate logs.
- [ ] Logs can be correlated with `tcpdump` ports.

---

### T3.3 — Log DTLS/SCTP/DataChannel lifecycle and send errors

**Priority:** P1

**Files to inspect:**

- `crates/p2p-webrtc`
- data channel setup code

**Tasks:**

- [ ] Log DTLS state transitions if exposed.
- [ ] Log SCTP association state if exposed.
- [ ] Log data channel open/close/error.
- [ ] Log data channel buffered amount if available.
- [ ] Log data channel send result/error for tunnel frames.
- [ ] Log if data channel closes while local streams are active.

**Acceptance criteria:**

- [ ] Logs show the exact point after DCEP/data-channel open where progress stops.
- [ ] Data channel send errors are not silent.

---

## Phase 4 — Reproduce without Chrome

### T4.1 — Run Android `toybox nc` local-forward request

**Priority:** P0 — **ALREADY DONE** (reproduced 0 bytes via `toybox nc`; failure is not
browser-specific). Re-run only to correlate with new instrumentation.

**Files / tools:**

- `adb`
- Android device shell
- answer server logs

**Tasks:**

- [ ] Start Android tunnel.
- [ ] Verify local port number.
- [ ] Run:

  ```bash
  adb shell 'printf "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n" | toybox nc -w 18 127.0.0.1 <LOCAL_PORT> | toybox wc -c'
  ```

- [ ] Save returned byte count.
- [ ] Run a verbose response capture:

  ```bash
  adb shell 'printf "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n" | toybox nc -w 18 127.0.0.1 <LOCAL_PORT>' > android-local-forward-response.txt
  ```

- [ ] Save Android app logs and answer logs for the run.

**Acceptance criteria:**

- [ ] We know whether the failure reproduces without Chrome.
- [ ] If `toybox nc` succeeds, browser behavior becomes a separate issue.
- [ ] If `toybox nc` fails, tunnel/WebRTC transport remains primary suspect.

---

### T4.2 — Verify Android local TCP listener behavior

**Priority:** P1

**Files to inspect:**

- Android offer/local forward code path
- `crates/p2p-tunnel` local listener code
- mobile runtime logs

**Tasks:**

- [ ] Log local TCP listener bind address and port.
- [ ] Log local TCP accept event.
- [ ] Log bytes read from local TCP client.
- [ ] Log local TCP write-back bytes.
- [ ] Verify `127.0.0.1:<LOCAL_PORT>` is reachable from `adb shell`.
- [ ] Verify browser and `toybox nc` hit the same local listener.

**Acceptance criteria:**

- [ ] Local Android listener is proven working or identified as the failure point.
- [ ] Local TCP accept/read/write logs are correlated with tunnel-frame logs.

---

## Phase 5 — Test answer host networking

### T5.1 — Run `p2p-answer` outside Docker or with host networking

**Priority:** P0 if answer runs in Docker **and** answer-host access exists; otherwise
**BLOCKED-BY-ACCESS** (Docker is now a secondary/contributing hypothesis, not leading —
the laptop succeeds through the same answer/Docker environment)

**Files / tools:**

- answer deployment scripts
- Docker compose/run scripts
- `p2p-answer` binary

**Tasks:**

- [ ] Determine current answer deployment mode.
- [ ] If Docker bridge is used, run an equivalent answer in host networking:

  ```bash
  docker run --network=host ...
  ```

  or run `p2p-answer` directly on the host.

- [ ] Reproduce Android connection.
- [ ] Save logs and packet captures.
- [ ] Compare with Docker bridge behavior.

**Acceptance criteria:**

- [ ] We know whether host networking changes Android behavior.
- [ ] If host networking fixes Android, Docker/networking becomes primary suspect.
- [ ] If host networking does not fix Android, Docker is deprioritized.

---

### T5.2 — Document Docker/candidate/network findings

**Priority:** P1

**Files:**

- `memory.md` or new docs note

**Tasks:**

- [ ] Record Docker mode tested.
- [ ] Record selected candidate pair for Docker and host-network runs.
- [ ] Record packet-capture differences.
- [ ] Record Android result for each mode.
- [ ] Record laptop result for each mode if tested.

**Acceptance criteria:**

- [ ] Docker is classified as root cause, contributing factor, or unlikely.

---

## Phase 6 — Fix misleading status/session reporting

### T6.1 — Stop fabricating self-targeted remote peer sessions

**Priority:** P1

> **Located (display/status bug only — NOT the routing/data-plane cause):**
> `DaemonStatus::new` stamps the single offer session's `remote_peer_id` with the local
> `peer_id`, reached via `write_daemon_status`. The answer path
> (`write_answer_status` → `with_sessions`) is already correct. Fix because it misleads
> debugging; do not treat it as the Android data-plane failure.

**Files to inspect:**

- `crates/p2p-daemon/src/status.rs` (`DaemonStatus::new`, ~lines 99–101)
- `crates/p2p-daemon/src/signaling.rs` (`write_daemon_status`)
- Android status mapping in `crates/p2p-mobile/src/runtime/` if needed

**Tasks:**

- [ ] Thread the real remote peer id into the offer session status (or report unknown/unset when not yet known) instead of the local `peer_id`.
- [ ] Verify whether local peer ID is being used as `remote_peer_id`.
- [ ] Replace fabricated self remote peer with actual remote peer when known.
- [ ] If remote peer is unknown, report unknown/unset instead of self.
- [ ] Add test for offer session remote peer reporting.

**Acceptance criteria:**

- [ ] Android status/logs no longer suggest self-targeting unless it is truly self.
- [ ] Status does not mislead debugging toward identity/peer mismatch.

---

### T6.2 — Clarify status file vs JNI/native status

**Priority:** P2

**Files to inspect:**

- status writer code
- Android JNI status path
- diagnostics/status docs

**Tasks:**

- [ ] Identify why status file can freeze or lag, if still true.
- [ ] Either fix status file freshness or document that JNI/native status is authoritative.
- [ ] Add log message if status file write fails/stalls.
- [ ] Avoid silent status write failures.

**Acceptance criteria:**

- [ ] Developer can tell which status source is authoritative.
- [ ] Status file issues no longer derail debugging.

---

## Phase 7 — Handle wedged open sessions

### T7.1 — Add stream progress timeouts

**Priority:** P1

> **Located:** the offer parks in the `data_channel.is_open()` wait loop in
> `crates/p2p-daemon/src/offer/session/mod.rs` (~line 156); `run_multiplex_offer` only
> starts after the channel opens. Attach the first-useful-activity timeout there and
> around the subsequent multiplex startup/progress path.

**Files to inspect:**

- `crates/p2p-daemon/src/offer/session/mod.rs` (data-channel wait + multiplex startup)
- `crates/p2p-tunnel/src/multiplex/offer.rs`
- `crates/p2p-tunnel/src/multiplex/answer.rs`
- daemon session tracking

**Tasks:**

- [ ] Identify stream lifecycle states.
- [ ] Add timeout for local TCP accepted but no tunnel open progress.
- [ ] Add timeout for open/data sent but no meaningful progress, where measurable.
- [ ] Close local TCP stream on timeout.
- [ ] Remove logical tunnel stream/session on timeout.
- [ ] Log timeout reason with stream ID and forward ID.
- [ ] Return daemon/app state to listening/ready if daemon remains healthy.
- [ ] Avoid killing unrelated active streams.

**Acceptance criteria:**

- [ ] Stalled stream eventually closes.
- [ ] App does not remain fake-connected forever.
- [ ] Successful streams continue to work.
- [ ] Timeout is covered by tests where practical.

---

### T7.2 — Clean active session count after failed/stalled stream

**Priority:** P1

**Files to inspect:**

- daemon status/session tracking
- mobile runtime status mapping

**Tasks:**

- [ ] Ensure active session count decrements on stream timeout.
- [ ] Ensure active session count decrements on data channel close/error.
- [ ] Ensure active session count decrements on local TCP close.
- [ ] Ensure Android returns to `Listening` when no active sessions remain.
- [ ] Add tests for session count cleanup.

**Acceptance criteria:**

- [ ] No stale `Connected` state after failed stream.
- [ ] No stale tunnel-open session remains after timeout/close.

---

## Phase 8 — Controlled WebRTC transport experiments

### T8.1 — Disable Android fallback net behind a debug flag

**Priority:** P2

**Files to inspect:**

- `crates/p2p-webrtc/src/lib.rs`
- runtime config/debug flags

**Tasks:**

- [ ] Add a debug-only/env/config flag to disable fallback `set_vnet()`.
- [ ] Do not remove fallback permanently yet.
- [ ] Log when fallback is disabled by the flag.
- [ ] Reproduce Android with fallback enabled and disabled.
- [ ] Save logs/captures for both runs.

**Acceptance criteria:**

- [ ] We know whether fallback net changes the failure.
- [ ] Working laptop behavior is not regressed.

---

### T8.2 — Test alternative fallback interface/IP selection

**Priority:** P3

**Files to inspect:**

- `p2p-webrtc` fallback network code

**Tasks:**

- [ ] Try alternative local IP/interface selection if T8.1 implicates fallback.
- [ ] Log selected IP/interface.
- [ ] Compare candidate logs and packet captures.
- [ ] Keep changes behind a flag until proven.

**Acceptance criteria:**

- [ ] Any fallback-net change is evidence-backed.

---

### T8.3 — Create or run minimal Android data-channel echo test

**Priority:** **P0 if answer-host capture is unavailable; otherwise P1** (promoted from
P2). This bypasses tunnel mux, forward ID, local TCP proxy, target HTTP service, and
most daemon/session bookkeeping — so if a bare data channel can't carry user bytes from
this phone over the same `webrtc-rs`, the root cause is almost certainly
Android/`webrtc-rs` transport, not tunnel logic. Likely the cleanest isolation
available without answer-host access.

**Files:**

- new minimal test app/binary if needed
- `crates/p2p-webrtc` test harness if possible

**Tasks:**

- [ ] Build a minimal Android offer data-channel echo path using current WebRTC.rs version, or create a minimal reproducible harness.
- [ ] Test against a simple answer echo.
- [ ] Send several payload sizes: 1, 32, 512, 1200, 4096 bytes; log send/recv result and SCTP retransmits/errors.
- [ ] Confirm whether data channel can carry user data beyond DCEP ACK.
- [ ] Run a laptop offer against the same echo answer as control.
- [ ] Repeat with newer WebRTC.rs in a branch if practical.

**Acceptance criteria:**

- [ ] We know whether the failure is reproducible outside the tunnel multiplexer.
- [ ] WebRTC.rs upgrade decision is based on evidence.

---

### T8.4 — WebRTC.rs upgrade branch

**Priority:** P3

**Files:**

- `Cargo.toml`
- crates using `webrtc`
- test harness

**Tasks:**

- [ ] Create a branch for WebRTC.rs upgrade.
- [ ] Update dependency.
- [ ] Fix compile/API changes.
- [ ] Run existing Rust tests.
- [ ] Run minimal Android data-channel test.
- [ ] Run full Android tunnel test only after minimal echo passes.

**Acceptance criteria:**

- [ ] Upgrade branch proves or disproves version-related data-channel bug.
- [ ] No speculative dependency upgrade is merged without tunnel test success.

---

## Phase 9 — Final proof and regression coverage

### T9.1 — End-to-end Android local-forward success proof

**Priority:** P0 for final fix validation

**Tasks:**

- [ ] Start `p2p-answer`.
- [ ] Start Android offer.
- [ ] Confirm state is `Listening` before local request.
- [ ] Run `toybox nc` HTTP request.
- [ ] Confirm returned byte count is nonzero.
- [ ] Run browser request.
- [ ] Confirm browser receives response or document browser-specific limitation.
- [ ] Save logs and captures from successful run.
- [ ] Confirm answer logs show `Open` and `Data` both directions.
- [ ] Confirm Android logs show response bytes written to local TCP.

**Acceptance criteria:**

- [ ] Android tunnel actually transfers bytes end-to-end.
- [ ] Evidence is saved and summarized.

---

### T9.2 — Regression tests for desktop/laptop path

**Priority:** P1

**Tasks:**

- [ ] Run existing Rust tests.
- [ ] Run known-working laptop offer against same answer.
- [ ] Confirm desktop behavior still works after instrumentation/fixes.
- [ ] Confirm new logs do not expose payloads/secrets.

**Acceptance criteria:**

- [ ] Android fix does not regress existing desktop offer/answer behavior.

---

### T9.3 — Update `memory.md` with final diagnosis

**Priority:** P1

**Files:**

- `memory.md`

**Tasks:**

- [ ] Summarize root cause.
- [ ] Summarize proof.
- [ ] Link/list capture files and logs.
- [ ] Summarize code changes.
- [ ] Summarize remaining risks.
- [ ] Include exact reproduction command that now succeeds.

**Acceptance criteria:**

- [ ] Future Claude Code sessions do not need to rediscover the same issue.
- [ ] The final diagnosis distinguishes proven facts from hypotheses.

---

## Final validation gate

### Rust validation

**Priority:** P0 before completion if Rust changed

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test -p p2p-webrtc
cargo test -p p2p-tunnel
cargo test -p p2p-daemon
cargo test -p p2p-mobile
cargo test --workspace
```

### Android validation

**Priority:** P0 before completion if Android changed

```bash
cd android
./gradlew --no-daemon -PskipRustBuild=true lintDebug
./gradlew --no-daemon -PskipRustBuild=true testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

### Manual Android validation

**Priority:** P0 before calling fixed

- [ ] Install debug APK on physical Android phone.
- [ ] Start offer tunnel.
- [ ] Confirm status reaches listening/ready.
- [ ] Confirm no false connected state before local request.
- [ ] Run `toybox nc` request through local forward.
- [ ] Confirm nonzero response bytes.
- [ ] Run browser request through local forward.
- [ ] Confirm response or document browser-specific failure separately.
- [ ] Confirm answer logs show request and response tunnel data frames.
- [ ] Confirm packet capture shows continued UDP traffic after data-channel open.
- [ ] Stop tunnel cleanly.
- [ ] Confirm status returns to listening/stopped correctly and no wedged active session remains.

---

## Completion checklist

- [ ] P0 captures collected and summarized.
- [ ] P0 offer/answer tunnel-frame logs added.
- [ ] P0 Android fallback/candidate path logs added.
- [ ] P0 Chrome-free reproduction completed.
- [ ] P0 answer host/Docker networking test completed if Docker is involved.
- [ ] P1 misleading status/session reporting fixed.
- [ ] P1 wedged stream/session cleanup added if confirmed relevant.
- [ ] P2/P3 WebRTC fallback/version experiments completed only if evidence requires them.
- [ ] End-to-end Android byte transfer proven.
- [ ] Desktop/laptop path regression checked.
- [ ] `memory.md` updated with final diagnosis.
- [ ] No secrets or payload bodies logged.
