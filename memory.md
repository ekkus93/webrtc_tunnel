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
