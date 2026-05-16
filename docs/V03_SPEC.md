# v0.3 Spec — Multiple Simultaneous Offer Sessions

## 1. Purpose

This document specifies the next runtime evolution after the current v0.2 multiplexed forwarding model: one always-on `p2p-answer` daemon must be able to serve **multiple simultaneous `p2p-offer` clients** at the same time.

Today, v0.2 supports:

- multiple configured forwards,
- one local listener per configured offer-side forward,
- one WebRTC peer session at a time,
- one reliable ordered data channel per session,
- many multiplexed logical TCP streams inside that session.

v0.3 keeps the existing multiplexed per-session tunnel model, but removes the **single active peer session** restriction on the answer side.

The target product model becomes:

```text
one answer daemon
many concurrent authorized offer peers
one WebRTC peer connection per active offer peer session
one reliable ordered data channel per active session
many multiplexed logical TCP streams per active session
```

## 2. Goals

### 2.1 In scope

1. Allow one `p2p-answer` process to host multiple simultaneous active peer sessions.
2. Allow different authorized offer peers to connect concurrently.
3. Preserve the v0.2 multiplexed forwarding model inside each session.
4. Keep signaling encrypted, signed, replay-protected, and session-bound.
5. Keep per-forward authorization enforced on the answer side.
6. Keep offer-side reconnect ownership.
7. Keep failures isolated so one broken session does not tear down unrelated sessions.

### 2.2 Out of scope

1. Do not add TURN.
2. Do not add a GUI or browser support.
3. Do not add arbitrary target selection by the offer side.
4. Do not preserve live TCP streams across WebRTC session failure.
5. Do not add a second forwarding data channel.
6. Do not add clustering or multi-process answer sharding.
7. Do not treat shared keys across multiple offer machines as a supported operator model.
8. Do not add cross-session TCP stream migration.

## 3. Compatibility and versioning

### 3.1 Protocol compatibility

The minimum viable v0.3 design should **not** require a signaling wire-format change.

Keep unchanged:

- MQTT topic layout,
- outer envelope format,
- inner signaling message schema,
- ACK/retry behavior,
- tunnel frame format,
- multiplexed stream framing inside one session.

This is primarily a **daemon/runtime architecture** change, not a crypto or wire-protocol rewrite.

### 3.2 Config compatibility

The minimum viable v0.3 design should keep the existing `p2ptunnel-config-v2` config shape.

No new public config fields are required for the first correct implementation. Current v0.2 config already expresses:

- multiple trusted peers via `authorized_keys`,
- per-forward allowlists via `allow_remote_peers`,
- multiple configured forwards.

If future tuning knobs are added, they must be real enforced behavior, not decorative config.

## 4. Security invariants

All existing security invariants remain mandatory:

1. MQTT broker remains untrusted transport.
2. Broker TLS remains required.
3. All signaling messages remain end-to-end encrypted.
4. All signaling messages remain signed.
5. Outer `sender_kid` and inner `sp` must agree on the authenticated peer.
6. Outer `recipient_kid` and inner `rp` must match the local node.
7. Messages remain replay-protected.
8. Unauthorized or disallowed peers must not receive useful plaintext diagnostics.
9. `allow_remote_peers` remains the per-forward authorization boundary.
10. Secrets, SDP, ICE candidates, and decrypted payload contents remain redacted by default.

v0.3 must not weaken any of the above in order to gain concurrency.

## 5. Product and operator model

### 5.1 Distinct offer identities are required

Each offer machine must use its **own** identity and `peer_id`.

Normal operator model:

- `offer-laptop`
- `offer-desktop`
- `offer-workstation`

All of their `identity.pub` files may be added to the answer host's `authorized_keys`, and those peer IDs may appear in per-forward `allow_remote_peers`.

Using the same identity file on multiple offer machines is operationally ambiguous and is not a supported multi-session model.

### 5.2 Per-peer policy

For the initial v0.3 design, the answer daemon should allow:

- **many peers concurrently**,
- but only **one active session per `peer_id`** at a time.

Rationale:

- one offer daemon already multiplexes many streams inside one session,
- allowing one peer to open multiple concurrent sessions adds little operator value,
- it complicates reconnect/replacement semantics,
- it makes shared-key misuse harder to detect.

If a second unrelated offer from the same authenticated `peer_id` arrives while that peer already has an active session, it should be rejected with encrypted `busy`.

## 6. Runtime model

### 6.1 Answer daemon becomes a multi-session manager

The answer daemon can no longer have a single global `active_session` slot. It must own a registry of active and pending sessions.

Minimum required indices:

- `sessions_by_id: SessionId -> SessionRuntime`
- `session_by_peer: PeerId -> SessionId`

Each `SessionRuntime` owns its own:

- authenticated remote peer identity,
- RTCPeerConnection,
- data channel handle,
- session state,
- ACK tracker,
- per-session retransmit state,
- per-session busy/duplicate helper state,
- multiplexed stream runtime,
- teardown and cancellation handles.

### 6.2 Session states

Each session should have explicit typed states. Recommended answer-side states:

1. `Negotiating`
2. `ConnectingDataChannel`
3. `Active`
4. `Reconnecting`
5. `Closing`
6. `Closed`

The daemon as a whole no longer uses a single `idle/busy` truth model. Instead:

- the daemon remains globally `serving`,
- `active_session_count` becomes the meaningful aggregate,
- each session has its own lifecycle.

### 6.3 MQTT polling remains centralized

There should still be **one** MQTT transport loop for the answer daemon process.

Do **not** create one MQTT client per active peer session.

Instead:

1. poll one signaling transport,
2. decode/authenticate/decrypt each message,
3. route it by authenticated sender and `session_id`,
4. deliver it to the owning session runtime or create a new session when policy allows.

This keeps broker connections, retry behavior, and status semantics coherent.

## 7. Signaling and routing rules

### 7.1 New incoming offers

When a valid encrypted `offer` arrives:

1. authenticate the sender,
2. validate the message as usual,
3. determine whether this is:
   - a new session from a peer with no active session,
   - a valid replacement/reconnect path for the same peer,
   - or a forbidden second concurrent session for the same peer.

### 7.2 Session lookup

Incoming session-bound signaling must be routed by `session_id`.

Rules:

1. If `session_id` matches an existing session, route to that session.
2. If it does not match an existing session and the message type is `offer`, treat it as a new-session candidate.
3. If it does not match and is not a valid new-session entry point, reject or ignore per current protocol rules.
4. Stale callbacks or old-session messages must never mutate a newer replacement session.

### 7.3 Callback binding

All async callbacks from WebRTC and stream runtime must remain bound to the session that created them.

This is a hard requirement:

- stale ICE callbacks from session A must not alter session B,
- stale data-channel events from a closed session must be ignored,
- teardown completion from one session must not clear global state for another session.

## 8. Busy and capacity policy

### 8.1 Current single-session busy policy must change

Today, any second allowed peer during an active answer session is rejected with encrypted `busy`.

In v0.3, `busy` should be emitted only when a real capacity rule is hit.

### 8.2 First-pass capacity policy

For the first v0.3 implementation, use these runtime rules:

1. allow concurrent sessions from different authorized peers,
2. allow at most one active session per `peer_id`,
3. enforce one global hard limit on concurrent answer sessions,
4. when the global limit is reached, fully allowed peers receive encrypted `busy`,
5. unauthorized or disallowed peers still receive no useful response.

The initial global limit may be a fixed implementation constant for the first pass rather than a public config knob.

### 8.3 Same-peer second session policy

If peer `offer-laptop` already has an active session:

- a duplicate/retransmitted message for that session follows normal dedupe rules,
- a valid reconnect/replacement flow for that session remains allowed,
- a second unrelated new offer from `offer-laptop` gets encrypted `busy`.

## 9. Reconnect and replacement

Offer-side reconnect ownership remains unchanged.

### 9.1 What stays the same

1. The offer side still drives reconnect and renegotiation.
2. The answer side still does not initiate a fresh session on its own.
3. Same-session replacement rules remain session-local.

### 9.2 What changes

Reconnect handling must now be isolated per session. One peer reconnecting must not:

- pause unrelated active peer sessions,
- overwrite global answer runtime state,
- drop other peers' streams,
- reset daemon-wide busy state.

### 9.3 Same-peer replacement

If a same-peer replacement offer is valid under existing reconnect semantics, the answer daemon may atomically replace only that peer's current session entry. This replacement must be scoped to that peer/session pair and must not disturb unrelated sessions.

## 10. Tunnel and forward behavior

No tunnel frame changes are required.

Each active peer session still owns:

- one data channel,
- one multiplexed logical-stream space,
- one stream ID allocator.

Important consequence:

- stream IDs remain unique **within a session**, not globally across all sessions.

The answer daemon therefore hosts:

- many sessions,
- each with many streams,
- each stream authorized by `forward_id` against that session's authenticated peer.

Per-forward authorization remains stream-local:

- a peer may establish a session successfully,
- but still be denied `OPEN` on a specific forward if `allow_remote_peers` rejects that peer.

## 11. Failure isolation

Failure isolation is a core v0.3 requirement.

### 11.1 Session-local failures

These must be session-local:

- target connect failure for one stream,
- stream protocol error,
- stream writer failure,
- WebRTC failure for one session,
- ACK timeout for one session,
- reconnect failure for one session,
- remote close/error for one session.

### 11.2 Daemon-fatal failures

These remain process-fatal:

- invalid startup config,
- invalid identity/authorized keys,
- TLS/security misconfiguration,
- cryptographic initialization failure,
- MQTT transport initialization failure that prevents entering service,
- listener/setup failure that prevents the daemon from serving at all.

## 12. Status and observability

### 12.1 Local status file

The current single-session status model is insufficient.

v0.3 status should include at least:

- local `peer_id`,
- role,
- `mqtt_connected`,
- daemon service state,
- `active_session_count`,
- session capacity,
- a list of active sessions.

Recommended per-session status fields:

- `session_id`
- `remote_peer_id`
- `state`
- `data_channel_open`
- `configured_forward_ids`

`active_stream_count` is omitted until it can be populated from real multiplex-runtime state. Configured forward IDs must not be labeled as open/active forward IDs.

For a healthy answer daemon, daemon-level `current_state` reports `serving` with zero or more active sessions; individual session lifecycle details remain in the per-session entries.

Status output must remain local-only and must not publish plaintext status over MQTT.

### 12.2 Logging

Every answer-side log for session-owned work should include:

- local role,
- `session_id`,
- remote `peer_id`,
- and `stream_id` where applicable.

Without this, concurrent behavior will be very difficult to debug.

## 13. Crate-by-crate implementation impact

### 13.1 `p2p-core`

Likely changes:

- status structures may need multi-session output support,
- new constants for answer-side session capacity may live here if shared.

No crypto or forward config rewrite is required for the minimum pass.

### 13.2 `p2p-signaling`

Likely changes:

- little or no wire-format change,
- possibly additional routing helpers around session dispatch,
- no weakening of replay or ACK behavior.

### 13.3 `p2p-webrtc`

Likely changes:

- confirm that peer/data-channel callbacks remain cleanly session-bound,
- expose only the minimal hooks needed for per-session lifecycle control.

### 13.4 `p2p-tunnel`

Likely changes:

- minimal protocol impact,
- ensure stream runtime remains fully session-local and can be owned many times concurrently by the answer daemon.

### 13.5 `p2p-daemon`

This is the main work.

Required changes:

1. replace single active answer session state with a session registry,
2. route inbound signaling by `session_id`,
3. maintain per-peer session occupancy,
4. enforce capacity and same-peer rules,
5. isolate reconnect/teardown to the owning session,
6. expose multi-session status,
7. keep `mqtt_connected` as a daemon-level transport usability signal.

### 13.6 `p2pctl`

Likely changes:

- `status` output must display multiple sessions cleanly.

## 14. Testing requirements

The implementation is not complete unless it has focused coverage for at least:

1. two different authorized offer peers establishing simultaneous sessions to one answer daemon,
2. each session opening multiple multiplexed forwards concurrently,
3. one session failing while the other remains healthy,
4. same-peer second unrelated offer receiving `busy`,
5. same-peer valid reconnect/replacement staying scoped to that peer only,
6. unauthorized peer still receiving no useful response,
7. per-forward allowlist enforcement across concurrent sessions,
8. duplicate signaling/replay handling remaining correct with many active sessions,
9. status output reporting multiple active sessions correctly,
10. stale callbacks from a closed session not mutating a different active session.

At least one end-to-end integration test should prove:

- peer A and peer B both connect,
- both open streams,
- peer A fails and is cleaned up,
- peer B remains active and usable throughout.

## 15. Migration and rollout

### 15.1 Operator migration

The operator-facing setup should remain simple:

1. keep one `p2p-answer` on the remote host,
2. generate one unique offer identity per client machine,
3. add each client `identity.pub` to the answer host's `authorized_keys`,
4. list those peer IDs in the relevant `allow_remote_peers` arrays,
5. upgrade the answer daemon binary.

No new per-client config shape should be required for the minimum pass.

### 15.2 Backward compatibility

Because the first pass keeps the signaling and tunnel wire formats stable, an updated answer daemon should continue to interoperate with existing v0.2 offer daemons, while simply allowing more than one of them to be active at once.

## 16. Completion criteria

v0.3 is complete only when all of the following are true:

1. one answer daemon can host multiple simultaneous authorized offer peers,
2. each peer still uses one session with multiplexed logical streams,
3. per-forward authorization still gates target access,
4. one session's failure does not kill unrelated sessions,
5. same-peer duplicate/new-session policy is explicit and enforced,
6. status and logs are multi-session aware,
7. signaling, replay, and reconnect invariants remain intact,
8. integration tests prove real concurrent-session behavior rather than only unit-level bookkeeping.
