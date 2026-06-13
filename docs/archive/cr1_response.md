# Response to `RUST_WEBRTC_CODE_REVIEW1.md`

Yes, the review mostly makes sense, and several findings are real in the current tree. I spot-checked the cited areas, and I agree the highest-value follow-up is a hardening pass rather than a redesign.

The findings I would treat as confirmed and important are:

1. **Idle answer replay cache is wrong**: the idle loop creates a fresh `ReplayCache` per message, so replay protection does not persist across iterations.
2. **Answer-side tunnel bridge blocks the session loop**: `bridge.run_answer(connector).await` is still inline, so signaling/ICE handling stalls while the tunnel is active.
3. **Offer reconnect for live tunnels is incomplete**: reconnect is still gated on `pending_stream.is_some()`, which stops being true once the bridge starts.
4. **MQTT TLS config drift is real**: the transport still uses default TLS config and does not honor most of the configured TLS fields.
5. **Active answer decode is weaker than it should be**: it still decodes with `expected_session = None` and ACKs before the later session mismatch check.
6. **Idle answer ACK ordering is sloppy**: an offer can get ACKed before `allow_remote_peers` policy rejection.
7. **`p2pctl keygen` overwrite risk is real**.
8. **Password-file and dead-config concerns are also plausible** from the current code.

The main places where I think we should clear scope before implementing are:

- **MQTT TLS support**: do we want full v1 support for custom CA/client cert/server name/skip-verify, or should unsupported TLS knobs be rejected and removed from the public config surface for now?
- **Reconnect policy for a live dropped tunnel**: based on the earlier direction, I think v1 should **fail the local client immediately and reconnect only for the next client**, not try to preserve the live TCP stream. I want to confirm that is still the intended rule for this hardening pass.
- **Data channel label**: should it remain **fixed in v1** (`tunnel`) and the config field be removed, or do we want true configurability?
- **`reconnect.max_attempts = 0`**: the review assumes `0` should mean unlimited retries, but that was never one of the frozen protocol decisions we set earlier. This needs an explicit product decision before changing behavior.

So overall: **the review is directionally good and worth following**, and the P0 list is mostly solid. The only things I would want clarified before coding are the four behavior/scope decisions above.
