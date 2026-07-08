# Responses: Lifecycle Coordinator, Verified-Start Cleanup, and Rollback-Integrity Spec/TODO Review

## Q: Implementation baseline
The TODO references `webrtc_tunnel-master2607072301.zip` — should I assume I'm working against the current codebase state (where some P0-001 code already exists) or a clean baseline?

A:

## Q: Destroy sequence
The spec says processor should be joined before startup (§5.3), but current code does the opposite. Which sequence is correct for P0-004?

A:

## Q: `StartupCompletion` integration
The existing implementation has `cleanupUnverifiedStart()` but the TODO shows adding it as new. Should I fix the existing implementation or add new functionality alongside it?

A:

## Q: `pendingPolicyResume` ownership
The spec wants coordinator ownership, but current implementation has it accessible from multiple coroutines. Should I refactor to make it coordinator-owned immediately or phase it?

A:

## Q: P1-001 scope
The forwards mutation receipt system (P1-001 through P1-004) is a major architectural change. Should this be in the same release or deferred to a separate pass?

A:

## Q: Test infrastructure
P0-008 requires `FakeLifecycleEvent` and test infrastructure. Should I assume these exist or create them as part of this TODO?

A:

## Q: P0-003 retry timing
Should the retry start before or after the old startup's cleanup completes? The acceptance criterion says "old startup fully completes before retry begins" but current implementation checks `pendingPolicyResume` immediately after startup completion.

A:

## Q: P0-008 old behavior restoration
The test scenario for "reverted old behavior fails deterministically" requires temporarily restoring old behavior to prove the test catches it. What specific old behavior should be restored?

A:

## Q: P1-005 mode field behavior
Should the mode field show the previous value or a default value when an unknown native mode is encountered?

A:

## Q: Cross-cutting — Destroy sequence consistency
The spec's destroy order (§5.3) differs from current implementation. Current code joins startup before processor, spec says processor before startup. Which sequence is correct and does it affect P0-004 implementation?

A:

## Q: Cross-cutting — `pendingPolicyResume` state management
Multiple tasks (P0-002, P0-003, P0-004) touch this flag. The spec wants coordinator ownership but current implementation has it accessible from multiple coroutines. How should this be refactored?

A:

## Q: Cross-cutting — Test infrastructure gaps
P0-008's test boundary fixes require `FakeLifecycleEvent` and test infrastructure that might not exist. Should these be created as part of this TODO or are they already present?

A:

## Q: Cross-cutting — Security considerations
The spec handles `StartStatusVerificationException` and cleanup failures but doesn't explicitly address security implications of unowned native runtimes or failed cleanup scenarios. Should security implications be documented or addressed in implementation?

A:

---

Please fill in the `A:` lines above and share back when ready. Implementation will begin after responses are received.