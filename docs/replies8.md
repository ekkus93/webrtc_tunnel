# replies8.md

## Answer to Copilot's Question — Offer Runtime Shutdown with Persistent Sessions

Yes. The proposed behavior is the intended v2 behavior.

For v2 persistent sessions:

- Zero active streams alone must **not** close the WebRTC session.
- The offer runtime should stay alive and wait for future accepted local clients.
- Future clients should open new logical streams over the same existing data channel.

The old condition:

```rust
manager.active_count() == 0 && opening_streams.is_empty() && streams.is_empty()
```

should no longer cause `run_multiplex_offer` to exit by itself.

That condition was appropriate for a “session exists only while streams exist” model, but it contradicts the persistent-session policy.

## Frozen v2 Behavior

The offer runtime exits when one of these happens:

- `accepted_clients.recv()` returns `None`
- data channel / WebRTC session fails
- central writer fails
- remote session closes
- daemon shutdown occurs
- fatal protocol/session error occurs

The offer runtime does **not** exit merely because active stream count reaches zero.

## Intended Runtime Flow

```text
WebRTC session opens
client A connects -> stream 1
stream 1 closes
session remains open
client B connects later -> stream 2
stream 2 closes
session remains open
...
listener/daemon shuts down -> accepted_clients closes -> runtime exits
```

## Implementation Direction

Remove or adjust the zero-stream exit condition in `run_multiplex_offer`.

Use a closed `accepted_clients` channel as the explicit local shutdown signal for the offer runtime.

This means:

```text
accepted_clients.recv() returns None
```

should be treated as:

```text
The accept/listener side has been intentionally shut down.
The offer runtime may exit cleanly.
```

## Summary

Copilot's proposed shutdown behavior is correct:

- persistent sessions stay open across zero active streams
- local accept/listener shutdown is represented by `accepted_clients` closing
- stream closure is not the same thing as session closure
- future local clients may reuse the existing WebRTC/data-channel session
