# Android UI/UX2 Follow-up TODO

## Objective

Finish the remaining Android UI/UX2 follow-up work from the code review. The current UIUX2 implementation is mostly correct, but several issues remain: partial `LogsScreen` lazy rendering, stale `Android v1` wording, unsafe status-chip contrast, inconsistent local-host behavior, missing native forward runtime status, and passive runtime status refresh.

Work in small patches. Keep the app secure: do not expose private identity material or secrets in UI, logs, diagnostics, tests, notifications, or native status JSON.

---

## P0 — Regression guard before editing

- [ ] Run the current Android tests if the environment supports it:
  - [ ] `cd android && ./gradlew --no-daemon testDebugUnitTest`
  - [ ] `cd android && ./gradlew --no-daemon lintDebug`
- [ ] Run current Rust tests if the environment supports it:
  - [ ] `cargo test -p p2p-mobile`
- [ ] Record any pre-existing failures before making code changes.
- [ ] Do not hide or suppress new lint/test failures.

---

## P1 — Fully fix `LogsScreen` lazy rendering

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`

**Tasks:**

- [ ] In `LogsScreen`, keep the fixed top control area outside the scrollable list:
  - [ ] `SectionHeader`
  - [ ] filter chips
  - [ ] Pause/Resume button
  - [ ] Clear Logs button
  - [ ] actions menu
  - [ ] optional `message`
- [ ] Always render one `LazyColumn` for the log content area.
- [ ] Move `EmptyStateCard("No logs available.")` into a `LazyColumn` `item { ... }`.
- [ ] Move `EmptyStateCard("Debug logs are hidden. Enable Debug logs in Advanced to see them.")` into a `LazyColumn` `item { ... }`.
- [ ] Keep each log row inside `items(visibleLogs) { ... }`.
- [ ] Preserve `Modifier.weight(1f)` on the lazy list so the pinned controls remain visible.
- [ ] Preserve outer padding from the `padding: PaddingValues` parameter.
- [ ] Verify behavior with:
  - [ ] no logs
  - [ ] visible logs
  - [ ] debug logs hidden
  - [ ] many logs

**Acceptance criteria:**

- [ ] No empty/debug notice is rendered outside the `LazyColumn` content area.
- [ ] Filter/action controls stay pinned at top while logs scroll.
- [ ] The screen does not regress copy/export/share diagnostics actions.

---

## P2 — Remove stale `Android v1` answer-mode wording

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/service/TunnelForegroundService.kt`
- Any other Android source file containing the same string

**Tasks:**

- [ ] Replace all occurrences of `Answer mode is not available in Android v1` with one of:
  - [ ] `Answer mode is not available on Android`
  - [ ] `Answer mode is not available in this Android build`
- [ ] Do not enable answer mode.
- [ ] Keep the same error code, unless a better existing code is already used consistently.
- [ ] Add or update a small unit/source test if practical.
- [ ] Run:

```bash
grep -R "Android v1" -n android/app/src/main/java && exit 1 || true
```

**Acceptance criteria:**

- [ ] Grep finds no `Android v1` string in user-facing Android source.
- [ ] Answer-mode action still fails safely.

---

## P3 — Make forward status chips contrast-safe

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/components.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
- Optional: new helper file such as `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/UiHelpers.kt`

**Tasks:**

- [ ] Replace the current single `statusColor` parameter with contrast-safe chip colors.
  - Option A:
    - [ ] Add `statusContainerColor: Color`.
    - [ ] Add `statusContentColor: Color`.
  - Option B:
    - [ ] Add a `StatusChipColors(container: Color, content: Color)` value type.
- [ ] Update the `Surface` in `ForwardSummaryRow` to set both `color` and `contentColor`.
- [ ] Add a helper for status chip colors, for example:
  - [ ] `Listening` / `Connected` / `Serving` -> success-like readable pair.
  - [ ] `Error` / `Configuration needs attention` -> `errorContainer` + `onErrorContainer`.
  - [ ] `Paused` -> warning/tertiary readable pair.
  - [ ] `Stopped` / `Disabled` / `Configured` -> neutral/secondary readable pair.
- [ ] Keep `stateColorToken()` available for icon/text tint if still useful.
- [ ] Update all `ForwardSummaryRow` call sites.
- [ ] Check light theme and dark theme manually if possible.

**Acceptance criteria:**

- [ ] Forward status chip text is readable in light mode.
- [ ] Forward status chip text is readable in dark mode.
- [ ] Error and listening statuses are visually distinguishable.
- [ ] No chip uses arbitrary dark foreground color as a container without explicit content color.

---

## P4 — Normalize local forward host/address handling

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`
- Optional: shared helper file in `ui` or model layer
- Tests under `android/app/src/test/java/...`

**Tasks:**

- [ ] Add shared helper functions for local forward address behavior.
  - [ ] `localForwardAddress(forward: ForwardConfig): String`
  - [ ] `browserHostForLocalForward(host: String): String`
  - [ ] `browserUrlForForward(forward: ForwardConfig): String`
- [ ] Use `localForwardAddress(forward)` in:
  - [ ] Home forward subtitle
  - [ ] Forwards list subtitle
  - [ ] Forward Details local address
  - [ ] Review step if appropriate
- [ ] Use `browserUrlForForward(forward)` in:
  - [ ] Home Open URL button
  - [ ] Forward Details Copy URL
  - [ ] Forward Details Open Browser
- [ ] Update `ForwardsViewModel.testLocalPort()`:
  - [ ] Connect to `forward.localHost` or the shared normalized test host.
  - [ ] Do not hardcode `127.0.0.1`.
  - [ ] Report the actual tested host and port in success/failure messages.
  - [ ] Preserve `SensitiveDataRedactor.redactText(...)` for the result message.
- [ ] Add unit tests for:
  - [ ] `127.0.0.1`
  - [ ] `localhost`
  - [ ] optional future-proof `0.0.0.0` browser normalization if the helper supports it
- [ ] Grep for hardcoded `127.0.0.1` in forward testing/browser-open code and remove any remaining non-default/non-helper use.

**Acceptance criteria:**

- [ ] A forward configured with `localhost` displays `localhost:PORT` consistently.
- [ ] Test Local Port attempts the configured host, not always `127.0.0.1`.
- [ ] Browser URL generation is centralized.
- [ ] Defaults may still use `127.0.0.1`; hardcoded operational behavior should not.

---

## P5 — Add native-to-Kotlin per-forward runtime status

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/model/Models.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt`
- `crates/p2p-mobile/src/runtime.rs`
- `crates/p2p-mobile/src/lib.rs`
- Tests under Android and Rust test directories

**Kotlin tasks:**

- [ ] Add `NativeRuntimeForwardStatusDto`:

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

- [ ] Add `val forwards: List<NativeRuntimeForwardStatusDto> = emptyList()` to `NativeRuntimeStatusDto`.
- [ ] Map native forward DTOs into `ForwardStatus` in `TunnelRepository.toTunnelStatus()`.
- [ ] Add a tolerant mapper from native string to `ListenState`:
  - [ ] `listening`
  - [ ] `stopped`
  - [ ] `error`
  - [ ] `disabled`
  - [ ] `paused`
  - [ ] unknown fallback does not crash
- [ ] Redact `last_error` before putting it into `ForwardStatus`.
- [ ] Preserve compatibility with native JSON that does not include `forwards`.

**Rust/mobile tasks:**

- [ ] Add an `AndroidForwardRuntimeStatus` type to `crates/p2p-mobile/src/runtime.rs`.
- [ ] Add an `AndroidForwardListenState` enum with `#[serde(rename_all = "snake_case")]`.
- [ ] Add `forwards: Vec<AndroidForwardRuntimeStatus>` to `AndroidRuntimeStatus`.
- [ ] On successful start, populate forward entries from the loaded app config.
- [ ] Mark enabled forwards as `listening` only when the runtime is accepted/running.
- [ ] Mark disabled forwards as `disabled` if disabled forwards are available from the config source.
- [ ] On stop, transition known forwards to `stopped` or clear them intentionally; prefer preserving stopped entries for UI continuity.
- [ ] On start/runtime error, mark affected enabled forwards as `error` when the daemon-level failure prevents listening.
- [ ] Ensure status JSON contains no private identity, broker password, token, certificate body, or unredacted secret path content.

**Test tasks:**

- [ ] Android: decode native status JSON with `forwards` and verify `TunnelStatus.forwards` is populated.
- [ ] Android: decode native status JSON without `forwards` and verify `TunnelStatus.forwards == emptyList()`.
- [ ] Android: unknown listen-state string does not crash.
- [ ] Rust: fresh runtime status JSON includes `forwards` as an empty array.
- [ ] Rust: status JSON shape test covers the new field.
- [ ] Rust: status JSON remains parseable and secret-safe.

**Acceptance criteria:**

- [ ] Home and Forwards can display real native `Listening` / `Error` / `Stopped` states when native status includes them.
- [ ] The UI no longer depends only on `Configured` fallback for enabled forwards while the tunnel is running.
- [ ] Native/Kotlin status schema changes are backward-compatible on the Kotlin side.

---

## P6 — Poll runtime status while the tunnel is active

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/service/TunnelForegroundService.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt` if helper methods are needed
- Existing viewmodel/tests if affected

**Tasks:**

- [ ] Add a `statusPollJob: Job?` field to `TunnelForegroundService`.
- [ ] Add `startStatusPolling()`:
  - [ ] No-op if a poll job is already active.
  - [ ] Loop every 1-2 seconds while active.
  - [ ] Call `repository.refreshStatus()` on an appropriate dispatcher.
  - [ ] Update/publish notification when visible status changes.
  - [ ] Handle decode/native errors without crashing the service.
- [ ] Add `stopStatusPolling()`:
  - [ ] Cancel and clear the job.
- [ ] Start polling after successful start/resume.
- [ ] Stop polling when:
  - [ ] Stop is requested.
  - [ ] Policy pause occurs.
  - [ ] Service is destroyed.
  - [ ] Native status becomes stopped/error and the service is no longer supposed to keep running.
- [ ] Avoid duplicate concurrent pollers.
- [ ] Keep Home uptime local ticking if desired, but do not rely on it for real runtime fields.

**Acceptance criteria:**

- [ ] Native runtime transition to error is visible on Home without navigation.
- [ ] Native forward status changes are visible without navigation.
- [ ] Foreground notification content updates when service state changes.
- [ ] Polling stops after tunnel stop.
- [ ] No runaway coroutine/job leak is introduced.

---

## P7 — Preserve existing UIUX2 fixes

Do not regress these already-implemented items.

- [ ] Network labels still use `mapNetworkTypeLabel(...)`, not raw enum names.
- [ ] `PolicyStepContent` still uses user-facing network labels.
- [ ] Review step still displays `${forward.localHost}:${forward.localPort}`.
- [ ] Home uptime still ticks once per second while running.
- [ ] Home forward rows still navigate to `forwardDetails/{id}`.
- [ ] Home forward rows still show a trailing chevron.
- [ ] Settings does not duplicate cellular/metered switches across Settings and Network Policy.
- [ ] Identity import/export has one canonical Settings location.
- [ ] Copy/share identity still use the full public identity, not the truncated display string.
- [ ] Copy status JSON and redacted config remain behind Advanced.
- [ ] Network Policy screen does not show the unconditional `Unknown network is blocked.` warning.
- [ ] Settings answer-mode text does not mention stale version numbering.
- [ ] Import/export advanced section says `Advanced (file paths)`.
- [ ] UI says `Remote forward ID`, not `Remote forward_id`.
- [ ] Broker password file has helper text.
- [ ] Home remote peer fallback says `Not configured`.
- [ ] Forward Details uses a single `canOpenBrowser` value.
- [ ] Wizard step label says `Broker`, not `MQTT Broker`.

---

## P8 — Validation gate

Run all applicable validation before declaring completion.

### Android validation

```bash
cd android
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

- [ ] `lintDebug` passes.
- [ ] `testDebugUnitTest` passes.
- [ ] `assembleDebug` passes.

### Rust validation

```bash
cargo test -p p2p-mobile
cargo test
```

- [ ] `cargo test -p p2p-mobile` passes.
- [ ] Full `cargo test` passes, or any unrelated pre-existing failures are clearly documented.

### Source grep validation

```bash
grep -R "Android v1" -n android/app/src/main/java && exit 1 || true
grep -R "Current network:.*networkType" -n android/app/src/main/java && exit 1 || true
grep -R "Remote forward_id" -n android/app/src/main/java && exit 1 || true
```

- [ ] No stale Android version string.
- [ ] No raw network enum display in user-facing text.
- [ ] No leaked `forward_id` UI label.

### Manual QA

- [ ] Fresh install / clear data.
- [ ] Complete setup wizard.
- [ ] Start tunnel on Wi-Fi.
- [ ] Confirm Home status updates without leaving the screen.
- [ ] Confirm uptime ticks.
- [ ] Confirm Home forward rows open Forward Details.
- [ ] Confirm Forward Details Test Local Port feedback appears near the button.
- [ ] Confirm `localhost` forward display/test/open behavior is consistent.
- [ ] Confirm Logs empty state and debug-hidden state render correctly.
- [ ] Confirm Logs screen remains smooth with many entries.
- [ ] Confirm Settings identity display is truncated but copy/share uses full identity.
- [ ] Confirm Advanced diagnostics are hidden until Advanced is enabled.
- [ ] Export diagnostics and confirm secrets/private identity are redacted.

---

## Definition of done

- [ ] All P1-P7 implementation tasks are complete.
- [ ] Validation gate passes.
- [ ] Any unavoidable limitation is documented in code comments or project docs.
- [ ] No new lint suppressions were added unless explicitly justified.
- [ ] No private identity material or secret config values are exposed.
- [ ] The final implementation is small, focused, and does not redesign unrelated screens.
