# Responses — WEBRTC_TUNNEL_RUNTIME_TRUTHFULNESS_PACKAGING_HARDENING_SPEC.md / _TODO.md

1.
Q: Two tasks in this TODO deliberately reverse work completed earlier in this project's history: (a) P0-007 removes the `sd_notify`/`Type=notify` readiness feature — `crates/p2p-daemon/src/notify.rs`, the `p2p-offer-notify.service`/`p2p-answer-notify.service` systemd units, and their tests — which was built as part of the original service-lifecycle TODO; (b) P1-006 requires inverting three existing Android tests in `SensitiveDataRedactorTest.kt` (`passwordFieldWithColonIsNotRedactedByThisRule`, `mqttSchemeCredentialsAreRedactedButSchemeIsNormalizedToMqtts`, `kexSecretWithSpaceIsNotRedactedByThisRule`) that currently assert specific redaction gaps are intended, documented behavior — this spec reclassifies them as security bugs that must be fixed (redact `password:`/`kex secret =` variants, preserve the original `mqtt://` scheme instead of rewriting it to `mqtts://`). Both reversals are well-justified in the spec's own reasoning. Confirm you want both carried out before I delete a shipped feature and invert recent test decisions.
A: Yes. Carry out both reversals.

For P0-007, remove the current `sd_notify` / `Type=notify` readiness feature from the supported surface because its current semantics are false: `READY=1` is emitted before the daemon future is polled and therefore before MQTT subscription, listener binding, or actual runtime readiness. Delete the premature readiness implementation, the `*-notify.service` units, and tests that only preserve that incorrect behavior. Keep the normal `Type=simple` units. Do not replace the removed feature with a sleep, an arbitrary delay, or another heuristic. Real `sd_notify` support is deferred until the daemon core exposes a supervisor-neutral readiness event tied to an actual runtime milestone.

For P1-006, invert and replace the three redaction tests. The currently documented gaps are security bugs, not compatibility requirements. `password:` and whitespace/hyphen/underscore variants such as `kex secret =` must be redacted, and redacting MQTT credentials must preserve the original scheme (`mqtt://` remains `mqtt://`; `mqtts://` remains `mqtts://`). Add regression coverage for common separators and field-name variants rather than preserving the known leaks.

These are intentional corrections to earlier implementation decisions. Do not retain dead compatibility shims or duplicate tests that keep the old behavior alive.

2.
Q: This TODO is 34 tasks (17 P0 + 12 P1 + 5 P2) spanning Rust daemon internals, Android/Kotlin, Debian/macOS packaging, and CI YAML — substantially larger than the unit-test TODO worked through earlier this session. The spec provides a 10-stage dependency-ordered execution plan (P0 phases 1-7, then P1 in stages 8-9, then P1 packaging/docs + final quality gates in stage 10). Should I work through the full sequence autonomously, committing/pushing per task at the same cadence used for the earlier unit-test TODO, or would you like a checkpoint after all P0 tasks complete (end of stage 7) before I continue into P1/P2?
A: Work through the full P0 and P1 sequence autonomously in the dependency order from the spec. Do not stop for a checkpoint after P0 unless you encounter a genuine architectural conflict that makes the written spec impossible or unsafe to implement.

Use the same incremental commit/push cadence as the earlier work: keep commits buildable and logically scoped, preferably one task per commit when practical, or one tightly coupled task group when separating them would create intentionally broken intermediate states. Do not make one giant final commit. Keep commit messages tied to the TODO IDs so regressions are bisectable.

Do not implement the P2 tasks in this pass. They are explicitly deferred future work. Finish P0, then P1, then the final quality gates and reporting.

3.
Q: This sandbox has no Docker, no macOS host, and no GitHub Actions runner. Concretely: P0-011's required real-process signal CI job and P1-011's package/install smoke tests can be authored and code-reviewed here but not executed against a real MQTT broker or real CI environment; P0-014/P0-015 (macOS launchd account/permission validation) and the macOS half of P1-011 can be written and shell-syntax-checked but not run against a real `_p2ptunnel` service account or `launchctl bootstrap`. The Debian package track (P0-012/P0-013, most of P1-011) can be fully built and verified locally (as was done for the original service-lifecycle work). Is it acceptable that I fully verify everything that's Linux/Rust/Android/Debian-testable locally, and explicitly report the CI-job and macOS-specific items as implemented-but-unverified (matching the spec's own "NOT RUN: exact reason" reporting discipline from P0-017/§27) rather than blocking on them?
A: Yes. Fully verify everything that is actually executable in the current environment, and explicitly report the unavailable checks as `NOT RUN: <exact reason>`. Do not block implementation solely because Docker, a macOS host, or a local GitHub Actions runner is unavailable.

However, three rules apply:

1. Do not convert unavailable validation into a pass. Use separate categories such as `PASS`, `FAIL`, and `NOT RUN`. Never write "verified" or check an acceptance box for a platform/runtime behavior that was not actually exercised.

2. Do not weaken tests, add permissive skips, or change production behavior merely to make the local sandbox green. Author the required CI jobs, macOS validation, and package/install smoke tests exactly as specified so they will fail when run in the proper environment.

3. If pushing the branch triggers GitHub Actions and you can inspect the remote results, review those results and fix failures before declaring the work complete. If remote CI is not available or cannot be observed, report those CI-only checks as unverified. Likewise, macOS `_p2ptunnel` account/permission behavior and `launchctl bootstrap` remain explicitly unverified until run on a real macOS host.

Linux/Rust/Android/Debian work that can run locally should be fully executed, not merely inspected. For every unrun item, include the exact command or procedure that still needs to be executed later and the environment prerequisite that is missing.

---
Fill in the `A:` lines above, then share this file back (or paste the answers) so implementation can begin.
