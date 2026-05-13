# Multiplexed forwarding review questions

These are the clarification items to resolve before implementing `docs/MULTIPLEXED_FORWARDING_SPEC.md` and `docs/MULTIPLEXED_FORWARDING_TODO.md`.

## 1. Offer-side bootstrap and listener lifecycle

The spec says to prefer starting forward listeners only after the WebRTC data channel is open, but the current product model uses the offer-side local TCP listener to trigger session creation.

Which behavior should v2 use?

- Option A: offer daemon binds all configured local listeners at startup; the first accepted local connection triggers session setup, and other listeners/clients are rejected or queued until the tunnel is ready.
- Option B: offer daemon establishes an always-on WebRTC session to `[peer].remote_peer_id` first, then starts local listeners only after the data channel opens.
- Option C: some other lifecycle.

This is the main architecture blocker.

## 2. TURN/config example cleanup

The multiplexing spec examples include:

```toml
turn_urls = []
```

Current v1 constraints say STUN-only and no TURN. Should v2 multiplexing keep the no-TURN constraint and remove `turn_urls` from the new docs/examples?

## 3. Role-specific forward config shape

The proposed `[[forwards]]` shape contains both offer-side fields and answer-side fields. The examples use placeholder opposite-role values such as:

```toml
target_host = ""
target_port = 0
allow_remote_peers = []
```

on offer configs, and:

```toml
listen_host = ""
listen_port = 0
```

on answer configs.

Should role-irrelevant fields be omitted instead of set to dummy placeholder values, assuming strict unknown-key validation remains enabled?

## 4. `allow_remote_peers` wildcard policy

One section mentions allowing all authorized peers in the future, but later says not to implement `["*"]` unless intentionally specified.

Should v2 freeze `allow_remote_peers` as explicit peer IDs only, with no wildcard/sentinel support?

## 5. Stream-level error disclosure

The docs allow `unknown_forward` and `forbidden_forward` stream-level errors for otherwise authorized peers. That means an authorized peer can probe whether a forward ID exists or is forbidden.

Is that acceptable for v2, or should some forward-denial cases be silent to avoid forward ID disclosure?

## 6. `OPEN` ACK payload

The spec allows either an empty `OPEN` payload or `{ "ok": true }` as the answer-side success ACK.

Should v2 freeze the ACK as an empty `OPEN(stream_id)` payload to match the current v1 `OPEN` ACK style?

## 7. Listener bind failure policy

The TODO says listener bind failure should fail startup or session according to the chosen policy.

Which policy should v2 use?

- If listeners bind at daemon startup, bind failure should be startup-fatal.
- If listeners bind only after data channel open, bind failure should fail that session and return the daemon to its normal recovery/waiting behavior.

## 8. Required protocol/config version bump

This is a breaking change to the config and tunnel frame behavior.

Should implementation require both:

- `format = "p2ptunnel-config-v2"`
- tunnel frame `version = 2`

rather than leaving frame version bump optional?
