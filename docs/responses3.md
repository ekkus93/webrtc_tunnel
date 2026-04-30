# Response to `RUST_WEBRTC_CODE_REVIEW3.md`

I read both `docs/RUST_WEBRTC_CODE_REVIEW3.md` and `docs/RUST_WEBRTC_CODE_REVIEW3_TODO.md` and spot-checked the main cited code paths.

My overall take is: **the review is directionally good, and the main remaining findings mostly look real**.

The findings I would currently treat as likely valid are:

1. **Real offer-daemon busy rejection is still missing at the top level**: `run_offer_daemon()` accepts one local client and then stays inside `run_offer_session()` until that session ends, so it is not clearly continuing to accept-and-reject extra local clients while busy.
2. **Offer-side busy machinery is only partially real today**: the listener-level `active_client` behavior works, but the actual daemon flow is still serialized around one accepted session, so the tested helper behavior does not fully match the runtime model.
3. **Active answer busy handling looks policy-inconsistent**: `maybe_reject_busy_offer()` can send `busy` for a new incoming offer during an active session without clearly applying the stricter `tunnel.answer.allow_remote_peers` policy used on the idle path.
4. **Some config fields still look dead or fixed-only**: `tunnel.offer.auto_open`, `tunnel.write_buffer_limit`, `health.heartbeat_interval_secs`, `health.ping_timeout_secs`, and `tunnel.frame_version` do not currently appear to drive meaningful runtime behavior. `webrtc.max_message_size` is validated, but I do not see it enforced in the actual send path.
5. **Some ordinary operational failures still appear able to terminate the daemon**: session-level recovery looks better now, but top-level errors like local listener accept failures, signaling transport poll/publish errors, and status-file writes still bubble out through `?`.

One thing I would phrase a little more carefully than the review is the testing criticism: the codebase does have meaningful tests now, but the remaining gap is mainly in **top-level orchestration and lifecycle behavior**, not in basic unit coverage.

The things I want clarified before implementing are:

1. **Offer-side busy UX**: for a second local client while a session is active, should v1 do **immediate close**, or should it write a short plaintext local “busy” banner and then close? My recommendation is **immediate close** unless you explicitly want a banner.
2. **Unauthorized or disallowed peer during an active answer session**: should that peer receive **no response at all**, or an explicit encrypted error? I agree it should **not** receive a normal `busy` response.
3. **Dead/fixed-only config cleanup policy**: for fields that are dead or only support one fixed v1 value, do we want to **remove them entirely** from the public config surface, or keep them but **reject non-default values**? My recommendation is to remove clearly dead knobs and only keep fixed-value fields when they document a real protocol/runtime constant.
4. **Operational robustness scope**: which ordinary runtime failures should be treated as recoverable with log/backoff/retry instead of process-fatal? My default recommendation is:
   - startup/config/identity/security failures stay fatal
   - ordinary runtime turbulence like listener accept errors, transient signaling transport errors, and status-file write failures should not kill the daemon

So overall: **I think the review is useful and worth following**, and the biggest items to prioritize are real top-level offer busy behavior, consistent answer-side busy policy, dead config cleanup, and stronger daemon operational robustness.
