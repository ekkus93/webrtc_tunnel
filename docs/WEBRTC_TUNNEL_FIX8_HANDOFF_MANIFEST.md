# WebRTC Tunnel FIX8 Handoff Manifest

**Package:** WebRTC Tunnel Authoritative State, Atomic Commit, Durable Quarantine, and Failure Truthfulness FIX8  
**Created:** 2026-07-21  
**Reviewed baseline:** `webrtc_tunnel-master_2607211131.zip`

Extract this ZIP at the repository root. It intentionally contains the `docs/` directory structure expected by the specification and TODO.

## Included input files

| Repository path | Purpose |
|---|---|
| `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_SPEC.md` | Binding architecture, invariants, error contracts, security rules, and definition of done. |
| `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_TODO.md` | Executable implementation order, blank checklists, code patterns, exact tests, validation, and signoff requirements. |
| `docs/review-source/WEBRTC_TUNNEL_FIX7_CODE_REVIEW_2026-07-21.md` | Detailed source review and task-by-task evidence that defines the FIX8 defect scope. |
| `docs/review-source/WEBRTC_TUNNEL_FIX8_HANDOFF_MANIFEST.md` | This inventory and extraction guidance. |

## Required reading order

1. This manifest.
2. `docs/review-source/WEBRTC_TUNNEL_FIX7_CODE_REVIEW_2026-07-21.md`.
3. `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_SPEC.md`.
4. `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_TODO.md`.
5. Current production and test code named by the first TODO task before editing.

## Baseline verification

The source archive supplied for review did not contain `.git` metadata. Before implementing:

```bash
git rev-parse HEAD
git status --short
```

Record the real repository baseline in the TODO evidence. Confirm that the reviewed code signatures still exist, including:

```bash
rg -n 'storeEncryptedIdentity\(' android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt
rg -n 'upsertWithReceipt\(|deleteWithReceipt\(' android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt
rg -n 'SetupInputSnapshot|configExisted|configContents' android/app/src/main/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinator.kt android/app/src/main/java/com/phillipchin/webrtctunnel/data/ExactFileSnapshot.kt
rg -n 'nativeRuntimeUncertain|nativeStopVerified' android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt android/app/src/main/java/com/phillipchin/webrtctunnel/OfferCoordinator.kt
rg -n '"unix_ms":0|None => Vec::new\(\)' crates/p2p-mobile/src
```

If the current repository has materially changed, document the difference in the FIX8 implementation report and adapt the implementation while preserving every FIX8 invariant. Do not blindly paste snippets over changed code.

## Future implementation output

The TODO requires Claude Code to create this file during implementation:

```text
docs/review-source/WEBRTC_TUNNEL_FIX8_IMPLEMENTATION_REPORT.md
```

It is deliberately not included as a preexisting input. The report must record task SHAs, commands, results, skipped checks, artifacts, and final readiness against the implemented code.

## Completeness rule

Do not add a reference to another assistant-created review, response, template, mockup, or companion file unless that file is also delivered and committed at the exact path named. This package currently has no dangling assistant-created input references.
