---
description: Lint all Rust and Android code and run the full test suite, reporting a PASS/FAIL summary for each gate. Use only when the user explicitly invokes this skill.
model: haiku
effort: low
disable-model-invocation: true
allowed-tools:
  - Bash(cargo fmt *)
  - Bash(cargo clippy *)
  - Bash(cargo test *)
  - Bash(cd android *)
  - Bash(./gradlew *)
---

# Lint and Test

Run every linter and test suite in this repo and report a clear PASS/FAIL summary. Never suppress a finding to make a gate pass (no `#[allow(...)]`, no ktlint/detekt suppressions, no baseline files) — see the repo's `CLAUDE.md` linting policy. Report findings; do not silently fix them unless the user asks afterward.

## Steps

### 1. Rust formatting and lint

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets
```

### 2. Rust tests

```bash
cargo test --workspace
```

### 3. Android lint (ktlint, detekt, Android lint)

```bash
cd android && ./gradlew check
```

### 4. Android unit tests

```bash
cd android && ./gradlew testDebugUnitTest
```

## Output

Report one PASS/FAIL line per gate:

- Rust fmt
- Rust clippy
- Rust tests
- Android lint (ktlint/detekt/lint)
- Android unit tests

For any FAIL, include the relevant error output. If a gate could not be run (e.g. Android SDK unavailable), report `NOT RUN: <exact reason>` instead of guessing.
