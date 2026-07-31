# WebRTC Tunnel — Exact-SHA Closure Automation Implementation Report

## Purpose

Automate the final Ralph Loop closure step without accepting statuses from a parent/sibling commit, mutating evidence after validation, relying on `GITHUB_TOKEN` push recursion, or automatically retrying failed gates.

## Implemented components

- `.github/workflows/exact-sha-closure.yml`
  - validates a closure manifest and current `master` target;
  - creates a unique same-tree `[full-signoff]` commit through the Git data API;
  - advances `master` only after a second compare-and-swap-style head check;
  - explicitly dispatches the main CI, RC diagnostic, and broker instrumentation workflows;
  - waits for the exact candidate's required commit statuses;
  - fails if `master` moves, a status fails, or the timeout expires;
  - publishes `closure/<closure-id>` and updates issue #5;
  - can start manually or from a successful `master` CI run whose request commit contains `[request-exact-sha-closure]`;
  - does not recurse because generated candidates contain `[full-signoff]`, not the request marker.
- `scripts/exact_sha_closure.py`
  - validates safe manifest/document paths and complete TODO state;
  - prevents a manifest from weakening the fixed status baseline;
  - selects the newest GitHub status for each context;
  - aggregates success/pending/failure fail-closed;
  - publishes final status and issue evidence.
- `scripts/test_exact_sha_closure.py`
  - covers valid manifests, unchecked TODO rejection, gate-removal rejection, missing/pending statuses, failure propagation, and newest-status selection.
- `.github/closure-manifests/fix9-review-followup.json`
  - binds the FIX9 review follow-up TODO/evidence to the complete closure gate set.
- `.github/workflows/ci.yml`
  - supports explicit full-signoff dispatch on an expected SHA;
  - rejects dispatch/event SHA mismatches;
  - checks out and posts statuses against the requested SHA;
  - runs the exact-SHA helper unit tests in the ordinary lint gate.
- `.github/workflows/rc-diagnostics.yml`
  - supports exact-SHA workflow dispatch and exact-target status publication.
- `.github/workflows/fix9-broker-secret-instrumentation.yml`
  - supports exact-SHA dispatch and publishes `ci/broker-secret-instrumentation` from the real emulator job result.

## Security and integrity decisions

- A closure candidate is a unique commit even when the tree is unchanged.
- The workflow rechecks `master` immediately before advancing the ref.
- No force update is used.
- Required status contexts are fixed in code and must match the manifest exactly.
- Missing statuses never count as success.
- A newer failed status for a context cannot be hidden by an older successful status.
- No validation failure is retried automatically.
- No post-success repository commit is created.
- Tag-only Rust artifact publication remains separate from commit-level closure.

## Validation contract

The automation is complete only after its own implementation commit passes ordinary CI and an Exact SHA Closure run creates a candidate for which all four required contexts and `closure/fix9-review-followup` report success.
