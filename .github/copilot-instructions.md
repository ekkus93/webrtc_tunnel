# Copilot instructions for `rust_webrtc`

## Project intent

This project is a **CLI-only Rust application** that creates a secure **TCP tunnel over a WebRTC data channel**. MQTT is used only for signaling and control, and must be treated as **untrusted transport**.

The primary operator workflow should feel like **SSH**, not PEM/PuTTY:

- local private identity file
- shareable public identity file
- `authorized_keys` trust list

## Authoritative design sources

- `docs/RUST_WEBRTC_SPECS.md` is the main design spec.
- `docs/RUST_WEBRTC_TODO.md` is the implementation checklist and suggested sequencing.
- When generating code, follow the spec's crate boundaries and protocol rules instead of inventing alternate architecture.

## Frozen v1 constraints

- Rust only
- CLI/headless only; **no GUI**
- MQTT signaling only; **no plaintext signaling**
- All MQTT signaling messages must be **encrypted end-to-end** and **signed**
- MQTT broker is **untrusted**
- Require broker TLS
- **STUN only** in v1; **no TURN**
- **One active tunnel session at a time**
- Always-on `answer` daemon is expected
- No browser support
- No multiplexed streams
- No PEM/PuTTY-centric workflow in the normal operator path

## Crate responsibilities

Keep code aligned with the planned workspace split:

- `p2p-core`: shared IDs, config types, constants, enums, errors
- `p2p-crypto`: identity parsing, authorized keys parsing, KID generation, sign/verify, ECDH, AEAD
- `p2p-signaling`: MQTT transport, topic generation, wire format, replay protection, ACK logic
- `p2p-webrtc`: PeerConnection lifecycle, SDP, ICE, data channel handling
- `p2p-tunnel`: tunnel frame codec, TCP listener/connector, TCP <-> data channel bridge
- `p2p-daemon`: orchestration, session state machine, reconnect policy, status/health
- `p2p-offer`, `p2p-answer`, `p2pctl`: CLI entry points

Do not collapse unrelated responsibilities into a single crate unless the user explicitly changes the design.

## Protocol and security rules to preserve

### Signaling

- MQTT publish payload is a binary encrypted/signed envelope, not JSON.
- All signaling messages are session-bound, replay-protected, and validated against sender and recipient identity.
- Use `sender_kid` / `recipient_kid` as specified by the wire format.
- `sender_kid` must map to an authorized peer.
- Inner `sp` must match that peer's configured `peer_id`.
- Outer `recipient_kid` must be ours.
- Inner `rp` must match our local `peer_id`.
- Any mismatch is a **hard protocol error**: reject, log locally, and do not process further.
- Reject stale, duplicate, future-skewed, unsigned, undecryptable, or unauthorized messages.
- **No retained MQTT signaling messages**. All signaling publishes must use `retain = false`.
- During an active answer session, dedupe repeated foreign busy-offer handling per session using at least `(sender_kid, msg_id)` so duplicates do not trigger repeated encrypted `busy` replies.
- Keep the authenticated per-session busy-offer cache as the correctness boundary; any earlier duplicate shortcut must stay best-effort only.

### ACK and retry behavior

- `offer`, `answer`, `ice_candidate`, `error`, `close`, `ice_restart_request`, and `renegotiate_request` require ACK.
- `ack`, `ping`, and `pong` do **not** require ACK.
- Retry timeout: **2 seconds**
- Max retries: **3**
- Retransmits must be **byte-identical** except for MQTT delivery metadata outside the encrypted payload.
- Duplicate retransmits are matched by **`(sender_kid, msg_id)`**.

### `hello`

- `hello` is **optional** in v1.
- It is a capability hint only.
- It is **not** required before `offer`.
- A peer may send `offer` as the first message of a new session.

### ICE and reconnect

- Do not rely on ambiguous library defaults for candidate completion.
- Support an explicit encrypted **end-of-candidates** signal, or otherwise implement a clearly defined end-of-candidates behavior in the signaling layer.
- The **offer side owns reconnect and renegotiation** in v1.
- The answer side never initiates a fresh session on its own.
- The answer side may send `ice_restart_request` or `error`, but only the offer side sends a new `offer`.
- If both sides detect failure, the answer side waits and the offer side drives recovery.

### Data channel and tunnel behavior

- WebRTC data channel must be **ordered = true** and **reliable = true** in v1.
- The v1 data channel label is fixed to **`tunnel`**; do not make it configurable unless the user explicitly expands scope.
- Data channel open means transport is available; it does **not** by itself mean the target TCP connection is established.
- Keep tunnel `OPEN` in v1.
- Tunnel `OPEN` payload is empty in v1.
- On receiving tunnel `OPEN`, the answer side attempts the target TCP connection for stream `1`.
- On success, the answer side sends tunnel `OPEN` back as an ACK.
- On target connect failure, the answer side sends tunnel `ERROR(target_connect_failed)` immediately.
- That failure ends the tunnel for the current session, even if WebRTC remains alive briefly for orderly shutdown.

### Single-session rule

- Offer and answer behavior must enforce **one active session at a time** in v1.
- The answer daemon allows exactly one active tunnel session.
- A second incoming offer while busy is rejected with an encrypted `error` using `code = "busy"`.

## Config, files, and failure handling

- Use strict config parsing and reject unknown keys when strict mode is enabled.
- Treat the public config surface as fail-closed: unsupported security toggles must be rejected, not silently ignored.
- In v1, `logging.log_rotation` must stay `none`, `health.status_socket` must stay empty, and the reconnect local-client hold knobs remain disabled.
- In v1, ICE timing behavior, WebRTC message size, tunnel frame version, and the single active stream are fixed protocol constants; do not expose them as decorative config knobs.
- In v1, TLS server-name behavior is derived from the broker URL host; do not add a separate public `server_name` override unless the product scope changes.
- Broker auth supports anonymous/certificate-only, username-only, or username+password-file modes; do not require a password file when the config leaves it empty.
- Refuse startup on insecure identity file permissions.
- Refuse startup on role/config mismatches.
- Fail closed on security errors.
- Do not add silent fallback behavior for invalid crypto, identity, replay, or config failures.
- Do not emit plaintext diagnostic or status messages over MQTT.

## Logging and secret handling

- Use structured logging where practical.
- Redact secrets always.
- Redact SDP and ICE candidates by default.
- Do not log decrypted sensitive payloads casually.
- Use `secrecy` and `zeroize` where appropriate for private keys and derived secrets.
- Treat `mqtt_connected` as a latest-known signaling transport usability flag in local status output; update it on recoverable poll/publish failure before backoff and restore it after successful transport activity.

## Implementation style

- Prefer explicit state machines over implicit async flow.
- Bind runtime callbacks and signaling events to session IDs so stale events cannot mutate new sessions.
- Prefer small typed wrappers for identifiers such as `PeerId`, `SessionId`, `MsgId`, and `Kid`.
- Prefer `Result`-based error propagation with precise error types; avoid `unwrap`/`expect` in runtime paths.
- Reuse shared constants and helpers from core crates rather than duplicating protocol literals.

## Testing expectations

When adding protocol or runtime code, prefer tests that protect the security and state-machine invariants:

- valid/invalid identity parsing
- authorized key parsing
- envelope encode/decode
- encrypt/decrypt round-trips
- invalid signature rejection
- replay rejection
- stale timestamp rejection
- busy-session rejection
- target connect failure behavior
- reconnect leadership behavior

## Avoid these mistakes

- Do not introduce GUI code.
- Do not add TURN support in v1.
- Do not trust MQTT payload contents before verification and decryption.
- Do not allow plaintext or unsigned signaling for convenience.
- Do not assume the broker is honest or ordered.
- Do not log secrets, SDP, or ICE candidates by default.
- Do not add multi-session or multiplexed stream behavior unless the user explicitly expands scope.

## Memory file
- You have access to a persistent memory file, memory.md, that stores context about the project, previous interactions, and user preferences.
- Use this memory to inform your decisions, remember user preferences, and maintain continuity across sessions. 
- Before sending back a response, update memory.md with any new relevant information learned during the interaction. Make sure to timestamp and format entries clearly.
- Include the GitHub Copilot model used for the entry in the heading line so memory history records both time and model (for example: `## 2024-06-01T12:00:00Z - GPT-5.4 - User prefers concise responses`).
- **NEVER fabricate or guess timestamps.** Always obtain the current time by running `date -u +"%Y-%m-%dT%H:%M:%SZ"` in the terminal immediately before writing the entry. If the entry describes a specific commit, use `git log -1 --format="%aI" <hash>` for that commit's actual timestamp.
- For each entry, add an ISO 8601 timestamp and a brief description of the information added. For example:
```markdown

## 2024-06-01T12:00:00Z - GPT-5.4 - User prefers concise responses
- User has expressed a preference for concise, to-the-point answers without unnecessary elaboration.
```
