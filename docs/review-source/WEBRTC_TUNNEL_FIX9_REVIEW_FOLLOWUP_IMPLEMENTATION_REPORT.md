# WebRTC Tunnel — FIX9 Review Follow-up Implementation Report

**Baseline:** `aa66695396cba84f6f9e066fb132fd6b37df158f`  
**Target branch:** `master`  
**Closure model:** exact-SHA `[full-signoff]` commit; authoritative status issues and commit statuses are the non-circular validation record.

## Scope

This follow-up addresses the non-blocking findings from the post-FIX9 comprehensive review. It does not reopen the core FIX9 stale setup-operation, transactional persistence, canonical import, broker-secret permission, or result-contract implementation.

## Release artifact contract

The current tagged release contract remains **Rust CLI/daemon archives only**.

Inspection found no Android production signing configuration, release keystore contract, protected secret flow, certificate fingerprint policy, or signature-verification gate. Adding `assembleRelease`/`bundleRelease` output to GitHub Releases in that state would risk presenting unsigned or debug-signed files as production artifacts. The follow-up therefore resolves the documentation mismatch by narrowing the current release claim rather than adding an unsafe packaging shortcut.

Future Android publication is tracked separately in:

- `docs/WEBRTC_TUNNEL_ANDROID_SIGNED_RELEASE_ARTIFACTS_TODO.md`

That work requires explicit signing ownership, protected credentials, signature/fingerprint verification, version/tag integrity, checksums/provenance, and negative tests before APK/AAB files can be release assets.

## Android E2E probe contract

Healthy Android emulator real-data-path E2E now requires two independent success conditions:

1. the unique marker is delivered through the Android offer tunnel to the dockerized answer; and
2. the answer log contains the explicit data-plane probe round trip, `received tunnel PING; sending PONG`.

Implemented files:

- `tests/e2e/lib/probe_evidence.sh`
- `tests/e2e/probe_evidence_test.sh`
- `tests/e2e/android_tunnel_e2e.sh`
- `android/app/src/test/java/com/phillipchin/webrtctunnel/ProbeEvidenceShellContractTest.kt`

The helper fails when the log line is absent or the answer log itself is missing and prints only bounded probe/data-channel/error lines. The fixture proves healthy, missing-evidence, and absent-log outcomes. The JUnit wrapper runs that shell fixture through ordinary Gradle `check`; it does not require an emulator or network.

Implementation commits:

- `1bcee719fb872fa495c0a17d1938d34696b5b23d` — shared probe verifier
- `8a9d9a5fb504541110b29b8f5e164420e4e43b22` — positive/negative shell fixtures
- `e0a8698df4d6255a4ae71226978e60d0981d17d1` — full E2E integration and temporary connector-probe cleanup
- `fbabe71a725c6aa98bb0fd84ffc94ffccaaa6a55` — Gradle/JUnit execution of shell fixtures
- `e89151a2ec8cefc98d6bce4f92fec27109ff4765` — Detekt-compliant fixture assertion formatting

No retry, timeout increase, alternate success line, or warning-only fallback was added.

## Static contract enforcement

The enforcement model is layered:

- Android Lint's real `CheckResult` detector, promoted to error, handles ordinary bare ignored authoritative results.
- `CheckResultEnforcementFixtureTest` proves the real detector fires and accepts a consumed result.
- `Fix9SourceContractAuditTest` retains existing result-contract/source-window fixtures.
- `Fix9FollowupContractAuditTest` catches fake `.also { }`, `.apply { }`, and `run { authoritativeCall(); Unit }` consumption; rejects executable `runCatching`/`catch(Throwable)` in the finite mutation-sensitive inventory; and checks setup-token routing/publication fragments across every production setup action.
- Runtime tests remain authoritative for cancellation, rollback, coherent storage, and stale publication behavior.

The follow-up scanner removes comments before mutation error-handling checks so explanatory documentation cannot create false positives. Its limitations and the authoritative API inventory are documented in:

- `docs/review-source/WEBRTC_TUNNEL_FIX9_FOLLOWUP_STATIC_CONTRACT_INVENTORY.md`

Implementation and quality-gate correction commits:

- `b33858e621d9dc7d66f2afdf89c27666770be346` — initial follow-up audit
- `f213fc98ef459a4b8fde72be7e2768bf0f253c37` — comment filtering and false-positive fixture
- `8960e33288e3dbfb176b0cb8f8b5a1bc9bedaf1a` — simplify the source scanner and wrap long audit lines
- `1e7eef8d6227e08bc3c7f6ca6fdeab9c4994614b` — remove loop jump statements
- `50dee78aa34d47b5ab30a9943a4ec8b04af287e8` — extract the setup-contract inventory and decompose parenthesis scanner state transitions

No Detekt suppression, threshold change, ignored result, or weakened source assertion was accepted.

## Stored identity compatibility contract

The stored identity behavior is now explicit:

- A canonical public identity derived from decrypted private identity validation wins for the setup save result/UI.
- If an older native validator omits canonical public output, the public file from the coherently-read stored identity pair may be used as a compatibility rendering fallback.
- The fallback cannot provide or replace the peer ID. The peer ID always comes from validating the decrypted private identity.
- The setup save rejects a local peer ID that does not match the private identity's peer ID, even when the stored public file claims another peer.
- A save using an existing identity does not rewrite the identity pair; only an explicit imported/generated replacement requests the identity persistence stage.

`SetupStoredIdentityCanonicalContractTest` proves canonical-public precedence and proves that a malicious/mismatched stored public rendering cannot authorize a save for a different private peer.

Implementation commit:

- `4641ed0e300a8fa3055b071c66b9f8006487f849`

## Validation defects found and repaired

### First exact full-signoff candidate

Exact candidate `d1e677a9a022ecf1f9a6b4e16adb1b71b2a68bdb` correctly failed Android job `91103082196` in main run `30614054982` at `:app:detekt` before unit tests or emulator E2E.

Detekt reported five issues introduced by the follow-up test code:

- excessive cyclomatic complexity and nested depth in the hand-written executable-source scanner;
- one long source-contract method line;
- multiple jump statements in the parenthesis scan loop;
- one long probe fixture assertion.

Rust lint/tests, Linux package/lifecycle, macOS install/lifecycle, Docker real-data-path/lifecycle E2E, and RC diagnostics passed on that SHA. Android and full matrix signoff remained failed, and emulator E2E was correctly skipped.

### Focused Android correction candidate

Path-required candidate `1e7eef8d6227e08bc3c7f6ca6fdeab9c4994614b` correctly failed Android job `91105504303` in run `30614798207` at `:app:detekt`. The first corrections removed the original five findings; Detekt then exposed two remaining structure limits:

- `realSetupActionsRetainFreshnessTokenContracts` was 61 lines against a 60-line limit;
- `findMatchingParen` remained nested too deeply.

Commit `50dee78aa34d47b5ab30a9943a4ec8b04af287e8` extracts the setup contract inventory and decomposes scanner state transitions into small helpers. It changes no production behavior and does not suppress or relax Detekt.

These failures are retained as validation evidence rather than retried or relabeled as infrastructure failures.

## Documentation

- `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md` is updated so the current tag contract does not claim APK/AAB validation.
- `docs/WEBRTC_TUNNEL_ANDROID_SIGNED_RELEASE_ARTIFACTS_TODO.md` preserves Android release work without silently lowering signing requirements.
- `docs/WEBRTC_TUNNEL_FIX9_REVIEW_FOLLOWUP_TODO.md` is the follow-up closure ledger.

## Validation rule

The final candidate is the exact `[full-signoff]` commit containing the implementation, tests, this report, and the completed follow-up ledger. Completion requires all applicable status records to refer to that same SHA:

- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- Android full Gradle `check`: success
- foreground-service stop truthfulness suite: success
- second Android unit invocation: success
- Docker real-data-path/lifecycle E2E: success
- Android emulator real-data-path E2E: success, including marker and explicit PING/PONG evidence
- Rust lint/tests and Linux/macOS lifecycle/package gates: success
- broker-secret instrumentation: success when triggered by the final `[full-signoff]` policy

No tag-only Android artifact result is required or claimed because Android release publication is not in the current release contract. Rust release-artifact jobs remain tag-only and are expected to skip on a commit candidate.
