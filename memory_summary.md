# Memory Summary

`memory.md` is a chronological log of development sessions (May–June 2026) across GPT and Claude agent sessions working on the `webrtc_tunnel` project. Here's a structured summary of the major themes and milestones:

## Project Baseline

`webrtc_tunnel` is a CLI-only Rust secure TCP tunnel over one reliable ordered WebRTC data channel (`tunnel`), using MQTT as an untrusted signaling transport. All signaling is encrypted and signed, with an SSH-like identity/`authorized_keys` workflow. STUN-only WebRTC (no TURN). v0.3 supports multi-session/multi-stream (multiplexed logical streams per peer session, one active session per authenticated peer ID, multiple authorized peers concurrently served by one answer daemon).

## Major Work Phases

### 1. Config & Schema Evolution
- Bumped config format identifier `p2ptunnel-config-v2` → `p2ptunnel-config-v3`
- Removed dead/decorative config knobs (`deny_when_busy`, `max_concurrent_clients`, `webrtc.ice_gather_timeout_secs`, `webrtc.ice_connection_timeout_secs`, public `broker.tls.server_name`)
- Made `[security]` section fail-closed (mandatory TLS, encryption, signatures, etc.)

### 2. Code Review Rounds (Review 2–5, Fix Passes)
Multiple rounds of adversarial code review and hardening:
- **Daemon recovery:** ordinary per-session failures no longer kill daemons—session errors are caught, logged, cleaned up, and daemons return to steady states (`Serving`/`WaitingForLocalClient`)
- **Busy policy:** offer-side immediate local close while busy, answer-side encrypted `busy` only for fully allowed peers, unauthorized/disallowed peers get no response
- **Status semantics:** `mqtt_connected` tracks latest-known transport usability, flips on failure/recovery
- **Replay/dedupe:** bounded per-session caches keyed by `(sender_kid, msg_id)` to prevent repeated `busy` replies

### 3. Multiplexed Forwarding (v2)
- Implemented `[[forwards]]` config with role-specific `[forwards.offer]`/`[forwards.answer]` sections
- Per-forward allowlists, multiple logical streams per data channel
- Stream-level error isolation (target-connect failure, queue overflow, late DATA)
- 10-second hardcoded answer target-connect timeout

### 4. v0.3 Multi-Session Answer Daemon
- Centralized MQTT polling, per-peer session routing, session-local task cleanup
- Same-peer pending replacement, multi-session status JSON
- Authenticated routing before session dispatch
- Same-peer replacement isolation, per-forward allowlist isolation

### 5. Test Expansion (Massive)
- Added extensive regression coverage: ACK lifecycle, data-channel handoff, reconnect leadership, remote-close recovery, transport buffering, two-node integration, multi-peer integration, signaling turbulence, status-file churn, and more
- In-memory `RecordingTransport` test harness with route-scoped fault injection
- WebRTC ICE state injection seams for deterministic integration tests

### 6. Android App (Experimental)
- Gradle wrapper setup, Kotlin 2.0/Compose plugin, theme/theme fixes
- Full Android build pipeline: `cargo ndk` for JNI, lint + unit + connected instrumentation tests
- UI polish: Setup Wizard, Home mode cards, Settings, Logs, forward editors, metered network policy
- Foreground service lifecycle, notification permission gating, identity handling
- Broad automated coverage: config/tunnel repositories, viewmodels, network policy, identity handling, notification behavior, foreground service instrumentation

### 7. CI & Release
- Tagged pushes build release tarballs and publish as GitHub release assets
- Workspace version bumped to `v0.2.0`
- `cargo clippy` collapsible_match fix
- Full validation gates: `cargo fmt --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, `cargo test --workspace --all-targets`, plus Android `lintDebug`, `testDebugUnitTest`, `connectedDebugAndroidTest`

### 8. Git Hygiene
- `.githooks/commit-msg` hook to reject Copilot co-author trailers
- Force-pushed rewritten history to remove `Co-authored-by: Copilot` trailers
- Annotated `v0.1` tag recreated on correct commit

## Current Open Issues

### Android ICE/Data-Plane Bug (P0)
**Symptom:** Android offer (Samsung A54, Android 16) connects to remote `answer-office` over WebRTC—everything succeeds through SCTP/data channel open—but user data fails to flow offer→answer (SCTP `T3-rtx`, 0 bytes).

**Key finding:** The failure localizes to **A54 (vnet fallback) + remote coworking answer-office**. It works everywhere else (local docker, T-Mobile cellular, laptop on same path).

**Leading hypothesis:** webrtc-rs in `set_vnet`/`Net::Ifs` mode sends SCTP data from a different socket/source than the STUN checks that established the NAT mapping. A symmetric/address-dependent NAT (coworking firewall) drops data from the unexpected mapping, while the laptop's single native socket never trips this.

**Blocked by:** need SSH access to answer-office server, phone pcap capability, or ability to simulate symmetric NAT locally.

### Side Bugs Found
1. Status `remote_peer_id` mislabel on offer path (display-only)
2. `status.json` file writer freezes
3. Wedged `tunnel_open` session stops serving new local clients until Stop→Start
4. answer-office advertises Docker-internal `172.17.0.4` host candidate (config smell)

## Key Files & Docs
- `docs/SPECS.md` — canonical design spec
- `docs/V03_SPEC.md`, `V03_TODO.md`, `V03_FIX_TODO.md` — v0.3 multi-session work
- `docs/ANDROID_*.md` — Android build, validation, UI polish
- `tests/e2e/` — E2E harness for controlled phone ↔ dockerized answer testing
- `.githooks/commit-msg` — commit-msg blocker for AI co-author trailers

## Models Used
GPT-5.3-Codex, GPT-5.4, GPT-5.4-mini, Claude Sonnet 4.6, Claude Opus 4.8
