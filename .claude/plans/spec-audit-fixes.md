# P0 Audit Fixes Plan

## Issues Found

Four bugs/deficiencies in the P0 implementation that conflict with user's answered responses:

---

### Issue 1: `processCommands()` uses `runCatching` (P0-003)

**File:** `TunnelLifecycleCoordinator.kt`, line 59
**Current:** `runCatching { handleCommand(command) }.onFailure { ... }`
**Problem:** Response #17 explicitly says: "Convert `processCommands()` to explicit `try`/`catch`. `runCatching` is not acceptable in this critical lifecycle path."
**Fix:** Convert to:
```kotlin
try {
    handleCommand(command)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    lifecycleOps.onError(
        error.message ?: "Lifecycle command failed",
        "lifecycle_command_failed",
    )
}
```

### Issue 2: Status poll silently swallows failures (P0-005)

**File:** `TunnelForegroundService.kt`, line 365
**Current:** `runCatching { repository.refreshStatus() }.onFailure { ... // Silently swallow }`
**Problem:** Response #20 says: "Publish status poll failures visibly with code `status_poll_failed`. Silent swallowing is not acceptable for this path."
**Fix:** Replace `runCatching` with explicit `try`/`catch` that publishes via `reporter.publishError()`:
```kotlin
try {
    repository.refreshStatus()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    reporter.publishError(
        code = "status_poll_failed",
        message = SensitiveDataRedactor.redactText(error.message ?: "Status poll failed"),
    )
}
```

### Issue 3: `onDestroy()` doesn't update `nativeStopVerified` (P0-008 / Response #23)

**File:** `TunnelForegroundService.kt`, lines 265-273
**Current:** Only publishes error on failure, doesn't update flags on success or failure
**Problem:** Response #23 says: Must update `nativeStopVerified` and `nativeRuntimeUncertain` after fallback stop with explicit success/failure handling.
**Fix:** Change `.onFailure { ... }` to `.fold()`:
```kotlin
repository.stop()
    .onSuccess {
        nativeStopVerified.set(true)
        nativeRuntimeUncertain.set(false)
    }
    .onFailure { error ->
        nativeStopVerified.set(false)
        nativeRuntimeUncertain.set(true)
        reporter.publishError(
            code = "destroy_fallback_stop_failed",
            message = SensitiveDataRedactor.redactText(error.message ?: "Destroy fallback stop failed"),
        )
    }
```

### Issue 4: Test loops network events instead of one-event invariant (P0-004 / Response #18)

**File:** `TunnelForegroundServiceStopFailureTest.kt`, lines 292-297
**Current:** `waitForCondition { if (bridge.state != ServiceState.Connected) { re-fire event } bridge.state == ServiceState.Connected }`
**Problem:** Response #18 says: "Rewrite it to the one-event invariant. The loop is exactly the workaround the recovery spec was trying to eliminate."
**Fix:** Rewrite to send exactly one unmetered event and assert the outcome without re-firing:
- Remove the loop that re-fires `onAvailable`
- Send one event and wait for connected state
- Assert `startOfferCalls == 2` (or equivalent)

---

## Implementation Order

1. **TunnelLifecycleCoordinator.kt** — `runCatching` → `try`/`catch` (smallest, no dependencies)
2. **TunnelForegroundService.kt** — Status poll publish (standalone)
3. **TunnelForegroundService.kt** — `onDestroy()` flag update (standalone)
4. **TunnelForegroundServiceStopFailureTest.kt** — One-event invariant test (standalone)

All four fixes are independent of each other and can be committed together.

## Files Modified

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelLifecycleCoordinator.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt`
- `android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt`
