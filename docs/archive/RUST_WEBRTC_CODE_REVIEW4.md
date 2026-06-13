# Rust WebRTC Tunnel Code Review 4

## Scope

This review covers the latest uploaded Rust workspace after the previous hardening pass. It is based on a static source review only. The code was **not** built or executed in this environment because the container does not have `cargo`/`rustc` installed.

The purpose of this review is to capture:

- what is good about the current code
- what is bad or still weak
- concrete bugs or likely-wrong behavior that should be fixed
- the next highest-value hardening tasks

---

## Overall assessment

This revision is better than the previous one.

The largest previous issues around:

- daemon survival after session failure
- top-level offer-side busy behavior
- dead config cleanup
- active-answer busy policy consistency

have mostly improved.

The code now looks closer to the intended v1 product:

- single-session CLI daemon model
- encrypted/signed MQTT signaling
- SSH-like key workflow
- always-on answer daemon behavior
- more honest config surface

That said, the code is **not fully production-ready** yet. The main remaining issues are now more about:

- observability accuracy
- replay/dedup handling in an active busy-offer edge path
- clearer fatal-vs-recoverable runtime policy
- higher-level lifecycle/integration coverage

---

## What is good about the code

### 1. The daemon lifecycle is materially better

The top-level daemons no longer appear to die immediately on ordinary session failures.

This is a major improvement over the earlier tree. The offer and answer daemons now attempt to recover to a steady state instead of treating every session failure as process-fatal.

That is much closer to the intended always-on behavior.

### 2. Real offer-side busy handling now exists at the top level

This is also a real improvement.

The offer daemon now uses a real accept loop while a session is active, instead of only testing busy behavior at the helper level. Extra local clients are accepted and immediately dropped rather than simply being left in the listen backlog until the active session ends.

That is much closer to the intended v1 policy:

- one active local client/session at a time
- additional local clients are rejected immediately
- no plaintext banner

### 3. The config surface is cleaner and more honest

A number of previously dead or misleading knobs appear to have been removed.

This is good. The config is now more aligned with the actual runtime model instead of pretending to support extra v1 behavior that is not really implemented.

### 4. Security/config validation remains strong

The security posture still looks appropriately fail-closed in important areas:

- `mqtts://` required
- encrypted + signed signaling required
- `authorized_keys` required
- `insecure_skip_verify` rejected
- path hardening checks present
- unsupported v1 knobs rejected instead of silently ignored

That is the right direction for this project.

### 5. Offer-client lifetime tracking looks improved

The earlier problem where the offer-side active-client flag could be cleared too early appears to have been addressed. The active-session tracking now looks closer to the actual lifetime of the accepted local session.

### 6. Test coverage is moving in the right direction

The code has meaningful unit tests and helper-level tests, including tests around:

- replay behavior
- config validation
- busy handling
- recovery behavior
- status-file write recovery
- accept-loop behavior

The test focus is more aligned with the real runtime model than before.

---

## What is bad about the code

### 1. The remaining risk is now mostly orchestration/runtime behavior

The weak spots are less about basic crypto framing and more about top-level behavior:

- actual daemon steady-state behavior
- accurate status reporting
- duplicate/replayed busy-offer behavior during active sessions
- how runtime turbulence is handled outside normal session failures

### 2. Observability is weaker than it looks

The code has a `DaemonStatus` surface, but from inspection the `mqtt_connected` field appears to be overly optimistic. It looks like status is being written as if MQTT is connected even when the daemon has just hit recoverable signaling transport problems.

That means the status surface exists, but may not be trustworthy enough yet.

### 3. Some runtime robustness questions remain

Ordinary session failures appear better handled now, but there are still likely non-session runtime failures that can bubble out and terminate the daemon when they would be better treated as recoverable with logging/backoff/retry.

### 4. There is still room for more high-level lifecycle testing

The unit tests are better than before, but the remaining risk area is not basic parsing. It is end-to-end daemon/session orchestration.

---

## Concrete bugs / things that look wrong

### 1. `mqtt_connected` status reporting appears inaccurate

This is the clearest remaining bug.

The current status surface appears to construct `DaemonStatus` with `mqtt_connected = true` in places where the daemon may have just encountered recoverable signaling transport failures.

That means the status file can claim the daemon is MQTT-connected even when:

- signaling transport polling has just failed
- a publish path has failed and recovery is in progress
- connectivity is not currently established but the daemon is attempting to recover

This is a real observability bug.

#### Why this matters

Operators may rely on the status file for local health checks. If it claims the daemon is connected when it is not, it becomes misleading and reduces the value of the local status surface.

#### Recommended fix

Track real MQTT connectivity state in daemon runtime state and propagate it into status writes.

At minimum:

- set `mqtt_connected = false` on recoverable transport disconnect/failure paths
- set `mqtt_connected = true` only after successful transport reconnection or known-good connected state
- write status transitions consistently during disconnect/recovery

---

### 2. Active busy-offer handling appears replay-blind

This looks like a real but lower-priority protocol weakness.

The active-answer busy-offer classification path appears to instantiate a fresh replay/dedup structure each time it classifies an incoming foreign offer during an active session.

If that reading is correct, then replayed or duplicated offers from an allowed peer can repeatedly trigger `busy` responses during an active session.

That is not as severe as the old idle replay-cache bug, but it is still not ideal.

#### Why this matters

For allowed peers:

- a duplicated or replayed offer during an active session should not be repeatedly treated as a new busy-worthy offer
- repeated `busy` responses create unnecessary noise and weaken protocol hygiene

#### Recommended fix

Introduce a small persistent dedupe cache for the active busy-offer path keyed by something like:

- sender KID
- message ID
- maybe session ID if applicable

Then suppress repeated `busy` responses for duplicate/replayed offers within the freshness window.

---

### 3. Status-write behavior is recoverable, but status semantics are still too optimistic

The code appears to correctly treat status-file write failures as recoverable. That is good.

But the status content itself is still not accurate enough if `mqtt_connected` is effectively hardcoded/optimistic.

So the mechanics are improving, but the status model still needs work.

---

### 4. Some ordinary runtime failures still appear more fatal than they should be

This is more of an operational robustness concern than a proven logic bug, but it is worth fixing.

The code now seems better at recovering from ordinary **session-level** failures. But there are still likely runtime failures outside active sessions that can bubble out and terminate the daemon when a more robust design would:

- log the problem
- back off briefly if appropriate
- return to idle / waiting

Examples to review carefully:

- transient signaling transport poll errors
- transient publish failures
- accept-loop turbulence
- local status update failures in non-session paths

This needs a clearer fatal-vs-recoverable policy and tests to lock that behavior in.

---

## What looks improved since the last review

Compared to the prior tree, this revision appears better in the following ways:

- top-level offer busy handling is now real, not only helper-level
- dead config cleanup is materially better
- active-answer busy policy is more consistent
- session failures no longer immediately kill the daemon
- tests better reflect intended behavior
- active-client lifetime tracking looks more correct

That is real progress.

---

## Summary of current strengths

- strong crate structure
- coherent security/signaling model
- tighter and more honest config surface
- better daemon/session recovery behavior
- real top-level busy handling on the offer side
- stronger tests than earlier revisions

---

## Summary of current weaknesses

- inaccurate or overly optimistic status reporting
- replay/dedup weakness in active busy-offer handling
- remaining uncertainty around recoverable vs fatal runtime turbulence
- still not enough higher-level daemon lifecycle/integration testing

---

## Highest-priority remaining issues

1. **Fix `mqtt_connected` status reporting so it reflects reality**
2. **Harden active busy-offer replay/dedup behavior**
3. **Clarify and enforce fatal vs recoverable runtime error policy**
4. **Add higher-level lifecycle/integration tests around daemon robustness and status transitions**

---

## Recommended next steps

The next hardening pass should focus on:

- tracking actual MQTT connectivity state in daemon runtime state
- using that state in `DaemonStatus`
- adding dedupe/replay suppression for repeated active busy offers
- clearly classifying runtime failures into fatal vs recoverable buckets
- adding integration-style tests for:
  - transport error but daemon stays alive
  - status file reflects disconnect/recovery correctly
  - repeated duplicate busy offers do not produce repeated `busy` replies
  - session failure returns daemon to steady state

---

## Bottom line

This is a better revision than the last one.

The code now looks much closer to the intended product and several important earlier issues were truly fixed.

The biggest remaining problems are no longer architectural. They are mostly:

- observability accuracy
- a replay/dedup edge in active busy handling
- further daemon hardening around runtime turbulence

That is good news: the project is moving in the right direction.
