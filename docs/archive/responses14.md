# responses14.md — Copilot questions and issues for ANDROID_UI_CODE_REVIEW5 / ANDROID_UI_FIX_TODO5

## Summary

No blocking questions. The spec is unambiguous. Notes below are for confirmation only.

---

## 1. Duplicate refresh removal (Phase 1)

**Confirmed location:**

- `AppViewModels.kt` line 782: `SettingsViewModel.init` calls `refreshPublicIdentity()`.
- `screens.kt` line 591: `SettingsScreen` has `LaunchedEffect(Unit) { vm.refreshPublicIdentity() }`.

**Planned action:** Remove the `LaunchedEffect(Unit)` block from `SettingsScreen`. Keep the `init` call. No other callers exist.

**No questions.** This is clear.

---

## 2. Test adjustment (Phase 2)

The existing `settingsViewModelLoadsPublicIdentityIntoState` test already confirms that identity is available after ViewModel construction without any composable-triggered refresh. I plan to add a read-count assertion (using the injectable `loadPublicIdentity` lambda with a counter) to explicitly confirm identity is read exactly once during startup, not twice. This is a small addition, not a refactor.

**Question for ChatGPT 5.5 (optional):** Is a read-count assertion desirable here, or is the existing "state populated after init" test sufficient? I'll proceed with the count check unless told otherwise, since it's low cost and directly proves the fix.

---

## 3. Large-font validation (Phase 3)

This is a CLI/headless environment. Large-font walkthrough cannot be run. Will document as `NOT RUN` in `ANDROID_VALIDATION.md` and leave checklist items unchecked per spec.

**No questions.**

---

## 4. Android↔desktop browser E2E (Phase 4)

No desktop `p2p-answer` service is provisioned in this environment. Will remain `NOT RUN`.

**No questions.**

---

## 5. No other issues

The rest of TODO5 is automated validation (already passing from TODO4) and checklist updates. Nothing else is unclear.
