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
