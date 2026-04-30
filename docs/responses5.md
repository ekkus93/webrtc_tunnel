# Response to `RUST_WEBRTC_CODE_REVIEW5.md`

I read both `docs/RUST_WEBRTC_CODE_REVIEW5.md` and `docs/RUST_WEBRTC_CODE_REVIEW5_TODO.md` and spot-checked the main cited code paths.

My overall take is: **the review is directionally good, and the strongest remaining findings mostly look real**.

The clearest likely-valid findings are:

1. **`webrtc.ice_gather_timeout_secs` still looks unused**: I do not see convincing evidence that this config field is enforced in the actual runtime flow.
2. **`webrtc.ice_connection_timeout_secs` still looks unused**: I likewise do not see clear evidence that this field governs connection-establishment behavior in the current daemon/runtime path.
3. **Active busy-offer dedupe still happens later than ideal**: repeated duplicate `busy` replies are now suppressed, but the active-answer busy-offer path still appears to fully decode / verify / decrypt / classify replayed duplicates before dedupe suppresses the response.

I do **not** see a major architectural concern in this round. This looks like a narrower honesty/hardening pass, which is the right shape for where the codebase is now.

The things I want clarified before implementing are:

1. **Should the two WebRTC timeout fields be removed or made real?** My recommendation is to **remove both from the public v1 config** unless you explicitly want them as real operator-facing features right now. As things stand, they look like fake knobs.
2. **For earlier active busy-offer dedupe, is it acceptable to suppress duplicates based on the outer-envelope key `(sender_kid, msg_id)` before full signature verification / decrypt / classification?** That is the cleanest way to avoid repeated work, but it means doing an early cheap dedupe on unauthenticated envelope metadata before the full decode path. I think that is reasonable here, but I would want that frozen explicitly.
3. **Do you want this round to trim any remaining fixed-only config fields beyond the two WebRTC timeouts?** I would be a little more cautious than the review here, because some remaining fixed-only fields seem intentionally kept as documented compatibility constants rather than accidental fake knobs. If you want more trimming, we should freeze exactly which ones.
