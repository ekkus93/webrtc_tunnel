# Memory summary

_Generated: 2026-07-20T14:54:33Z — derived from memory.md; do not edit by hand._

## Current state

The Rust workspace is on the v0.3 config/protocol line (`p2ptunnel-config-v3`), with a
multi-session answer daemon (multiple authorized offer peers concurrently, one active
session per peer, multiplexed logical streams per session) and hardened daemon recovery,
busy/replay semantics, and status reporting. `docs/SPECS.md` is the current canonical spec.
The experimental Android offer-mode app (Kotlin + Compose) is built, lints/tests clean, and
has been through several UI polish passes.

The most recent, **unresolved** thread is an Android-specific WebRTC data-plane bug: on an
Android 11+ device (Samsung A54) using the `Net::Ifs` vnet ICE fallback, SCTP tunnel data
fails to flow offer→answer specifically against one remote answer server ("answer-office"
at a coworking space), while every local/cellular test and a non-vnet phone/laptop succeed
against the same server. Investigation was paused (2026-06-14) waiting on access to
instrument/capture on the answer-office host. See **Open items** below before resuming.

## Timeline

- **2026-04-08** — GitHub Actions CI restricted to build/upload release artifacts only on
  tagged pushes; normal branches/PRs just run lint+test.
- **2026-04-30** — Five rounds of code-review hardening (review 1–5, each triaged →
  `docs/responsesN.md` questions → ChatGPT decisions frozen → implemented):
  - Round 2: daemons survive ordinary per-session failures instead of exiting; removed
    `deny_when_busy`/`max_concurrent_clients`; fixed offer active-client bookkeeping.
  - Round 3: offer accepts+immediately-closes extra local clients while busy; answer-side
    `busy` replies now respect `allow_remote_peers`; removed dead config knobs; idle
    accept/poll turbulence and status-file write failures made recoverable, not fatal.
  - Round 4: `mqtt_connected` now reflects latest-known transport usability (flips on
    failure/recovery); added bounded per-active-session `(sender_kid, msg_id)` busy-offer
    dedupe cache.
  - Round 5: removed unused `webrtc.ice_gather_timeout_secs`/`ice_connection_timeout_secs`
    from config; busy-offer dedupe correctness moved to post-auth path with an earlier
    best-effort optimization.
  - CI clippy fix for `collapsible_match` in `crates/p2p-tunnel/src/bridge.rs`.
  - Signaling hardening: `MqttSignalingTransport` now pumps the `rumqttc` event loop after
    publish and waits for `SUBACK` before relying on inbound signaling, fixing races where
    publishes/subscriptions were only locally queued.
  - Offer session no longer aborts on duplicate active-session signaling (matches answer).
  - Added extensive regression coverage: tunnel bridge OPEN/target-connect-failure, ACK
    lifecycle, incoming data-channel handoff, daemon duplicate re-ACK/duplicate-survival,
    reconnect leadership (offer owns ICE-restart/renegotiation, answer never initiates),
    remote-close recovery, MQTT transport buffering — full workspace test/clippy/fmt passed
    repeatedly after each addition.
- **2026-05-10 to 05-13** — Baseline rereads of README/memory/docs. Fixed a session-bound
  signaling regression (late previous-session packets rejected but new-session ACKs also
  failing) by hardening MQTT polling to skip non-own-topic broker noise. Runtime directories
  (`state_dir`, `log_dir`, status/log parents) are now created at startup instead of failing
  with generic `Io(NotFound)`; startup file errors now include the exact path and print via
  `Display`. Fixed a `two_node_daemon` integration-harness race in reconnect tests and a
  test-only `wait_for_status` EOF flake. Offer daemon now keeps polling signaling while idle
  waiting for a local client (previously only polled TCP accept). Active offer connection
  loss (mid-tunnel ICE disconnect) now recovers without restarting `p2p-offer` — this was a
  real user-reported bug (2026-05-15).
- **2026-05-13** — `v0.1` release prepared after `cargo fmt` cleanup. Found and removed a
  `Co-authored-by: Copilot` trailer from published history (rewrote `master`, moved tag);
  installed the tracked `.githooks/commit-msg` hook (`core.hooksPath = .githooks`) to block
  Claude/Copilot co-author trailers going forward.
- **2026-05-13 to 05-14** — Multiplexed forwarding (config v2) designed and implemented:
  `docs/MULTIPLEXED_FORWARDING_SPEC.md`/`TODO.md` reviewed, open questions frozen via
  `responses6.md`/`replies6.md` (offer listeners bind at startup, bounded pending-client
  queue, role-specific `[forwards.offer]`/`[forwards.answer]`, explicit peer allowlists,
  frame version 2, no v1 shim). Implemented, then three fix rounds (`FIX_TODO`,
  `FIX2_TODO`, `FIX3_TODO`) closed real gaps: stream task cancellation/writer-failure
  propagation, async answer-side target connect with 10s timeout, non-empty OPEN ACK
  rejection, removed legacy first-forward-only flags/env vars, stream-local EOF/write-
  failure/closed-queue cleanup, malformed answer OPEN as stream-local error, and offer
  sessions persisting across zero active streams (no longer exiting) until explicit
  accepted-client shutdown. README/SPECS updated to match at each stage.
- **2026-05-14 to 05-16** — v0.3 multi-session answer daemon implemented per
  `docs/V03_SPEC.md`/`TODO.md`: centralized MQTT polling, per-peer session routing, one
  session per peer, multi-peer status/logging, offer keeps reconnect ownership. Hardening
  pass (`V03_CODE_REVIEW.md`/`FIX_TODO.md`, decisions in `replies9.md`) added
  authenticate-once-before-routing, session generation tokens, honest status
  (`DaemonState::Serving`, `configured_forward_ids`, no fake stream counts). `V03_FIX2`
  closed remaining routing/status wording gaps. Two rounds of dedicated unit-test TODOs
  (`UNIT_TEST1`/`UNIT_TEST2`) and integration-test TODOs (`INT_TEST1`/`INT_TEST2`) added
  broad coverage (status rendering, replay/ACK matrices, allowlist isolation, transport
  turbulence, restart/recovery, stream churn, malformed signaling, port-flake fix for
  parallel integration tests). Merged `master` into `v0.3_dev` (kept v0.3 semantics, ported
  master's duplicate-ACK throttling and ICE recovery fixes). Cleaned "v0.2" release wording,
  tightened `docs/SPECS.md` wording, renamed the canonical spec doc to `docs/SPECS.md`.
- **2026-05-23** — Config format identifier bumped `p2ptunnel-config-v2` → `-v3` across
  validator, fixtures, examples, and docs. Migrated the user's local temp offer/answer
  configs (`~/work/rust_webrtc/tmp/...`) to the current schema, then fixed a stale
  `/home/jovyan/...` identity path in the answer config; separately added a missing
  `web-ui` forward to the offer temp config (port 8080 wasn't working because only the
  answer side had that forward defined).
- **2026-05-31** — Android app built out end-to-end: added a proper Gradle wrapper, fixed
  Kotlin 2.0/Compose theme and dependency issues, resolved Kotlin compile errors, wired
  `cargo-ndk` (`arm64-v8a`, `x86_64`) via a Gradle `buildRustAndroid` task, and got
  `lintDebug assembleDebug testDebugUnitTest` green. Added `docs/ANDROID_BUILD.md`,
  `docs/ANDROID_USER_GUIDE.md`, README Android section, Android CI job. Fixed app lifecycle
  wiring so Home actions actually start/stop the foreground service, and persisted startup
  failures to `last_error`. Completed live emulator↔desktop validation (MQTT/WebRTC connect,
  foreground service survives backgrounding, localhost forward works). Added broad
  unit/instrumentation coverage across config/tunnel repos, viewmodels, network policy,
  identity, notifications, foreground service, with new testability seams
  (`HasAppDependencies`, lazy native bridge, injectable adapters). `docs/
  ANDROID_E2E_VALIDATION_CLEANUP_TODO.md` closed with honest status: manual Android↔desktop
  browser E2E and large-font walkthrough explicitly marked **NOT RUN** (documented in
  `docs/ANDROID_VALIDATION.md`), not silently skipped.
- **2026-05-31 to 2026-06-01** — Android UI polish: reviewed `ANDROID_UI_POLISH_SPEC/TODO`,
  froze UX decisions via `responses12.md`/`replies12.md` (normal-flow username/password with
  advanced password-file path, answer mode shown disabled, MQTT test = authenticated connect
  only, non-localhost bind gated behind Advanced with a warning; default settings
  `startTunnelWhenAppOpens=false`, `allowMetered=false`, `resumeOnUnmetered=true`,
  `showMeteredWarning=true`, `debugLogsEnabled=false`, `advancedSettingsEnabled=false`).
  Implemented across Home/Setup Wizard/Logs/Settings/Import-Export, then three further fix
  rounds (`FIX_TODO2` add-forward defaults, `FIX_TODO3` metered-allowance/scroll/dialog-
  label/content-description fixes, `FIX_TODO4` Setup Wizard edit-mode + moving Settings
  identity load out of composable-time file I/O). Each round re-ran full Rust+Android
  validation (fmt/clippy/tests, lint/unit/connected instrumentation, `cargo ndk`,
  `assembleDebug`) and kept manual large-font/E2E honestly **NOT RUN**. `TODO5` removed a
  duplicate `refreshPublicIdentity()` call so `SettingsViewModel.init` is the sole startup
  read (added a read-count regression test). All committed/pushed to branch `android-app`.
- **2026-06-13** — Live debugging session against a physical Samsung A54 (Android 16,
  `R5CW31AX4FL`) over USB adb. Made four uncommitted Android UI fixes (Setup Wizard Broker
  step's Next button was off-screen; default broker host now `broker.emqx.io`; Review step's
  Start Tunnel button was scrunched into a circle; Home-from-Logs bottom-nav bug from stale
  Compose Navigation `saveState`/`restoreState`). Diagnosed a "localhost:8080 unreachable"
  report: root cause was a stale native runtime self-targeting its own peer_id because the
  app's Start path is a silent no-op if a runtime is already running with old config
  (`crates/p2p-mobile/src/runtime/mod.rs:66` rejects a second start). `force-stop` cleared
  it. Investigation then pivoted to a deeper bug (see next entries) before this could be
  fully re-verified; session paused when the phone dropped off USB adb.
- **2026-06-13 (later)** — Deep-dived the real data-plane bug: phone (offer,
  `peer_id=android-a54`) completes ICE/DTLS/SCTP handshake and DCEP open against remote
  `answer-office`, but SCTP **offer→answer** DATA is never SACKed (`T3-rtx` retransmits);
  answer→offer direction works. A same-moment, same-NAT laptop offer (`offer-arisu`) against
  the same server works perfectly with an identical selected srflx↔srflx candidate pair.
  Ruled out: network path/NAT, candidate selection, MTU, self-targeting (see side bug below),
  forward-id mismatch (tested by switching phone's forward id `llama`→`web-ui`, still failed).
  Traced the Android-only code path: `crates/p2p-webrtc/src/lib.rs` `build_setting_engine()`
  injects a `Net::Ifs` vnet fallback host interface when OS interface enumeration is
  unavailable (Android 11+ NETLINK restriction) — confirmed `Net::Ifs` is a thin passthrough
  to real sockets, and that `UDPNetwork::Muxed` mode cannot yield srflx candidates at all
  (ruled out as an alternative). Found three real side bugs, none yet fixed: (1)
  `crates/p2p-daemon/src/status.rs:99-101` `DaemonStatus::new` mislabels the offer single-
  session `remote_peer_id` with the local peer_id (display-only, caused an earlier false
  self-targeting diagnosis); (2) `status.json` file writer can freeze while live JNI status
  keeps updating; (3) a wedged `tunnel_open` session can block new local clients until a
  manual Stop→Start. Session ended with the repo tree clean and the laptop offer restored.
- **2026-06-13 (later still)** — Reviewed `docs/ANDROID_P2P_ANSWER_DATACHANNEL_DEBUG_SPEC.md`/
  `TODO.md` with ChatGPT 5.5 (my review in `docs/responses1.md`, agreed answers in
  `docs/replies1.md`); confirmed the facts above as established and froze a plan: phone-side
  packet capture is the new P0 task, answer-host capture is P0-if-access, a minimal data-
  channel echo test is promoted, Docker is demoted to a secondary/contributing factor, and
  instrumentation must be permanent/flag-gated (no add-then-revert churn).
- **2026-06-14** — Built a controlled, reusable test rig: `tests/e2e/android_tunnel_e2e.sh`
  (pass/fail, auto-teardown) and `tests/e2e/android_tunnel_debug.sh` (persistent, DEBUG-level
  answer for frame-log inspection via `docker logs`; env `ANDROID_SERIAL`, `ANSWER_NET`,
  `ANSWER_LEVEL`, `REBUILD`). Fixed four physical-device wizard-automation bugs in
  `lib/android_wizard.sh` (now committed). Ran a failure matrix across two phones (LG G6,
  Android 8, no vnet; Samsung A54, Android 16, vnet fallback) and answer placements (local
  Docker host-network, local Docker bridge-network, cellular-to-home, remote answer-office).
  **Result: every combination passes except A54 (vnet) → remote answer-office**, which fails
  exactly as before (0 bytes, SCTP `T3-rtx`). This localizes the bug to a specific
  interaction between the vnet-fallback UDP socket path and the coworking network's
  NAT/firewall, not vnet alone and not Docker/NAT generically. Leading hypothesis: the vnet
  path sends SCTP data from a different socket/mapping than the STUN checks that established
  the NAT binding; a cone NAT (home router, T-Mobile cellular) tolerates this, a symmetric/
  address-dependent NAT (suspected at the coworking firewall) does not. Paused because
  answer-office was down; next step is deploying an instrumented `p2p-answer` there (or
  simulating a symmetric NAT locally with netns/iptables) to capture both ends.

## Decisions & preferences

- Never add `Co-Authored-By`/Copilot trailers to commits — enforced by the tracked
  `.githooks/commit-msg` hook (requires `core.hooksPath = .githooks`, a local config that
  does not survive a fresh clone).
- v0.3's `[security]` section is intentionally fail-closed; TLS, encryption, signatures,
  authorized keys, strict config parsing, and path/identity safety checks are mandatory, not
  optional tuning knobs.
- No TURN support by design (v0.3): keeps the network/trust/failure model simpler for early
  rollout. The user explicitly does not want to add TURN even to work around the unresolved
  Android NAT issue — they want the root cause found instead.
- Removed/rejected config knobs are treated as dead weight to delete, not leave as decorative
  (`deny_when_busy`, `max_concurrent_clients`, `broker.tls.server_name`,
  `webrtc.ice_gather_timeout_secs`/`ice_connection_timeout_secs`, legacy first-forward-only
  flags/env vars).
- Ordinary runtime/session failures must never be daemon-fatal; only startup/security/init
  failures should exit the process (frozen decision from review round 3).
- Manual validation steps that cannot actually be run in this environment (large-font
  walkthrough, Android↔desktop browser E2E) must be reported as explicit **NOT RUN**, never
  silently skipped or assumed passing — this is a recurring, explicit project convention
  documented in `docs/ANDROID_VALIDATION.md`.
- Diagnostic instrumentation for the Android data-plane bug must be permanent and flag-gated
  rather than added-then-reverted each session, and surfaced via the Android ring
  buffer/JNI log path since mobile file logging is unreliable.
- Workflow pattern used throughout: spec/TODO doc reviewed → open questions written to
  `docs/responsesN.md` → decisions frozen in `docs/repliesN.md` (usually via ChatGPT) →
  implemented → full validation (`cargo fmt --all --check`, `cargo clippy --workspace
  --all-targets --all-features -- -D warnings`, `cargo test --workspace --all-targets`, plus
  Android `lintDebug testDebugUnitTest connectedDebugAndroidTest` when Android is touched).
- At one point work happened on a `v0.3_dev` branch merged back into `master`; branching
  policy has since been superseded by the project's current CLAUDE.md instruction to always
  work directly on `master`.

## Open items

- **Primary unresolved bug**: Android (vnet-fallback, e.g. Samsung A54) SCTP offer→answer
  data fails only against the remote `answer-office` coworking server, not against any local
  or cellular answer target. Root cause not yet confirmed; leading hypothesis is a
  vnet-fallback socket/NAT-mapping mismatch meeting a symmetric/address-dependent NAT at that
  specific site. Next step: instrument `p2p-answer` on answer-office with debug logging +
  `tcpdump`, or reproduce with a simulated symmetric NAT locally (netns/iptables). Blocked on
  answer-office host access when last touched (2026-06-14); need to check whether it's back
  up.
- **Three known side bugs, not yet fixed**: offer-side `status.json` mislabels
  `remote_peer_id` with the local peer_id (`crates/p2p-daemon/src/status.rs:99-101`); the
  `status.json` file writer can freeze/go stale while JNI status keeps updating; a wedged
  `tunnel_open` session can block new local clients until a manual Stop→Start restart.
- **Four uncommitted Android UI fixes** from 2026-06-13 (Setup Wizard Broker-step Next
  button, default broker host, Review step Start-Tunnel button layout, Home/Logs bottom-nav
  fix) are installed on the test device but not committed — need a branch, a decision to
  commit, and a wizard-nav regression test.
- Phone's forward id was left as `web-ui` after testing (changed from the wizard-default
  `llama`) — worth confirming/reverting depending on what answer-office actually has
  configured.
- `answer-office` advertises a Docker-internal `172.17.0.4` host candidate, wasting ICE
  checks — worth filtering RFC1918/Docker host candidates or binding to the host network.
- Manual large-font Android walkthrough and Android↔desktop browser E2E remain **NOT RUN**
  across every Android validation pass to date (CLI/headless environment).
