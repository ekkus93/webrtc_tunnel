# responses15 — review of ANDROID_LIFECYCLE_STATUS_HARDENING (spec + TODO)

This is a review of `ANDROID_LIFECYCLE_STATUS_HARDENING_SPEC.md` and
`ANDROID_LIFECYCLE_STATUS_HARDENING_TODO.md`, cross-checked against the **current**
code on branch `android-app` (not the review archive). No code has been written yet;
these are questions/issues/decisions needed before implementation.

Context worth knowing: since the review archive was taken, the repo had a cleanup pass
that (a) removed **every** lint suppression across Rust and Android and wired detekt
(with type resolution) into `./gradlew check`, (b) split `AppViewModels.kt` and its test
into per-ViewModel files, and (c) injected coroutine dispatchers **per-ViewModel**
(constructor params) rather than centrally. Two of those facts collide with the spec and
are called out below (Issues 1 and 2).

---

## Verification — all 9 items are real and still present

Each item was confirmed against current source:

| Item | Status in current code |
|---|---|
| **P1** lifecycle VMs | Confirmed. `ui/App.kt:97` builds `AppScreenModels(factory)`, which calls `factory.home()`/`forwards()`/… directly (`App.kt:137-144`). `AppViewModelFactory` is a plain class, not `ViewModelProvider.Factory`. |
| **P2** main-thread work | Confirmed (partially already done). `ForwardsViewModel.testLocalPort` already runs on an injected IO dispatcher; but `saveForward`/`deleteForward`/`regenerateActiveConfig`, plus Settings and ImportExport disk+native calls, still run synchronously on the caller thread. |
| **P3** truthful status | Confirmed, concretely: `data/TunnelRepository.kt:183` maps native `"running"` → **`Connected`** (offer) / `Serving` (answer) with no peer/session/data-channel check. |
| **P4** stale metadata | Confirmed. The exact no-op is at `crates/p2p-mobile/src/runtime.rs:392-393` (`if last_error.is_none() { last_error = None }`); `stop()` does not clear `started_at_unix_ms`. |
| **P5** notification SDK gating | Partially done. `NotificationController:22` already gates on `< TIRAMISU`, but a second path at `:39-44` does an **un-gated** `checkSelfPermission` (with a comment that it exists so Android lint can verify the permission is held). |
| **P6** dup/dead settings | Confirmed. `resumeOnUnmetered` switch appears in **both** `SettingsScreen:107` and `NetworkPolicyScreen:49`. `startTunnelWhenAppOpens` is **dead** — persisted (`ConfigRepository`) and shown (`SettingsScreen:104`) but has no behavioral consumer. |
| **P7** atomic forwards | Confirmed. `ForwardsConfigStore:39` does a plain `writeText`; `loadForwards` does `.getOrElse { emptyList() }` — silent data loss on corrupt JSON. |
| **P8** skip Rust build | Confirmed absent. `verifyRustJniLibs` is wired into `preBuild`, so it runs for every task including unit tests. |
| **P9** launcher icon | Confirmed. `AndroidManifest.xml:13-14` uses `@android:drawable/sym_def_app_icon` for both `icon` and `roundIcon`. |

Nothing in the spec is factually wrong. The items below are open design choices the spec
left unspecified, plus one hard conflict with the repo's linting policy.

---

## Blocking decisions

### Issue 1 — P1's factory example uses `@Suppress("UNCHECKED_CAST")`, which violates the no-suppression policy

`CLAUDE.md` forbids `@Suppress` (and we just removed every suppression in the repo, and
wired detekt into `check`). The textbook `ViewModelProvider.Factory.create()` body in the
spec needs `@Suppress("UNCHECKED_CAST")` for the `as T` cast.

Proposed alternative (cast-free, suppression-clean): use the AndroidX
`androidx.lifecycle.viewmodel.viewModelFactory { initializer { … } }` DSL (or per-class
`viewModel(factory = …)`), which avoids the unchecked cast entirely.

**Question:** OK to use the cast-free `viewModelFactory {}` DSL instead of the spec's
`create(modelClass)` + `@Suppress("UNCHECKED_CAST")` example?

### Issue 2 — Dispatcher injection strategy conflicts with what was just shipped

P2 repeatedly assumes a centralized `deps.ioDispatcher`, but **`AppDependencies` has no
dispatcher today**. The recent cleanup injected `ioDispatcher` *per-ViewModel*
(constructor params on `ForwardsViewModel`, `SetupSaveController`, defaulting to
`Dispatchers.IO`).

Proposal: centralize a single `ioDispatcher` (and maybe `defaultDispatcher`) in
`AppDependencies`, and refactor the existing per-VM params to read it. This is cleaner and
plugs directly into the P1 factory.

**Question:** Centralize the dispatcher in `AppDependencies`, or keep per-ViewModel
injection?

---

## Design questions

### Issue 3 — P3: how much "truth" can we actually express, and what is in scope?

Today the native layer exposes `mqtt_connected` and per-forward listen state, and
`runtime.rs` flips state to `Running` immediately on `start()`. The Kotlin mapping then
shows `Connected` for offer as soon as the task is running.

- **3a.** Is **adding richer native state plumbing** (a distinct peer/session/data-channel
  "connected" signal from the daemon) in scope, or do you want a **relabel-only** pass
  using existing signals — i.e., never show `Connected` for offer; use `Listening` /
  `Waiting for local client`, and only claim connected when `mqtt_connected` plus an
  active session/forward is true?
- **3b.** For **answer mode**, what should `Serving` vs `Connected` mean? Is "answer daemon
  running and listening" enough for `Serving`, or must a peer actually be connected?
- **3c.** Related bug: Kotlin's `toTunnelStatus` `when(state)` only handles
  `running/starting/stopping/error/else→Stopped`. If the Rust side emits any richer state
  string, it silently falls through to `Stopped`. The P3.1 audit should pin down the exact
  enum on **both** sides so the mapping is total.

### Issue 4 — P6.2: remove vs implement `startTunnelWhenAppOpens`

It is a persisted DataStore key (`start_tunnel_when_app_opens`) plus an
`AndroidAppPreferences` field, with no behavioral consumer. Proposal: remove the UI toggle
and the model field; leave the orphaned DataStore key (harmless, just never read).

**Question:** Remove it (preferred, matches the spec's lean), or actually implement
auto-start? (Implementing it safely is real work — foreground-service/permission/policy
guards, once-per-launch, valid config+identity.)

### Issue 5 — P7 ↔ P1 overlap: single source of truth for forwards

P7.2 wants an observable `StateFlow<List<ForwardConfig>>`; P1 wants a lifecycle-scoped
shared `ForwardsViewModel`. To avoid doing this twice, proposal: put the `StateFlow` in a
small `ForwardsRepository` inside `AppDependencies` (survives ViewModel scope), and have
both Home and Forwards observe it — which also removes the `LaunchedEffect(Unit) {
refreshForwards() }` staleness pattern.

**Question:** Agree with the repository-owns-`StateFlow` approach?

### Issue 6 — P5 + lint interaction

The un-gated `checkSelfPermission` at `NotificationController:39` is deliberately placed so
Android lint's `NotificationPermission` check passes. Adding the
`SDK_INT >= TIRAMISU` guard the spec wants *should* still satisfy lint, but it needs
verification — because if it tripped lint, we could not suppress it and would need a
different structure. Flagging so the fix is validated against `lintDebug`, not just logic.

---

## Smaller notes (confirming intent, not blocking)

- **P2 pseudocode names are illustrative, not literal.** Current code uses
  `deps.forwardsStore` (not `forwardsConfigStore`), `configRepository.writeConfigAtomically`
  / `renderOfferConfig` (not `writeActiveConfig`), `deps.identityValidation.validateConfig`
  (not `deps.rustBridge.validateConfig`), and `ForwardsViewModel` exposes `_message` (no
  `_uiState`/`isSaving` yet). Implementation will adapt to the real APIs.
- **P3/P4 are cross-language** (touch `crates/p2p-mobile`), so they trigger the pre-commit
  `cargo test` hook and a workspace rebuild; will run `cargo test -p p2p-mobile` and
  `cargo test --workspace` for them.
- **P8 skip is safe for unit tests.** Robolectric tests use `FakeTunnelBridge` /
  `RecordingBridge` and never load the `.so`, so gating `verifyRustJniLibs` off for
  `testDebugUnitTest` / `lintDebug` won't break them. The change is making
  `verifyRustJniLibs` conditional on the `-PskipRustBuild` property while keeping
  `assembleDebug` packaging native libs by default.
- **Suggested sequencing:** P1 + P2 together (same VMs/factory); P3 + P4 together (shared
  status path, both Rust + Kotlin); then P5, P6, P7, P8, P9 are largely independent.
- **All linters/tests are currently green** (Rust fmt/clippy/test, Android
  detekt+ktlint+lint+unit, both Docker E2E tiers, emulator instrumentation, and the
  on-device smoke test), so this hardening pass starts from a clean baseline.
