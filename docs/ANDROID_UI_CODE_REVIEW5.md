# ANDROID_UI_CODE_REVIEW5.md

# Android WebRTC Tunnel UI Code Review 5 — Final Validation Cleanup Review

## 1. Review scope

This review covers the latest Android UI state after `ANDROID_UI_FIX_TODO4.md`.

The Android UI code is now close enough that this review is intentionally tiny. It should not trigger another broad UI polish cycle.

The only remaining code cleanup identified in the latest review is:

```text
Settings public identity refresh is triggered twice.
```

The remaining non-code items are validation/documentation tasks:

```text
large-font UI walkthrough
Android↔desktop browser E2E
validation doc honesty
```

## 2. Current high-level status

The Android UI cleanup work is mostly complete.

The latest pass fixed the two actual code issues from the previous review:

1. **Setup Wizard Add/Edit Forward mode**
   - Add path uses `ForwardEditorMode.Add`.
   - Edit path uses `ForwardEditorMode.Edit`.
   - Dialog labels now correctly show `Add Forward` / `Add` and `Edit Forward` / `Save`.

2. **Settings public identity loading**
   - `SettingsScreen` no longer directly reads the public identity file during composition.
   - `SettingsViewModel` exposes public identity via state.
   - Copy/share actions use state and handle missing identity gracefully.

The remaining items are small.

## 3. Remaining issue

## P2 — Settings public identity refresh appears to run twice

### Problem

`SettingsViewModel` loads public identity during initialization, and `SettingsScreen` also triggers a refresh with a `LaunchedEffect(Unit)`.

This likely causes two public-identity reads when Settings opens.

Example pattern:

```kotlin
class SettingsViewModel(...) : ViewModel() {
    init {
        refreshPublicIdentity()
    }
}
```

and also:

```kotlin
@Composable
fun SettingsScreen(...) {
    LaunchedEffect(Unit) {
        vm.refreshPublicIdentity()
    }
}
```

### Why this matters

This is not the same as the old bug. The old bug was disk I/O directly from a composable body. That is fixed.

This remaining issue is just redundant work. It is minor, but easy to clean up.

### Required fix

Pick one source of refresh.

Recommended v1 choice:

```text
Keep ViewModel init refresh.
Remove SettingsScreen LaunchedEffect refresh.
```

Reason:

- the ViewModel owns the state,
- the state is available as soon as Settings observes it,
- the composable stays passive,
- fewer side effects in UI code.

If a manual refresh action is ever needed later, add an explicit button or lifecycle-aware refresh event.

### Acceptance

- Settings public identity is loaded once on ViewModel creation.
- `SettingsScreen` does not trigger duplicate public identity refresh on first composition.
- Copy/share still use `SettingsUiState.publicIdentity`.
- Missing/error states still work.

---

## 4. Validation items still open

## 4.1 Large-font UI walkthrough

The validation docs honestly state that large-font validation has not been run.

This is acceptable, but the UI is not fully accessibility-stress-validated until it is performed.

Minimum screens to check:

- Home with no forwards
- Home with multiple forwards
- Home paused/metered state
- Forwards list
- Forward Details
- Logs
- Settings
- Setup Wizard
- Import / Export
- Network Policy

If run, document:

```text
device/emulator
Android version
screen size/orientation
font scale
screens checked
result
known issues
```

If not run, keep:

```text
Manual large-font UI validation: NOT RUN
```

## 4.2 Android↔desktop browser E2E

The real product acceptance test is still:

```text
desktop p2p-answer
Android p2p-offer
Android browser -> http://127.0.0.1:<local_port>
remote service responds
```

If this has not run, keep it documented as:

```text
Manual Android↔desktop browser E2E: NOT RUN
```

Do not claim full product acceptance until this passes.

## 4.3 Automated validation

The repo validation docs may say automated Rust/Android validation passed. That is fine if it was actually run by Copilot or the developer environment.

This review environment did not independently verify those commands.

Required validation commands remain:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets

cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

and:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

## 5. What should not change

Do not change:

- MQTT signaling wire format
- tunnel frame format
- desktop Rust protocol semantics
- STUN/TURN policy
- VPN/TUN scope
- Android Keystore identity-at-rest behavior
- private identity export warning behavior
- metered/cellular warning behavior
- temporary metered session behavior
- log/diagnostic redaction
- offer-side `forward_id` model
- Setup Wizard Add/Edit Forward behavior
- Review Save/Start behavior
- Home configured-forwards display

## 6. Recommended final cleanup order

1. Remove the duplicate Settings public identity refresh.
2. Add or adjust a small test if practical.
3. Run Android unit tests.
4. Run full automated validation if the environment is available.
5. Run large-font walkthrough if available.
6. Run Android↔desktop browser E2E if available.
7. Update `docs/ANDROID_VALIDATION.md` honestly.

## 7. Bottom line

The Android UI code is now in good shape.

The last code cleanup is tiny:

```text
remove duplicate Settings public identity refresh
```

The remaining work is validation:

```text
large-font UI walkthrough
Android↔desktop browser E2E
```

After that, the Android UI cleanup work can be considered complete, subject to any explicitly documented validation items that remain not run.
