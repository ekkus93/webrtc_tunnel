
## 2026-05-31T04:49:14Z - Claude Sonnet 4.6 - INT_TEST3 integration test suite added
- Added 4 new integration test files covering genuine test gaps (INT_TEST3_TODO.md):
  - `crates/p2p-crypto/tests/crypto_roundtrip.rs`: 17 tests covering identity TOML roundtrip, authorized-key trust chain, symmetric key agreement, encrypt/decrypt payloads, sign/verify, KID determinism
  - `crates/p2p-signaling/tests/timestamp_and_replay.rs`: 13 tests covering stale/future-skewed timestamp boundary, session mismatch, replay-status distinction, ACK-flag table
  - `crates/p2p-tunnel/tests/answer_frame_handling.rs`: 2 tests covering unknown_forward and forbidden_forward stream-local errors via real WebRTC data channel; added tokio dev-dep to p2p-tunnel/Cargo.toml
  - `crates/p2p-core/tests/config_parsing.rs`: 16 tests covering unknown-key rejection, each security toggle fail-closed, broker TLS validation, and ForwardTable::target_for authorization
- All 48 new tests pass; full workspace clippy clean; all existing tests still pass
