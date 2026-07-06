# Responses — WEBRTC_TUNNEL_RUNTIME_TRUTHFULNESS_PACKAGING_HARDENING_SPEC.md / _TODO.md

1.
Q: Two tasks in this TODO deliberately reverse work completed earlier in this project's history: (a) P0-007 removes the `sd_notify`/`Type=notify` readiness feature — `crates/p2p-daemon/src/notify.rs`, the `p2p-offer-notify.service`/`p2p-answer-notify.service` systemd units, and their tests — which was built as part of the original service-lifecycle TODO; (b) P1-006 requires inverting three existing Android tests in `SensitiveDataRedactorTest.kt` (`passwordFieldWithColonIsNotRedactedByThisRule`, `mqttSchemeCredentialsAreRedactedButSchemeIsNormalizedToMqtts`, `kexSecretWithSpaceIsNotRedactedByThisRule`) that currently assert specific redaction gaps are intended, documented behavior — this spec reclassifies them as security bugs that must be fixed (redact `password:`/`kex secret =` variants, preserve the original `mqtt://` scheme instead of rewriting it to `mqtts://`). Both reversals are well-justified in the spec's own reasoning. Confirm you want both carried out before I delete a shipped feature and invert recent test decisions.
A:

2.
Q: This TODO is 34 tasks (17 P0 + 12 P1 + 5 P2) spanning Rust daemon internals, Android/Kotlin, Debian/macOS packaging, and CI YAML — substantially larger than the unit-test TODO worked through earlier this session. The spec provides a 10-stage dependency-ordered execution plan (P0 phases 1-7, then P1 in stages 8-9, then P1 packaging/docs + final quality gates in stage 10). Should I work through the full sequence autonomously, committing/pushing per task at the same cadence used for the earlier unit-test TODO, or would you like a checkpoint after all P0 tasks complete (end of stage 7) before I continue into P1/P2?
A:

3.
Q: This sandbox has no Docker, no macOS host, and no GitHub Actions runner. Concretely: P0-011's required real-process signal CI job and P1-011's package/install smoke tests can be authored and code-reviewed here but not executed against a real MQTT broker or real CI environment; P0-014/P0-015 (macOS launchd account/permission validation) and the macOS half of P1-011 can be written and shell-syntax-checked but not run against a real `_p2ptunnel` service account or `launchctl bootstrap`. The Debian package track (P0-012/P0-013, most of P1-011) can be fully built and verified locally (as was done for the original service-lifecycle work). Is it acceptable that I fully verify everything that's Linux/Rust/Android/Debian-testable locally, and explicitly report the CI-job and macOS-specific items as implemented-but-unverified (matching the spec's own "NOT RUN: exact reason" reporting discipline from P0-017/§27) rather than blocking on them?
A:

---
Fill in the `A:` lines above, then share this file back (or paste the answers) so implementation can begin.
