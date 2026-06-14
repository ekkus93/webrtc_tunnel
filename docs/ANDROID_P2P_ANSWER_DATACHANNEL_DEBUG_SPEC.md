# Android ↔ p2p-answer Data-Channel Debugging and Transport Hardening Spec

## Purpose

This spec defines a focused diagnostic and hardening pass for the Android version of the WebRTC tunnel app.

The current symptom is:

- Android offer-side tunnel appears to partially connect to a `p2p-answer` server.
- Signaling and WebRTC setup progress far enough to indicate that the failure is probably not basic configuration, forward ID, or authorization.
- Observed behavior suggests ICE / DTLS / SCTP / DCEP reaches data-channel-open territory, but actual tunnel payload transfer stalls.
- Browser or local client receives zero bytes.
- Answer side appears to retransmit SCTP DATA.
- Offer-side SCTP DATA or SACK traffic may not be reaching or being processed by the answer side.

The goal of this pass is **not** to blindly rewrite WebRTC code. The goal is to prove where the traffic stops, add targeted instrumentation, remove misleading status reports, and then test the smallest safe transport/networking changes.

---

## High-level diagnosis

Current evidence points toward a **post-signaling, post-ICE, post-DTLS, post-SCTP-open data-plane problem**.

This ordering is **re-baselined** around the established laptop-vs-phone evidence (see
"Established evidence" below): a laptop offer, on the same Wi-Fi/NAT, same public IP,
same remote answer, same `srflx↔srflx` selected pair, at the same moment, **succeeds**
while the phone fails. That removes generic NAT/Docker/answer-config as the leading
standalone theory and points at an Android-device-specific data plane.

The most likely failure classes, in order:

1. Android UDP egress / `webrtc-rs` `Net::Ifs` (`set_vnet`) vnet data-plane behavior after data-channel open.
2. Android-specific `webrtc-rs` SCTP/DataChannel behavior (version/stack).
3. Phone-specific interaction with answer Docker/conntrack/NAT (possible contributing factor, not leading).
4. Tunnel mux/session wedging after the data channel opens but user-data frames do not complete.
5. Misleading status/session reporting causing debugging confusion.

Less likely primary causes (largely ruled out as *sole* cause by the laptop comparison):

- forward ID mismatch (tested: changing the phone forward id to the known-good `web-ui` did not fix it)
- broker URL mismatch
- identity authorization failure
- basic MQTT signaling failure
- browser-specific behavior (tested: `toybox nc` also returns 0 bytes)
- generic Docker/NAT/answer config (the laptop traverses the same path and succeeds)

Those lower-probability causes should not consume most of this pass unless new evidence points back to them.

## Established evidence (do not re-derive)

The following were proven in a prior diagnostic session and should be treated as facts
unless a fresh run contradicts them:

1. **Not browser-specific.** `toybox nc` reproduced the failure with **0 bytes**.
2. **Android local TCP accept fires.** The local forward listener accepts the client
   (`accepted local forward client` is logged with `forward_id` + client addr).
3. **Android uses the fallback network path.** OS interface enumeration is unavailable
   (Android NETLINK restriction) and the code injects a fallback host interface via
   `set_vnet` / `Net::Ifs`. The laptop does **not** take this path.
4. **Same public NAT path, opposite result** (captured live, same moment):
   - Phone:  `udp4 srflx 24.130.174.186:45766 <-> udp4 srflx 162.229.61.169:36114` → `T3-rtx`, 0 bytes.
   - Laptop: `udp4 srflx 24.130.174.186:48473 <-> udp4 srflx 162.229.61.169:36415` → HTTP 200, 6917 bytes.
5. **SCTP directionality is asymmetric.** Answer→offer receive works (offer receives
   DATA, `peer_last_tsn` advances); offer→answer DATA/SACK is not acked and the answer
   retransmits the same TSN.

The remaining open question — the gap this pass must close — is:

> Is the Android phone actually putting the stalled SCTP DATA/SACK UDP packets onto the
> network, and if so, where do they disappear (Wi-Fi/router/NAT, answer host, Docker
> bridge, container, or webrtc-rs/SCTP demux)?

---

## Scope

### In scope

- Android app tunnel start/status/log plumbing
- `crates/p2p-mobile`
- `crates/p2p-webrtc`
- `crates/p2p-tunnel`
- `crates/p2p-daemon`
- `p2p-answer` runtime/logging where needed
- diagnostics/logging scripts or documentation
- answer-side capture instructions
- Docker/host networking reproduction steps
- local Android `toybox nc` reproduction steps

### Out of scope

- General Android UI/UX polish
- Broad app architecture refactors unrelated to the failure
- Full WebRTC.rs upgrade unless diagnostics justify it
- Changing tunnel protocol semantics without proof
- Logging secret material or payload contents
- Replacing MQTT signaling
- Implementing TURN as a first response before diagnosing the direct UDP path

---

## Non-negotiable safety and debugging rules

1. **Do not log private keys, auth tokens, passwords, MQTT credentials, or payload contents.**
2. **Do not log full HTTP request/response bodies.** Log frame type, stream ID, forward ID, length, and redacted peer IDs only.
3. **Do not make speculative transport rewrites before packet capture and frame logs identify where the failure happens.**
4. **Do not hide errors with quiet fallbacks.** If a capture, socket operation, candidate selection, or status write fails, log a clear redacted diagnostic message.
5. **Do not mark the issue fixed solely because the UI status changes.** The acceptance test is actual bytes flowing through the Android local forward.
6. **Keep Android/laptop comparison available.** The same answer server and same forward should be tested from both Android and a working laptop offer where practical.
7. **Preserve successful desktop behavior.** Any change to shared Rust crates must not regress existing desktop offer/answer paths.

---

## Priority scale

Use the following priority scale in the TODO:

- **P0 — Blocking diagnostic/correctness item.** Required before making a reliable root-cause claim.
- **P1 — High-priority implementation or test item.** Should be done in this pass unless a P0 result proves it unnecessary.
- **P2 — Useful hardening or follow-up item.** Do after P0/P1 if still relevant.
- **P3 — Optional experiment or cleanup.** Do only if earlier work does not explain the bug or if time permits.

---

## Phase 1 — Prove where traffic stops

### Objective

Determine whether Android SCTP/DTLS/UDP packets are:

1. not leaving the phone/network,
2. reaching the answer host but not the container,
3. reaching the container but not being processed by WebRTC/SCTP,
4. being processed by WebRTC but not by the tunnel protocol,
5. or failing after tunnel frames reach the answer target TCP socket.

### Required phone-side capture (highest leverage)

The answer-side capture alone **cannot** prove whether the phone actually emitted the
UDP packet. Capture on the handset during a failing run:

- **Rooted phone:** `tcpdump` directly on the device.
- **No-root phone:** PCAPdroid (or an equivalent VPN-based capture app).
- Filter for the selected answer srflx address/port if known.
- Confirm whether the phone emits SCTP/DTLS UDP after DCEP/data-channel open when
  offer-side DATA/SACK should be sent.

Interpretation:

- Phone emits UDP but answer host never sees it → Wi-Fi/router/NAT/transit issue.
- Phone emits **no** UDP after the stall → Android / `webrtc-rs` socket/vnet/SCTP send path.
- Phone emits and answer sees incoming → answer-side WebRTC/SCTP/demux issue.

### Required answer-side captures

Capture on the answer host during an Android reproduction. (Blocked-by-access: do only
if answer-office host access exists; otherwise mark blocked and proceed with the
phone-side capture and the minimal data-channel echo test.)

General capture:

```bash
sudo tcpdump -ni any 'host <ANDROID_PUBLIC_IP> and udp' -w android-webrtc-any.pcap
```

If selected UDP ports are known:

```bash
sudo tcpdump -ni any 'host <ANDROID_PUBLIC_IP> and (udp port <PORT1> or udp port <PORT2>)' -w android-webrtc-selected-ports.pcap
```

If running in Docker:

```bash
sudo tcpdump -ni docker0 'host <ANDROID_PUBLIC_IP> and udp' -w android-webrtc-docker0.pcap
```

If the container has its own network namespace capture available, also capture inside the container.

### Required comparison capture

Run a known-working laptop offer against the same answer server and capture:

```bash
sudo tcpdump -ni any 'host <LAPTOP_PUBLIC_IP> and udp' -w laptop-webrtc-any.pcap
```

### Interpretation

- If Android packets stop arriving after data-channel open, suspect Android device/Wi-Fi/NAT/outbound UDP.
- If host sees packets but Docker bridge/container does not, suspect Docker networking/conntrack/NAT.
- If container sees packets but WebRTC does not process SCTP/SACK/DATA, suspect WebRTC.rs transport/demux/SCTP stack.
- If tunnel frame logs show frames arrive at answer but target TCP gets no bytes, suspect tunnel multiplexing or target forward connection.
- If target TCP receives bytes but Android receives no response, suspect reverse tunnel frame path or SCTP return path.

---

## Phase 2 — Add tunnel-frame instrumentation

### Objective

Prove whether tunnel `Open`, `Data`, and `Close` frames are sent and received across the WebRTC data channel.

### Offer-side logging

Add redacted logs around offer-side tunnel frame send/receive boundaries.

Log at least:

- frame direction: sent/received
- frame type: `Open`, `Data`, `Close`, `Error`, ACK-like control if present
- stream ID
- forward ID for open/control frames
- encoded byte length
- local TCP read/write lengths
- data channel send result
- data channel send error if any
- elapsed time from local TCP accept to first tunnel data frame

Do **not** log payload contents.

### Answer-side logging

Add redacted logs around answer-side tunnel frame handling and target TCP interaction.

Log at least:

- received frame type
- stream ID
- forward ID
- encoded byte length
- forward lookup result
- target TCP connect attempt
- target TCP connect success/failure
- bytes written to target TCP
- bytes read from target TCP
- response `Data` frame send length
- data channel send result/error
- stream close/error reason

### Expected diagnostic result

This instrumentation should answer:

- Did the Android offer send an `Open` frame?
- Did answer receive the `Open` frame?
- Did Android offer send `Data` after local browser/nc request?
- Did answer receive that `Data`?
- Did answer connect to target TCP?
- Did answer read response bytes from target TCP?
- Did answer send response `Data`?
- Did Android offer receive response `Data`?
- Did Android write response bytes back to local TCP?

---

## Phase 3 — Instrument Android/WebRTC network path

### Objective

Determine whether Android is using a special WebRTC network fallback path and whether the selected candidate/socket behavior differs from the working laptop path.

### Required logs

Add startup logs in `p2p-webrtc` for:

- whether OS interface enumeration succeeded
- whether fallback network / virtual net is used
- primary local IPv4 selected for fallback, if any
- candidate gathering start/end
- local candidates gathered
- remote candidates received
- selected candidate pair, if exposed by WebRTC.rs
- ICE connection state transitions
- DTLS state transitions if available
- SCTP/data-channel open/close/error events
- data channel buffered amount / send errors if exposed

### Android-specific suspicion

The current code path may use `set_vnet(Some(...))` with a synthetic fallback interface when OS interface enumeration fails. This may be Android-specific.

The diagnostic goal is to prove:

- Android uses fallback net while laptop does not, or
- both use normal interface enumeration, or
- fallback use is unrelated to the failure.

Do not remove this path permanently until the capture/log evidence shows it is the cause.

---

## Phase 4 — Reproduce without Chrome

### Objective

Remove Chrome/browser behavior from the failure equation.

Run a raw HTTP request through the Android local forward using `toybox nc`:

```bash
adb shell 'printf "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n" | toybox nc -w 18 127.0.0.1 <LOCAL_PORT> | toybox wc -c'
```

Also capture a verbose version:

```bash
adb shell 'printf "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n" | toybox nc -w 18 127.0.0.1 <LOCAL_PORT>' > android-local-forward-response.txt
```

Interpretation:

- If `toybox nc` gets bytes but Chrome does not, investigate browser/cleartext/network security/localhost behavior.
- If `toybox nc` also gets zero bytes, continue focusing on tunnel/WebRTC transport.
- If the local TCP accept never fires, investigate Android listener/bind/local forward.
- If local TCP accept fires and data is read from local client, but answer receives no tunnel `Data`, investigate offer-side DataChannel send/SCTP path.

---

## Phase 5 — Test answer host networking

### Objective

Determine whether Docker/container networking is part of the Android failure.

### Required tests

Run the answer server in at least one alternative network mode:

1. directly on the host, outside Docker, or
2. in Docker host network mode where supported:

```bash
docker run --network=host ...
```

Then reproduce Android.

Interpretation:

- If Android works outside Docker / host network, inspect Docker bridge/NAT/conntrack/candidate advertisement.
- If Android still fails, Docker is less likely the root cause.
- If laptop works in both modes but Android only fails in bridge mode, suspect a phone/NAT/Docker interaction.

---

## Phase 6 — Fix misleading status/session reporting

### Objective

Remove status output that falsely suggests the Android offer is connected to itself or that a session is open when the remote peer is unknown.

### Required behavior

- Do not fabricate an offer session with the local peer ID as `remote_peer_id`.
- If the remote peer is not known yet, status should say unknown/unset rather than self.
- If the data channel opens but no tunnel payload successfully completes, status should distinguish:
  - data channel open
  - tunnel open frame sent
  - tunnel data flowing
  - active local TCP stream
- If status file output differs from JNI/native status, logs should make clear which source is authoritative.

### Acceptance criteria

- No self-targeted session appears unless the actual remote peer is truly self.
- Debug logs do not mislead the developer toward identity/forward ID when transport is the real suspect.
- Status file and JNI/native status have documented behavior.

---

## Phase 7 — Handle wedged open sessions

### Objective

Prevent Android from remaining stuck in a fake-open state when the data channel opens but tunnel traffic stalls.

### Required behavior

Introduce bounded timeouts and cleanup around the first useful tunnel activity.

Examples:

- local TCP accepted but no tunnel `Open` send completion within timeout
- `Open` sent but no answer/control response within timeout, if such a response exists
- `Data` sent but stream makes no progress within timeout
- target response path stalls indefinitely
- data channel closes/errors while a local TCP stream is still open

When a stream times out:

- close the local TCP stream
- close/remove the logical tunnel stream
- report a redacted error/log message
- return daemon state to listening/ready if the daemon itself is still healthy
- do not leave stale active session count or tunnel-open state

### Acceptance criteria

- A stalled stream eventually closes and logs why.
- The app returns to `Listening` rather than staying fake-connected forever.
- Existing successful streams are not prematurely killed.

---

## Phase 8 — WebRTC.rs / fallback-net experiments

### Objective

Only after captures and frame logs narrow the failure, test controlled WebRTC transport changes.

### Candidate experiments

1. Disable Android fallback `set_vnet()` path temporarily.
2. Change fallback interface/IP selection.
3. Force normal interface enumeration if possible.
4. Test newer WebRTC.rs in a branch.
5. Create a minimal Android data-channel echo test using the same WebRTC.rs version.
6. Create a minimal Android data-channel echo test using newer WebRTC.rs.

### Rules

- Each experiment must be isolated in a branch or clearly guarded flag.
- Do not merge speculative transport changes without reproduction evidence.
- Preserve working laptop offer behavior.
- Record before/after logs and packet captures.

---

## Final acceptance criteria

The issue is considered diagnosed when the team can state one of the following with evidence:

1. Android packets stop before reaching the answer host.
2. Packets reach the host but not Docker/container.
3. Packets reach container but WebRTC.rs does not process SCTP/SACK/DATA.
4. WebRTC receives frames but tunnel multiplexing fails.
5. Tunnel frames reach target TCP but reverse data path fails.
6. Browser-specific behavior is responsible.
7. A specific Android fallback/WebRTC.rs path causes the failure.

The issue is considered fixed when:

- Android local forward returns nonzero bytes from the target service.
- `toybox nc` through `127.0.0.1:<local_port>` succeeds.
- Browser through `127.0.0.1:<local_port>` succeeds or any browser-specific issue is separately documented.
- Answer logs show tunnel `Open` and `Data` in both directions.
- Packet capture confirms expected UDP traffic continues after data-channel open.
- The app returns to `Listening` after stream close/failure.
- No secrets or payload bodies are logged.
