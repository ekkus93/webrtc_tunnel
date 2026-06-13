# Android Lifecycle, Runtime Status, and Release Hardening Spec

## 1. Purpose

This specification defines the next Android-focused hardening pass for the WebRTC tunnel app. It is based on the latest Android code review of the corrected Android app archive.

The Android app is now a real Compose/ForegroundService/JNI application with a substantially better Rust mobile runtime bridge. This pass should stop treating the app as a UI prototype and tighten the parts that matter for an Android tunnel/security app:

- lifecycle correctness
- main-thread safety
- truthful runtime status labels
- deterministic native/service cleanup
- notification behavior across Android versions
- settings consistency
- atomic persistence
- Home screen data freshness
- practical local build/test workflow
- release polish

The work should preserve the existing architecture direction: Kotlin/Compose is the Android control plane; Rust remains the tunnel/protocol runtime; the foreground service owns long-running tunnel execution; identities remain protected through Android Keystore-backed storage.

## 2. Non-goals

Do not redesign the app UI from scratch.

Do not change the desktop tunnel protocol or config format unless required for Android correctness.

Do not introduce Hilt, Koin, or another dependency injection framework unless the existing code cannot be reasonably fixed with standard AndroidX lifecycle APIs. A small custom `ViewModelProvider.Factory` is preferred.

Do not weaken identity security, diagnostic redaction, or the default no-cellular/no-metered-network policy.

Do not fabricate runtime status. If the runtime cannot honestly distinguish a state, expose a conservative label rather than pretending the tunnel is connected.

## 3. Current baseline

The corrected app archive contains:

- Android Gradle project under `android/`
- Kotlin/Jetpack Compose UI under `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/`
- split viewmodels under `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/`
- data/repository/security/network/notification layers
- `TunnelForegroundService`
- `RustTunnelBridge`
- `crates/p2p-mobile` Rust JNI/mobile runtime layer
- tests for config, diagnostics, identity, status mapping, notification behavior, UI helpers, viewmodels, and Rust mobile status schema

The app is much improved, but the following issues remain the implementation target for this pass.

## 4. Required changes

---

## P1 — Use real Android lifecycle-scoped ViewModels

### Problem

`ui/App.kt` currently constructs an `AppViewModelFactory`, then creates an `AppScreenModels` object with manually constructed `ViewModel` subclasses. These objects are remembered by Compose, but they are not owned by an Android `ViewModelStore`.

This defeats key `ViewModel` semantics:

- `viewModelScope` lifetime is not reliably tied to the Activity/NavBackStack lifecycle.
- state retention across configuration changes is not idiomatic.
- coroutine cancellation can be surprising.
- lifecycle bugs may be hidden by tests that instantiate viewmodels directly.

### Required behavior

All Android `ViewModel` subclasses must be created through AndroidX lifecycle APIs:

- `ViewModelProvider.Factory`
- `androidx.lifecycle.viewmodel.compose.viewModel()`
- optionally `NavBackStackEntry`-scoped viewmodels where screen-specific state should be per route

The app may keep the existing `AppDependencies` object as the dependency root.

### Implementation contract

Replace the current manual factory pattern with a real factory, for example:

```kotlin
class AppViewModelFactory(
    private val deps: AppDependencies,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            HomeViewModel::class.java -> HomeViewModel(deps)
            SetupViewModel::class.java -> SetupViewModel(deps)
            ForwardsViewModel::class.java -> ForwardsViewModel(deps)
            LogsViewModel::class.java -> LogsViewModel(deps)
            SettingsViewModel::class.java -> SettingsViewModel(deps)
            NetworkPolicyViewModel::class.java -> NetworkPolicyViewModel(deps)
            ImportExportViewModel::class.java -> ImportExportViewModel(deps)
            else -> error("Unknown ViewModel: ${modelClass.name}")
        } as T
    }
}
```

Then create viewmodels inside composable destinations with AndroidX Compose lifecycle integration:

```kotlin
val homeVm: HomeViewModel = viewModel(factory = factory)
```

For shared app-wide viewmodels such as `ForwardsViewModel`, choose the desired scope intentionally:

- activity-level shared scope if Home and Forwards must observe the same configured-forward state
- nav graph/back-stack-entry scope if independent screen state is required

The recommended approach for this app is:

- `ForwardsViewModel`: activity/nav-graph shared, because Home and Forwards should observe the same configured-forward list.
- `HomeViewModel`: activity/nav-graph shared is acceptable.
- `LogsViewModel`: activity/nav-graph shared is acceptable so logs keep pause/filter state while navigating.
- `SetupViewModel`: can be setup-route scoped if wizard draft state should reset when leaving setup, or app-scoped if the current behavior intentionally preserves draft setup state. Pick one and document it in a code comment.
- `ImportExportViewModel`: route scoped is acceptable.

### Acceptance criteria

- `AppScreenModels` is removed or no longer directly constructs viewmodels.
- `AppViewModelFactory` implements `ViewModelProvider.Factory`.
- No production UI code calls `HomeViewModel(deps)`, `ForwardsViewModel(deps)`, etc. directly.
- `viewModelScope` cancellation is owned by Android lifecycle.
- Existing viewmodel tests still pass, with direct construction allowed only in unit tests.

---

## P2 — Move file/native/config operations off the UI thread

### Problem

Several UI-triggered viewmodel methods still perform potentially blocking work synchronously. These include config writes, forward JSON writes, encrypted identity access, ContentResolver import/export, TOML rendering, and native validation.

Examples to inspect and fix include:

- `ForwardsViewModel.saveForward()`
- `ForwardsViewModel.deleteForward()`
- `ForwardsViewModel.regenerateActiveConfig()`
- `SettingsViewModel.validateConfig()` and reset/config operations
- `ImportExportViewModel` import/export operations
- setup save/import paths if any still call disk/native work directly from the main dispatcher

### Required behavior

All disk IO, ContentResolver stream IO, encrypted identity IO, and native validation must run on an injected IO dispatcher.

UI-triggered operations must:

- launch from `viewModelScope`
- switch to `deps.ioDispatcher` or an equivalent injected dispatcher
- expose `isBusy`, `message`, and/or `error` state as appropriate
- disable destructive or duplicate-trigger buttons while the operation is running
- avoid blocking Compose recomposition or main-thread click handlers

### Implementation contract

Use a pattern like:

```kotlin
fun saveForward(forward: ForwardConfig) {
    if (_uiState.value.isSaving) return
    viewModelScope.launch {
        _uiState.update { it.copy(isSaving = true, message = null) }
        val result = withContext(deps.ioDispatcher) {
            runCatching {
                deps.forwardsConfigStore.upsertForward(forward)
                deps.configRepository.writeActiveConfig(...)
                deps.rustBridge.validateConfig(...)
            }
        }
        _uiState.update { old ->
            old.copy(
                isSaving = false,
                message = result.fold(onSuccess = { "Forward saved." }, onFailure = { null }),
                error = result.exceptionOrNull()?.message,
            )
        }
    }
}
```

Do not update Compose state from a background thread except through normal coroutine context return/update patterns.

### Acceptance criteria

- No UI-triggered production viewmodel method performs file/native/config work directly on the main dispatcher.
- Tests cover at least one save/import/export path to prove busy state and result state transition correctly.
- App remains responsive during import/export/config save.

---

## P3 — Make runtime status labels semantically truthful

### Problem

The Rust mobile runtime now reports more measured status than before, including MQTT connection, session counts, capacity, and per-forward state. However, Android still risks collapsing daemon states into overly optimistic user-facing labels.

In particular, Android must not display `Connected` merely because the daemon task is running. `Connected` should mean there is an actual active tunnel/session/data channel or equivalent measured connected state.

### Required behavior

User-facing status must distinguish at least these concepts:

- stopped
- starting
- running/listening/waiting but not connected
- MQTT/signaling connected, if known
- peer/session/tunnel connected, if known
- reconnecting/waiting, if known
- paused by network policy
- blocked by network policy
- error

If the native layer does not expose a sufficiently precise distinction, the UI must choose a conservative label such as:

- `Running`
- `Listening`
- `Waiting for connection`
- `Waiting for local client`

rather than `Connected`.

### Implementation contract

Audit the mapping chain end to end:

1. Rust daemon/runtime state
2. `AndroidRuntimeStatus` / mobile status JSON
3. Kotlin native DTO in `Models.kt` or equivalent
4. `TunnelRepository.refreshStatus()` / `toTunnelStatus()` mapping
5. UI helpers such as `mapServiceStateLabel()` and `mapForwardListenLabel()`
6. notification title/body mapping

Expose raw daemon state if possible. If raw daemon state is too unstable as an API, expose a stable Android-facing enum/string with conservative semantics.

Recommended Android service states:

```text
Stopped
Starting
Listening
WaitingForLocalClient
Connecting
Connected
Serving
PausedMeteredBlocked
BlockedNoNetwork
Error
```

The exact enum names may differ, but the semantics must be clear and tested.

### Acceptance criteria

- UI does not show `Connected` when the only known fact is `daemon running`.
- Offer mode can show a non-connected running/listening/waiting state.
- Answer mode can show serving/listening without implying a peer is connected unless a peer/session is actually connected.
- Notification labels use the same truthful mapping as Home.
- Unit tests cover state mapping for stopped, starting, running-but-not-connected, connected/session-active, paused, and error.

---

## P4 — Clear stale runtime metadata on stop

### Problem

Runtime stop/cleanup may leave stale metadata such as `started_at_unix_ms`, `config_path`, or `last_error`. Android can then display stale uptime or stale error/status information after a clean stop.

There is also a suspicious no-op pattern in Rust similar to setting `last_error` to `None` only if it is already `None`. That should be cleaned up.

### Required behavior

On clean stop:

- daemon/task state becomes stopped
- active/session count becomes zero
- MQTT connected becomes false
- per-forward runtime status becomes inactive/configured/disabled/error-free as appropriate
- start timestamp is cleared
- uptime becomes unavailable/null in Kotlin
- clean stop does not display a stale error
- errors from failed stops may still be reported explicitly

On error stop:

- state becomes error
- last error is retained and surfaced
- start timestamp handling is explicit and tested

### Implementation contract

Fix `crates/p2p-mobile/src/runtime.rs` stop/status cleanup semantics.

Fix Kotlin mapping so uptime is shown only while a state is active/running/connected/serving, not merely because a timestamp was present.

### Acceptance criteria

- Starting and then stopping clears uptime in Home.
- Clean stop clears stale errors.
- Error stop preserves the relevant error.
- Rust tests cover `start -> stop -> status` cleanup.
- Kotlin tests cover native stopped status mapping with stale native fields absent/ignored.

---

## P5 — Fix notification behavior across Android versions

### Problem

Notification code has a possible pre-Android-13 permission bug risk. Runtime `POST_NOTIFICATIONS` permission checks should only apply on Android 13/API 33 and newer. On Android 8-12, notification updates should not be skipped because of a runtime permission that does not exist there.

Notification status labels also need to follow the same truthful runtime mapping as Home.

### Required behavior

- On API < 33, notification updates should not require `POST_NOTIFICATIONS` runtime permission.
- On API >= 33, notification updates should respect `POST_NOTIFICATIONS` permission.
- Foreground service notification must always be available in a way that satisfies Android service requirements.
- Notification title/body must not say `connected` unless the runtime state is actually connected.

### Implementation contract

Update `NotificationController` permission checks to gate on SDK version:

```kotlin
if (
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
) {
    return
}
```

Make sure any direct `NotificationManagerCompat.notify()` helper uses the same rule.

### Acceptance criteria

- Unit tests cover API < 33 notification behavior.
- Unit tests cover API >= 33 denied permission behavior.
- Notification labels match the Home/status mapping tests.

---

## P6 — Clean up duplicated or dead settings

### Problem

Some settings are duplicated or not wired to behavior:

- `Resume tunnel when Wi-Fi returns` appears in both Settings and Network Policy.
- `Start tunnel when app opens` appears to be stored/displayed but not actually used.
- `Validate config` behavior may not surface a meaningful result to the user.

Duplicate authoritative controls confuse users. Dead preferences are worse because they imply behavior that does not exist.

### Required behavior

- Network policy controls should have one canonical edit location.
- Settings may show read-only summary lines and navigation buttons, but should not duplicate interactive controls for the same underlying policy.
- `Start tunnel when app opens` must either be implemented or removed.
- Config validation actions must produce visible feedback.

### Implementation contract

Recommended choices:

1. Keep `Resume tunnel when Wi-Fi returns` editable only in `NetworkPolicyScreen`.
2. In `SettingsScreen`, show a read-only summary such as `Wi-Fi resume: On/Off` plus an `Open Network Policy` button.
3. For `Start tunnel when app opens`, prefer removal unless there is a clear, safe implementation. Auto-starting a tunnel on Activity creation can surprise users and interacts with permissions/network policy/foreground-service rules. If implemented, it must:
   - respect network policy
   - require a valid config and identity
   - avoid repeated starts across recompositions/activity recreation
   - show a clear setting description
4. Make `Validate config` show success/failure text near the button.

### Acceptance criteria

- No duplicated interactive network policy controls across Settings and Network Policy.
- No visible setting exists without behavior or a clear disabled/not-implemented explanation.
- Config validation success/failure is visible and testable.

---

## P7 — Make forward persistence atomic and observable

### Problem

`ForwardsConfigStore.saveForwards()` writes `forwards.json` directly. A crash, process kill, or storage error during write can corrupt the file. On load, the app can silently fall back to an empty list.

Home also refreshes configured forwards opportunistically, which can make the Home forward list stale after edits.

### Required behavior

Forward config persistence must be atomic, and configured forwards should be observable from a single source of truth.

### Implementation contract

For atomic writes:

- write JSON to a temp file in the same directory
- flush/sync if practical
- atomically move/rename to `forwards.json`
- clean up temp files on failure
- never silently discard a corrupt existing forwards file without surfacing an error/log

For observability:

- expose configured forwards through a repository/store `StateFlow`, or
- have `ForwardsViewModel` own a shared observable state used by both Home and Forwards, now properly lifecycle-scoped, or
- trigger refresh on lifecycle resume/navigation return using a clear lifecycle hook

Preferred approach: make `ForwardsConfigStore` or a small `ForwardsRepository` expose a `StateFlow<List<ForwardConfig>>` and update it after successful atomic writes.

### Acceptance criteria

- `forwards.json` writes are atomic.
- Corrupt JSON does not silently erase configured forwards without a visible/logged warning.
- Home updates after editing/deleting a forward without requiring app restart.
- Tests cover atomic save success and load behavior after invalid JSON.

---

## P8 — Decouple local Kotlin lint/unit-test workflow from mandatory Rust rebuild where practical

### Problem

The Android Gradle build is tightly coupled to Rust JNI verification/build. That is appropriate for packaged artifacts, but it can make local Kotlin-only lint/unit-test cycles brittle when Rust targets/cargo-ndk are unavailable.

### Required behavior

Developers should be able to run ordinary Kotlin/JVM unit tests and Android lint without unnecessarily rebuilding Rust, while release/debug assemble tasks still verify/include native libraries.

### Implementation contract

Add a documented Gradle property, for example:

```bash
-PskipRustBuild=true
```

or equivalent, with these semantics:

- `assembleDebug` / packaging tasks build or verify native libs by default.
- local JVM tests and lint can skip Rust rebuild when the property is set.
- CI/release tasks should not skip native verification unless explicitly configured.
- the app must fail clearly if someone tries to package without required native libraries.

### Acceptance criteria

- `./gradlew testDebugUnitTest -PskipRustBuild=true` does not require Rust/cargo-ndk.
- `./gradlew lintDebug -PskipRustBuild=true` does not require Rust/cargo-ndk unless lint truly needs packaged native libs.
- `./gradlew assembleDebug` still builds/verifies native libs by default.
- README or Android build docs mention the property.

---

## P9 — Release polish: replace default launcher icon

### Problem

The manifest appears to use Android's default system app icon. This makes the app look unfinished.

### Required behavior

Use app-owned launcher icons and round icons for supported densities. A simple generated vector/adaptive icon is acceptable for now.

### Implementation contract

- Add launcher icon resources under `android/app/src/main/res/mipmap-*` or adaptive icon resources under `res/mipmap-anydpi-v26` plus foreground/background drawables.
- Update `AndroidManifest.xml` to reference app-owned icon resources.
- Do not use `@android:drawable/sym_def_app_icon`.

### Acceptance criteria

- Manifest uses app-owned `@mipmap/...` or equivalent icon resources.
- App installs with a non-default launcher icon.

---

## 5. Validation requirements

Run as many of these locally as the environment supports:

```bash
cd android
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

If implementing Rust/mobile status changes:

```bash
cargo test -p p2p-mobile
cargo test --workspace
```

If adding the skip property:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest -PskipRustBuild=true
./gradlew --no-daemon lintDebug -PskipRustBuild=true
./gradlew --no-daemon assembleDebug
```

If physical Android testing is available:

1. Install debug APK on a physical phone.
2. Start offer mode with a valid config.
3. Confirm foreground notification appears.
4. Confirm Home status does not say `Connected` until an actual session/tunnel is connected.
5. Edit a forward and confirm Home updates after returning.
6. Stop tunnel and confirm uptime disappears and stale errors are not shown.
7. Toggle network policy to blocked/metered and confirm polling does not resurrect `Connected`.
8. Test on Android 12 or lower if available to verify notification behavior without POST_NOTIFICATIONS.

## 6. Done definition

This hardening pass is done when:

- all ViewModels are lifecycle-scoped through AndroidX APIs
- blocking operations are off the main thread
- status labels are conservative and truthful
- clean stop clears stale runtime metadata
- notification permission checks are SDK-correct
- Settings has no duplicate/dead controls
- forward persistence is atomic
- Home's forward list stays fresh after edits
- Kotlin lint/unit tests pass
- Rust/mobile tests pass for touched Rust code
- the app no longer uses the default launcher icon
