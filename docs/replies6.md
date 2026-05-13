# Replies to Copilot Questions for `MULTIPLEXED_FORWARDING_SPEC.md` and `MULTIPLEXED_FORWARDING_TODO.md`

These are the final decisions for the multiplexed forwarding implementation pass.

## 1. Offer-side bootstrap and listener lifecycle

Use **Option A**, with one refinement:

**Bind all configured offer-side local listeners at daemon startup. The first accepted local connection triggers WebRTC session setup. Accepted local connections during setup are held in a bounded pending queue, then converted into multiplexed streams once the data channel opens.**

Do **not** use always-on WebRTC for v2.

Reason:

- preserves the current product model where local TCP activity triggers the tunnel
- avoids keeping an idle WebRTC session alive forever from the offer side
- still supports browser-style multiple connections arriving close together
- keeps the answer side as the always-on MQTT listener

Freeze this behavior:

```text
offer daemon starts
bind all configured local forward listeners
wait for local TCP clients
first client triggers WebRTC negotiation
while negotiating, accept additional clients into bounded pending queue
when data channel opens, assign stream_ids and send OPEN frames
if negotiation fails, close pending clients
when all streams close, session may return to idle
```

The pending queue must be bounded. If it fills, immediately close new local clients.

## 2. TURN/config example cleanup

Keep the current **STUN-only / no TURN** constraint for v2.

Remove `turn_urls = []` from the multiplexing docs and examples.

The v2 multiplexing change should not reintroduce TURN support accidentally. If TURN is added later, it should be a separate explicit feature pass.

## 3. Role-specific forward config shape

Omit role-irrelevant fields instead of using dummy placeholders.

Use role-specific sub-tables inside each forward.

Example offer-side config:

```toml
[[forwards]]
id = "ssh"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 2223
```

Example answer-side config:

```toml
[[forwards]]
id = "ssh"

[forwards.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["laptop"]
```

Validation rules:

- `role = "offer"` requires `[forwards.offer]` for every configured forward used by that offer node.
- `role = "answer"` requires `[forwards.answer]` for every configured forward exposed by that answer node.
- role-irrelevant sub-tables may be omitted.
- unknown keys should still be rejected.

## 4. `allow_remote_peers` wildcard policy

Freeze v2 as **explicit peer IDs only**.

No wildcard support.

This is valid:

```toml
allow_remote_peers = ["laptop", "workstation"]
```

This is invalid in v2:

```toml
allow_remote_peers = ["*"]
```

Reason:

- avoids accidental exposure of sensitive local services
- keeps authorization explicit
- matches the secure-by-default design

## 5. Stream-level error disclosure

For v2, it is acceptable for an **authorized and allowed signaling peer** to receive stream-level errors such as:

- `unknown_forward`
- `forbidden_forward`
- `target_connect_failed`

But do **not** send these to unauthorized or disallowed signaling peers.

Policy:

```text
unauthorized peer: no response
authorized but not allowed at daemon/session level: no response
authorized peer allowed to talk to this daemon, but not allowed for this forward: stream ERROR forbidden_forward
authorized peer requests unknown forward_id: stream ERROR unknown_forward
```

This does allow an authorized peer to learn whether a forward ID exists. That is acceptable for v2 because the peer is already trusted enough to establish encrypted signaling with the daemon. The benefit is much better debuggability.

If forward ID secrecy becomes important later, add a config option like `hide_forward_denials = true`, but do not complicate v2.

## 6. `OPEN` ACK payload

Freeze the success ACK as:

```text
OPEN(stream_id) with empty payload
```

No `{ "ok": true }`.

Rules:

- offer sends `OPEN(stream_id, payload = { forward_id })`
- answer accepts and connects target
- answer replies with `OPEN(stream_id, empty payload)`
- answer failure replies with `ERROR(stream_id, code/message)`

This matches the v1 style and keeps the frame protocol simple.

## 7. Listener bind failure policy

Because v2 should bind offer-side listeners at daemon startup:

**listener bind failure is startup-fatal.**

If any configured local listener cannot bind, the offer daemon should fail startup with a clear error.

Reason:

- partial listener startup is confusing
- a bad port conflict should be obvious immediately
- it keeps the daemon state honest

Example failure:

```text
failed to bind forward "web-ui" on 127.0.0.1:8080: address already in use
```

## 8. Required protocol/config version bump

This is a breaking change.

Require both:

```toml
format = "p2ptunnel-config-v2"
```

and tunnel frame:

```text
frame version = 2
```

Do not keep this optional.

Reason:

- config shape changes from single tunnel to `[[forwards]]`
- stream ID semantics change from effectively fixed `1` to real multiplexing
- `OPEN` payload semantics change
- answer target mapping changes to `forward_id`
- older peers should fail clearly rather than misinterpret frames

Freeze this as:

- v2 daemon rejects `p2ptunnel-config-v1`
- v2 tunnel codec emits frame version `2`
- v2 tunnel codec rejects frame version `1`
- no v1/v2 compatibility shim in this pass

## Final frozen v2 choices

1. Offer binds all local forward listeners at startup.
2. First local client triggers WebRTC negotiation.
3. Local clients accepted during negotiation go into a bounded pending queue.
4. No TURN in v2.
5. Role-specific forward config uses `[forwards.offer]` and `[forwards.answer]`.
6. `allow_remote_peers` requires explicit peer IDs only.
7. Authorized peers may receive stream-level `unknown_forward` / `forbidden_forward` errors.
8. `OPEN` success ACK is empty `OPEN(stream_id)`.
9. Listener bind failure is startup-fatal.
10. Require `p2ptunnel-config-v2` and tunnel frame version `2`.
