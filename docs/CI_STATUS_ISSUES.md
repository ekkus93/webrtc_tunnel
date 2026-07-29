# Authoritative CI status issues

The repository publishes the latest state of each important GitHub Actions workflow to a fixed, always-open issue. These issues provide a stable discovery endpoint for tools that can read repository issues but cannot reliably list push-triggered workflow runs.

## Status issue map

| Workflow | Authoritative issue | Purpose |
|---|---:|---|
| `CI` | [#1 — CI Status: Main Workflow](https://github.com/ekkus93/webrtc_tunnel/issues/1) | Rust, Android, platform, E2E, and release-candidate matrix state |
| `RC diagnostics` | [#2 — CI Status: RC Diagnostics](https://github.com/ekkus93/webrtc_tunnel/issues/2) | Focused Rust formatting and `p2p-tunnel` diagnostics for `[full-signoff]` commits |
| `FIX9 broker-secret instrumentation` | [#3 — CI Status: Broker Secret Instrumentation](https://github.com/ekkus93/webrtc_tunnel/issues/3) | Android emulator proof of broker-secret owner-only permissions |

The publisher is implemented in `.github/workflows/publish-ci-status-issues.yml`.

## Published contract

Each issue contains a short human-readable summary followed by machine-readable JSON. The JSON includes:

- workflow name, ID, and workflow path;
- run ID, run number, attempt, URL, status, and conclusion;
- branch, event, and exact head commit SHA;
- failed jobs and failed step names with job IDs;
- currently active steps;
- artifact names and artifact IDs;
- compact per-job state and runner metadata;
- source-run and publisher observation timestamps.

The publisher re-fetches the canonical run state before writing the issue. It does not trust the potentially stale `workflow_run` event payload. It also rejects an event when a newer run exists for the same workflow.

## Conclusion and scope semantics

`conclusion` is the effective conclusion exposed to readers:

- `success`, `failure`, `cancelled`, and other GitHub conclusions retain their normal meaning;
- `pending` means the run has not reached a terminal conclusion;
- `not_run` means the workflow completed without executing its conditionally gated job or jobs.

For the main `CI` workflow, `scope` distinguishes how much validation actually ran:

- `metadata_only`: path detection and signoff bookkeeping ran, while Rust and Android jobs were not required;
- `path_required`: the Rust and/or Android jobs selected by changed paths ran;
- `release_candidate`: the full `[full-signoff]` or tag matrix included Docker E2E and Android-emulator E2E;
- `pending`: the run has not yet exposed enough job information to classify its scope.

For the two focused workflows, `scope` is `focused`.

A successful main-workflow run is not release-candidate evidence unless its published `scope` is `release_candidate`.

## Recommended debugging flow

1. Read the workflow's authoritative issue.
2. Verify that `head_sha` is the commit being investigated.
3. Check `status`, `conclusion`, `scope`, `failed_jobs`, and `failed_steps`.
4. Use `run_id` to retrieve the workflow jobs.
5. Use a failed job's `id` to retrieve its logs.
6. Use the published artifact IDs, or list artifacts for the run, to retrieve failure reports.

The issues are overwritten whenever the latest run changes state. They are discovery/status records, not historical logs; GitHub Actions remains the historical source.

## Security boundary

The publisher has only `actions: read`, `contents: read`, and `issues: write`. It does not check out repository code, execute artifacts, or evaluate log contents as shell commands. Keep that separation intact, especially for workflows that can run on pull-request code.
