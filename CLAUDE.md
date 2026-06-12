# CLAUDE.md

Guidance for Claude Code working in this repository.

## Linting policy (IMPORTANT)

**Never hide or suppress lint errors or warnings — fix them.**

Do not make linters pass by silencing findings: no suppression annotations
(`@Suppress`, `#[allow(...)]`, `// ktlint-disable`, `@SuppressLint`), no baseline
files, no lowering rule severities, and no disabling/excluding rules just to get a
clean run. If something looks like a false positive, surface it and ask — do not
quietly suppress it.

This applies to every linter in the repo (Rust and Android).

> Exception that is *configuration, not suppression*: aligning a linter with a
> framework's official convention (e.g. ktlint must allow PascalCase for Jetpack
> Compose `@Composable` functions). Such config changes require explicit sign-off
> and must be documented here.

## Android linting

Android code under `android/` is linted with **all three** of these, and all must
pass with zero errors and warnings:

- **ktlint** — Kotlin formatting / style.
- **detekt** — Kotlin static analysis / code smells.
- **Android lint** — Android-specific correctness (resources, manifest, API usage).

Commands (run from `android/`):

- `./gradlew ktlintCheck`   (auto-fix what's fixable with `./gradlew ktlintFormat`)
- `./gradlew detekt`
- `./gradlew lintDebug`

`./gradlew check` runs ktlint, detekt, and Android lint together.

Current wiring status:
- Android lint: available (AGP built-in).
- ktlint: wired via the `org.jlleitschuh.gradle.ktlint` Gradle plugin
  (`gradle/libs.versions.toml`).
- detekt: wired **with type resolution**. `check` depends on the umbrella tasks
  `detektMain` (production), `detektTest` (unit tests) and `detektDebugAndroidTest`
  (instrumentation tests) in `app/build.gradle.kts`, so the rules that require type
  resolution (e.g. `InjectDispatcher`, `UseOrEmpty`) are enforced across all
  source sets. Coroutine dispatchers are injected (constructor params defaulting to
  `Dispatchers.IO`/`Default`) rather than referenced directly, per `InjectDispatcher`.

## Rust linting

All of these must be clean:

- `cargo fmt --check`
- `cargo clippy --workspace --all-targets`

Same policy: fix findings, do not add `#[allow(...)]` to silence them.

## Tests

- Rust: `cargo test --workspace`. A docker-backed E2E
  (`crates/p2p-daemon/tests/real_broker_tunnel.rs`) auto-skips when Docker is absent.
- Android: `cd android && ./gradlew testDebugUnitTest`.
- E2E harnesses live under `tests/e2e/` (see `tests/e2e/README.md`).
