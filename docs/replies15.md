# replies15 — answers for Claude Code

This file answers the questions and implementation issues raised in `responses15(1).md` for the `ANDROID_LIFECYCLE_STATUS_HARDENING_SPEC.md` / `ANDROID_LIFECYCLE_STATUS_HARDENING_TODO.md` pass.

## Summary decisions

Use these decisions for implementation:

1. **P1 ViewModel lifecycle:** Yes, use the cast-free AndroidX `viewModelFactory { initializer { ... } }` DSL. Do **not** use `@Suppress("UNCHECKED_CAST")`.
2. **P2 dispatchers:** Centralize dispatchers in `AppDependencies` using a small dispatcher holder, then inject them from there.
3. **P3 status truthfulness:** Do a truthful relabel/mapping pass using the native signals that exist now. Do **not** add a large new daemon→controller data-channel/session status system in this patch unless it is already trivial.
4. **P4 stale metadata:** Clear stale stop metadata in Rust and keep Kotlin uptime/status display gated by active/running states.
5. **P5 notifications:** Add the SDK guard and validate with `lintDebug`. No suppressions.
6. **P6 settings:** Remove `startTunnelWhenAppOpens` from the UI/model for now. Do not implement autostart in this pass.
7. **P7 forwards state:** Yes, add a repository-owned `StateFlow<List<ForwardConfig>>` as the single source of truth.
8. **P8 Rust build skip:** Add a `-PskipRustBuild=true` path for Android lint/unit-test workflows, but keep native verification enabled by default for assemble/package/release.
9. **P9 icon:** Replace the default Android launcher icon with a real app icon or generated adaptive placeholder.

The implementation should remain suppression-clean. The repo’s no-suppression policy wins over any example code in the spec.

---

## Issue 1 — P1 factory example and `@Suppress("UNCHECKED_CAST")`

Agreed: **do not use the textbook `ViewModelProvider.Factory.create(modelClass)` implementation if it requires `@Suppress("UNCHECKED_CAST")`.**

Use the AndroidX lifecycle factory DSL instead:

```kotlin
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

fun appViewModelFactory(deps: AppDependencies) = viewModelFactory {
    initializer { HomeViewModel(deps) }
    initializer { ForwardsViewModel(deps) }
    initializer { LogsViewModel(deps) }
    initializer { SettingsViewModel(deps) }
    initializer { SetupViewModel(deps) }
    initializer { ImportExportViewModel(deps) }
    initializer { NetworkPolicyViewModel(deps) }
}
```

Then in Compose, create ViewModels through the real lifecycle owner:

```kotlin
val factory = remember(deps) { appViewModelFactory(deps) }

val homeViewModel: HomeViewModel = viewModel(factory = factory)
val forwardsViewModel: ForwardsViewModel = viewModel(factory = factory)
val logsViewModel: LogsViewModel = viewModel(factory = factory)
val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
val setupViewModel: SetupViewModel = viewModel(factory = factory)
val importExportViewModel: ImportExportViewModel = viewModel(factory = factory)
val networkPolicyViewModel: NetworkPolicyViewModel = viewModel(factory = factory)
```

Exact names can be adapted to the current source layout. The important rule is:

- `ViewModel` subclasses must be created by Android’s `ViewModelProvider` machinery.
- Do not manually construct them inside `remember { ... }`.
- Do not add `@Suppress`.
- Do not add detekt/ktlint/lint exclusions.

If the DSL requires dependencies not currently imported, add the appropriate AndroidX lifecycle dependency rather than falling back to unchecked casts.

---

## Issue 2 — dispatcher injection strategy

Centralize dispatchers in `AppDependencies`.

The recent per-ViewModel dispatcher injection is a good start, but the hardening pass should consolidate it so all ViewModels and controllers use the same test-overridable dispatcher source.

Recommended shape:

```kotlin
data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)
```

Then add it to `AppDependencies`:

```kotlin
data class AppDependencies(
    ...
    val dispatchers: AppDispatchers = AppDispatchers(),
)
```

ViewModels should use:

```kotlin
viewModelScope.launch(deps.dispatchers.io) {
    ...
}
```

or, if a class already accepts dispatchers explicitly for tests, keep that constructor parameter but default it from the dependency object at the creation site.

Preferred direction:

- Production wiring: dispatchers come from `AppDependencies`.
- Tests: override `AppDependencies(dispatchers = testDispatchers)`.
- Avoid raw `Dispatchers.IO` in ViewModel business methods.
- Avoid creating one-off dispatcher params inconsistently across different ViewModels.

This will pair cleanly with P1 because the lifecycle factory can pass the same `deps` object into each ViewModel.

---

## Issue 3 — P3 status truthfulness

### 3a — Scope: relabel/mapping pass now, not a large new native status channel

For this pass, do **not** add a large new daemon→controller status channel unless the current native code already has the signal and it is a small extension.

Use the signals that already exist:

- native daemon state string
- `mqtt_connected`
- `active_session_count`
- `session_capacity`
- per-forward listen state
- last error
- policy pause state on the Kotlin side

The goal is to stop making false claims in the UI.

The main rule:

> Do not show `Connected` merely because the daemon task is running.

For offer mode, a reasonable mapping is:

| Native / derived condition | Android UI state |
|---|---|
| policy blocked | `PausedMeteredBlocked` or equivalent existing paused state |
| native starting | `Connecting` / `Starting` |
| native running, no active session | `Listening` or `WaitingForLocalClient` |
| native running, `active_session_count > 0` | `Connected` |
| native stopping | `Stopping` / `Disconnecting` |
| native error | `Error` |
| native stopped | `Stopped` |
| unknown native state | `Error` or `Unknown`, but **not** `Stopped` |

If the existing enum does not have enough states, add clear user-facing labels without over-expanding the model. `Listening` is better than `Connected` when the app is ready but no peer/session is active.

### 3b — Answer mode: `Serving` vs `Connected`

For answer mode:

- **`Serving`** means the answer daemon is running and ready/available to participate in signaling or accept work.
- **`Connected`** means an actual peer/session/tunnel is active.

So answer mode should not claim `Connected` just because the daemon is running.

Suggested answer-mode mapping:

| Derived condition | UI label |
|---|---|
| daemon running, no active session | `Serving` |
| daemon running, `active_session_count > 0` | `Connected` |
| signaling unavailable but daemon still running | `Serving` plus a secondary `Signaling disconnected` indicator if the UI supports it |
| daemon error | `Error` |

If there is no explicit `Connected` state in the current Android service enum, keep `Serving` as the primary answer-mode running state and surface active sessions separately. The key is to avoid implying a peer is connected when it is not.

### 3c — make native/Kotlin state mapping total

Yes, P3.1 should pin down the native state vocabulary on both sides.

Do this explicitly:

1. List every native state string that `p2p-mobile` can emit.
2. Add a Kotlin parser/mapping that handles every known value.
3. Treat unknown values as `Error` or `UnknownRuntimeState`.
4. Never silently map an unknown native state to `Stopped`.
5. Add tests for every native state string.

This is important because future richer native states should not accidentally appear as `Stopped` in the Android UI.

---

## Issue 4 — P6.2 `startTunnelWhenAppOpens`

Remove it for now.

Do **not** implement auto-start in this hardening pass. Auto-start is real feature work because it needs to respect:

- foreground-service restrictions
- notification permission state
- valid config state
- identity presence
- network policy / metered blocking
- once-per-launch behavior
- user expectation after a crash or force stop

For this patch:

- Remove the `Start tunnel when app opens` toggle from Settings.
- Remove it from `AndroidAppPreferences` if that is not too invasive.
- Stop reading it from DataStore.
- It is fine to leave the old DataStore key orphaned. Do not add a migration just to delete it.
- Do not break existing preferences parsing if the key is still present on disk.

If removing the model field causes too much churn, it is acceptable to keep the stored key internally but hide it from the UI and ensure no code claims the feature works.

---

## Issue 5 — P7 and P1 overlap: single source of truth for forwards

Agreed: add a small repository-owned `StateFlow<List<ForwardConfig>>`.

The forward list should not live only inside `ForwardsViewModel`, because both Home and Forwards need the same current data. A repository-owned flow is the right shape.

Recommended direction:

```kotlin
class ForwardsRepository(
    private val store: ForwardsConfigStore,
    private val dispatchers: AppDispatchers,
) {
    private val _forwards = MutableStateFlow<List<ForwardConfig>>(emptyList())
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()

    suspend fun load()
    suspend fun save(forwards: List<ForwardConfig>)
    suspend fun upsert(forward: ForwardConfig)
    suspend fun delete(id: String)
}
```

Then:

- `AppDependencies` owns one `ForwardsRepository`.
- `HomeViewModel` observes `deps.forwardsRepository.forwards`.
- `ForwardsViewModel` observes and mutates the same repository.
- `ForwardsConfigStore` remains the persistence layer.
- Repository methods perform IO on the injected IO dispatcher.
- Config regeneration can be triggered after successful repository mutations.

This solves the Home staleness problem cleanly and avoids tying shared state to a particular screen ViewModel.

Also update persistence:

- `ForwardsConfigStore.saveForwards()` should use temp-file + atomic move.
- corrupt JSON should not silently erase the user’s configured forwards.
- keep the previous in-memory list if reload fails and surface an error/log message.

---

## Issue 6 — P5 notification SDK gating and lint

Proceed with the SDK guard, but validate with lint.

The permission check should be structured so it is both logically correct and lint-clean:

```kotlin
private fun canPostNotifications(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
}
```

Then all notification posting paths should call that helper before `notify()`.

Rules:

- No `@SuppressLint`.
- No detekt/ktlint/lint suppression.
- Run `./gradlew --no-daemon lintDebug`.
- If lint complains, restructure the code rather than suppressing it.

It is acceptable to keep a comment explaining why the helper gates API 33+ only, but do not use comments as a replacement for validation.

---

## Smaller notes / implementation clarifications

### P2 pseudocode names

Confirmed: the spec pseudocode names are illustrative. Use the real current APIs:

- `deps.forwardsStore`
- `configRepository.writeConfigAtomically`
- `renderOfferConfig`
- `deps.identityValidation.validateConfig`
- current ViewModel message/state fields

Do not create duplicate abstractions just to match the spec pseudocode.

### P3 / P4 cross-language validation

For P3 and P4, run the Rust-side tests too:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test -p p2p-mobile
cargo test --workspace
```

Also run Android tests that exercise the Kotlin mapping:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest
```

### P8 skip-Rust-build path

Implement `-PskipRustBuild=true` for Android-only workflows.

Expected behavior:

```bash
cd android
./gradlew --no-daemon -PskipRustBuild=true lintDebug
./gradlew --no-daemon -PskipRustBuild=true testDebugUnitTest
```

should not require cargo-ndk or prebuilt `.so` files.

But:

```bash
cd android
./gradlew --no-daemon assembleDebug
```

should still verify/package native libraries by default.

Do not make release packaging silently skip missing Rust artifacts.

### Sequencing

Use this sequence:

1. **P1 + P2 together** — lifecycle ViewModels and dispatcher centralization are coupled.
2. **P3 + P4 together** — runtime state truthfulness and stop metadata cleanup share the same native/Kotlin status path.
3. **P5** — notification permission/status cleanup.
4. **P6** — settings cleanup.
5. **P7** — forwards repository, atomic persistence, Home freshness.
6. **P8** — skip Rust build for lint/unit-test workflow.
7. **P9** — launcher icon.

P7 can also be done earlier if it makes the P1 refactor easier, but avoid mixing all nine items into one huge unreviewable patch.

---

## Acceptance criteria

The patch is acceptable when all of the following are true:

- No new `@Suppress`, `@SuppressLint`, lint baseline entries, detekt suppressions, ktlint disables, clippy allows, or similar bypasses.
- ViewModels are lifecycle-owned by Android `ViewModelProvider`, not manually remembered objects.
- Long-running disk/native/config/import/export operations are off the main thread.
- The UI does not show `Connected` unless an actual session/tunnel is active.
- Unknown native runtime state does not map to `Stopped`.
- Clean stop clears stale uptime/start metadata.
- Notification permission checks are correct on Android 8–12 and Android 13+.
- Dead `startTunnelWhenAppOpens` UI is removed or hidden.
- `resumeOnUnmetered` is not presented as two equal control surfaces.
- Forward persistence is atomic.
- Home and Forwards observe the same forward source of truth.
- Android lint/unit tests can run with `-PskipRustBuild=true`.
- Normal assemble/package still verifies native libraries by default.
- Launcher no longer uses `@android:drawable/sym_def_app_icon`.

Run the full relevant gate before reporting completion:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

cd android
./gradlew --no-daemon -PskipRustBuild=true lintDebug
./gradlew --no-daemon -PskipRustBuild=true testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

If any command cannot be run locally, report exactly which command was skipped and why.
