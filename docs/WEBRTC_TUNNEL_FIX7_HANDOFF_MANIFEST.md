# WebRTC Tunnel FIX7 Handoff Manifest

**Created:** 2026-07-20  
**Target:** Existing `webrtc_tunnel` repository based on the reviewed archive `webrtc_tunnel-master_2607201054.zip`.

Copy the `docs/` directory from this package into the repository root, preserving paths. The package intentionally mirrors repository paths.

## Included files

| Repository path | Purpose | SHA-256 |
|---|---|---|
| `docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_SPEC.md` | Binding FIX7 implementation specification | `d39fe0ecad7381f4492430dcd310b07ef9b83a8dddee0d46f38b7a7875effef0` |
| `docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_TODO.md` | Executable task/subtask checklist with target code and exact tests | `34e4c8c361fb08db76b82bb72b8df7fcb31f68952142790820da3e1eb609b2fc` |
| `docs/review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md` | Complete source review that produced FIX7 | `2bc4d9f9851661ff50cd74c0283cb0cad4f3a556f51d2d3cc3acfd278c24957e` |
| `docs/review-source/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md` | This manifest | Not self-hashed to avoid a recursive checksum |

## Reference integrity

- The specification references the TODO and review report at the exact paths included above.
- The TODO references the specification and review report at the exact paths included above.
- The review report's uploaded checklist name `WEBRTC_TUNNEL_STATE_INTEGRITY_FAILURE_VISIBILITY_FIX6_TODO(1).md` was byte-for-byte identical to the repository file `docs/WEBRTC_TUNNEL_STATE_INTEGRITY_FAILURE_VISIBILITY_FIX6_TODO.md` at review time.
- No baseline source archive is included in this handoff package. Claude Code should apply the documents to the existing repository checkout, not create a new repository from this package.
- Earlier FIX6 documents are already present in the reviewed repository and are historical context only; FIX7 restates its binding decisions.

## Recommended first instruction to Claude Code

```text
Read docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_SPEC.md,
docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_TODO.md,
and docs/review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md.
Treat the FIX7 spec as binding, execute the TODO in its stated order, write the exact negative-path tests before each production change, and do not mark a checkbox complete until the named behavior is directly proven and the focused checks pass.
```
