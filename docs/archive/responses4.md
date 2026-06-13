# Response to `RUST_WEBRTC_CODE_REVIEW4.md`

I read both `docs/RUST_WEBRTC_CODE_REVIEW4.md` and `docs/RUST_WEBRTC_CODE_REVIEW4_TODO.md` and spot-checked the main cited code paths.

My overall take is: **the review is directionally good, and the two main remaining findings mostly look real**.

The clearest likely-valid findings are:

1. **`mqtt_connected` status reporting still looks too optimistic**: `DaemonStatus` is still written with `mqtt_connected = true` in the steady-state, session-active, and reconnect/backoff paths, and the idle recoverable transport-error path currently logs and retries without updating the status surface first.
2. **Active busy-offer handling still looks replay-blind**: the active-session busy-offer classifier creates a fresh `ReplayCache` per call, so duplicate copies of the same allowed foreign offer can likely trigger repeated encrypted `busy` responses.

The broader round-4 direction also looks sensible: clearer fatal-vs-recoverable runtime policy and stronger top-level lifecycle testing are the right remaining areas. I do not have a major architectural disagreement with the review.

The things I want clarified before implementing are:

1. **Exact meaning of `mqtt_connected`**: should it mean “currently usable transport based on the latest known success/failure,” or something stricter like “confirmed broker session is up”? The current transport wrapper exposes polling and publish success/failure, but not a rich broker connection-state API, so this affects how exact the implementation should be.
2. **Lifetime and scope of busy-offer dedupe**: my default recommendation is a **per-active-answer-session** dedupe cache keyed by at least `(sender_kid, msg_id)`, dropped when that active session ends. If you want daemon-wide persistence instead, that should be frozen explicitly.
3. **Round-4 behavioral scope**: do you want this pass to mainly fix observability accuracy and duplicate `busy` replies, or do you also want a stricter frozen rule for every remaining publish/poll failure path? Right now the first two items look like concrete bugs, while the broader runtime-policy section looks more like hardening/coverage work unless you want additional behavior frozen now.
