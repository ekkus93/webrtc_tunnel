
## 2026-05-31T04:49:14Z - Claude Sonnet 4.6 - INT_TEST3 integration test suite added
- Added 4 new integration test files covering genuine test gaps (INT_TEST3_TODO.md):
  - `crates/p2p-crypto/tests/crypto_roundtrip.rs`: 17 tests covering identity TOML roundtrip, authorized-key trust chain, symmetric key agreement, encrypt/decrypt payloads, sign/verify, KID determinism
  - `crates/p2p-signaling/tests/timestamp_and_replay.rs`: 13 tests covering stale/future-skewed timestamp boundary, session mismatch, replay-status distinction, ACK-flag table
  - `crates/p2p-tunnel/tests/answer_frame_handling.rs`: 2 tests covering unknown_forward and forbidden_forward stream-local errors via real WebRTC data channel; added tokio dev-dep to p2p-tunnel/Cargo.toml
  - `crates/p2p-core/tests/config_parsing.rs`: 16 tests covering unknown-key rejection, each security toggle fail-closed, broker TLS validation, and ForwardTable::target_for authorization
- All 48 new tests pass; full workspace clippy clean; all existing tests still pass

## 2026-05-31T05:30:37Z - GPT-5.3-Codex - Full validation rerun surfaced current failures
- Ran `cargo clippy --workspace --all-targets --all-features -- -D warnings` (pass).
- Ran `cargo test --workspace --all-targets`; `crates/p2p-daemon/tests/two_node_daemon.rs` currently fails 5 tests:
  `active_answer_poll_failure_flips_status_and_recovers`,
  `signaling_turbulence_does_not_interrupt_active_tcp_stream`,
  `delayed_and_duplicate_delivery_do_not_cross_mutate_active_sessions`,
  `offer_daemon_accepts_next_client_after_active_connection_loss`,
  `route_scoped_drop_duplicate_stress_is_peer_isolated`.
- Ran Android validation `./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest`; connected tests fail with `ForegroundServiceDidNotStartInTimeException` in `TunnelForegroundServiceInstrumentationTest.startAnswerActionStartsAnswerPath`.
