---
name: lint-n-test
description: Run all linters and tests for the project
model: haiku
---

# lint-n-test

Run all linters and all unit tests for both Rust and Android code.

## Run the lints and tests

Run these in parallel:

1. **Rust lint:** `cargo fmt --check` and `cargo clippy --all-targets --all-features` (from repo root)
2. **Rust tests:** `cargo test --workspace` (from repo root)
3. **Android lint+tests:** `./gradlew check` (from `android/`)

`./gradlew check` covers ktlint, detekt, lintDebug, and unit tests together.

## Report

- If everything passes: report "all clean"
- If anything fails: report the failing command(s) and the error output
- Do NOT add suppressions or skip rules to hide errors
