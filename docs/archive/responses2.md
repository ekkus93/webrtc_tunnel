# Response to `RUST_WEBRTC_CODE_REVIEW2.md`

I read both `docs/RUST_WEBRTC_CODE_REVIEW2.md` and `docs/RUST_WEBRTC_CODE_REVIEW2_TODO.md` and spot-checked the main cited code paths.

My overall take is: **the review is directionally good, and several of the top findings look real**.

The findings I would currently treat as likely valid are:

1. **Per-session failures can terminate the daemons**: both `run_offer_daemon()` and `run_answer_daemon()` currently call their per-session runners with `?`, so session-level failures appear able to bubble out and end the process instead of returning to waiting/idle.
2. **`deny_when_busy` looks behaviorally dead**: the busy path in `crates/p2p-tunnel/src/offer.rs` appears to do the same thing regardless of the flag.
3. **Offer-side active-client bookkeeping looks wrong**: `OfferClient::into_stream()` consumes the wrapper, and `Drop` then clears the active-client flag too early, before the full session lifetime is over.
4. **The v1 concurrency surface is misleading**: the code/config still expose knobs like `max_concurrent_clients` and `deny_when_busy`, but the actual model is effectively one session at a time.
5. **`server_name` is not really an independent override today**: it behaves more like a consistency check against the broker host than a truly separate TLS control.
6. **The integration-test gap is real**: there is decent unit coverage, but not enough high-level daemon/session lifecycle coverage for “session fails, daemon survives, returns to waiting.”

The things I want clarified before implementing are:

1. **`deny_when_busy`**: for v1, do we want to remove it outright and hardcode “reject new local clients while busy”? That is my recommendation.
2. **`max_concurrent_clients`**: do we also want to remove this from the v1 config surface, since the product rule is one active session at a time? That is my recommendation.
3. **`server_name` semantics**: do we want to remove `broker.tls.server_name` from the public config surface for v1 rather than pretend it is independently configurable? That is my recommendation unless you explicitly want true override semantics.
4. **Daemon post-failure state**: when a session fails but the daemon survives, should the daemon always return to `Idle` / `WaitingForLocalClient` immediately after cleanup, including for ACK timeout, target-connect failure, bridge failure, ICE failure, and remote error? I think the answer should be yes, but I want that frozen explicitly.

So overall: **I think the review is useful and worth following**, and the biggest items to prioritize are daemon survival, dead busy/concurrency config, and fixing the offer-side active-client lifetime tracking.
