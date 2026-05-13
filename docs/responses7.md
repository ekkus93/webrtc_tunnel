# Responses 7 - Multiplexed Forwarding Fix Questions

These are the implementation questions that should be cleared before starting the hardening pass from `docs/MULTIPLEXED_FORWARDING_FIX_TODO.md`.

## 1. Target connect timeout

The TODO recommends moving answer-side target connect out of the central frame dispatcher and adding an internal target-connect timeout.

Should we freeze a hardcoded internal v2 timeout value, such as `10` seconds, for answer-side target connects?

If not `10` seconds, what exact value should be used?

## 2. Late DATA after stream close

For frames that arrive after a stream has already closed, the TODO says:

```text
DATA for closed/unknown stream: ignore or send ERROR(stream_not_found) at most once
```

Which behavior should be frozen for v2?

Options:

1. Ignore late `DATA` for closed streams.
2. Send `ERROR(stream_not_found)` at most once per closed/unknown stream.
3. Send `ERROR(stream_not_found)` every time for unknown streams, but ignore known-closed streams.

## 3. First-forward-only CLI/env overrides

Current CLI/env override behavior can silently mutate only the first configured forward:

- `p2p-offer run --listen-port ...`
- `p2p-answer run --target-host ...`
- `p2p-answer run --target-port ...`
- `P2PTUNNEL_LISTEN_PORT`
- `P2PTUNNEL_TARGET_HOST`
- `P2PTUNNEL_TARGET_PORT`

Should these be removed completely for v2, meaning the CLI flags are no longer accepted and the env vars are ignored?

Or should the env vars be rejected with a clear startup error when present?

## 4. `remote_io_error`

The code review lists `remote_io_error` as a stream-level error, but the current implementation and freshly updated spec only use `local_io_error` for TCP read failures plus the other stream errors:

- `unknown_forward`
- `forbidden_forward`
- `target_connect_failed`
- `stream_not_found`
- `stream_already_exists`
- `protocol_error`
- `local_io_error`
- `queue_overflow`

Should `remote_io_error` be added as a real v2 stream-level error code, or should it be treated as stale review wording and omitted?

## 5. Listener shutdown wording

The TODO says session failure should:

```text
stops listeners
```

But the current v2 offer daemon intentionally binds all configured local listeners at startup and keeps them alive across sessions. That was one of the frozen v2 decisions: first local client triggers negotiation, and the daemon returns to waiting/recovery after session failure.

Should this requirement mean:

1. Do not unbind daemon-level offer listeners; only detach/close accepted clients and stop routing accepted clients into the failed session.
2. Actually unbind and recreate offer listeners after session failure.

I recommend option 1 because it preserves the startup-bound listener model.
