# Android UI/UX2 Follow-up Specification

## Context

This specification is for the Android version of `rust_webrtc_tunnel` after the `UIUX_IMPROVEMENTS2_TODO` pass. The UIUX2 implementation is mostly present, but the review found several remaining correctness and polish issues:

1. `LogsScreen` only partially satisfies H4 because the empty state is still rendered outside the `LazyColumn`.
2. The stale user-facing string `"Answer mode is not available in Android v1"` still exists in `TunnelForegroundService.kt`.
3. `ForwardSummaryRow` now accepts a `statusColor`, but uses foreground-like status colors as chip container colors without an explicit content color, which can produce poor contrast.
4. Forward local-host handling remains inconsistent: review display uses `forward.localHost`, but browser open and local port test still hardcode `127.0.0.1`.
5. Per-forward runtime status is not actually populated from the native runtime path. The UI reads `TunnelStatus.forwards`, but `NativeRuntimeStatusDto` and `p2p-mobile` do not currently expose forward status.
6. Runtime status refresh is still too passive. The Home screen uptime ticks locally, but other runtime status fields can go stale unless another status refresh occurs.

The goal of this patch is to finish the UIUX2 follow-up cleanly and make the Android UI honest, responsive, and consistent without introducing new security regressions.

## Goals

- Fully satisfy the remaining UIUX2 review gaps.
- Make forward status chips visually accessible and semantically meaningful.
- Ensure forward host/port presentation, testing, and browser-open behavior all use the same source of truth.
- Surface native runtime status changes to the Android UI without requiring user navigation or manual refresh.
- Add enough tests to prevent regression of the UIUX2 fixes.
- Preserve the current Android security model: no private identity leakage, no unredacted secrets in logs/diagnostics/status, and no backup/export behavior changes.

## Non-goals

- Do not implement Android answer mode in this patch.
- Do not redesign the entire Android UI.
- Do not change the desktop CLI behavior unless required by shared Rust types.
- Do not loosen network policy defaults. Cellular/metered data must remain blocked unless explicitly allowed by the user.
- Do not expose private key material in native status JSON, Kotlin status objects, logs, diagnostics, or test failure output.

## Affected files

Expected Android/Kotlin files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/components.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/model/Models.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/service/TunnelForegroundService.kt`
- Existing Android unit tests under `android/app/src/test/java/...`

Expected Rust/mobile files if forward runtime status is implemented at the native boundary:

- `crates/p2p-mobile/src/runtime.rs`
- `crates/p2p-mobile/src/lib.rs`
- Existing Rust tests for `p2p-mobile`

## Detailed requirements

### 1. Finish `LogsScreen` lazy rendering

`LogsScreen` must always use one `LazyColumn` for the scrollable log content area. The fixed top area must remain outside the list:

- Section title/header.
- Filter chips.
- Pause/resume button.
- Clear button.
- More/actions menu.
- Optional user feedback message.

The following must be `LazyColumn` items, not separate composables outside the list:

- `EmptyStateCard("No logs available.")`
- `EmptyStateCard("Debug logs are hidden. Enable Debug logs in Advanced to see them.")`
- Each log `StatusCard`

The screen should retain scaffold/window inset padding via the existing `padding: PaddingValues` parameter.

Acceptance behavior:

- With zero logs and no hidden debug logs, the screen shows a single empty state inside the scrollable list area.
- With only hidden debug logs, the screen shows the debug-hidden notice inside the list area.
- With visible logs, the screen shows log cards in a lazy list and the filter/action controls remain pinned at the top.

### 2. Remove stale Android version wording

No user-facing Android code should say `Android v1` for answer mode.

Replace all occurrences of:

```text
Answer mode is not available in Android v1
```

with:

```text
Answer mode is not available on Android
```

or:

```text
Answer mode is not available in this Android build
```

Acceptance behavior:

- Grepping the Android source for `Android v1` returns no matches.
- Triggering answer mode still fails safely and clearly.
- This patch does not enable answer mode.

### 3. Make forward status chips accessible

`ForwardSummaryRow` must not use dark/error foreground colors as chip container colors without a matching content color.

Recommended implementation:

- Replace `statusColor: Color` with either:
  - `statusContainerColor: Color` and `statusContentColor: Color`, or
  - a small value type such as `StatusChipColors(container: Color, content: Color)`.
- Add a helper in the `ui` package, for example `forwardStatusChipColors(stateLabel: String)`.
- Use Material color roles where possible:
  - Listening / Connected / Serving: success-like readable container/content pair. If using custom green, set explicit white/dark content color with sufficient contrast.
  - Error / ConfigInvalid: `errorContainer` + `onErrorContainer` or equivalent.
  - Paused / Disabled / Stopped / Configured: neutral or secondary container + matching content.

Do not rely on `Surface` to infer readable content color from arbitrary custom colors.

Acceptance behavior:

- Status chip text is readable in both light and dark themes.
- Error chips are visually distinct from Listening/Configured chips.
- Existing `stateColorToken()` can remain for text/icon tint, but chip containers should use contrast-safe colors.

### 4. Normalize local forward host handling

Forward local-host behavior must be consistent across:

- Home screen forward summaries.
- Review step summaries.
- Forward details local address.
- Copy URL / copy address.
- Open Browser.
- Test Local Port.

Current config validation allows `127.0.0.1` and `localhost`. The code should therefore handle both consistently.

Required behavior:

- Display should show the configured `forward.localHost` exactly as saved.
- `ForwardDetailsScreen` should build local address from `forward.localHost` and `forward.localPort`.
- `ForwardsViewModel.testLocalPort()` should connect to the configured local host, not a hardcoded `127.0.0.1`.
- Test result messages should report the actual tested host/port.
- Browser URL generation should use a normalized browser host:
  - `127.0.0.1` remains `127.0.0.1`.
  - `localhost` remains `localhost` or normalizes to `127.0.0.1`, but the behavior must be consistent and tested.
  - If future validation allows `0.0.0.0`, browser-open should normalize it to `127.0.0.1` instead of trying to open `http://0.0.0.0:port`.

Recommended helper functions:

```kotlin
internal fun localForwardAddress(forward: ForwardConfig): String =
    "${forward.localHost}:${forward.localPort}"

internal fun browserHostForLocalForward(host: String): String = when (host.trim().lowercase()) {
    "", "0.0.0.0", "::" -> "127.0.0.1"
    else -> host.trim()
}

internal fun browserUrlForForward(forward: ForwardConfig): String =
    "http://${browserHostForLocalForward(forward.localHost)}:${forward.localPort}"
```

If IPv6 loopback support is added later, bracket formatting must be handled correctly. That is not required for this patch because current validation does not allow IPv6 hosts.

Acceptance behavior:

- A forward configured with `localhost` displays `localhost:PORT` and local port testing attempts `localhost:PORT`.
- A forward configured with `127.0.0.1` displays and tests `127.0.0.1:PORT`.
- There are no remaining hardcoded `127.0.0.1` references in forward testing/browser-open code except defaults and explicit normalization helpers.

### 5. Populate per-forward runtime status honestly

The UI currently renders per-forward state from `TunnelStatus.forwards`, but that list is normally empty because native runtime status does not expose per-forward status. This makes status-chip improvements misleading.

Implement a minimal native-to-Kotlin forward status bridge.

#### Kotlin model requirements

Extend `NativeRuntimeStatusDto` with a defaulted list field:

```kotlin
@Serializable
data class NativeRuntimeForwardStatusDto(
    val id: String,
    val name: String,
    val local_host: String,
    val local_port: Int,
    val remote_forward_id: String,
    val enabled: Boolean = true,
    val listen_state: String,
    val last_error: String? = null,
)
```

Then update `NativeRuntimeStatusDto`:

```kotlin
val forwards: List<NativeRuntimeForwardStatusDto> = emptyList()
```

Map native forward DTOs into existing `ForwardStatus` in `TunnelRepository.toTunnelStatus()`.

Listen-state mapping must be tolerant:

- `listening` -> `ListenState.Listening`
- `stopped` -> `ListenState.Stopped`
- `error` -> `ListenState.Error`
- `disabled` -> `ListenState.Disabled`
- `paused` -> `ListenState.Paused`
- Unknown values -> safe fallback, preferably `Stopped` or `Error` depending on whether `last_error` is present

All native strings must be passed through existing redaction where appropriate before storing in UI state.

#### Rust/mobile status requirements

Extend `AndroidRuntimeStatus` in `crates/p2p-mobile/src/runtime.rs` with a `forwards` list. Use snake_case JSON field names to match Kotlin DTOs.

Recommended Rust shape:

```rust
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct AndroidForwardRuntimeStatus {
    pub id: String,
    pub name: String,
    pub local_host: String,
    pub local_port: u16,
    pub remote_forward_id: String,
    pub enabled: bool,
    pub listen_state: AndroidForwardListenState,
    pub last_error: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidForwardListenState {
    Listening,
    #[default]
    Stopped,
    Error,
    Disabled,
    Paused,
}
```

On successful runtime start:

- Populate `forwards` from loaded `AppConfig` / configured tunnel forwards.
- Enabled forwards should be marked `listening` only after the runtime has successfully accepted the config and the controller has transitioned to running.
- Disabled forwards should be marked `disabled` if disabled forwards are represented in the relevant config source.

On stop:

- Existing configured forwards in runtime status should transition to `stopped` or be cleared. Prefer preserving them as `stopped` so the UI can still display recent runtime context.

On native runtime error:

- Set overall state to `error`.
- Set enabled forward entries to `error` if the failure prevents local listening.
- Include a redacted/safe `last_error` string.

Important: do not fabricate granular per-forward success if the native layer cannot know it. If the runtime only knows daemon-level state, make that explicit by setting all enabled forwards to the daemon-level state. The UI must not imply one forward is listening while another is failed unless native status actually knows that.

Acceptance behavior:

- When native status JSON includes forward entries, Home and Forwards screens render real `Listening`/`Error`/`Stopped` states instead of only `Configured` fallback.
- Kotlin can still decode older/native test status JSON without a `forwards` field because the field defaults to `emptyList()`.
- The native status JSON remains secret-safe.

### 6. Refresh runtime status while active

The Android UI must not depend on manual refresh or unrelated emissions for runtime status changes.

Required behavior:

- While the foreground service believes the tunnel is starting/running/serving/connected, it should periodically call `TunnelRepository.refreshStatus()`.
- The polling interval should be modest, for example 1-2 seconds.
- Polling must stop when the tunnel is stopped, paused by policy, or the service is destroyed.
- Status refresh should update foreground notification content when the user-visible state changes.
- Home may keep its local uptime ticking behavior, but actual status fields such as `lastError`, `activeSessionCount`, `mqttConnected`, and `forwards` must come from repository/native refresh.

Recommended implementation:

- Add a `statusPollJob: Job?` to `TunnelForegroundService`.
- Start the poll job after successful `startOffer()` and after successful resume.
- Cancel the poll job in explicit stop, policy pause, and `onDestroy()`.
- Each poll calls `repository.refreshStatus()` on `Dispatchers.IO`, then `publishStatus()` or `notificationController.update(...)` if needed.
- Avoid launching multiple concurrent poll jobs.

Acceptance behavior:

- If the native task transitions to error after start, Home and notification update within the polling interval.
- If native status reports forward status changes, Home/Forwards reflect them without leaving and re-entering the screen.
- Polling stops after Stop is tapped.

### 7. Keep UIUX2 behavior from regressing

The following existing UIUX2 fixes must remain intact:

- Network type labels use user-facing labels, not raw enum names.
- Review step uses `forward.localHost`, not hardcoded `127.0.0.1`.
- Home uptime counter ticks once per second while running.
- Home forward rows navigate to Forward Details and show a trailing chevron.
- Settings has a single canonical identity import/export location.
- Diagnostics developer tools are behind Advanced.
- Public identity display is truncated but copy/share use the full identity.
- `Remote forward ID` label does not expose `forward_id` variable naming.
- Broker step is labeled `Broker`, not `MQTT Broker`.

## Testing requirements

### Kotlin unit tests

Add or update tests for:

- `TunnelRepository` decodes native status with forward entries and maps them into `TunnelStatus.forwards`.
- `TunnelRepository` still decodes native status without `forwards`.
- Unknown listen-state strings do not crash status decoding.
- `ForwardsViewModel.testLocalPort()` uses `forward.localHost` in socket connection and user message.
- Browser/local-address helper functions produce expected output for `127.0.0.1` and `localhost`.
- No user-facing string contains `Android v1`.

### Compose/UI tests if existing infrastructure supports them

Add or update tests for:

- `LogsScreen` zero-log state appears in the lazy content area.
- `ForwardSummaryRow` renders chip text with explicit readable content color.
- Home forward rows are clickable and navigate to `forwardDetails/{id}`.

If Compose UI tests are not currently practical, add small pure helper tests and keep the UI implementation simple enough for manual verification.

### Rust tests

If native forward status is added:

- `p2p-mobile` status JSON shape includes `forwards` as an array.
- Fresh runtime returns `forwards: []`.
- Started runtime status includes configured forwards with safe fields only.
- Error status does not leak secrets.
- Existing status JSON tests are updated without weakening them.

### Validation commands

From the repository root:

```bash
cargo test -p p2p-mobile
cargo test
cd android
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

Also run targeted source checks:

```bash
grep -R "Android v1" -n android/app/src/main/java && exit 1 || true
grep -R "forward_id" -n android/app/src/main/java/com/phillipchin/webrtctunnel/ui && exit 1 || true
grep -R "Current network:.*networkType" -n android/app/src/main/java && exit 1 || true
```

Hardcoded `127.0.0.1` is still allowed in defaults, validation, and normalization helpers. It should not remain in `testLocalPort()` or browser-open URL construction except through a named helper.

## Manual QA checklist

Use a physical Android device if possible.

1. Fresh install / cleared app data.
2. Complete setup wizard.
3. Confirm Network Policy screen shows `Wi-Fi`, `Cellular`, `No network`, etc., not enum names.
4. Start tunnel on Wi-Fi.
5. Confirm Home status updates without navigating away.
6. Confirm uptime ticks every second.
7. Confirm Home forward rows navigate to Forward Details.
8. Confirm Forward Details Test Local Port message appears near the button.
9. Add a forward using `localhost`; confirm review, details, test, copy, and open behavior are consistent.
10. Open Logs with no logs; confirm empty state renders correctly.
11. Generate logs; confirm scrolling remains smooth with many entries.
12. Disable debug logs; confirm hidden debug notice appears when relevant.
13. Open Settings; confirm identity display is truncated but copy/share still use full identity.
14. Enable Advanced; confirm developer diagnostics appear only there.
15. Attempt answer-mode action if reachable; confirm safe error string does not mention `Android v1`.
16. Export diagnostics; inspect output for redaction and absence of private identity material.

## Definition of done

This patch is complete when:

- All required behavior above is implemented.
- All new and existing tests pass.
- Android lint, unit tests, and debug build pass.
- Rust tests pass if Rust/mobile status was changed.
- The UIUX2 improvements remain intact.
- No private identity or secret-bearing config value is exposed in logs, diagnostics, status JSON, notifications, or UI test output.
