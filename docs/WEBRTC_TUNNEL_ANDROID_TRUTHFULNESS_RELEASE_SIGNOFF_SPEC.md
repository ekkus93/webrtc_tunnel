# WebRTC Tunnel Android Truthfulness and Final Release-Signoff Hardening Specification

## 0. Document purpose

This specification applies to:

```text
webrtc_tunnel-master_2607061257.zip
```

It is a **corrective release-signoff pass**.

It does **not** replace the previously accepted architecture or reopen already-completed work around:

- foreground-process service architecture;
- shutdown-aware Rust daemon APIs;
- offer/answer task supervision;
- final `Closed` status semantics;
- atomic status replacement;
- Debian package paths and maintainer scripts;
- systemd/launchd baseline design;
- removal of premature `sd_notify` readiness;
- signaling wire format;
- cryptographic identity format;
- authorized peer semantics;
- WebRTC architecture.

The purpose of this pass is to close the remaining correctness and proof gaps identified after review of the 2607061257 snapshot.

The central theme is:

> A failure must remain truthful from the point where it occurs through every later observer, test, UI state, retry path, and persistence boundary.

The current repository is close to release quality, but several remaining paths can still:

- overwrite a truthful Android stop failure with stale status;
- use thread-unsafe test fakes for required CI proof;
- prove the wrong cleanup branch while a test still passes;
- leave one startup-supersedence cleanup path untested;
- silently downgrade identity-aware validation when identity loading fails;
- expose `Result`-returning storage APIs that can still throw;
- diagnose every forwards-file failure as corruption;
- use unsynchronized policy state across coroutine threads;
- lose historical cleanup-failure information when a later teardown retry succeeds.

This specification fixes those problems without broadening the architecture.

---

# 1. Release-signoff principles

## 1.1 Runtime truth must survive concurrent observers

A lifecycle operation is not complete merely because one method returned the right `Result`.

Example:

```text
repository.stop() fails
        │
        ▼
Error state published
        │
        ▼
old status poll finishes later
        │
        ▼
stale active state overwrites Error
```

This is still a false-success system.

The required invariant is:

```text
once a lifecycle transition has invalidated an older status read,
that older read must not be able to commit state afterward.
```

This may be achieved by:

- cancelling and joining the old poll before the lifecycle operation;
- generation-stamping status reads and rejecting stale completions;
- another equally explicit mechanism.

Do not solve this with sleeps.

---

## 1.2 Required tests must prove the exact branch

A test is not sufficient merely because the final state looks right.

Bad proof:

```text
arm "fail next stop"
trigger several possible stop paths
assert Error eventually appeared
```

That test may fail the wrong stop call.

Required proof:

```text
force exact branch
observe exact branch synchronization point
inject failure for that branch
assert branch-specific outcome
```

Tests for concurrency or cancellation must identify:

- which task;
- which stop call;
- which generation;
- which lifecycle branch;
- which event boundary.

---

## 1.3 Test infrastructure must itself be concurrency-safe

Required tests now use real `Dispatchers.IO`.

Therefore test fakes are part of the concurrent system under test.

Forbidden:

```kotlin
var stopCalls = 0
var failNextStop = false
```

when read/written across test and IO threads without synchronization.

Use:

- `AtomicInteger`;
- `AtomicBoolean`;
- channels;
- latches;
- mutexes;
- thread-safe state holders.

A required test may not depend on accidental JVM visibility.

---

## 1.4 No validation downgrade on dependency failure

This is a critical policy.

The system must distinguish:

```text
identity does not exist
```

from:

```text
identity exists but cannot be read/decrypted
```

Only the first may legitimately choose a non-identity validation path.

The second must be a visible failure.

Forbidden:

```kotlin
runCatching { readIdentity() }.getOrNull()
```

followed by:

```text
null => use weaker validation
```

That converts a security or storage failure into reduced validation.

---

## 1.5 Result-returning APIs must contain failure

If an API returns:

```kotlin
Result<T>
```

then all expected I/O, parsing, seeding, and persistence failures in that operation must be represented inside that `Result`.

Forbidden:

```kotlin
fun loadSomethingResult(): Result<T> {
    if (missing) {
        saveDefaults() // can throw outside Result
        return Result.success(defaults)
    }
    return runCatching { ... }
}
```

A `Result` API that can unexpectedly throw breaks caller reasoning.

---

# 2. Scope

## 2.1 P0 release blockers

P0 covers:

1. stop-status polling race;
2. thread-safe required Android test fakes;
3. branch-specific startup cleanup failure injection;
4. deterministic supersedence-cleanup test;
5. integration proof of the central token-aware status gate;
6. real CI observation of required gates.

P0 is required before release signoff.

---

## 2.2 P1 hardening

P1 covers:

1. identity validation downgrade removal;
2. `ForwardsConfigStore.loadForwardsResult()` Result-contract repair;
3. forwards read/parse/write error taxonomy;
4. synchronized `pausedByPolicy`;
5. preservation of prior cleanup failure across later retry;
6. stale atomic status temp collision handling;
7. final targeted silent-failure audit.

---

## 2.3 Explicitly out of scope

Do not:

- change signaling message schema;
- change cryptography;
- change identity file format;
- change remote peer authorization semantics;
- add daemon mode;
- add PID files;
- reintroduce `sd_notify`;
- add systemd to Docker;
- add hidden global state;
- add hidden timeouts;
- refactor all Android state into a new architecture;
- rewrite the entire storage layer;
- extract generic supervision frameworks.

---

# 3. P0 architecture

## 3.1 Stop status polling must quiesce before stop truth is published

### Problem

Current behavior is vulnerable to:

```text
poll starts refreshStatus()
        │
        ▼
pause/stop begins
        │
        ▼
poll Job.cancel()
        │
        ▼
poll still running in IO
        │
        ▼
stop fails → Error
        │
        ▼
stale poll returns → active status overwrites Error
```

### Preferred solution

Make poll shutdown suspend and join:

```kotlin
private suspend fun stopStatusPollingAndJoin() {
    val job = statusPollJob
    statusPollJob = null
    job?.cancelAndJoin()
}
```

Lifecycle operations must call this before invoking native stop:

```kotlin
lifecycleMutex.withLock {
    stopStatusPollingAndJoin()

    val stopResult = withContext(ioDispatcher) {
        repository.stop()
    }

    // Handle result.
}
```

This is preferred because it is easy to reason about.

### Acceptable alternative

Generation-stamp each poll:

```kotlin
val generation = statusGeneration.get()

val result = repository.refreshStatusSnapshot()

if (generation != statusGeneration.get()) {
    return@launch // stale
}

repository.commitStatus(result)
```

If using this approach:

- generation invalidation must happen before stop;
- stale completion must never mutate repository state;
- tests must deterministically force stale completion after stop.

### Do not

Do not:

- sleep after cancellation;
- assume `cancel()` means the underlying IO already stopped;
- ignore a polling job because its parent scope is cancelled;
- add a hidden timeout.

---

## 3.2 Required status-poll race test

The test must force:

```text
status refresh entered
        │
        ▼
refresh blocked
        │
        ▼
stop requested
        │
        ▼
stop returns failure
        │
        ▼
release old refresh
        │
        ▼
assert Error remains final
```

Use an explicit barrier.

Recommended test fake capability:

```kotlin
class StatusRefreshBarrier {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}
```

Fake bridge:

```kotlin
override fun statusJson(): Result<String> {
    statusRefreshBarrier?.let { barrier ->
        barrier.entered.complete(Unit)
        runBlocking {
            barrier.release.await()
        }
    }

    return Result.success(staleActiveStatusJson)
}
```

Adapt to current bridge shape.

The test must not use `Thread.sleep`.

---

## 3.3 Required test fakes must be thread-safe

Any fake field shared across:

- Robolectric test thread;
- service coroutine;
- IO dispatcher;
- callback thread;

must use explicit synchronization.

Recommended primitives:

```kotlin
private val stopCalls = AtomicInteger(0)
private val failStopCallNumber = AtomicInteger(-1)
private val statusState = AtomicReference<ServiceState>(ServiceState.Stopped)
```

For exact event synchronization:

```kotlin
val startupCleanupEntered = CompletableDeferred<Unit>()
val releaseStartupCleanup = CompletableDeferred<Unit>()
```

Do not poll plain mutable variables.

---

## 3.4 Branch-specific stop failure injection

Generic:

```text
fail next stop
```

is too weak when multiple stop calls may occur.

Use call-number or branch-keyed failure.

### Call-number model

```kotlin
class StopFailurePlan {
    private val nextCall = AtomicInteger(0)
    private val failCall = AtomicInteger(-1)

    fun failCall(number: Int) {
        failCall.set(number)
    }

    fun onStop(): Result<Unit> {
        val call = nextCall.incrementAndGet()
        return if (failCall.compareAndSet(call, -1)) {
            Result.failure(TestStopFailure("stop call $call failed"))
        } else {
            Result.success(Unit)
        }
    }
}
```

### Better branch-keyed model

If practical, add a test-only branch event:

```text
StartupCancellationCleanupEntered
StartupSupersedenceCleanupEntered
ServicePauseStopEntered
ServiceStopWorkEntered
PolicyPauseStopEntered
```

Then test can wait for exact branch before releasing failure result.

Production behavior must not depend on test hooks.

---

## 3.5 Startup supersedence must have focused proof

The untested path is conceptually:

```text
first startup starts
        │
        ▼
first native start succeeds
        │
        ▼
second startup increments generation
        │
        ▼
first startup resumes and sees stale generation
        │
        ▼
first startup calls cleanup stop
```

Test must deterministically force that ordering.

Recommended test seam:

```kotlin
data class StartupTestHooks(
    val afterNativeStartBeforeGenerationCheck: CompletableDeferred<Unit>?,
    val releaseAfterNativeStart: CompletableDeferred<Unit>?,
)
```

Production flow under test build:

```kotlin
val result = repository.startOffer(...)

testHooks.afterNativeStartBeforeGenerationCheck?.complete(Unit)
testHooks.releaseAfterNativeStart?.await()

if (generation != lifecycleGeneration) {
    withContext(NonCancellable + ioDispatcher) {
        repository.stop()
    }.onFailure { ... }
    return
}
```

Test:

```text
start first startup
wait until first native start succeeded and paused before generation check
start second startup / increment generation
arm failure for exact supersedence cleanup
release first startup
assert supersedence cleanup stop was called
assert exact stop_failed error
assert no clean success state from first startup
```

No sleeps.

---

## 3.6 Integration proof for central token-aware status gate

The current integration test has two defenses:

```text
central token-aware status gate
+
local loop-top shutdown check
```

That means removing only the central gate may not fail the test.

Add a scenario where the central gate is the only defense.

Preferred point:

```text
ordinary session recovery
```

Test seam:

```text
session returns ordinary result
        │
        ▼
pause immediately before recover_daemon_after_session writes
        │
        ▼
request shutdown
        │
        ▼
release recovery
```

The recovery helper attempts to write ordinary status.

Expected:

```text
central status gate suppresses write
```

Regression-strength rule:

```text
remove central token check only
    → test fails

restore central token check
    → test passes
```

This is the required proof.

---

# 4. P1 architecture

## 4.1 Identity-aware validation must fail closed

### Current dangerous pattern

```kotlin
val identity =
    runCatching {
        identityRepository.readPrivateIdentityPlaintext()
    }.getOrNull()

if (identity != null) {
    validateWithIdentity(...)
} else {
    validateWithoutIdentity(...)
}
```

### Required model

First establish identity existence separately.

Conceptual:

```kotlin
when {
    !identityRepository.hasEncryptedIdentity() -> {
        validateWithoutIdentity(...)
    }

    else -> {
        val identity = identityRepository
            .readPrivateIdentityPlaintext()
            .getOrElse { error ->
                return Result.failure(
                    IdentityUnavailableException(
                        "Identity exists but could not be read",
                        error,
                    ),
                )
            }

        try {
            validateWithIdentity(identity)
        } finally {
            identity.fill(0)
        }
    }
}
```

Use current API shapes.

Required invariant:

```text
no identity exists
    → basic validation permitted

identity exists and loads
    → identity-aware validation

identity exists but load/decrypt fails
    → visible hard failure
```

Never silently downgrade.

---

## 4.2 Forwards Result contract

Target:

```kotlin
fun loadForwardsResult(): Result<List<ForwardConfig>> =
    runCatching {
        if (!forwardsFile.exists()) {
            val defaults = defaultForwards()
            saveForwards(defaults)
            defaults
        } else {
            loadExistingForwardsOrThrow()
        }
    }
```

Everything in the logical operation must be inside the `Result`.

---

## 4.3 Separate read, parse, and write failures

Do not call every failure “corrupt.”

Suggested exceptions:

```kotlin
sealed class ForwardsConfigException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class ForwardsReadException(path: String, cause: Throwable) :
    ForwardsConfigException("Unable to read forwards configuration at $path", cause)

class ForwardsParseException(path: String, cause: Throwable) :
    ForwardsConfigException("Unable to parse forwards configuration at $path", cause)

class ForwardsWriteException(path: String, cause: Throwable) :
    ForwardsConfigException("Unable to write forwards configuration at $path", cause)
```

Avoid leaking sensitive full paths if diagnostics export them.

At minimum, preserve the difference between:

- unreadable;
- malformed;
- unwritable.

---

## 4.4 Synchronize `pausedByPolicy`

Use one of:

### AtomicBoolean

```kotlin
private val pausedByPolicy = AtomicBoolean(false)
```

### StateFlow

```kotlin
private val _pausedByPolicy = MutableStateFlow(false)
```

### Mutex-only access

Only if every read and write is guaranteed to occur under the same mutex.

Do not rely on incidental coroutine scheduling.

Because the flag influences retry and auto-resume, its visibility must be explicit.

---

## 4.5 Preserve cleanup failure history across retry

A later successful teardown retry must not erase historical evidence that an earlier cleanup failed.

Recommended minimum:

```text
first stop fails
    → Error state + diagnostic log

onDestroy retries
    → success may produce final Stopped
    → prior error remains in diagnostic history
```

Possible model:

```kotlin
private var lastCleanupFailure: TunnelError? = null
```

On failure:

```kotlin
lastCleanupFailure = error
```

On later success:

- do not silently clear it unless an explicit policy says so;
- log that retry succeeded after prior failure.

Avoid overcomplicating the persistent user-facing state.

---

## 4.6 Atomic status stale temp handling

Current temp names are:

```text
<pid>-<sequence>
```

This is good for concurrent same-process writes.

Add collision retry for stale crash debris:

```rust
loop {
    let sequence = STATUS_TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let temp_path = ...

    match OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp_path)
        .await
    {
        Ok(file) => break (file, temp_path),
        Err(error) if error.kind() == ErrorKind::AlreadyExists => continue,
        Err(error) => return Err(error),
    }
}
```

Boundless retry is acceptable only because the sequence monotonically advances and collision requires stale files. If desired, use a generous explicit cap and return a real error when exhausted.

Do not delete an arbitrary pre-existing temp file just to continue.

---

# 5. Error and fallback policy

## 5.1 Forbidden

Do not introduce:

```text
getOrNull() -> weaker validation
getOrElse { emptyList() }
catch -> continue as normal
cancel job -> assume work stopped
next generic failure -> assume target branch
plain mutable fake state across IO threads
Result-returning API that throws expected operation failures
all I/O errors -> "corrupt"
```

---

## 5.2 Allowed

These remain acceptable:

- optional watch receiver closure;
- abandoned oneshot reply during teardown when primary failure is logged;
- test cleanup file removal after assertions;
- `ShutdownToken` repeated request;
- empty session list representing no active session;
- explicit best-effort notification where failure is logged.

---

# 6. Required test matrix

## Rust

Must prove:

1. shared-token central gate suppresses ordinary status;
2. integration recovery-path test fails when central gate alone is removed;
3. audit log records every write;
4. existing offer/answer lifecycle tests still pass.

## Android JVM/Robolectric

Must prove:

1. stale status poll cannot overwrite stop failure;
2. thread-safe fake sees deterministic call counts;
3. startup cancellation cleanup fails exact targeted stop call;
4. startup supersedence cleanup is exercised;
5. `pausedByPolicy` state remains synchronized and truthful.

## Android storage

Must prove:

1. identity absent uses allowed basic validation;
2. identity present and readable uses identity-aware validation;
3. identity present but unreadable fails;
4. missing forwards file + seeding write failure returns `Result.failure`;
5. forwards read failure is not labeled parse corruption;
6. malformed JSON is labeled parse failure.

---

# 7. CI and release-signoff policy

The new focused Android unit-test step must run on a real workflow.

Required:

```text
push branch
        │
        ▼
observe GitHub Actions
        │
        ├── focused Android truthfulness step ran
        ├── full Android unit tests ran
        ├── Rust gates ran
        ├── required signal lifecycle job ran
        └── package/service jobs remained green
```

Do not mark real CI as passed based on local execution.

Use:

```text
PASS
FAIL
NOT RUN: exact reason
```

---

# 8. Completion definition

Release signoff is reached only when:

```text
old poll cannot overwrite new lifecycle truth
required test fakes are thread-safe
startup cancellation test proves exact branch
startup supersedence cleanup has focused proof
central status gate has an integration test where it is the only defense
real CI ran the focused Android step
identity load failure cannot downgrade validation
forwards Result API cannot throw expected operation failures
forwards read/parse/write errors remain distinguishable
pausedByPolicy visibility is synchronized
```

Anything less remains a known signoff gap.
