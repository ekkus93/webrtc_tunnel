# Memory summary

_Generated: 2026-07-10T06:01:34Z — derived from memory.md; do not edit by hand._

## Current state

The project is a Rust-based secure TCP tunnel over WebRTC data channels with Android support. Recent work focused on Android UI polish completion (through TODO4/TODO5), phone tunnel debugging with the Samsung A54 device, and localizing the SCTP data-plane failure to a specific interaction between the Android `Net::Ifs` vnet fallback and the coworking space's symmetric NAT. The Android app is functional for local/E2E scenarios; the failure only manifests when the A54 (with vnet fallback) connects to the remote answer-office. The Rust workspace has full multi-session/multi-stream support (v0.3 model) with comprehensive test coverage.

## Timeline

- **2026-04-30 to 2026-05-13** — Core hardening rounds: review rounds 1-5 hardened daemon recovery, busy-policy consistency, status semantics, config-surface honesty, duplicate signaling handling, and reconnect leadership. v0.1 release.
- **2026-05-13** — v2 multiplexed forwarding implemented (phases 1-4): multiple logical streams, stream-local failure isolation, answer target-connect timeout, legacy flag removal. Hardened through three review/fix passes.
- **2026-05-14** — v0.3 multi-session answer daemon implemented: centralized MQTT polling, per-peer session routing, multi-session status JSON.
- **2026-05-15** — v0.3 hardening: centralized auth/decrypt routing, session generation tokens, honest status fields. Integration test expansion (transport turbulence, restart, stream churn, same-peer pressure). Workspace reached 74 daemon lib tests, 25 integration tests.
- **2026-05-16** — Merged master into v0.3_dev. Workspace version bumped to v0.2.0. CI tagged releases publish GitHub assets.
- **2026-05-23** — Config format identifier bumped to v3 (`p2ptunnel-config-v3`). Co-authored-by trailer policy established (hook rejects).
- **2026-05-31** — Android app development: Gradle wrapper installed, build unblocked, lint/test/instrumentation coverage added. Android E2E validation completed with connected tests (12/12 passing). UI polish spec reviewed and implemented through TODO5 completion.
- **2026-06-01** — TODO4/TODO5 completed: Setup Wizard forward editing with mode semantics, Settings public-identity loading moved out of composable-time I/O. Duplicate refresh removed from SettingsScreen.
- **2026-06-13** — Major phone tunnel diagnosis session on Samsung A54 (Android 16). Found four UI fixes (committed to working tree). Root cause of "localhost:8080 can't reach" identified as stale native runtime targeting itself. Deep SCTP diagnosis: data plane stalls offer→answer after full ICE/DTLS/SCTP handshake. Failure localized to A54 (vnet fallback) + remote answer-office only. Controlled rig proved vnet works locally in all shapes.
- **2026-06-13 (later)** — Spec review completed with ChatGPT 5.5. Agreed plan: phone-side pcap capture (P0), answer-host pcap (P0 if accessible), minimal data-channel echo test (P0 if no answer access). Leading hypothesis: symmetric NAT at coworking drops data from unexpected socket mapping.

## Decisions & preferences

- **Branching:** work directly on `master`; do not create feature branches unless explicitly told.
- **Commit trailers:** reject `Co-authored-by`, `Co-Authored-By`, and any AI-attribution trailers (enforced via `.githooks/commit-msg`).
- **Linting:** never suppress/hide lint errors — fix findings, don't add suppression annotations.
- **Config:** v2 multiplexed forwarding model; v0.3 multi-session answer daemon; one active session per authenticated peer ID.
- **Android:** foreground service lifecycle; app-scoped dependencies shared between MainActivity and TunnelForegroundService; test via connected instrumentation (12-13 tests on API 36 emulator).
- **E2E testing:** `tests/e2e/android_tunnel_e2e.sh` for Android→docker answer; `tests/e2e/android_tunnel_debug.sh` for persistent debug rig.

## Open items

- **SCTP data-plane failure (A54 + answer-office):** needs phone-side pcap capture (root or PCAPdroid), answer-host pcap, and/or minimal data-channel echo test. Blocked by answer-office SSH access and phone packet-capture capability.
- **Symmetric NAT hypothesis:** test by deploying instrumented answer on answer-office or simulating symmetric NAT locally (Option A').
- **Four Android UI fixes:** on working tree but not committed (wizard nav button, broker host default, review step layout, bottom nav state). Need branch and commit.
- **Android start-cleanup failure sticky history (P0-009):** implemented and committed.
- **Unknown native mode rejection (P1-008):** implemented and committed.