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
