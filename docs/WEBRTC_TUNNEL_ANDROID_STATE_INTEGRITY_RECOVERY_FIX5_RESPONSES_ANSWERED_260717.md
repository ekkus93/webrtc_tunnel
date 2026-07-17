# FIX5 Spec + TODO Responses

## Questions for the User

### Q1: Should `pausedByPolicy.get()` be required in the NativeFailure pending-retry check?

In `handleStartupCompleted()`'s `NativeFailure` branch, once the pending retry is read
and matches the current generation, should resuming also require
`pausedByPolicy.get() == true`, or does the pending retry itself already prove policy
had paused the tunnel (making the extra check redundant)? The spec's stated default is
"require it" (safer), but notes the alternative. Any reason to relax that for this app?

A: Keep the `pausedByPolicy.get()` requirement.

Do **not** relax it for this app. The pending retry generation proves that a retry was
requested earlier, but it does not prove the service is still policy-paused at the time
the `NativeFailure` completion is processed. State can change between the network event,
startup completion, pause/stop/destroy, or another explicit user action.

Use the stricter invariant:

```text
RetryPolicyResume may be submitted only when:
1. pendingPolicyResumeGeneration == completed generation
2. pausedByPolicy == true
3. runtime is not quarantined
```

Recommended implementation:

```kotlin
private suspend fun handleNativeFailureAfterStartup(
    generation: Long,
    error: TunnelError,
) {
    val pending =
        pendingPolicyResumeGeneration
            .getAndSet(null)

    if (
        pending == generation &&
            pausedByPolicy.get()
    ) {
        submitLifecycleCommand(
            LifecycleCommand.RetryPolicyResume(
                expectedGeneration = generation,
            ),
        )
        return
    }

    reporter.publishError(
        code = error.code,
        message = error.message,
    )
}
```

Add tests for both sides:

```text
pending matches + pausedByPolicy true -> retry submitted exactly once
pending matches + pausedByPolicy false -> no retry, pending cleared
stale pending generation -> no retry
no pending retry -> publishes native failure
```

---

### Q2: Where should `NetworkPolicyManager`'s production reporter come from?

I read `AppDependencies.kt` and `DiagnosticsRepository.kt` to check the spec's proposed
options, and neither fits cleanly:

- There is no existing "AppErrorReporter" abstraction at the app level.
  `DiagnosticsRepository` only builds/exports a redacted diagnostics payload — it has no
  `publishError`-style method. The only thing with `publishError` is
  `TunnelForegroundService.StatusReporter`, an **inner class of the service** that
  doesn't exist until the service starts.
- `NetworkPolicyManager` is a **single app-wide instance** created once in
  `AppDependencies` (line 14) and shared by `SetupViewModel`, `NetworkPolicyViewModel`,
  and `TunnelForegroundService` — they all observe the same `status` `StateFlow`. The
  spec's "Option B" (construct a second `NetworkPolicyManager` inside
  `TunnelForegroundService.onCreate()`) would fork that shared state into two instances,
  which breaks the current single-source-of-truth design.

So the real choice is between:

(a) Add a small app-wide diagnostic event bus/reporter to `AppDependencies` (e.g. a
`MutableSharedFlow<DiagnosticEvent>`) that `NetworkPolicyManager` reports into at
construction time, and have `TunnelForegroundService` subscribe to it and relay events
through its existing `StatusReporter.publishError`.

(b) Give `NetworkPolicyManager` a mutable/settable reporter reference (defaulting to
no-op) that `TunnelForegroundService.onCreate()` installs on the shared instance when
the service starts, so notifications only appear while the service is alive (arguably
correct anyway, since delivery failures matter most while the service is monitoring).

(c) Something else you'd prefer.

A: Choose **option (a)**: add a small app-wide diagnostic event bus/reporter to
`AppDependencies`.

Do **not** construct a second `NetworkPolicyManager` inside
`TunnelForegroundService.onCreate()`. Keeping `NetworkPolicyManager` as a single
app-wide instance is the right architecture because `SetupViewModel`,
`NetworkPolicyViewModel`, and `TunnelForegroundService` all observe the same policy
state.

Also avoid option (b) unless you absolutely cannot finish option (a). A mutable/settable
reporter is workable, but it is easier to get wrong:

```text
service not started -> reporter no-op
service destroyed -> reporter stale
race while installing/uninstalling reporter
tests pass with mutable reporter but production still misses events
```

Use an app-wide event bus instead.

Recommended minimal model:

```kotlin
data class DiagnosticEvent(
    val code: String,
    val message: String,
)
```

```kotlin
interface DiagnosticEventReporter {
    fun reportDiagnosticEvent(
        event: DiagnosticEvent,
    )
}
```

Implementation backed by `MutableSharedFlow`:

```kotlin
class AppDiagnosticEventBus :
    DiagnosticEventReporter {
    private val _events =
        MutableSharedFlow<DiagnosticEvent>(
            extraBufferCapacity = 64,
        )

    val events: SharedFlow<DiagnosticEvent> =
        _events.asSharedFlow()

    override fun reportDiagnosticEvent(
        event: DiagnosticEvent,
    ) {
        _events.tryEmit(event)
    }
}
```

Then adapt it to `NetworkPolicyEventReporter`:

```kotlin
class AppNetworkPolicyEventReporter(
    private val diagnostics: DiagnosticEventReporter,
) : NetworkPolicyEventReporter {
    override fun reportNetworkPolicyEventDeliveryFailed(
        message: String,
    ) {
        diagnostics.reportDiagnosticEvent(
            DiagnosticEvent(
                code = "network_policy_event_delivery_failed",
                message = message,
            ),
        )
    }
}
```

`AppDependencies` should create one bus and one shared manager:

```kotlin
val diagnosticEventBus =
    AppDiagnosticEventBus()

val networkPolicyManager =
    NetworkPolicyManager(
        context = context.applicationContext,
        reporter =
            AppNetworkPolicyEventReporter(
                diagnostics = diagnosticEventBus,
            ),
    )
```

Then `TunnelForegroundService` should collect from the shared bus while the service is
alive and relay to its existing `StatusReporter.publishError` path:

```kotlin
serviceScope.launch {
    deps.diagnosticEventBus.events.collect { event ->
        reporter.publishError(
            code = event.code,
            message = event.message,
        )
    }
}
```

If the service is not running, it is acceptable for the diagnostic to remain in the
app-wide event stream and not create a foreground-service status event. The key
requirement is that production no longer uses only `NoopNetworkPolicyEventReporter`
for active monitoring paths.

Acceptance criteria:

```text
one shared NetworkPolicyManager instance remains
production manager is constructed with AppNetworkPolicyEventReporter
NetworkPolicyManager does not depend on TunnelForegroundService
TunnelForegroundService collects diagnostic events while alive
tests can inject/observe the diagnostic event bus
```

---

### Q3: Should `Log.w` still include a `Throwable`, or only the redacted string?

Once P0-004 is fixed so the throwable itself is never passed with an unredacted
message, is it acceptable to keep passing a (now genuinely redacted, freshly
constructed) `Throwable`/stack trace to `Log.w` for debuggability, or do you want
`Log.w` to take only the redacted `String` with no throwable at all, to eliminate any
risk of a future regression reintroducing a leak through that path?

A: Use only the redacted `String`. Do **not** pass a `Throwable` to `Log.w` for this
diagnostic.

The whole point of Fix 5 P0-004 is to prevent a future regression where a throwable
with a secret-bearing message leaks through Logcat or reporter payloads. Even a freshly
constructed sanitized throwable creates a pattern that future code may copy incorrectly.
For this diagnostic, choose safety over stack trace.

Recommended helper:

```kotlin
private fun redactedDeliveryFailureMessage(
    cause: Throwable?,
): String {
    val raw =
        cause?.message
            ?: "Network policy event could not be delivered"

    return SensitiveDataRedactor.redactText(raw)
}
```

Recommended logging/reporting:

```kotlin
val message =
    redactedDeliveryFailureMessage(cause)

Log.w(
    TAG,
    "Network policy event delivery failed: $message",
)

reporter.reportNetworkPolicyEventDeliveryFailed(
    message,
)
```

Do **not** do this:

```kotlin
Log.w(TAG, "Network policy event delivery failed", cause)
```

Also avoid:

```kotlin
Log.w(TAG, "Network policy event delivery failed", sanitizedThrowable)
```

Tests should verify the reporter message does not include representative sensitive
values such as:

```text
password=...
token=...
192.168.x.x
broker URL with credentials
```

If debugging stack traces becomes necessary later, add a separate debug-only path with
very explicit safeguards. Do not add it in Fix 5.

---

### Q4: For the P1-005 true rollback-failure test, fake or real failure?

Is it acceptable to use a fake/failing `ConfigRepository` (or a `RecordingSetupStore`-
style fake) to force the **rollback** operation itself to fail (not just the forward
stage), or do you want a real file-permission-based failure scenario? Faking is simpler
and CI-reliable; a real filesystem scenario is more "real" but risks flakiness across
CI environments.

A: Use a fake/failing repository or file operation abstraction. Do **not** use a
file-permission scenario for this test.

The purpose of P1-005 is to prove coordinator behavior:

```text
config reset succeeds
setup reset fails
rollback of config is attempted
config rollback fails
ResetResult reports rollback failure
```

That is best tested with a deterministic fake. File-permission tests are usually flaky
across local Linux, Android unit tests, macOS, CI containers, and Gradle sandboxing.

Recommended test shape:

```kotlin
@Test
fun rollbackFailureIsReportedAsRollbackFailure() =
    runTest {
        val configRepository =
            FakeConfigRepository(
                resetConfigResult = Result.success(Unit),
                restoreConfigResult =
                    Result.failure(
                        IOException("rollback write failed"),
                    ),
            )

        val setupRepository =
            FakeSetupRepository(
                resetSetupResult =
                    Result.failure(
                        IOException("setup reset failed"),
                    ),
            )

        val coordinator =
            newCoordinator(
                configRepository = configRepository,
                setupRepository = setupRepository,
                forwardsRepository = RecordingForwardsRepository(),
            )

        val result =
            coordinator.resetConfiguration()

        val failed =
            result as ResetResult.Failed

        assertTrue(
            failed.rollback.any {
                it is RollbackStageResult.Failure &&
                    it.stage == ResetStage.Config
            },
        )

        assertFalse(
            result is ResetResult.Success,
        )
    }
```

Use the current project’s actual fake names and constructor shape. The important point
is that the **rollback operation itself** fails. Do not use a final forwards reset
failure as a substitute.

---

## Previously open questions already resolved from codebase

The following do not need further user input:

- Q5: Implement P0-004 before P0-003 because the reporter interface changes from
  `Throwable?` to `String`.
- Q6: Keep existing specific catches before the new generic lifecycle catch.
- Q7: Update existing `NetworkPolicyManager` tests in Fix 5 after the reporter
  signature changes.
- Q8: Use observable behavior for pending-retry destroy tests instead of inventing a
  broad new `submitLifecycleCommandIfPossible` test hook.

---

Ready for Claude Code to continue Fix 5.
