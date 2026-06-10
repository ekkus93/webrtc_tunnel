# replies1.md — Responses to Claude Code Questions on ANDROID_UIUX2_FOLLOWUP

These are the implementation decisions and clarifications for the questions raised in `responses1(23).md` after reviewing `ANDROID_UIUX2_FOLLOWUP_SPEC.md` and `ANDROID_UIUX2_FOLLOWUP_TODO.md`.

## Summary decisions

1. **P5 direction:** Choose **(a) defer real per-forward runtime status** for this patch. Do **not** implement the coarse daemon-level version that marks all enabled forwards as `Listening` when the tunnel task is merely spawned.
2. **P6 pause behavior:** Implement polling, but polling must **not clobber policy-paused states**. Use both safeguards: stop/suspend normal polling while policy-paused, and make `refreshStatus()` preserve policy-paused states if it is called manually or races with a pause transition.
3. **Phasing:** Use **phased implementation**.
   - **Phase A now:** P1, P2, P3, P4, P7.
   - **Phase B now/next, but scoped carefully:** P6 polling with policy-pause guards.
   - **Defer P5 real per-forward status** into a future native-runtime observability task.

The main reason is correctness: a UI chip labeled `Listening` must not be based only on “the tunnel daemon task was spawned.” That would be more misleading than the current `Configured` fallback.

---

## 1. P5 direction — defer coarse per-forward runtime status

Choose **P5 option (a): defer P5** until the Rust daemon/controller can report real runtime state.

Do **not** implement option (b), the coarse fallback that stamps all enabled forwards with the daemon-level `Running` state. That would make the UI claim each forward is `Listening` immediately after Start is tapped, even though the current controller does not know whether:

- MQTT connected successfully,
- the peer connected,
- a local listener bound successfully,
- any individual forward failed,
- any session is active.

That is worse than the current `Configured` fallback because it converts an honest configuration-state label into a false runtime-state label.

For this patch, keep the current fallback behavior:

```kotlin
val stateLabel = mapForwardListenLabel(
    runtime?.listenState?.name ?: if (forward.enabled) "configured" else "disabled"
)
```

The important distinction is:

- `Configured` means “this forward exists and is enabled in config.”
- `Listening` must mean “the runtime has verified this forward is actually listening.”

Until the native runtime can report the second condition honestly, do not show it.

### Update requested to the TODO/spec interpretation

Treat P5 as **deferred** and replace it with a future task named something like:

> Add native runtime observability for per-forward status.

That future task should add a real daemon-to-controller status path rather than a Kotlin-only DTO surface.

### Future P5 design direction

When P5 is implemented later, the correct shape is closer to option (c): add a real Rust status channel from the offer/answer daemon layer back to `AndroidTunnelController`.

That future implementation should report at least:

- daemon lifecycle state,
- MQTT connection state if knowable,
- peer/session count if knowable,
- per-forward configured/binding/listening/error state,
- per-forward error messages where available,
- timestamp of last status update.

It should also stop pretending that `mqttConnected = active` and `activeSessionCount = 1` are measured values. If they are not measured, either omit them, set them to null/unknown, or expose a separate `daemonRunning` boolean.

For now, do not expand `NativeRuntimeStatusDto` with fake `forwards` data.

---

## 2. P6 pause behavior — implement polling, but make it policy-safe

Polling is still worth implementing in this patch because it fixes a real stale-state problem: if the Rust task fails after being spawned, the UI currently may not observe the transition to `Error` until a manual refresh or unrelated event happens.

However, the warning in `responses1(23).md` is correct: a naive polling loop would be dangerous because `refreshStatus()` currently maps native `Running/active` back to `Connected`. If the tunnel is paused by policy, native may still report running, and a poll could incorrectly resurrect the UI state from `PausedMeteredBlocked` back to `Connected`.

Use **both** of these safeguards:

### Safeguard A — suspend regular polling while policy-paused

The regular polling loop should run only while the effective UI/service state is an active runtime state, such as:

- `Connecting`,
- `Connected`,
- `Listening`,
- `Serving`.

It should not run while the state is:

- `PausedMeteredBlocked`,
- `NoNetwork`,
- `Stopped`,
- `Error`,
- `Stopping`,
- any other non-active/policy-blocked state.

This is the simple behavior implied by the spec and should be the primary control.

### Safeguard B — make `refreshStatus()` preserve policy-paused states defensively

Even if normal polling is suspended, `refreshStatus()` can still be called from another path or race with a policy transition. Therefore `TunnelRepository.refreshStatus()` should not blindly overwrite policy-paused states.

Add a helper similar to:

```kotlin
private fun isPolicyPausedState(state: ServiceState): Boolean =
    state == ServiceState.PausedMeteredBlocked ||
    state == ServiceState.NoNetwork
```

Then preserve that state when the previous status is policy-paused and native says the daemon is still running/active.

Example intent, not exact required code:

```kotlin
fun refreshStatus() {
    val previous = _status.value
    val native = nativeBridge.status()
    val mapped = native.toTunnelStatus(previous)

    val safe = if (isPolicyPausedState(previous.serviceState) && native.active) {
        mapped.copy(serviceState = previous.serviceState)
    } else {
        mapped
    }

    _status.value = safe
}
```

The exact implementation can differ, but the invariant must hold:

> A native status poll must never convert `PausedMeteredBlocked` or `NoNetwork` back to `Connected` while network policy says the tunnel is blocked.

If there is already a better source of current network-policy truth available, use that instead of relying only on previous `serviceState`.

### P6 implementation location

Prefer putting polling in the ViewModel/repository/service layer rather than directly in the composable. A composable `LaunchedEffect` can work, but the runtime state is app/service state, not just rendering state. The repository or foreground service is the better long-term owner.

A practical small patch is acceptable:

- `HomeViewModel` starts a coroutine that polls `TunnelRepository.refreshStatus()` every 1–2 seconds while the state is active.
- The coroutine cancels/stops when the state becomes stopped, paused, blocked, or errored.
- `onCleared()` cancels the job.

If the foreground service owns the poll instead, make sure it is cancelled on service stop/destroy.

---

## 3. Phasing — proceed with phased implementation

Use the phased plan.

## Phase A — implement immediately

Implement these first because they are Kotlin/UI-only and low risk:

- **P1:** Finish `LogsScreen` by always using `LazyColumn`, including the empty state and debug-hidden notice as `item { ... }` entries.
- **P2:** Replace remaining `Android v1` strings in `TunnelForegroundService.kt` with the same wording used by Settings: `Answer mode is not available on Android`.
- **P3:** Fix `ForwardSummaryRow` chip contrast by providing an explicit `contentColor` or by using status-specific container/content color pairs.
- **P4:** Centralize local-host behavior for browser/test actions.
- **P7:** Re-run regression checks for prior UIUX2 fixes after changes.

Phase A should not touch Rust.

## Phase B — implement polling, but not fake per-forward status

Implement:

- **P6:** status polling with the policy-pause protections described above.

Do not implement the fake/coarse form of P5 in Phase B. If touching Rust is necessary for P6, keep it limited to exposing already-real daemon lifecycle/error state. Do not manufacture per-forward runtime data.

## Deferred future phase

Defer:

- **P5 real per-forward runtime status.**

Create a separate future TODO/spec item for native runtime observability if useful. That is a larger architecture task and should not be mixed into this UI polish patch unless the explicit goal becomes to add daemon-to-controller status reporting.

---

## 4. P1 clarification — LogsScreen final shape

The final `LogsScreen` should have one fixed top controls area and one `LazyColumn` below it.

Do not branch between `EmptyStateCard` and `LazyColumn`. Always render the `LazyColumn`.

Expected structure:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 16.dp)
) {
    SectionHeader("Logs", "Recent tunnel and app events")
    FilterChipsRow(...)
    ActionsRow(...)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        if (debugHidden) {
            item { StatusCard { Text("Debug logs hidden. Enable advanced settings to show them.") } }
        }

        if (visibleLogs.isEmpty() && !debugHidden) {
            item { EmptyStateCard("No logs available.") }
        }

        items(visibleLogs, key = { it.id }) { log ->
            StatusCard { ... }
        }
    }
}
```

The exact layout can differ, but the invariant is:

> The scrollable part of `LogsScreen` is always a `LazyColumn`; empty/debug/log rows are all lazy items.

---

## 5. P2 clarification — string alignment

Use this exact wording in `TunnelForegroundService.kt`:

```text
Answer mode is not available on Android
```

That matches the Settings screen and avoids stale version references.

Replace all remaining user-visible occurrences of:

```text
Answer mode is not available in Android v1
```

Do not introduce a new alternative wording unless there is a specific reason.

---

## 6. P3 clarification — status-chip contrast

Do not rely on Material defaults when passing custom status colors into `Surface(color = ...)`.

Preferred approach: pass a status color pair.

Example direction:

```kotlin
data class StatusChipColors(
    val containerColor: Color,
    val contentColor: Color,
)
```

Then use:

```kotlin
Surface(
    color = statusColors.containerColor,
    contentColor = statusColors.contentColor,
    shape = MaterialTheme.shapes.small
) {
    Text(
        text = status,
        color = statusColors.contentColor,
        ...
    )
}
```

If keeping the existing single `statusColor` parameter, then at minimum set a content color explicitly when using dark semantic colors.

Acceptance criterion:

> `Listening`, `Configured`, `Disabled`, and `Error` chips must be readable in both light and dark themes.

If there are screenshot/UI tests, add or update them. If not, at least manually verify on a 360dp-wide emulator/preview in light and dark mode.

---

## 7. P4 clarification — local-host normalization

The goal is to eliminate inconsistent hardcoded `127.0.0.1` behavior while still opening sane browser URLs.

Use two separate concepts:

1. **Test/connect host:** should use the configured host where possible.
2. **Browser-open host:** should normalize wildcard hosts to loopback.

Suggested helpers:

```kotlin
internal fun connectHostForLocalForward(host: String): String =
    host.trim().ifBlank { "127.0.0.1" }

internal fun browserHostForLocalForward(host: String): String = when (host.trim()) {
    "", "0.0.0.0", "::", "[::]" -> "127.0.0.1"
    else -> host.trim()
}
```

Current validation only permits `127.0.0.1` or `localhost`, so the wildcard cases are future-proofing. That is fine.

Apply the helper consistently:

- `HomeScreen` Open URL button should use `browserHostForLocalForward(forward.localHost)`.
- `ForwardDetailsScreen` should use the same helper for browser URLs.
- `ForwardsViewModel.testLocalPort()` should use `connectHostForLocalForward(forward.localHost)` rather than hardcoding `127.0.0.1`.

Acceptance criterion:

> The UI must not display/save one local host while testing/opening a different local host, except for intentional browser normalization of wildcard bind addresses.

---

## 8. P7 regression checklist

After Phase A and Phase B changes, re-check these prior UIUX2 fixes manually or with tests:

- No raw `UnmeteredWifi`, `MeteredWifi`, or `NoNetwork` labels appear in UI copy.
- Review screen displays `${localHost}:${localPort}`, not hardcoded `127.0.0.1`.
- Home uptime still ticks once per second while active.
- Home forward rows still navigate to Forward Details.
- Settings does not duplicate the network-policy switches.
- Identity import/export appears in one canonical location.
- Copy status JSON and copy redacted config remain behind Advanced.
- Public identity display is truncated, but copy/share uses the full value.
- Forward Details feedback appears near the triggering controls.
- `Remote forward ID` label remains human-readable.
- Setup step label remains `Broker`, not `MQTT Broker`.

---

## 9. Validation commands

Run these after each phase:

```bash
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

If Rust/native code is touched for P6, also run the relevant Rust checks/tests for the mobile crate/workspace, for example:

```bash
cargo fmt --check
cargo test
```

Use the exact project-specific Rust commands if the workspace requires narrower package flags.

---

## Final instruction to Claude Code

Proceed with **Phase A** immediately and implement **P6** only with the policy-pause safeguards. Do **not** implement coarse/fake per-forward runtime status. Defer P5 until the native runtime has real per-forward observability.
