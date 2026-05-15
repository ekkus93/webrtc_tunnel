## 2026-04-08T01:22:52Z - GPT-5.4 - Tag-only GitHub Actions artifacts
- The GitHub Actions workflow should run normal lint/test CI on branch and pull request builds, but only create and upload release artifacts for tagged pushes.

## 2026-04-30T07:57:15Z - GPT-5.4 - Review 2 triage started
- Added and reviewed `docs/RUST_WEBRTC_CODE_REVIEW2.md` and `docs/RUST_WEBRTC_CODE_REVIEW2_TODO.md`; the main likely real issues are daemon survival across session failures, dead/misleading busy config, and early-cleared offer active-client tracking.

## 2026-04-30T07:59:34Z - GPT-5.4 - Added review 2 response file
- Wrote `docs/responses2.md` summarizing which round-2 review findings look real and listing the four scope questions to clear with ChatGPT before starting another hardening pass.

## 2026-04-30T08:07:22Z - GPT-5.4 - Review 2 decisions frozen
- ChatGPT confirmed the next hardening pass should remove `deny_when_busy`, remove `max_concurrent_clients`, remove public `broker.tls.server_name` unless true override semantics are implemented, and ensure ordinary session failures never kill either daemon.

## 2026-04-30T08:12:30Z - GPT-5.4 - Review 2 phase 1 landed
- Round-2 phase 1 now keeps both daemons alive across ordinary per-session failures by catching session-bound errors, logging them, cleaning up session resources, and returning status to idle/waiting instead of exiting the process.

## 2026-04-30T08:15:52Z - GPT-5.4 - Review 2 phase 2 landed
- Round-2 phase 2 removed `deny_when_busy` and `max_concurrent_clients` from the v1 config surface, made the offer listener explicitly single-session, and fixed offer-side active-client bookkeeping so it stays set until full session teardown.

## 2026-04-30T08:18:06Z - GPT-5.4 - Review 2 phase 3 landed
- Round-2 phase 3 removed public `broker.tls.server_name` from the v1 config/runtime surface, kept `connect_timeout_secs` and `session_expiry_secs` as explicitly fixed compatibility settings, and updated docs/instructions to match the real supported semantics.

## 2026-04-30T08:23:27Z - GPT-5.4 - Review 2 phase 4 landed
- Round-2 phase 4 added explicit daemon lifecycle documentation plus recovery-path tests that prove offer/answer daemons return to their steady states after target-connect, bridge-task, ICE, and remote-session failures, and the round-2 TODO checklist is now fully complete.

## 2026-04-30T08:34:59Z - GPT-5.4 - Review 3 triage started
- Reviewed `docs/RUST_WEBRTC_CODE_REVIEW3.md` and `docs/RUST_WEBRTC_CODE_REVIEW3_TODO.md`; the main likely real issues are top-level offer-daemon busy behavior still being serialized, active-answer busy replies bypassing `allow_remote_peers`, several dead/fixed-only config fields, and ordinary operational errors still exiting the daemon.

## 2026-04-30T08:36:55Z - GPT-5.4 - Added review 3 response file
- Wrote `docs/responses3.md` summarizing which round-3 review findings look real and listing the implementation questions to clear with ChatGPT before starting another hardening pass.

## 2026-04-30T08:39:55Z - GPT-5.4 - Review 3 decisions frozen
- ChatGPT confirmed the round-3 v1 decisions: extra local offer-side clients should be accepted and immediately closed with no banner, unauthorized or disallowed peers during an active answer session should receive no response, dead config knobs should be removed rather than left decorative, and only startup/security/init failures should be fatal while ordinary runtime failures should be recoverable.

## 2026-04-30T08:48:09Z - GPT-5.4 - Review 3 phase 1 landed
- Round-3 phase 1 changed the offer daemon to keep a dedicated accept loop alive during active sessions so extra local clients are accepted and immediately closed with no banner, and added daemon-level busy tests to verify prompt rejection while the first session stays intact.

## 2026-04-30T08:52:17Z - GPT-5.4 - Review 3 phase 2 landed
- Round-3 phase 2 made active-answer busy handling respect both authorization and `allow_remote_peers`, so only fully allowed peers receive encrypted `busy` during an active session while unauthorized or disallowed peers get no response.

## 2026-04-30T08:54:54Z - GPT-5.4 - Review 3 phase 3 landed
- Round-3 phase 3 removed dead or fixed-only v1 config knobs from the public schema, aligned README/spec/Copilot guidance with the leaner config surface, and added config tests that reject those removed fields explicitly.

## 2026-04-30T08:58:55Z - GPT-5.4 - Review 3 phase 4 landed
- Round-3 phase 4 made idle runtime accept/poll turbulence recoverable with log-and-retry behavior, converted status-file writes to best-effort logging instead of daemon-fatal errors, added a regression for recoverable status-write failure, and completed the remaining review3 lifecycle/docs checklist.

## 2026-04-30T09:25:56Z - GPT-5.4 - Review 4 docs missing
- The user asked for triage of `docs/RUST_WEBRTC_CODE_REVIEW4.md` and `docs/RUST_WEBRTC_CODE_REVIEW4_TODO.md`, but those files were not present in `docs/`; only review1 through review3 files currently exist, so review4 triage is blocked until the files or correct paths are provided.

## 2026-04-30T09:29:27Z - GPT-5.4 - Review 4 triage started
- Reviewed `docs/RUST_WEBRTC_CODE_REVIEW4.md` and `docs/RUST_WEBRTC_CODE_REVIEW4_TODO.md`; the clearest likely-real remaining issues are optimistic `mqtt_connected` status reporting and replay-blind active busy-offer classification, while the broader runtime-policy/testing items look directionally right but need scope decisions around exact connectivity semantics and dedupe lifetime.

## 2026-04-30T09:30:26Z - GPT-5.4 - Added review 4 response file
- Wrote `docs/responses4.md` summarizing which round-4 review findings look real and listing the three clarification questions to freeze with ChatGPT before starting another hardening pass.

## 2026-04-30T09:33:10Z - GPT-5.4 - Review 4 decisions frozen
- ChatGPT confirmed that `mqtt_connected` should mean latest-known signaling transport usability, flipping false immediately on recoverable poll/publish failure and true again after successful transport activity/recovery, with status updated before retry/backoff sleeps.
- ChatGPT also froze active busy-offer dedupe as a per-active-answer-session cache keyed by at least `(sender_kid, msg_id)`, and kept round 4 narrowly scoped to those two concrete bugs plus focused tests rather than a broad runtime-policy rewrite.

## 2026-04-30T09:46:29Z - GPT-5.4 - Review 4 phase 1 landed
- Round-4 phase 1 added explicit daemon-side transport usability tracking for `mqtt_connected`, routed daemon status writes through that tracked runtime state, flipped status false before idle/session transport backoff on recoverable poll/publish failures, restored it after successful transport activity, and added focused tests for healthy, disconnected, and recovered status transitions.

## 2026-04-30T09:51:17Z - GPT-5.4 - Review 4 phases 2 and 3 landed
- Round-4 phase 2 added a bounded per-active-answer-session dedupe cache keyed by `(sender_kid, msg_id)` so duplicate replays of the same foreign offer no longer trigger repeated encrypted `busy` replies, while unauthorized or disallowed peers still receive no response.
- Round-4 phase 3 added a regression proving daemon recovery preserves a disconnected `mqtt_connected` state until transport activity succeeds again, aligned README/spec/Copilot guidance with the status and busy-dedupe semantics, and completed the remaining review4 checklist within the frozen focused scope.

## 2026-04-30T10:16:25Z - GPT-5.4 - Review 5 triage started
- Reviewed `docs/RUST_WEBRTC_CODE_REVIEW5.md` and `docs/RUST_WEBRTC_CODE_REVIEW5_TODO.md`; the clearest likely-real remaining issues are that `webrtc.ice_gather_timeout_secs` and `webrtc.ice_connection_timeout_secs` still appear unused in runtime flow, and active busy-offer dedupe still happens after full decode/decrypt/classification work instead of earlier in the path.

## 2026-04-30T10:17:41Z - GPT-5.4 - Added review 5 response file
- Wrote `docs/responses5.md` summarizing which round-5 review findings look real and listing the three clarification questions to freeze with ChatGPT before starting another narrow hardening pass.

## 2026-04-30T10:23:38Z - GPT-5.4 - Review 5 decisions frozen
- ChatGPT confirmed that `webrtc.ice_gather_timeout_secs` and `webrtc.ice_connection_timeout_secs` should be removed from the public v1 config unless they become real enforced runtime features.
- ChatGPT also froze active busy-offer dedupe correctness on the authenticated side after successful decode/verification/decrypt/authz with per-session `(sender_kid, msg_id)` caching, and kept round 5 narrowly scoped rather than expanding into a broader fixed-only config cleanup.

## 2026-04-30T10:28:47Z - GPT-5.4 - Review 5 hardening landed
- Round-5 removed `webrtc.ice_gather_timeout_secs` and `webrtc.ice_connection_timeout_secs` from the public config schema, fixtures, and docs, and config tests now reject them as removed v1 knobs instead of pretending they are live runtime controls.
- The active answer busy-offer path still uses the authenticated per-session `(sender_kid, msg_id)` cache as its correctness boundary, but it now also drops already-seen duplicates earlier from decoded outer-envelope metadata as a best-effort optimization before a second full reclassification pass.
- `docs/RUST_WEBRTC_CODE_REVIEW5_TODO.md` is fully marked complete, and README/spec/Copilot guidance now match the reduced v1 config surface and the tightened busy-offer replay behavior.

## 2026-04-30T10:44:28Z - GPT-5.4 - CI clippy fix landed
- GitHub Actions hit a stricter `clippy::collapsible_match` diagnostic in `crates/p2p-tunnel/src/bridge.rs` that did not surface earlier locally; the EOF arm was rewritten with a match guard so the behavior stays the same while satisfying the newer CI toolchain.
- After the fix, `cargo fmt --all`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets` all passed locally again.

## 2026-04-30T10:57:40Z - GPT-5.4 - README refreshed
- README was audited against the current config schema, CI workflow, and runtime behavior; it now lists the public `[security]` section explicitly, explains that v1 security controls are fail-closed rather than optional tuning knobs, and makes the single-session rule match both offer-side and answer-side behavior.
- The CI badge URL now explicitly targets `master` so the README points at the intended branch status.

## 2026-04-30T10:59:16Z - GPT-5.4 - Spec refreshed
- `docs/RUST_WEBRTC_SPECS.md` had a few stale operator-facing details: it still implied `hello` was part of the required offer lifecycle, documented `p2pctl keygen --peer-id <peer_id>` instead of the real positional CLI syntax, and stated the single-session rule less precisely than the implemented offer/answer busy behavior.
- The spec now says `hello` is optional in v1, shows `keygen <peer_id>`, and aligns the single-session wording with the actual offer-side immediate-close and answer-side encrypted-`busy` behavior.

## 2026-04-30T11:02:11Z - GPT-5.4 - Project baseline refreshed
- Re-read `README.md`, `docs/RUST_WEBRTC_SPECS.md`, and `memory.md` to refresh the active project baseline before further work.
- The current baseline remains: CLI-only Rust secure TCP tunnel over a reliable ordered WebRTC data channel, MQTT treated as untrusted transport, all signaling encrypted and signed, SSH-like identity workflow, STUN-only v1, and one active tunnel session at a time with offer-side immediate local close while busy and answer-side encrypted `busy` only for allowed peers.

## 2026-04-30T11:10:32Z - GPT-5.4 - Workspace validation rerun passed
- A full `cargo clippy --workspace --all-targets --all-features -- -D warnings` and `cargo test --workspace --all-targets` rerun now passes cleanly.
- The first attempt failed for environmental reasons only: the filesystem hit `No space left on device` while linking `p2p-daemon`; running `cargo clean` freed about 2.6 GiB and the rerun succeeded without code changes.

## 2026-04-30T11:25:53Z - GPT-5.4 - README public broker example updated
- Replaced the placeholder `broker.example.com` URLs in `README.md` with the real public test broker `mqtts://test.mosquitto.org:8886`.
- The README broker example now sets `broker.tls.ca_file = ""` to use the system CA store for that Let's Encrypt-backed listener and explicitly warns that public brokers are test-only and should use a unique `topic_prefix`.

## 2026-04-30T11:29:17Z - GPT-5.4 - README broker password docs clarified
- Fixed the README broker example so the public `test.mosquitto.org:8886` listener now uses anonymous auth with empty `username` and `password_file` instead of implying a password is required.
- Added explicit README text that `broker.password_file` should point to a local text file containing only the MQTT broker password or token, usually as a single line, and documented the three supported broker auth modes.

## 2026-04-30T11:30:39Z - GPT-5.4 - README broker term defined
- Added a plain-language definition to `README.md` explaining that the broker is the MQTT server both peers connect to for signaling and that it is only a relay, not a trusted holder of plaintext signaling or tunnel contents.
- Also clarified the `[broker]` config-section bullet so readers understand it refers to MQTT server connection settings.

## 2026-04-30T11:33:12Z - GPT-5.4 - README broker TLS client-auth clarified
- Updated the README broker example so the public `test.mosquitto.org:8886` config leaves `client_cert_file` and `client_key_file` empty instead of implying mutual TLS files are required.
- Added explicit README text that `client_cert_file` is the certificate presented to a broker that requires mutual TLS, `client_key_file` is the matching private key, and v1 requires both to be set together or left empty together.

## 2026-04-30T11:46:19Z - GPT-5.4 - README config examples rewritten
- Replaced the README's misleading subsection-only config fragments with complete minimal offer-side and answer-side `config.toml` examples that include `format` and every required section expected by `p2pctl check-config`.
- Switched the public broker example to `test.mosquitto.org:8883` plus an explicit downloaded `mosquitto.org.crt` CA file because the current validator requires `broker.tls.ca_file` to be a real local file for `mqtts://` brokers.
- Added README notes that the current schema still requires both `[tunnel.offer]` and `[tunnel.answer]` blocks to be present and that `check-config` validates referenced files like `identity`, `authorized_keys`, and `broker.tls.ca_file`.

## 2026-04-30T12:01:55Z - GPT-5.4 - README state_dir clarified
- Clarified in `README.md` that `paths.state_dir` is the base directory for local runtime artifacts such as the log and status files under `~/.local/state/p2ptunnel`.
- Added that the daemons create parent directories for `logging.log_file` and `health.status_file` as needed, while `mkdir -p ~/.local/state/p2ptunnel/log` remains a safe optional setup step.
- Aligned the README runtime override examples with the config examples by changing the broker URL override from `test.mosquitto.org:8886` to `test.mosquitto.org:8883`.

## 2026-04-30T12:03:35Z - GPT-5.4 - README status_file clarified
- Added README text that the daemon creates `~/.local/state/p2ptunnel/status.json` when status-file writing is enabled, so users do not normally create it manually.
- Also clarified that `p2pctl status --config ...` expects the status file to already exist, which usually means starting `p2p-offer run` or `p2p-answer run` first so an initial status snapshot is written.

## 2026-04-30T12:27:45Z - GPT-5.4 - README identity-role matching clarified
- Added explicit README guidance that the server should use an `answer-office` identity and answer-side config, while the client should use an `offer-home` identity and offer-side config.
- The keygen section now shows both `p2pctl keygen offer-home` and `p2pctl keygen answer-office`, and the config/operator setup sections now state that the `peer_id` inside `~/.config/p2ptunnel/identity` must match `[node].peer_id` or the daemon will refuse to start.

## 2026-04-30T12:34:10Z - GPT-5.4 - README public broker example switched to EMQX
- Replaced the public `test.mosquitto.org:8883` example with `broker.emqx.io:8883` after confirming the Mosquitto listener presents an X.509 version 1 leaf certificate that the current Rust TLS stack rejects with `UnsupportedCertVersion`.
- Updated the README examples to use the system CA bundle path `/etc/ssl/certs/ca-certificates.crt` instead of the downloaded `mosquitto.org.crt` file.

## 2026-04-30T13:06:16Z - GPT-5.4 - Added signaling publish/receive diagnostics
- Added `debug` logs in `p2p-daemon` around offer-session startup, signaling message publish attempts/successes, and answer-side idle payload decode so stalled signaling runs can distinguish publish failure from receive/decode failure.
- Validated the change with `cargo test -p p2p-daemon --lib`.

## 2026-04-30T13:13:37Z - GPT-5.4 - Pump MQTT event loop after signaling publishes
- Updated `p2p-signaling` so `MqttSignalingTransport::publish_signal` now advances the `rumqttc` event loop after queueing a publish and buffers any own-topic payloads seen during that pump.
- This addresses the runtime gap where `AsyncClient::publish` only queued requests locally until `event_loop.poll()` ran, which could leave offer-side signaling messages unsent while the session remained stuck in `negotiating`.
- Validated with `cargo test -p p2p-signaling` and `cargo test -p p2p-daemon --lib`.

## 2026-04-30T13:23:43Z - GPT-5.4 - Offer session no longer aborts on duplicate active-session signaling
- Updated the offer-side active session loop in `p2p-daemon` to log and ignore signaling decode/replay failures, matching the answer-side behavior, instead of aborting the whole session on duplicate retransmits.
- This was triggered by a live run where signaling and WebRTC reached `connected`, but the offer session then failed on `protocol error: duplicate message detected`.
- Validated with `cargo test -p p2p-daemon --lib`.

## 2026-04-30T13:38:35Z - GPT-5.4 - MQTT subscribe handshake is now completed before sessions rely on inbound signaling
- Updated `p2p-signaling` so `MqttSignalingTransport::subscribe_own_topic` waits for `SUBACK` and buffers any own-topic publishes seen while pumping the event loop.
- This addresses the offer-side runtime gap where `AsyncClient::subscribe` could still be only locally queued while the daemon was already accepting a local client, allowing the answer side's first `Ack` and `Answer` to race ahead of the active subscription.
- Validated with `cargo test -p p2p-signaling --lib`, `cargo test -p p2p-daemon --lib`, and `cargo clippy -p p2p-signaling -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T13:51:58Z - GPT-5.4 - Added tunnel bridge regression tests for OPEN handshake and target-connect failure
- Added real local WebRTC-backed tunnel tests in `p2p-tunnel` that verify the offer/answer `OPEN` handshake reaches the target TCP service and bridges bytes end to end.
- Added a companion regression that verifies answer-side target connect failure is surfaced back to the offer bridge as `RemoteFailure(TargetConnectFailed)` instead of silently hanging.
- Validated with `cargo test -p p2p-tunnel --lib` and `cargo clippy -p p2p-tunnel --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:15:14Z - GPT-5.4 - Added ACK lifecycle and incoming data-channel regressions
- Added focused `p2p-signaling` unit tests covering ACK retirement for the exact `msg_id`, ignoring non-ACK-required message types, and expiry only after the configured retry limit.
- Added a `p2p-webrtc` regression proving the answer side receives the incoming `tunnel` data channel immediately after SDP exchange, matching the daemon's bridge-on-incoming-channel handoff.
- Validated with `cargo test -p p2p-signaling --lib`, `cargo test -p p2p-webrtc --lib`, and `cargo clippy -p p2p-signaling -p p2p-webrtc --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:20:37Z - GPT-5.4 - Added daemon duplicate active-session re-ACK regression
- Extracted the daemon's duplicate active-session re-ACK decision into a small pure helper so the exact re-ACK condition can be tested without needing a broker.
- Added a `p2p-daemon` regression that feeds a real encoded duplicate ack-required payload through that helper and asserts the daemon builds an ACK for the original duplicate `msg_id` back to the authorized remote peer.
- Validated with `cargo test -p p2p-daemon --lib` and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:21:54Z - GPT-5.4 - Full workspace tests passed after latest regression additions
- Re-ran `cargo test --workspace --all-targets` after the latest ACK lifecycle, incoming data-channel, and daemon duplicate re-ACK regression additions.
- The workspace passed cleanly, including 31 `p2p-daemon` tests, 25 `p2p-signaling` tests across unit and mocked-MQTT coverage, 10 `p2p-tunnel` tests, 5 `p2p-webrtc` tests, 11 `p2p-core` tests, 10 `p2p-crypto` tests across unit and integration coverage, and 2 `p2pctl` tests.

## 2026-04-30T14:26:45Z - GPT-5.4 - Added daemon ACK retry and retirement regression
- Added a `p2p-daemon` tokio test that builds a real `ActiveSession`, forces one outbound ack-required message into retry, verifies a duplicate inbound ack-required payload is re-ACKed, and then proves a later inbound ACK retires the pending outbound entry.
- This pins the daemon-owned ACK lifecycle that previously led to operational timeout pain, without depending on a live broker.
- Validated with `cargo test -p p2p-daemon --lib` and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:29:14Z - GPT-5.4 - Full workspace lint and tests passed after latest daemon ACK regression
- Re-ran `cargo clippy --workspace --all-targets --all-features -- -D warnings` and `cargo test --workspace --all-targets` after the latest daemon ACK retry/retirement regression landed.
- Both passed cleanly; the workspace now reports 32 `p2p-daemon` tests, 25 `p2p-signaling` tests across unit and mocked-MQTT coverage, 10 `p2p-tunnel` tests, 5 `p2p-webrtc` tests, 11 `p2p-core` tests, 10 `p2p-crypto` tests, and 2 `p2pctl` tests.

## 2026-04-30T14:38:23Z - GPT-5.4 - Added answer-daemon incoming-channel handoff regression
- Extracted the answer-session incoming-data-channel branch into a small helper so the daemon-owned handoff behavior can be tested directly.
- Added a `p2p-daemon` tokio regression that creates real connected WebRTC peers, hands the answer side an incoming `tunnel` channel, starts the answer bridge immediately from that handoff, and proves an end-to-end `ping`/`pong` exchange succeeds without a separate daemon-side open-event branch.
- Validated with `cargo test -p p2p-daemon --lib` and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:42:58Z - GPT-5.4 - Added offer-session duplicate-survival regression
- Extracted the active offer-session payload branch into a small helper so the daemon's duplicate-message handling and follow-on processing can be tested directly.
- Added a `p2p-daemon` tokio regression that feeds an inbound ACK twice, proves the duplicate replay is ignored instead of aborting the active offer session, and then proves a later valid ACK still retires the pending outbound offer state.
- Validated with `cargo test -p p2p-daemon --lib` and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:45:53Z - GPT-5.4 - Added reconnect leadership regression for answer sessions
- Added a `p2p-daemon` tokio regression that feeds `IceRestartRequest` and `RenegotiateRequest` into an active answer session and proves the answer side ignores both without creating a data channel, bridge task, or replacement session state.
- This pins the v1 policy that offer-side recovery owns reconnect and renegotiation while the answer side does not initiate a fresh session on remote request messages.
- Validated with `cargo test -p p2p-daemon --lib` and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:49:27Z - GPT-5.4 - Added remote-close recovery regressions
- Added focused `p2p-daemon` regressions proving both offer and answer daemons return to their steady states after `DaemonError::RemoteClosed`, covering the missing remote-close half of the teardown recovery path.
- This complements the existing remote-error and target-connect-failure recovery tests so ordinary remote session shutdown now has explicit steady-state coverage for both roles.
- Validated with `cargo test -p p2p-daemon --lib` and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:53:07Z - GPT-5.4 - Added signaling transport buffering regressions
- Added focused `p2p-signaling` coverage for the MQTT transport buffering seam: own-topic publishes are buffered when observed during subscription/pump handling, unrelated events are ignored, and `poll_signal_payload()` drains buffered payloads before polling the broker again.
- This pins the earlier runtime fix around `pending_payloads` so own-topic messages seen around `subscribe_own_topic()` and publish pump boundaries stay recoverable without depending on a live broker race.
- Validated with `cargo test -p p2p-signaling --lib` and `cargo clippy -p p2p-signaling --all-targets --all-features -- -D warnings`.

## 2026-04-30T14:54:25Z - GPT-5.4 - Full workspace validation passed after transport regressions
- Ran the full workspace verification sweep after adding the `p2p-signaling` transport buffering tests; all crates passed `cargo test --workspace --all-targets`.
- The workspace also passed `cargo clippy --workspace --all-targets --all-features -- -D warnings`, so the new regressions are clean outside the signaling crate too.

## 2026-04-30T15:04:29Z - GPT-5.4 - Added daemon-level two-node integration coverage
- Added a public daemon transport seam that keeps the production MQTT entry points unchanged but allows `run_offer_daemon_with_transport` and `run_answer_daemon_with_transport` to run against an injected signaling transport in tests.
- Added `crates/p2p-daemon/tests/two_node_daemon.rs`, which runs real offer and answer daemon tasks over an in-memory signaling transport pair, drives a local client through the offer listener into an answer-side target service, and verifies one full `ping`/`pong` tunnel session plus steady-state recovery back to `waiting_for_local_client` and `idle`.
- Validated with `cargo test -p p2p-daemon --test two_node_daemon`, `cargo test -p p2p-daemon`, and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T15:07:58Z - GPT-5.4 - Added daemon duplicate-survival integration coverage
- Extended the in-memory daemon transport harness to support scripted duplicate delivery on a route and added a second outer-loop test in `crates/p2p-daemon/tests/two_node_daemon.rs` that duplicates the first answer-to-offer signaling payload during an active session.
- The new integration test proves the offer daemon tolerates the duplicated active-session signaling payload, still completes the end-to-end `ping`/`pong` tunnel session, and returns to `waiting_for_local_client` while the answer daemon returns to `idle`.
- Validated with `cargo test -p p2p-daemon --test two_node_daemon`, `cargo test -p p2p-daemon`, and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-04-30T15:11:15Z - GPT-5.4 - Added WebRTC ICE fault injection seam for integration tests
- Added a small non-release ICE state injection hook to `WebRtcPeer` in `crates/p2p-webrtc/src/lib.rs` by retaining a sender for the existing ICE state stream behind `#[cfg(any(test, debug_assertions))]` and exposing `inject_ice_state_for_tests`.
- Added a focused `p2p-webrtc` test proving an injected `IceConnectionState::Disconnected` is observed through the normal `next_ice_state` path, which gives the daemon integration layer a deterministic fault seam for reconnect-leadership coverage.
- Validated with `cargo test -p p2p-webrtc` and `cargo clippy -p p2p-webrtc --all-targets --all-features -- -D warnings`.

## 2026-04-30T15:24:02Z - GPT-5.4 - Added reconnect leadership integration coverage
- Reworked the WebRTC test seam into a cloneable non-release `IceStateInjectorForTests`, added a daemon-side non-release `OfferSessionTestHandle`, and exposed `run_offer_daemon_with_transport_and_test_hook` so integration tests can inject an offer-side ICE disconnect after the initial offer is published.
- Extended `crates/p2p-daemon/tests/two_node_daemon.rs` with message tracing plus a reconnect-leadership integration scenario that injects `IceConnectionState::Disconnected`, verifies the offer side publishes a replacement `Offer`, and verifies the answer side never initiates reconnect signaling (`Offer`, `IceRestartRequest`, or `RenegotiateRequest`). The scenario uses renegotiation-only recovery (`enable_ice_restart = false`) to isolate leadership policy from the currently unsupported active-session ICE-restart path on the answer side.
- Hardened reconnect response handling in `crates/p2p-daemon/src/lib.rs` so reconnect wait loops now ignore stale or duplicate session payloads by reusing the existing tolerant offer-session payload helper.
- Validated with `cargo test -p p2p-daemon --test two_node_daemon`, `cargo test -p p2p-daemon`, `cargo test -p p2p-webrtc`, `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`, and `cargo clippy -p p2p-webrtc --all-targets --all-features -- -D warnings`.

## 2026-04-30T15:32:09Z - GPT-5.4 - Added ICE-restart failure-mode integration regression
- Hardened `wait_for_status` in `crates/p2p-daemon/tests/two_node_daemon.rs` so daemon integration tests tolerate transient empty/partial status-file reads instead of panicking on JSON EOF while the status writer is updating the file.
- Added a targeted daemon integration regression, `active_session_ice_restart_attempt_drops_local_client_before_recovery`, which exercises reconnect with `enable_ice_restart = true`, proves from the signaling trace that the offer side attempts a same-session replacement `Offer`, and verifies the answer side still does not initiate reconnect signaling while the local client is dropped before recovery completes.
- This corrected an earlier shorthand assumption: the trace does show a second `Answer`, so the stable regression is now framed around the verified failure mode rather than asserting that the answer side emits only one answer.
- Validated with `cargo test -p p2p-daemon --test two_node_daemon`, `cargo test -p p2p-daemon`, `cargo test -p p2p-webrtc`, `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`, and `cargo clippy -p p2p-webrtc --all-targets --all-features -- -D warnings`.

## 2026-04-30T15:50:34Z - GPT-5.4 - Fixed pending-session reconnect fallback before tunnel open
- Added `DataChannelHandle::is_open()` in `crates/p2p-webrtc/src/lib.rs` and used it in `crates/p2p-daemon/src/lib.rs` so reconnect logic can distinguish between an established transport and a pre-open session that never reached data-channel availability.
- Changed offer-side reconnect policy so a pre-open ICE failure skips same-session ICE restart and falls back directly to full renegotiation with a replacement `session_id`, keeping the pending local client alive instead of resetting it.
- Changed the active answer-session loop so a replacement offer from the same authorized peer is accepted while the current session is still `Pending`; the answer daemon now swaps to the new session instead of replying `busy` before any tunnel has opened.
- Updated the daemon integration coverage in `crates/p2p-daemon/tests/two_node_daemon.rs` to assert the repaired behavior with `active_session_ice_restart_recovers_pending_local_client`, including the replacement-session fallback while the answer side remains passive.
- Validated with `cargo test -p p2p-daemon --test two_node_daemon active_session_ice_restart_recovers_pending_local_client -- --nocapture`, `cargo test -p p2p-daemon`, `cargo test -p p2p-webrtc`, `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`, and `cargo clippy -p p2p-webrtc --all-targets --all-features -- -D warnings`.

## 2026-04-30T15:59:53Z - GPT-5.4 - Added daemon unit coverage for pending-session replacement
- Added a test-local `RecordingTransport` in `crates/p2p-daemon/src/lib.rs` and a focused unit test, `pending_answer_session_is_replaced_by_same_peer_offer`, that drives `maybe_replace_pending_answer_session` directly instead of relying on the daemon integration harness.

## 2026-04-30T18:13:36Z - GPT-5.4 - Fixed release-build lint split for injected offer transport
- Adjusted `run_offer_daemon_with_transport` in `crates/p2p-daemon/src/lib.rs` so the transport binding stays immutable at the function boundary and becomes mutable only inside the non-test branch that passes `&mut transport` into `run_offer_daemon_inner`.
- This preserves the earlier `cargo build --release --workspace` fix while also satisfying workspace `clippy` by removing the test-build-only `unused_mut` warning.
- The new unit test proves that a pending answer session is swapped to the replacement `session_id`, remains in `connecting_data_channel`, and publishes exactly the expected `Ack` plus `Answer` back to the same authorized peer.
- Validated with `cargo test -p p2p-daemon pending_answer_session_is_replaced_by_same_peer_offer -- --nocapture`, `cargo test -p p2p-daemon`, and `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 2026-05-10T23:00:10Z - GPT-5.4 - Baseline reread refreshed
- Re-read `README.md` and `memory.md` to refresh the current project baseline before further work.
- The active baseline remains: a CLI-only Rust secure TCP tunnel over one reliable ordered WebRTC data channel, MQTT treated as untrusted signaling transport, encrypted and signed signaling, SSH-like identity plus `authorized_keys` workflow, STUN-only v1, and one active tunnel session at a time.

## 2026-05-10T23:01:35Z - GPT-5.4 - Docs baseline reread refreshed
- Re-read the markdown files in `docs/`, including the main spec, implementation TODO, and review-response history, to refresh the current project baseline before further work.
- The docs baseline remains aligned on the v1 model: frozen CLI-only Rust scope, SSH-like identity workflow, encrypted and signed MQTT signaling over an untrusted broker, STUN-only WebRTC, single active session behavior, and the completed review rounds that hardened daemon recovery, busy-policy consistency, status semantics, and config-surface honesty.

## 2026-05-10T23:09:37Z - GPT-5.4 - Session-bound signaling regression triaged from runtime logs
- Runtime log triage showed the first offer/answer session establishing successfully, then later sessions failing with offer-side `acknowledgement timed out` while the answer side retransmits valid current-session `Answer` and `IceCandidate` messages.
- The strongest current diagnosis is an offer-side active-session signaling receive/ACK handling bug around session boundaries: a late previous-session packet is rejected as expected, but the new session then fails to ACK the answer side's current-session signaling traffic even though auth, MQTT connectivity, and the initial session all worked.

## 2026-05-10T23:14:24Z - GPT-5.4 - MQTT signal polling hardened against broker noise
- Hardened `p2p-signaling` MQTT receive polling so the transport keeps consuming non-publish and foreign-topic broker events until it reaches a payload for the node's own signaling topic instead of surfacing spurious `None` results to the daemon loop.
- Added transport coverage for own-topic payload extraction and reran the relevant `p2p-signaling` and `p2p-daemon` test suites, which passed after the change.

## 2026-05-10T23:17:00Z - GPT-5.4 - Transport fix prepared for check-in
- Confirmed the MQTT signal polling hardening and its transport test coverage are the only changes from this session being staged for check-in, while unrelated local edits in other crates remain unstaged.
- The repository-wide validation suite passed before check-in: `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets`.

## 2026-05-12T23:19:16Z - GPT-5.4 - Baseline reread refreshed
- Re-read `README.md` and `memory.md` to refresh the current project baseline before further work.
- The active baseline remains: CLI-only Rust secure TCP tunnel, one reliable ordered WebRTC data channel named `tunnel`, MQTT treated as untrusted signaling transport, encrypted and signed signaling, SSH-like identity plus `authorized_keys` workflow, STUN-only v1, and one active tunnel session at a time.

## 2026-05-12T23:20:38Z - GPT-5.4 - Docs baseline reread refreshed
- Re-read the markdown files in `docs/`, including the main spec, implementation TODO, and review-response history, to refresh the current project baseline before further work.
- The docs baseline remains aligned on the frozen v1 model: CLI-only Rust scope, encrypted and signed MQTT signaling over an untrusted broker, SSH-like identity workflow, STUN-only WebRTC, one active session at a time, completed hardening rounds through review 5, and a narrow remaining focus on keeping the config surface honest and preserving replay/session invariants.

## 2026-05-12T23:49:33Z - GPT-5.4 - Runtime dirs startup behavior fixed
- `p2p-offer` and `p2p-answer` now allow missing runtime artifact paths like `paths.state_dir`, `paths.log_dir`, and the parent dirs for `logging.log_file` and `health.status_file` as long as an existing ancestor passes the world-writable security check.
- Startup now creates those runtime directories before logging/status initialization, so missing state/log trees no longer fail with a generic `Io(NotFound)` while required input files like config, identity, authorized_keys, and broker CA files still remain startup-fatal if absent.

## 2026-05-13T02:50:36Z - GPT-5.4 - Context recovery after runtime-dir fix
- The last completed and pushed change is commit `37ba200` (`Create runtime dirs before daemon startup`), which landed the missing runtime directory creation fix on `master`.
- The current worktree has additional uncommitted edits in `crates/p2p-daemon/src/lib.rs`, `crates/p2p-daemon/tests/two_node_daemon.rs`, `crates/p2p-tunnel/src/bridge.rs`, and `crates/p2p-webrtc/src/lib.rs`; no newer saved session checkpoint was found beyond the pushed runtime-dir fix.

## 2026-05-13T04:01:24Z - GPT-5.4 - Startup file-path errors clarified
- Startup file I/O errors now carry the exact path for config loading, identity and authorized_keys reads, runtime directory creation, and MQTT password/CA/client-cert/client-key reads.
- `p2p-offer` and `p2p-answer` now print top-level errors with `Display`, so a missing config shows as `Error: i/o error for '/path/to/config.toml': No such file or directory (os error 2)` instead of the raw enum debug output.

## 2026-05-13T04:16:46Z - GPT-5.4 - Reconnect integration harness race fixed
- The two failing `two_node_daemon` reconnect tests were tripping a harness race: the injected-disconnect path delayed only the first answer-to-offer publish, but answer-side idle offer handling sends `Ack` before `Answer`, so the `Answer` could still arrive immediately and open the data channel before the test injected `Disconnected`.
- The in-memory test transport now applies that first-response delay inline instead of spawning a background delayed send, which serializes the answer-side response path and makes the pre-open reconnect injection deterministic again.

## 2026-05-13T09:09:45Z - GPT-5.4 - Offer idle MQTT polling fixed
- The offer daemon now keeps polling the signaling transport while waiting for the next local client instead of blocking only on the TCP accept loop.
- That fixes a real runtime gap where an idle offer process could sit on a dead MQTT connection and only discover the broker failure on the next publish, and `p2p-daemon` now has a regression test that forces an idle poll failure/recovery and checks `mqtt_connected` flips false then true while still in `waiting_for_local_client`.

## 2026-05-13T09:15:00Z - GPT-5.4 - Workspace validation rerun passed after test helper hardening
- `cargo clippy --workspace --all-targets --all-features -- -D warnings` and `cargo test --workspace --all-targets` both pass again after tightening the new `p2p-daemon` test helper to retry when the status file exists but is briefly still mid-write.
- The regression was only in test code: `wait_for_status` had assumed any visible status file was immediately parseable JSON, which could fail with EOF during concurrent status writes.

## 2026-05-13T17:31:20Z - GPT-5.5 - Baseline reread refreshed
- Re-read `README.md` and `memory.md` to refresh the current project baseline before further work.
- The active baseline remains: CLI-only Rust secure TCP tunnel, one reliable ordered WebRTC data channel named `tunnel`, MQTT treated as untrusted signaling transport, encrypted and signed signaling, SSH-like identity plus `authorized_keys` workflow, STUN-only v1, one active tunnel session at a time, and latest-known `mqtt_connected` status semantics.

## 2026-05-13T17:33:16Z - GPT-5.5 - Validation run results
- `cargo clippy --workspace --all-targets --all-features -- -D warnings` and `cargo test --workspace --all-targets` passed.
- `cargo fmt --all --check` failed due existing rustfmt differences in `crates/p2p-daemon/src/lib.rs`, `crates/p2p-tunnel/src/bridge.rs`, and `crates/p2p-webrtc/src/lib.rs`.

## 2026-05-13T17:47:22Z - GPT-5.5 - Formatting fixed and validation clean
- Ran `cargo fmt --all` to apply the rustfmt diffs in `crates/p2p-daemon/src/lib.rs`, `crates/p2p-tunnel/src/bridge.rs`, and `crates/p2p-webrtc/src/lib.rs`.
- `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets` all passed after formatting.

## 2026-05-13T17:48:24Z - GPT-5.5 - v0.1 release check-in prepared
- Preparing the validated formatted tree for the `v0.1` release check-in on `master`.
- The release tree includes rustfmt-only cleanup in `crates/p2p-daemon/src/lib.rs`, `crates/p2p-tunnel/src/bridge.rs`, and `crates/p2p-webrtc/src/lib.rs`, plus memory updates documenting the validation baseline.

## 2026-05-13T17:52:42Z - GPT-5.5 - Copilot coauthor trailer investigation
- Commit `cec0f2c` contains a literal `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>` trailer in its commit message.
- The repository currently has only sample hooks in `.git/hooks` and no configured `core.hooksPath`, commit template, trailer, coauthor, or Copilot-related git config, so no active local hook was present to block that trailer.

## 2026-05-13T17:54:55Z - GPT-5.5 - Copilot coauthor trailer blocked locally
- Installed a `commit-msg` hook that rejects `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>` trailers and configured this checkout with `core.hooksPath = .githooks`.
- Verified an actual `git commit --allow-empty` attempt with the Copilot co-author trailer is blocked and leaves `HEAD` unchanged.

## 2026-05-13T17:55:46Z - GPT-5.5 - Copilot coauthor hook check-in
- Preparing to check in the tracked `.githooks/commit-msg` blocker and memory notes so future checkouts can enable the same Copilot co-author trailer protection.

## 2026-05-13T17:59:22Z - GPT-5.5 - Copilot coauthor trailers removed from published history
- Rewrote `master` from `37ba200` onward to remove the exact Copilot co-author trailer from the two affected commit messages, then force-pushed `master`.
- Moved and force-pushed the annotated `v0.1` tag to the rewritten release commit `5d5366b`; rewritten commits are `4a2bd8c` for runtime dirs and `dd94936` for startup file errors/reconnect tests.

## 2026-05-13T18:05:49Z - GPT-5.5 - macOS daemon test CA-path fix
- Fixed the `active_offer_session_ignores_duplicate_signal_and_processes_later_valid_ack` unit test so it uses the existing in-memory `RecordingTransport` instead of building a real MQTT transport from `/etc/ssl/certs/ca-certificates.crt`, which does not exist on macOS CI runners.
- Validated with `cargo fmt --all --check`, the focused daemon regression, `cargo test -p p2p-daemon --lib`, workspace clippy with `-D warnings`, and `cargo test --workspace --all-targets`.

## 2026-05-13T18:14:05Z - GPT-5.5 - v0.1 tag moved to latest master
- Deleted and recreated the annotated `v0.1` tag so it now points to `0f8c89f` (`Fix daemon test CA path portability`) on `master`.
- Pushed the recreated `v0.1` tag to GitHub after deleting the previous remote tag.

## 2026-05-13T18:16:30Z - GPT-5.5 - Contributor status checked after Copilot trailer rewrite
- Verified reachable local history has no Copilot author/committer/co-author identity references; only commit subjects/memory notes mention the word Copilot.
- `gh api repos/ekkus93/rust_webrtc/contributors` currently reports only `ekkus93`, so GitHub's contributors API no longer lists Copilot as a contributor.

## 2026-05-13T19:34:14Z - GPT-5.5 - Multiplexed forwarding docs reviewed
- Read `docs/MULTIPLEXED_FORWARDING_SPEC.md` and `docs/MULTIPLEXED_FORWARDING_TODO.md` without making implementation changes.
- Main items needing clarification before coding are offer-session bootstrap/listener lifecycle, v2 config shape for role-specific forward fields, TURN/config example cleanup, and several stream-level error disclosure/policy choices.

## 2026-05-13T19:35:32Z - GPT-5.5 - Multiplexed forwarding questions written
- Wrote `docs/responses6.md` with the implementation-blocking questions from the multiplexed forwarding doc review so they can be passed to ChatGPT for decisions.

## 2026-05-13T19:38:43Z - GPT-5.5 - Multiplexed forwarding v2 decisions frozen
- Read `docs/replies6.md`; it freezes v2 behavior as offer listeners bound at startup, first local client triggers negotiation, additional clients during negotiation enter a bounded pending queue, no always-on offer WebRTC, and bind failure is startup-fatal.
- It also freezes no TURN, role-specific `[forwards.offer]`/`[forwards.answer]` config, explicit peer allowlists only, authorized-peer stream errors, empty `OPEN(stream_id)` ACK, `p2ptunnel-config-v2`, and tunnel frame version `2` with no v1 compatibility shim.
## 2026-05-13T20:20:53Z - GPT-5.5 - Multiplexed forwarding cleanup phase
- Removed obsolete p2p-tunnel single-stream bridge/answer connector code after moving daemon and tunnel tests to the multiplexed runtime.
- Updated README v2 config examples and migration notes for `[[forwards]]`, added configured forward IDs to status output, and expanded config/mux tests.
## 2026-05-13T20:28:24Z - GPT-5.5 - Multiplexed forwarding final edge cases
- Hardened multiplex stream dispatch so per-stream write queue overflow emits `queue_overflow` and closes only that stream.
- Added final mux tests for OPEN ACK transition, unknown stream DATA, duplicate CLOSE, queue overflow isolation, simultaneous streams, and two forwards on one data channel.
## 2026-05-13T20:50:31Z - GPT-5.5 - Validation passed
- Ran `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets`; all passed.
## 2026-05-13T20:51:50Z - GPT-5.5 - README audit
- README mostly matches the v2 multiplexed forwarding implementation, but still has stale reconnect/status wording that should be corrected.

## 2026-05-13T20:53:22Z - GPT-5.5 - README updated
- Updated README reconnect and status wording so it matches the current v2 multiplexed forwarding implementation.

## 2026-05-13T20:55:04Z - GPT-5.5 - RUST_WEBRTC_SPECS audit
- `docs/RUST_WEBRTC_SPECS.md` is stale after the v2 multiplexed forwarding work; it still describes v1 single-stream config, frame, and scope details.

## 2026-05-13T20:59:32Z - GPT-5.5 - RUST_WEBRTC_SPECS updated
- Rewrote `docs/RUST_WEBRTC_SPECS.md` for the implemented v2 multiplexed forwarding model: `p2ptunnel-config-v2`, frame version 2, `[[forwards]]`, nested `[forwards.offer]`/`[forwards.answer]`, explicit allowlists, multiple logical streams, and current reconnect/status semantics.

## 2026-05-13T21:29:19Z - GPT-5.5 - Multiplexed forwarding fix review triaged
- Read `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW.md` and `docs/MULTIPLEXED_FORWARDING_FIX_TODO.md`; the main real issues are stream task ownership/cancellation, writer failure propagation, inline answer-side target connect, non-empty OPEN ACK acceptance, first-forward-only overrides, and stale multiplexing docs.

## 2026-05-13T21:32:17Z - GPT-5.5 - Multiplexed forwarding fix questions written
- Wrote `docs/responses7.md` with the open implementation questions for ChatGPT: target-connect timeout value, late-DATA policy, first-forward-only override removal/rejection, `remote_io_error`, and listener shutdown wording.

## 2026-05-13T21:35:33Z - GPT-5.5 - Multiplexed forwarding fix decisions frozen
- Read `docs/replies7.md`; it freezes a hardcoded 10-second answer target-connect timeout, late DATA ignored for unknown/closed streams, removal of legacy first-forward CLI flags, startup rejection of legacy first-forward env vars, omitting `remote_io_error`, and keeping daemon-level offer listeners bound across session failures.

## 2026-05-13T21:50:42Z - GPT-5.5 - Multiplexed runtime hardening phase
- Implemented the first multiplexed forwarding hardening phase: runtime streams now retain task handles, stream/session cleanup cancels owned tasks, writer failures are reported to offer/answer loops, answer target connects run asynchronously with a 10-second timeout, late DATA is ignored, and non-empty OPEN ACKs are rejected.

## 2026-05-13T21:57:18Z - GPT-5.5 - Multiplexed cleanup phase
- Removed legacy first-forward-only CLI flags, made legacy first-forward env vars fail startup with clear config errors, removed unused `MultiplexedTunnel`, updated v2 docs/examples, and added parse/validate coverage for documented sample configs.

## 2026-05-13T22:01:11Z - GPT-5.5 - Multiplexed fix pass completed
- Added final lifecycle coverage for daemon-level concurrent forwards and browser-like multiple same-forward streams, completed the multiplexed forwarding fix TODO status/checklist, and confirmed final stale-search matches are historical/review text or valid current tests/behavior.

## 2026-05-13T22:14:12Z - GPT-5.5 - Multiplexed forwarding fix2 review triaged
- Read `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW2.md` and `docs/MULTIPLEXED_FORWARDING_FIX2_TODO.md`; the review aligns with current gaps in local EOF cleanup, TCP write failure reporting, closed per-stream write queue handling, stale OPEN ACK spec wording, and focused stream-local isolation tests.

## 2026-05-13T22:23:39Z - GPT-5.5 - Multiplexed forwarding fix2 completed
- Implemented stream-local EOF/write-failure/closed-queue cleanup, added lifecycle/failure-isolation tests and an OPEN ACK spec guard, updated stale docs, completed `docs/MULTIPLEXED_FORWARDING_FIX2_TODO.md`, and pushed phase commits.

## 2026-05-13T22:48:56Z - GPT-5.5 - Multiplexed forwarding fix3 review triaged
- Read `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW3.md` and `docs/MULTIPLEXED_FORWARDING_FIX3_TODO.md`; the real gaps are malformed answer-side OPEN bubbling as session error and offer-side zero-stream exit behavior conflicting with persistent-session policy.

## 2026-05-13T22:50:00Z - GPT-5.5 - Multiplexed forwarding fix3 question written
- Wrote `docs/responses8.md` asking whether a closed `accepted_clients` channel should be treated as explicit offer-runtime shutdown once zero-active-stream exit is removed for persistent sessions.

## 2026-05-13T22:52:44Z - GPT-5.5 - Multiplexed forwarding fix3 decision frozen
- Read `docs/replies8.md`; v2 persistent sessions must not exit on zero active streams, and `accepted_clients.recv() == None` is the explicit offer-runtime shutdown signal.

## 2026-05-13T23:09:55Z - GPT-5.5 - Multiplexed forwarding fix3 completed
- Implemented Fix 3: malformed answer-side OPEN is stream-local protocol_error, offer sessions persist across zero streams until accepted-client shutdown, docs/guards were updated, and Fix 3 TODO checklist was completed.

## 2026-05-14T23:12:23Z - GPT-5.5 - Baseline reread refreshed
- Re-read `README.md` and `memory.md` to refresh the current project baseline before further work.
- The active baseline remains: CLI-only Rust secure TCP tunnel, one reliable ordered WebRTC data channel named `tunnel`, MQTT treated as untrusted signaling transport, encrypted and signed signaling, SSH-like identity plus `authorized_keys` workflow, STUN-only, v2 multiplexed forwarding over one active tunnel session, and latest-known `mqtt_connected` status semantics.

## 2026-05-14T23:13:51Z - GPT-5.5 - v0.3 docs reread
- Read `docs/V03_SPEC.md` and `docs/V03_TODO.md`.
- v0.3 target is multiple simultaneous authorized offer-peer sessions served by one answer daemon, with one session per peer, unchanged signaling/tunnel wire formats and config shape, per-session failure isolation, centralized MQTT polling, multi-session status/logging, and preserved offer-side reconnect ownership.

## 2026-05-14T23:31:44Z - GPT-5.5 - v0.3 multi-session answer implemented
- Implemented the v0.3 answer-daemon multi-session manager with centralized MQTT polling, per-peer session routing, one active session per peer, internal session capacity, session-local task cleanup, same-peer pending replacement, multi-session status JSON, and readable `p2pctl status` output.
- Added daemon integration coverage for two authorized offer peers served concurrently and for target-connect failure in one peer session not breaking another peer, then marked `docs/V03_TODO.md` complete.
- Full workspace validation passed with `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets`.

## 2026-05-15T03:22:24Z - GPT-5.5 - v0.3 hardening review read
- Read `docs/V03_CODE_REVIEW.md` and `docs/V03_FIX_TODO.md` without making code changes.
- The fix pass focuses on authenticated routing before session dispatch, stale event isolation, honest status fields, explicit same-peer replacement semantics, canonical doc cleanup, and expanded multi-session tests.

## 2026-05-15T03:38:08Z - GPT-5.5 - v0.3 hardening decisions frozen
- Read `docs/replies9.md`; the v0.3 hardening pass should simplify status honestly with `configured_forward_ids` and no fake stream counts, add `DaemonState::Serving`, centrally authenticate/decrypt once before routing typed signals, require session generation tokens on session-owned events, and treat `V03_FIX_TODO.md` as the active checklist while leaving `V03_TODO.md` historical.

## 2026-05-15T03:55:06Z - GPT-5.5 - v0.3 hardening implemented
- Implemented the v0.3 hardening pass: answer routing now authenticates/decrypts once before typed session dispatch, daemon-level replay status preserves duplicate re-ACK behavior, session events carry generation tokens, and generic status/end events cannot re-key or remove newer sessions.
- Status output now uses `DaemonState::Serving` while answer sessions are active, removes fake stream counts, and reports `configured_forward_ids`; docs and `V03_FIX_TODO.md` were updated to match the completed hardening pass.
- Full workspace validation passed with `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets`.

## 2026-05-15T04:33:00Z - GPT-5.5 - Unit test TODO created
- Created `docs/UNIT_TEST1_TODO.md` with a detailed test-only checklist for p2pctl status rendering, signaling replay-status behavior, same-peer replacement isolation, per-forward allowlist isolation, multi-session failure isolation, authenticated-routing edge cases, status schema regressions, and final validation.

## 2026-05-15T04:42:40Z - GPT-5.5 - Unit test TODO implemented
- Implemented `docs/UNIT_TEST1_TODO.md`: added p2pctl status rendering tests, direct signaling replay-status tests, daemon routing/status/replacement/failure-isolation unit tests, and two-node allowlist/failure-isolation integration coverage.
- Full workspace validation passed with `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets`.

## 2026-05-15T04:47:27Z - GPT-5.5 - Integration test TODO created
- Created `docs/INT_TEST1_TODO.md` with a detailed integration-test checklist for transport turbulence, answer daemon restart/recovery, long-lived stream churn, same-peer connection pressure, malformed authenticated signaling, status-file churn stability, harness expansion, and final validation.

## 2026-05-15T06:07:08Z - GPT-5.5 - Integration test TODO implemented
- Implemented `docs/INT_TEST1_TODO.md`: expanded the in-memory integration harness with route-scoped fault injection and added multi-session tests for transport turbulence, restart, stream churn, same-peer pressure, malformed authenticated signaling, and status-file churn.
- Full workspace validation passed with `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets`.

## 2026-05-15T06:55:50Z - GPT-5.5 - V03 FIX2 implemented
- Implemented `docs/V03_FIX2_TODO.md`: unknown-session non-offer messages no longer peer-fallback route, healthy answer steady-state status reports `serving`, and canonical docs now describe current v0.3 multi-session behavior.
- Added focused daemon, p2pctl, and canonical-doc guard tests. Full workspace validation passed with fmt, clippy `-D warnings`, and all workspace tests.

## 2026-05-15T07:00:15Z - GPT-5.5 - Integration test port flake fixed
- Full validation initially exposed parallel `two_node_daemon` port-probe flakiness. Updated the integration test `unused_local_port()` helper to allocate unique candidate ports per test process, then full fmt, clippy, and workspace tests passed.

## 2026-05-15T07:09:16Z - GPT-5.5 - Unit test TODO 2 created
- Created `docs/UNIT_TEST2_TODO.md` with a detailed checklist for additional unit coverage around answer routing matrices, replay/ACK behavior, answer status, p2pctl rendering, canonical doc guards, harness helpers, and status schema compatibility.

## 2026-05-15T08:28:46Z - GPT-5.5 - Unit test TODO 2 implemented
- Implemented `docs/UNIT_TEST2_TODO.md`: added daemon routing matrix tests, replay/ACK duplicate coverage, answer status matrix tests, p2pctl partial/old status rendering tests, canonical doc guards, in-memory transport helper tests, and status schema invariant tests.
- Full workspace validation passed with `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets`.

## 2026-05-15T09:13:02Z - GPT-5.5 - Integration test TODO 2 created
- Created `docs/INT_TEST2_TODO.md` with a detailed checklist for multi-peer integration coverage around simultaneous reconnect pressure, answer daemon restart, signaling turbulence during active streams, same-peer replacement isolation, status churn, malformed authenticated traffic under load, and route-scoped replay stress.

## 2026-05-15T09:24:26Z - GPT-5.5 - Integration test TODO 2 implemented
- Implemented `docs/INT_TEST2_TODO.md`: added two-node integration coverage for simultaneous multi-peer reconnect pressure, multi-peer answer restart with fresh sessions, signaling turbulence during an active TCP stream, and route-scoped drop/duplicate stress, while reusing existing same-peer replacement, status churn, and malformed authenticated signaling coverage.
- Full workspace validation passed with `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo test --workspace --all-targets`.

## 2026-05-15T09:30:18Z - GPT-5.5 - Observed p2p-offer reconnect issue
- User reported seeing cases where a lost connection required restarting `p2p-offer`, and restarting `p2p-offer` fixed the issue. Logs were not saved, so future reconnect investigation should add coverage for established-session loss cleanup and next-client recovery without process restart.

## 2026-05-15T09:37:02Z - GPT-5.5 - Active offer connection-loss recovery fixed
- Added a two-node regression test proving `p2p-offer` accepts a later local client after an established tunnel sees an injected ICE disconnect, without restarting the process.
- Fixed offer-session active tunnel handling so the offer side continues watching ICE state while `run_multiplex_offer` is active and returns to waiting after an active connection-loss failure.
