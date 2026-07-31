# Exact-SHA Closure Automation

The repository provides an **Exact SHA Closure** GitHub Actions workflow for Ralph Loop and release-candidate closure. It creates one unique no-tree-change `[full-signoff]` commit, explicitly dispatches every required validation workflow for that commit, and publishes one final machine-readable closure status.

## Running a closure

1. Finish the implementation and evidence documents on `master`.
2. Ensure the selected TODO contains no unchecked Markdown tasks (`- [ ]`).
3. Open **Actions → Exact SHA Closure → Run workflow**.
4. Select a manifest under `.github/closure-manifests/`.
5. Usually leave `target_sha` blank. The workflow then uses the exact `master` SHA captured when the workflow starts.
6. Run the workflow once.

For the FIX9 review follow-up, use:

```text
.github/closure-manifests/fix9-review-followup.json
```

## What the workflow does

The orchestrator fails closed unless all of the following are true:

- the manifest path is confined to `.github/closure-manifests/*.json`;
- the closure ID and document paths use safe repository-relative forms;
- every required document exists;
- the TODO has no unchecked tasks;
- the manifest contains the repository's complete, ordered baseline status set;
- the requested target is a full commit SHA and is the current `master` head;
- `master` has not moved before the candidate ref update;
- the target is not already a `[full-signoff]` candidate.

It then creates a new commit with the **same tree** as the target and this message form:

```text
[full-signoff] closure: <closure-id>
```

The unique commit prevents an earlier candidate's statuses from being reused. No evidence file is changed after validation.

The orchestrator explicitly dispatches:

- `ci.yml` with `full_signoff=true` and the exact candidate SHA;
- `rc-diagnostics.yml` with the exact candidate SHA;
- `fix9-broker-secret-instrumentation.yml` with the exact candidate SHA.

The CI and diagnostic workflows reject a dispatched run whose event SHA differs from the requested SHA. Their checkout and status-publication targets are also bound to that SHA.

## Required status contexts

A closure manifest cannot remove or reorder these gates:

```text
ci/rc-diagnostics
ci/full-matrix
ci/release-candidate
ci/broker-secret-instrumentation
```

After all four are successful on the candidate, the orchestrator posts:

```text
closure/<closure-id> = success
```

The current closure state is also published to issue **#5, CI Status: Exact SHA Closure**.

## Failure behavior

The workflow does not automatically retry a failed, cancelled, or timed-out gate. It posts a failed closure status and stops when:

- `master` moves away from the candidate;
- any required status reports `failure` or `error`;
- a required status is still missing or pending at the two-hour timeout;
- candidate creation or workflow dispatch fails.

A failed closure attempt remains authoritative evidence for that SHA. Fix the underlying defect in a new implementation commit and run the closure workflow again. Do not rerun the failed validation until green without a corrective commit.

## Adding another closure

Add a JSON manifest with schema version 1:

```json
{
  "schema_version": 1,
  "closure_id": "example-closure",
  "todo_path": "docs/EXAMPLE_TODO.md",
  "required_documents": [
    "docs/EXAMPLE_TODO.md",
    "docs/review-source/EXAMPLE_IMPLEMENTATION_REPORT.md"
  ],
  "required_statuses": [
    "ci/rc-diagnostics",
    "ci/full-matrix",
    "ci/release-candidate",
    "ci/broker-secret-instrumentation"
  ]
}
```

The baseline statuses are deliberately fixed in `scripts/exact_sha_closure.py`; changing only a manifest cannot weaken closure policy.
