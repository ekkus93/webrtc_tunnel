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
