# replies1 — answers to Claude Code on Android ↔ p2p-answer data-channel debugging

This file responds to `responses1(26).md`, which reviewed `ANDROID_P2P_ANSWER_DATACHANNEL_DEBUG_SPEC.md` and `ANDROID_P2P_ANSWER_DATACHANNEL_DEBUG_TODO.md`.

## Bottom line

Claude Code is right: the diagnostic spec is directionally correct, but it should be re-baselined around the evidence already gathered.

The current lead hypothesis should be:

> Android-specific UDP/WebRTC data-plane behavior, most likely involving the Android `webrtc-rs` `Net::Ifs` / `set_vnet()` fallback path or Android UDP egress behavior after SCTP/data-channel open.

The laptop-vs-phone comparison is especially important. Since the laptop and phone used the same Wi-Fi/NAT, same public IP, same remote answer, same srflx↔srflx shape, and only the phone failed, generic NAT/Docker/answer configuration is no longer the leading theory.

Do not redo already-proven work unless a fresh run is needed for correlation with new logs.

---

## Confirmed evidence to carry forward

Treat the following as established facts unless a new run contradicts them:

1. **Not browser-specific.** `toybox nc` reproduced the failure with 0 bytes. Chrome is not the primary suspect.
2. **Android local TCP accept fires.** The Android local forward listener accepts the local client, so the failure is not simply “local port is not listening.”
3. **Android uses the fallback network path.** Android logs showed OS interface enumeration unavailable and the code injecting a fallback host interface via `set_vnet` / `Net::Ifs`.
4. **Laptop does not take the same path and succeeds.**
5. **Laptop and phone used the same public NAT path.**
   - Phone: `24.130.174.186:45766 <-> 162.229.61.169:36114`, failed with `T3-rtx`, 0 bytes.
   - Laptop: `24.130.174.186:48473 <-> 162.229.61.169:36415`, succeeded with HTTP 200 and 6917 bytes.
6. **SCTP directionality is asymmetric.**
   - Answer→offer receive appears to work enough for the offer to receive DATA chunks and advance `peer_last_tsn`.
   - Offer→answer DATA/SACK is not being acked; answer retransmits the same TSN.
7. **Forward ID, basic signaling, basic auth, and browser behavior are not the primary suspects.**

These facts should be added to `memory.md` and used to avoid going in circles.

---

## Answers to Claude Code’s direct questions

### 1. Answer-office host access

Proceed as follows:

- If we have shell/SSH access to the answer-office host, run the answer-side `tcpdump`, Docker/network namespace captures, and instrumented `p2p-answer` immediately.
- If we do **not** have answer-office host access, do **not** block all progress. Shift the next P0 work to phone-side capture and minimal Android data-channel echo.

So the answer is:

> Answer-host access is preferred and still needed for final packet-path proof, but it is not required to continue isolating the Android-side WebRTC problem.

Claude Code should make this explicit in the TODO:

- `T1.1/T1.2/T5.1` are **P0 if answer host access exists**.
- If answer host access is unavailable, mark them **blocked by access**, not “failed.”
- Promote phone-side capture and minimal echo to the immediate P0 path.

### 2. Phone-side capture

Yes, add phone-side capture. This should be treated as a missing P0 task.

The answer-side capture alone cannot prove whether the phone actually sent the UDP packet. If answer sees nothing, that could mean:

- phone never sent,
- phone sent but Wi-Fi/router/NAT dropped it,
- phone sent but wrong interface/socket/path,
- answer capture missed it.

Preferred options:

1. **Rooted phone:** use `tcpdump` directly on the handset.
2. **No-root phone:** use PCAPdroid or an equivalent VPN-based packet capture app.
3. **Fallback if neither is available:** rely on Android-side webrtc/tunnel logs plus answer-side capture, but explicitly mark “phone actually sent UDP DATA/SACK” as unproven.

Add a P0 task like:

```markdown
### T1.0 — Capture phone-side UDP traffic

**Priority:** P0

- [ ] Capture Android app traffic during the failing local-forward request using root tcpdump or PCAPdroid.
- [ ] Filter for the selected answer srflx address/port if possible.
- [ ] Confirm whether the phone emits UDP packets after DCEP/data-channel open when offer-side SCTP DATA/SACK should be sent.
- [ ] Correlate phone capture with Android logs and answer-side capture.
```

Interpretation branch:

- Phone capture shows outgoing SCTP/DTLS UDP but answer host does not see it → Wi-Fi/router/NAT/transit issue.
- Phone capture shows no outgoing UDP after the stall → Android/webrtc-rs socket/vnet/SCTP send path issue.
- Phone capture shows outgoing and answer sees incoming → answer-side WebRTC/SCTP/demux issue.

### 3. Answer deployment / Docker

Proceed with the assumption that answer-office is currently Dockerized because the prior notes say it advertises `172.17.0.4`, the Docker default bridge address.

But Docker should now be considered **secondary**, not leading.

The laptop succeeds over the same answer/Docker environment, so Docker is unlikely to be the sole cause. It can still be a contributing factor if the phone’s UDP flow interacts differently with conntrack/NAT, but we should not spend the whole pass chasing Docker first.

Decision:

- If answer host access is available, run the host-network/outside-Docker test.
- If answer host access is not available, do not block; continue with phone capture and minimal echo.
- Document Docker as:
  - “possible contributing factor,” not
  - “current lead root cause.”

### 4. Instrumentation lifetime

Use **permanent, flag-gated, redacted instrumentation**.

Do not keep adding and reverting the same logs. That wastes time and makes future investigations harder.

Preferred approach:

- Keep frame-level and WebRTC path instrumentation in the tree.
- Gate noisy logs behind log level, feature flag, config flag, or debug build behavior.
- Default release/user logs should stay quiet.
- Debug diagnostics should be available without rebuilding if practical.
- Never log payload bodies or secrets.
- Logs should be visible through the Android ring buffer/JNI log path, since mobile file logging is not reliable.

Suggested controls:

- `RUST_LOG=p2p_webrtc=debug,p2p_tunnel=debug,p2p_daemon=debug,webrtc_ice=trace`
- Android-side advanced diagnostics toggle if practical
- internal debug-only setting if runtime env vars are hard on Android

Keep candidate/SDP redaction consistent with existing `redact_candidates` / `redact_sdp` behavior.

---

## Corrections to apply to the spec/TODO

### Correction 1 — T6.1 file pointer

Claude Code is correct. The self-targeted session/status bug is not primarily in mobile runtime.

Update the docs from:

```text
crates/p2p-mobile/src/runtime.rs
```

to:

```text
crates/p2p-daemon/src/status.rs
crates/p2p-daemon/src/signaling.rs
```

Specific issue:

- `DaemonStatus::new` stamps the single session’s `remote_peer_id` with the local `peer_id`.
- This is reached through `write_daemon_status`.
- `write_answer_status` / `with_sessions` is already more correct.

Important framing:

> This is a display/status bug, not the real offer routing bug.

Fix it because it misleads debugging, but do not treat it as the cause of the Android data-plane failure.

### Correction 2 — Phase 2 answer-side logs partially exist

Update T2.2 from “add answer-side logs from scratch” to:

> Audit, complete, and surface answer-side frame logs.

Known existing logs include:

- `unknown_forward`
- `target connect failed`
- ignoring DATA for unknown/opening stream

The missing work is likely:

- ensure logs are enabled/captured during the run,
- add any missing positive-path logs,
- make logs visible in the right process/diagnostic channel,
- ensure timestamps/correlation IDs are sufficient.

### Correction 3 — Wedged-session location

Add the known location to the TODO:

```text
crates/p2p-daemon/src/offer/session/mod.rs
```

Specifically, the offer parks in the `data_channel.is_open()` wait loop around the session startup path, and `run_multiplex_offer` only starts after the channel is open.

T7.1 should attach first-useful-activity timeouts around this area and the subsequent multiplex startup/progress path.

### Correction 4 — Re-baseline failure classes

Update the spec’s likely failure classes to this order:

1. Android UDP egress / `webrtc-rs` `Net::Ifs` vnet data-plane behavior.
2. Android-specific WebRTC.rs SCTP/DataChannel behavior.
3. Phone-specific interaction with answer Docker/conntrack/NAT.
4. Tunnel mux/session wedging after data channel open.
5. Misleading status/session reporting.

Move generic Docker/NAT and answer config down.

### Correction 5 — Mark already-done tasks

Mark these as already answered or do-not-repeat-unless-needed:

- Phase 4 / T4.1 `toybox nc`: done; reproduced 0 bytes.
- T4.2 local TCP listener accept: substantially proven; keep only if new logs need correlation.
- Phase 3 fallback-net usage: substantially answered; Android uses fallback, laptop does not.
- T1.3 laptop comparison: already run once; rerun only if needed with new capture/log instrumentation.

---

## Priority changes

### Promote phone-side capture to P0

Add a new P0 task before answer-side capture.

This is now one of the highest-leverage missing facts.

### Promote minimal Android data-channel echo test

Claude Code’s suggestion is good. Promote T8.3 from P2 to **P0/P1**, depending on answer-host access.

Recommended priority:

- **P0 if answer host capture access is unavailable.**
- **P1 if answer host and phone-side captures are available and already localize the issue.**

Reason:

A minimal Android data-channel echo test bypasses:

- tunnel mux,
- forward ID,
- local TCP proxy,
- target HTTP service,
- most daemon/session bookkeeping.

If bare data channel user bytes fail on Android with the same WebRTC.rs stack, the root cause is almost certainly Android/webrtc-rs transport rather than tunnel logic.

Suggested test shape:

- Android offer creates data channel.
- Simple answer echoes arbitrary small binary/text payloads.
- Send several payload sizes:
  - 1 byte
  - 32 bytes
  - 512 bytes
  - 1200 bytes
  - 4096 bytes
- Log send result, receive result, SCTP retransmits/errors.
- Run laptop offer against the same echo answer as control.

### Keep Phase 6 and Phase 7 as P1

Even if the transport root cause is elsewhere, these are real bugs:

- self-targeted status reporting misleads debugging,
- wedged open sessions should time out and clean up.

Keep them in the pass, but do not let them distract from the P0 transport proof.

---

## Recommended revised execution order

Use this order:

1. **Write the established evidence into `memory.md`.**
   - Include toybox nc failure.
   - Include laptop-vs-phone selected pair comparison.
   - Include Android fallback-net usage.
   - Include SCTP asymmetry.
2. **Add phone-side capture task and run it if possible.**
   - root tcpdump or PCAPdroid.
3. **If answer-office access exists, run host/Docker tcpdump.**
   - host `any`
   - docker0
   - container namespace if practical.
4. **Make instrumentation permanent, gated, and redacted.**
   - tunnel-frame logs,
   - candidate/fallback logs,
   - data channel lifecycle logs.
5. **Promote and build minimal Android data-channel echo test.**
   - especially if host-side capture is blocked.
6. **Fix the self-targeted status display bug.**
7. **Add wedged-session timeout/cleanup.**
8. **Only then run transport experiments.**
   - disable fallback net behind flag,
   - alternative fallback IP/interface,
   - newer WebRTC.rs branch.

---

## Concrete answers by issue

### Issue: “Avoid redoing P0 work”

Agreed. Do not redo already-established P0 work unless needed to correlate with new instrumentation.

Update the TODO statuses internally:

- `toybox nc` reproduction: already done.
- browser-specific theory: deprioritized.
- Android fallback-net use: already proven.
- laptop comparison: already done once.
- SCTP asymmetric stall: already observed.

The remaining P0 evidence is packet localization: phone-side and answer-side.

### Issue: “Answer-side pcap access”

Answer:

> Required for final packet-path proof if available. If access is not available, mark those tasks blocked and proceed with phone-side capture plus minimal echo.

Do not let missing answer-host access stall all progress.

### Issue: “Phone-side capture should be added”

Answer:

> Yes. Add it as new P0 task T1.0.

Preferred method:

- root tcpdump if phone is rooted,
- PCAPdroid if not rooted.

### Issue: “Instrumentation permanent or temporary?”

Answer:

> Permanent, redacted, and gated.

Temporary instrumentation caused churn. We want the ability to reproduce this class of problem later.

### Issue: “T6.1 file pointer wrong”

Answer:

> Correct. Update it to `crates/p2p-daemon/src/status.rs` and `crates/p2p-daemon/src/signaling.rs`.

Also document that this is a status/display bug only.

### Issue: “Answer logs partly exist already”

Answer:

> Correct. Change T2.2 to audit/complete/surface answer-side logs rather than add everything from scratch.

The key is log visibility and correlation, not duplicate log lines.

### Issue: “Wedged-session location is known”

Answer:

> Correct. Add `crates/p2p-daemon/src/offer/session/mod.rs` and the `data_channel.is_open()` wait/startup path to T7.1.

### Issue: “Promote minimal data-channel echo”

Answer:

> Yes. Promote it.

Make it:

- P0 if answer host capture is unavailable,
- P1 otherwise.

This is likely the cleanest way to isolate WebRTC.rs/Android from the tunnel mux.

---

## Updated hypothesis statement for Claude Code

Use this in `memory.md` and future diagnostics:

> The Android app reaches ICE/DTLS/SCTP/DCEP-open but stalls on user data. The laptop succeeds at the same time over the same NAT/public IP and same answer server. Android uses the `webrtc-rs` fallback `Net::Ifs` path while the laptop does not. The leading hypothesis is Android-specific UDP/WebRTC data-plane behavior after data-channel open, potentially involving `set_vnet` / fallback interface handling or WebRTC.rs SCTP/DataChannel behavior on Android. Docker/answer networking remains possible but is no longer the leading standalone cause.

---

## Acceptance criteria for the revised diagnostic pass

Claude Code should not call the issue fixed until all of the following are true:

- `toybox nc` through Android `127.0.0.1:<local_port>` returns nonzero bytes.
- Browser through Android `127.0.0.1:<local_port>` also succeeds, or browser-specific behavior is separately documented.
- Logs show tunnel `Open` and `Data` frames in both directions.
- The failing/successful path is localized with at least one of:
  - phone-side pcap,
  - answer-side pcap,
  - minimal Android data-channel echo result.
- Android fallback-net usage is documented.
- No payload bodies, credentials, private keys, or MQTT secrets are logged.
- Desktop/laptop offer still works.

---

## Final note

The diagnostic plan should now be less “start from scratch” and more “close the one missing gap.”

The missing gap is:

> Is the Android phone actually putting the stalled SCTP/DATA/SACK UDP packets onto the network, and if yes, where do they disappear?

Everything else should be organized around answering that question.
