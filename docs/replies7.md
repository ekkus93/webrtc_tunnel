# Replies 7 — Multiplexed Forwarding Fix Questions

These are the implementation decisions to hand back to Copilot for the hardening pass from `docs/MULTIPLEXED_FORWARDING_FIX_TODO.md`.

## 1. Target connect timeout

Use a hardcoded internal v2 timeout of **10 seconds** for answer-side target TCP connects.

Freeze this as:

```text
ANSWER_TARGET_CONNECT_TIMEOUT = 10 seconds
```

Required behavior:

- `OPEN(stream_id, forward_id)` starts an async target-connect task.
- The central frame dispatcher must not block on `TcpStream::connect`.
- If target connect succeeds within 10 seconds, the answer side sends an empty `OPEN(stream_id)` ACK.
- If target connect fails or times out, the answer side sends `ERROR(stream_id, target_connect_failed)`.
- That failure affects only that stream, not the whole WebRTC session.

Ten seconds is long enough for normal local/LAN service startup weirdness, but short enough that a bad target does not tie up stream state indefinitely.

## 2. Late `DATA` after stream close

Freeze **Option 1: ignore late `DATA` for closed or unknown streams**.

Do **not** send `ERROR(stream_not_found)` for late `DATA`.

Reason:

- late frames can happen naturally around close races
- sending errors for late data can create error loops
- ignoring unknown/closed stream data is simpler and safer
- stream errors should be used for active protocol failures, not cleanup races

Required v2 behavior:

```text
DATA for unknown stream: ignore and log at debug level
DATA for known-closed stream: ignore and log at trace/debug level
CLOSE for unknown/closed stream: ignore
ERROR for unknown/closed stream: ignore
```

Keep `stream_not_found` available for cases where the implementation actively needs to reject an operation, but do not emit it for ordinary late `DATA`.

## 3. First-forward-only CLI/env overrides

Remove the first-forward-only CLI flags for v2, and reject the legacy environment variables with a clear startup error if present.

### Remove these CLI flags

```text
p2p-offer run --listen-port
p2p-answer run --target-host
p2p-answer run --target-port
```

Do not accept them in v2. They are single-forward-era flags and are misleading with `[[forwards]]`.

### Reject these environment variables if present

```text
P2PTUNNEL_LISTEN_PORT
P2PTUNNEL_TARGET_HOST
P2PTUNNEL_TARGET_PORT
```

Do not silently ignore them. Fail startup with a clear message, for example:

```text
P2PTUNNEL_LISTEN_PORT is no longer supported in config v2.
Use [[forwards]].offer.listen_port in config.toml instead.
```

Reason:

- silently ignoring environment variables causes confusing deployments
- mutating only the first forward is dangerous
- startup failure is much easier to debug

If forward-scoped overrides are needed later, add explicit names such as:

```text
P2PTUNNEL_FORWARD_SSH_LISTEN_PORT
```

Do not add forward-scoped overrides in this pass.

## 4. `remote_io_error`

Treat `remote_io_error` as stale review wording and omit it from v2.

Use one generic stream-level TCP I/O error code:

```text
local_io_error
```

Even though the code runs on both offer and answer sides, from each process's perspective the failing socket is local to that process. So `local_io_error` is enough.

Keep the v2 stream-level error code set as:

```text
unknown_forward
forbidden_forward
target_connect_failed
stream_not_found
stream_already_exists
protocol_error
local_io_error
queue_overflow
```

Do not add `remote_io_error` unless a later diagnostics pass intentionally adds more detailed error semantics.

## 5. Listener shutdown wording

Use **Option 1**.

Do **not** unbind daemon-level offer listeners on session failure.

The intended v2 model is:

```text
offer daemon starts
binds all configured local listeners once
listeners stay alive across sessions
first local client triggers negotiation
session failure closes accepted clients and active streams
daemon returns to waiting using the same bound listeners
```

So the TODO wording should be clarified.

When the TODO says “stop listeners,” it should mean:

```text
stop routing accepted clients into the failed session;
close/detach accepted clients belonging to that session;
do not unbind the daemon-level listener sockets.
```

Listener bind failure is startup-fatal, but session failure should not unbind/recreate listeners. This preserves the startup-bound listener model and avoids unnecessary port churn.

## Final decisions to freeze

1. **Target connect timeout:** hardcoded internal 10 seconds.
2. **Late DATA:** ignore for unknown/closed streams.
3. **Legacy first-forward CLI flags:** remove.
4. **Legacy first-forward environment variables:** reject with clear startup error.
5. **`remote_io_error`:** omit; use `local_io_error`.
6. **Listener shutdown:** keep daemon-level listeners bound; only close session-owned accepted clients/streams.
