# Responses to ANDROID_P2P_ANSWER_DATACHANNEL_DEBUG SPEC + TODO — Questions & Issues

Review of `docs/ANDROID_P2P_ANSWER_DATACHANNEL_DEBUG_SPEC.md` and
`docs/ANDROID_P2P_ANSWER_DATACHANNEL_DEBUG_TODO.md`, cross-checked against the deep
investigation already performed in a prior session (captures, the live
laptop-vs-phone comparison, and the code paths traced). No code has been written.

Overall: the spec is well-structured and the diagnostic-first, "prove where traffic
stops" philosophy is correct. The notes below reconcile it with what was already
proven, flag access questions that gate the P0 work, and correct a few inaccuracies.

---

## 1. Evidence already established this session (avoid redoing P0 work)

Several phases ask for evidence that was already gathered (instrumentation was added,
captured, then reverted). Folding this in avoids redundant P0 work and reorders the
priors.

- **Phase 4 (reproduce without Chrome) — essentially DONE.** Reproduced with
  `toybox nc` → **0 bytes**, not just Chrome. The local TCP accept *does* fire
  (`accepted local forward client` with `forward_id` + client addr is logged). So
  it is **not** browser-specific; transport is the suspect.
- **Phase 3 (WebRTC path instrumentation) — substantially answered.** With
  `webrtc_ice=TRACE`: Android **does** use the `set_vnet`/`Net::Ifs` fallback
  ("OS interface enumeration unavailable … injecting a fallback host interface");
  laptop does **not**. Selected pair logged on both.
- **The decisive comparison (TODO T1.3) — already run once.** Laptop and phone, same
  Wi-Fi/NAT, same moment:
  - Phone:  `udp4 srflx 24.130.174.186:45766 related 0.0.0.0:45766 <-> udp4 srflx 162.229.61.169:36114` → `T3-rtx`, 0 bytes.
  - Laptop: `udp4 srflx 24.130.174.186:48473 related 0.0.0.0:48473 <-> udp4 srflx 162.229.61.169:36415` → HTTP 200, 6917 bytes.
  - **Identical path** (same public IP, same remote, same srflx↔srflx pair, same
    `0.0.0.0` base), **opposite result**.
- **SCTP direction matches the spec's hypothesis** (spec lines 13–14): offer→answer
  DATA/SACK never acked (`T3-rtx`); answer retransmits the same TSN; answer→offer
  receive works (offer receives DATA chunks, `peer_last_tsn` climbs).

### Strategic consequence (re-baseline the priors)
The laptop succeeding on the **identical** srflx pair, through the **same**
double-NAT/Docker answer, at the **same** moment, means network-path / NAT / Docker
affect laptop and phone identically and are therefore unlikely to be the *primary*
cause. Recommend re-leveling the spec's "most likely failure classes":

- **Lead hypothesis:** Android UDP egress / `webrtc-rs` `Net::Ifs` vnet data plane
  (Android-device-specific).
- **Secondary:** a phone-specific interaction with Docker/conntrack (the spec's own
  line 293 nuance) — possible but not leading.
- **Largely ruled out as sole cause:** generic network path, NAT type, answer-side
  config/forward/auth, browser behavior.

The Phase 1 captures are still worth doing — but to **localize where the phone's
packets die**, not because Docker is the leading suspect.

---

## 2. The genuine blocking unknown (what is still missing)

The one unresolved P0 is exactly Phase 1's question; we could not do it before due to
access.

- **Answer-side pcap (T1.1 / T1.2)** — needs shell access to the answer-office host
  (and its Docker network namespace). We did not have it.
- **Phone-side pcap (NOT in the current TODO — should be added).** The spec's
  interpretation #1 ("packets not leaving the phone") **cannot be proven from the
  answer side alone.** If the answer host sees nothing, that is ambiguous between
  "phone never sent" and "lost in transit." We need a capture **on the handset**:
  root `tcpdump`, or a no-root VPN-capture app such as **PCAPdroid**. This is the
  single highest-leverage missing datum.

---

## 3. Questions (access items that gate P0)

1. **Answer-office host access.** Do we have shell/SSH access to the answer-office
   host to (a) run `tcpdump`, (b) deploy an instrumented `p2p-answer` build, and
   (c) run Docker commands? T1.1, T1.2, T2.2, and T5.1 all depend on this.
2. **Phone-side capture.** Is the phone rootable (for `tcpdump`), or can PCAPdroid be
   installed? Without it, "the phone is/ isn't sending the UDP DATA" stays unprovable.
3. **Answer deployment.** Is `p2p-answer` actually in Docker, and can we redeploy it
   (host-network mode and/or an instrumented build)? T5.1 and answer-side logging
   hinge on this.
4. **Instrumentation lifetime.** Should the Phase 2/3 logging be **permanent and
   flag-gated** (stays in the tree behind a debug toggle) or **temporary** (added for
   a capture, then reverted, as done before)? The spec reads as permanent + redacted +
   behind debug logging, which seems the better choice — please confirm.

---

## 4. Concrete issues / inaccuracies to fix in the docs

- **T6.1 file pointer is wrong.** It lists `crates/p2p-mobile/src/runtime.rs` (does
  not exist — it is the `crates/p2p-mobile/src/runtime/` module dir) and frames the
  self-targeting status bug as a mobile issue. It is actually in the **shared
  daemon**: `crates/p2p-daemon/src/status.rs:99–101` (`DaemonStatus::new` stamps the
  single session's `remote_peer_id` with the local `peer_id`), reached via
  `crates/p2p-daemon/src/signaling.rs` `write_daemon_status`. The answer path
  (`write_answer_status` → `with_sessions`) is already correct. It is a **display bug
  only** — the real offer session targets `answer-office`; this mislabel is what
  caused the earlier false "self-targeting" conclusion.
- **Phase 2 answer-side logs partly exist already.** `crates/p2p-tunnel/src/multiplex/answer.rs`
  already logs (at DEBUG) `unknown_forward`, `target connect failed`,
  `ignoring DATA for unknown/opening stream`, etc. So T2.2 is closer to "ensure
  complete + ensure these are surfaced/captured during a run" than "add from scratch."
  On Android these are DEBUG and were invisible until the tracing filter was widened —
  so the **capture/log-plumbing** matters as much as the log lines themselves
  (mobile installs a single process-global subscriber once; level is fixed at first
  start; file logging is a no-op on mobile — logs live in a ring buffer + JNI).
- **Wedged-session location is known** (helps T7.1): the offer parks in the
  `data_channel.is_open()` wait loop at `crates/p2p-daemon/src/offer/session/mod.rs:156`;
  `run_multiplex_offer` only starts once the channel is open. That loop is where a
  "first useful activity" timeout would attach.

---

## 5. Suggestions

- **Promote T8.3 (minimal data-channel echo) toward the front.** A minimal Android
  offer ↔ trivial answer-echo over the *same* webrtc-rs, bypassing our tunnel mux, is
  arguably the most decisive isolation test available **without** answer-host access:
  if a bare data channel also cannot carry user bytes from this phone, it is
  webrtc-rs/Android, full stop, and large parts of Phase 1/2 become unnecessary.
  Currently it is P2 at the very end.
- **Add a phone-side capture task to Phase 1** (per §2/§3.2) plus an explicit
  "is the phone actually sending the UDP DATA?" interpretation branch.
- **Re-baseline the priors** at the top of the spec to reflect the laptop result
  (see §1).

---

## 6. Points of agreement

- Do not lead with TURN; prove the data plane first. Correct, and matches the stated
  preference.
- Safety rules (no secrets / no payload bodies; redacted frame metadata only) align
  with the existing `redact_candidates` / `redact_sdp` posture.
- Phases 6 (status) and 7 (wedged session) are real, independently-worth-fixing bugs
  located during the prior investigation, regardless of the transport outcome.

---

## 7. Key reference facts (from the prior investigation)

- Phone: Samsung SM-A546E, Wi-Fi `192.168.88.106`, public/srflx `24.130.174.186`,
  `peer_id=android-a54`, app `com.phillipchin.webrtctunnel`.
- Laptop: Wi-Fi `192.168.88.109`, **same** public IP `24.130.174.186`,
  `peer_id=offer-arisu`, config `~/.config/p2ptunnel/config.toml` (forward id
  `web-ui` → 8080). Works.
- answer-office: remote, in Docker (advertises host candidate `172.17.0.4` = Docker
  default bridge), public/srflx `162.229.61.169`. Broker `broker.emqx.io:8883`.
- Stack: `webrtc` 0.8.0, `webrtc-ice` 0.9.1, `webrtc-sctp` 0.8.0, `webrtc-dtls` 0.7.2,
  `webrtc-util` 0.7.0. STUN-only (no TURN).
- Android fallback: `crates/p2p-webrtc/src/lib.rs` `build_setting_engine()` →
  `set_vnet(Net::Ifs([primary_local_ipv4()/24]))` when `os_interface_enumeration_works()`
  is false (Android NETLINK restriction). `Net::Ifs` is a thin passthrough to real
  `tokio::net::UdpSocket`; selected-pair DATA and STUN both go through the same
  `conn.send_to(raw, addr)` (`candidate_base.rs:276`). `UDPNetwork::Muxed` is not a
  fix — it skips srflx gathering (`webrtc-ice-0.9.1/src/agent/agent_gather.rs:115`).
- Full prior write-up is in `memory.md` under the `2026-06-13 (later)` entry.
