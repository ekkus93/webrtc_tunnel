# Responses 8

## 2026-05-13T22:50:00Z - GPT-5.5 - Multiplexed Forwarding Fix 3 Questions

I reviewed `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW3.md` and `docs/MULTIPLEXED_FORWARDING_FIX3_TODO.md`.

The review appears accurate overall. I found one implementation detail that should be frozen before coding.

## Question 1 — Offer runtime shutdown when persistent sessions are enabled

Fix 3 asks us to freeze this persistent-session policy:

```text
After the last logical stream closes, the WebRTC peer connection and data channel remain open.
The offer daemon may accept future local clients and open new logical streams over the same existing data channel.
The session closes only on data-channel/WebRTC failure, explicit daemon shutdown, remote session close, writer/session failure, or fatal protocol/session error.
```

Current `run_multiplex_offer` exits after a data-channel message if:

```rust
manager.active_count() == 0 && opening_streams.is_empty() && streams.is_empty()
```

That zero-stream exit should be removed or adjusted for persistent sessions.

However, once that exit is removed, the offer runtime needs a clear local shutdown condition for tests and daemon shutdown. The natural option is:

```text
If accepted_clients.recv() returns None, treat that as explicit offer-runtime shutdown.
```

In other words:

- zero active streams alone does not close the session,
- a closed accepted-client channel means the local accept/listener side is gone and the offer runtime may exit cleanly,
- data-channel/WebRTC failure, central writer failure, remote close, or daemon shutdown still terminate the session.

Is this the intended v2 behavior?

