# INT_TEST3_TODO.md

# Rust Integration Test Expansion TODO 3

## Goal

Add four new integration test files covering genuine gaps in the Rust test suite.
Each file tests multi-component flows that existing unit or inline `#[cfg(test)]` modules do not cover.

| New file | Scope |
|---|---|
| `crates/p2p-crypto/tests/crypto_roundtrip.rs` | Identity generation + key agreement + encrypt/decrypt + authorized-key trust chain |
| `crates/p2p-signaling/tests/timestamp_and_replay.rs` | Stale/future-skewed message rejection + replay-status distinction + EndOfCandidates + ACK flags |
| `crates/p2p-tunnel/tests/answer_frame_handling.rs` | Answer-side stream authorization (unauthorized/unknown forward_id → stream-local error, session survives) |
| `crates/p2p-core/tests/config_parsing.rs` | Full TOML config load → validate pipeline; security-toggle fail-closed; unknown-key rejection |

## What is already covered (do NOT re-implement)

- `p2p-crypto` inline `crypto.rs` unit tests: `sign_message`/`verify_message` tamper detection, KDF determinism, `derive_aead_key` basic path, `decrypt_message` tampered-AAD and tampered-ciphertext.
- `p2p-crypto/tests/identity_parsing.rs`: valid identity file parse, mismatched public key rejection, weak-permission rejection, `authorized_keys` valid+invalid lines, missing identity file error.
- `p2p-signaling/tests/mock_mqtt_roundtrip.rs`: codec roundtrip, two-node signaling roundtrip, session setup + ICE candidate exchange, ICE failure error path, unauthorized-peer rejection, replay rejection.
- `p2p-tunnel/src/multiplex.rs` inline tests: stream-ID allocator, stream manager, forward-table lookups, full multiplex open/data/close handshake, TCP target connect failure, two-forward isolation, multiple streams on one forward, persistent session after zero streams, offer OPEN-ACK validation.
- `p2p-daemon/tests/two_node_daemon.rs`: full two-daemon session lifecycle, reconnect, ICE restart, multi-peer churn, fault injection, per-forward allowlist isolation.

## Guardrails

- All new tests must be deterministic and offline (no live MQTT broker, no STUN).
- Do not weaken security invariants; tests must enforce them, not work around them.
- Use real in-process TCP listeners for tunnel tests; do not mock the TCP stack.
- Prefer `Result`-based assertions over `unwrap`/`expect` in test bodies where clarity allows.
- Run `cargo clippy --workspace --all-targets --all-features -- -D warnings` and `cargo test --workspace --all-targets` at each phase boundary; fix all warnings.

---

## Phase 0 — Baseline and pre-flight

### 0.1 Confirm existing tests still pass

- [x] Run `cargo test --workspace --all-targets` and record pass count before any changes.
- [x] Run `cargo clippy --workspace --all-targets --all-features -- -D warnings` and confirm zero warnings.
- [x] Note exact test counts per crate as a regression baseline.

### 0.2 Identify shared test-helper needs

- [x] Confirm `generate_identity` + `AuthorizedKeys::parse` are reachable from external test files in `p2p-crypto`.
- [x] Confirm `SignalCodec`, `ReplayCache`, `InnerMessageBuilder`, `ReplayStatus`, `SessionId`, `MsgId` are all re-exported from `p2p_signaling` (they are via `lib.rs`).
- [x] Confirm `p2p_webrtc::WebRtcPeer` and `DataChannelHandle` are reachable as dev-dependencies from `p2p-tunnel` external tests.
- [x] Confirm `AppConfig::load_from_file` and `AppConfig::validate` are accessible from `p2p-core` external tests.
- [x] Check whether any helpers need to be gated under `#[cfg(test)]` or added to `[dev-dependencies]` in the relevant `Cargo.toml`.

### 0.3 Dependency audit

- [x] Review `crates/p2p-crypto/Cargo.toml` — confirm `tempfile` is available as a dev-dependency (needed for permission tests in Phase 1).
- [x] Review `crates/p2p-signaling/Cargo.toml` — confirm `tokio` with `macros` + `rt` features is available for async test helpers.
- [x] Review `crates/p2p-tunnel/Cargo.toml` — confirm `p2p-webrtc` is already a regular dependency (it is; `DataChannelHandle` is used in `multiplex.rs`).
- [x] Review `crates/p2p-core/Cargo.toml` — confirm `tempfile` and `serde_json` are available or add them as dev-dependencies.

---

## Phase 1 — `p2p-crypto` identity and crypto roundtrip integration tests

**New file:** `crates/p2p-crypto/tests/crypto_roundtrip.rs`

**Why these are NOT already covered:** The existing inline unit tests operate on individual crypto
primitives with fixed keys. These tests combine `generate_identity`, `IdentityFile`,
`PublicIdentity`, `AuthorizedKeys`, and the crypto functions into multi-step flows that exercise
the full trust chain.

### 1.1 Identity generation and TOML roundtrip

- [x] `generate_identity_produces_parseable_identity_file`
  - Call `generate_identity("alice")`.
  - Call `generated.identity.render_toml()` to get TOML string.
  - Parse back via `IdentityFile::from_toml(&toml)`.
  - Assert `peer_id` matches `"alice"`.
  - Assert `signing_kid()` of the parsed file equals `signing_kid()` of the original.

- [x] `render_toml_then_from_toml_preserves_signing_and_kex_keys`
  - Generate identity, render to TOML, parse back.
  - Sign a test message with the original signing key.
  - Verify the signature with the parsed file's verifying key.
  - Assert verification succeeds (end-to-end key round-trip).

- [x] `generate_identity_rejects_empty_peer_id`
  - Call `generate_identity("")` and assert it returns `Err`.

### 1.2 Public identity → authorized-keys trust chain

- [x] `public_identity_renders_as_valid_authorized_key_line`
  - Generate identity for `"bob"`.
  - Call `generated.public_identity.render()` to produce an authorized-key line.
  - Parse that line via `AuthorizedKeys::parse(&line)`.
  - Assert the result contains exactly one entry.
  - Assert `get_by_peer_id("bob")` returns the entry.
  - Assert `get_by_kid(&bob_kid)` returns the same entry (KID derived from the generated
    verifying key).

- [x] `authorized_keys_lookup_by_kid_after_two_peer_generate`
  - Generate identities for `"alice"` and `"bob"`.
  - Build an authorized-keys string with both public identity lines.
  - Parse with `AuthorizedKeys::parse`.
  - Assert `get_by_kid` finds each peer by their own KID.
  - Assert `get_by_kid` for a random `Kid::new([0u8; 32])` returns `None`.

- [x] `duplicate_peer_id_in_authorized_keys_is_rejected`
  - Generate one identity and render its public line twice.
  - Parse a string containing the duplicate lines.
  - Assert `AuthorizedKeys::parse` returns `Err`.

- [x] `comments_and_blank_lines_in_authorized_keys_are_ignored`
  - Build an authorized-keys string with comment lines (`# comment`) and blank lines interspersed.
  - Parse and assert no error; only real entries are returned.

### 1.3 Symmetric two-party key agreement

- [x] `symmetric_key_agreement_sender_and_recipient_derive_same_aead_key`
  - Generate ephemeral secret for Alice (sender).
  - Generate static kex secret for Bob (recipient).
  - Derive AEAD key on Alice's side via `derive_aead_key(&alice_eph, &bob_pub, &alice_kid, &bob_kid, &msg_id)`.
  - Derive AEAD key on Bob's side by performing ECDH and calling
    `derive_aead_key_from_shared_secret(...)` with the same KIDs and `msg_id`.
  - Assert the two derived keys are equal.

- [x] `different_msg_id_produces_different_key`
  - Same ECDH parties; derive key for two distinct `MsgId::random()` values.
  - Assert the two keys differ (the `msg_id` is bound into the HKDF info).

- [x] `swapped_sender_recipient_kid_order_produces_different_key`
  - Derive key with `(alice_kid, bob_kid)` and separately with `(bob_kid, alice_kid)`.
  - Assert the two keys differ (KID order is bound into HKDF salt).

### 1.4 Encrypt / decrypt roundtrip across varied payloads

- [x] `encrypt_decrypt_roundtrip_empty_payload`
  - Derive a valid AEAD key (from two-party agreement above).
  - Call `encrypt_message` with empty plaintext; call `decrypt_message`; assert output is empty.

- [x] `encrypt_decrypt_roundtrip_single_byte_payload`
  - Same flow with a single-byte plaintext `[0xAB]`.

- [x] `encrypt_decrypt_roundtrip_large_payload`
  - Generate a 256 KB payload of repeating bytes.
  - Encrypt and decrypt; assert decoded bytes equal the original.

- [x] `decrypt_with_wrong_key_returns_error`
  - Encrypt with key derived from proper ECDH; decrypt with `[0xFF_u8; 32]`.
  - Assert the result is `Err(CryptoError::Decryption)`.

- [x] `decrypt_with_wrong_nonce_returns_error`
  - Encrypt with a random nonce; attempt decrypt with `[0x00_u8; 24]`.
  - Assert the result is `Err`.

### 1.5 Sign / verify across identity boundaries

- [x] `cross_identity_sign_then_verify`
  - Generate two identities (Alice and Bob).
  - Sign a message with Alice's signing key.
  - Verify with Alice's verifying key — assert `Ok`.
  - Verify the same signature with Bob's verifying key — assert `Err`.

- [x] `kid_is_deterministic_for_generated_identity`
  - Generate identity, compute `kid_from_signing_key(&generated.public_identity.sign_public)`.
  - Assert it equals `generated.identity.signing_kid()`.
  - Assert it equals the KID recovered from the TOML-roundtripped identity.

### 1.6 Phase validation

- [x] Run `cargo test -p p2p-crypto` and confirm all new tests pass.
- [x] Run `cargo clippy -p p2p-crypto --all-targets --all-features -- -D warnings` with zero warnings.
- [x] Run `cargo test --workspace --all-targets` to confirm no regressions.
- [x] Commit and push.

---

## Phase 2 — `p2p-signaling` timestamp validation and replay-status edge cases

**New file:** `crates/p2p-signaling/tests/timestamp_and_replay.rs`

**Why these are NOT already covered:** `mock_mqtt_roundtrip.rs` tests that a replayed message is
rejected but does NOT test stale-timestamp rejection, future-skewed-timestamp rejection, or the
three-way `ReplayStatus` distinction (`Fresh` / `DuplicateSameSession` /
`DuplicateDifferentSession`) returned by `decode_with_replay_status`. It also does not test
`EndOfCandidates` codec or verify that the `ack_required` envelope flag is correct for every
message type.

### 2.1 Two-peer codec test helper

- [x] Implement `make_two_peer_codecs()` returning two `SignalCodec` instances + their authorized-key entries:
  - Generate identities for `"alice"` and `"bob"`.
  - Build `AuthorizedKeys` for Alice containing only Bob's key; likewise for Bob.
  - Construct `SignalCodec` for each with `max_clock_skew_secs = 5` and `max_message_age_secs = 30`.
  - Return the two codecs and each peer's `AuthorizedKey` (needed for `encode_for_peer`).
  - This helper is used in every test in Phase 2 to avoid repeating identity setup.

### 2.2 Stale and future-skewed message rejection

- [x] `stale_message_is_rejected`
  - Use `make_two_peer_codecs()` with `max_message_age_secs = 1`.
  - Encode a message from Alice to Bob (timestamp = now).
  - In the `ReplayCache::check_and_record_status` call, pass a synthetic `ReplayCheck`
    with `now_ms` advanced by 2 000 ms past the message `timestamp_ms`.
  - Assert the result is `Err` (message too old).

- [x] `future_skewed_message_is_rejected`
  - Encode a message from Alice (timestamp = now).
  - Pass a synthetic `ReplayCheck` where `now_ms` is set to
    `timestamp_ms - (max_clock_skew_secs + 1) * 1_000` (clock appears far behind message).
  - Assert the result is `Err`.

- [x] `message_within_clock_skew_window_is_accepted`
  - Pass a `ReplayCheck` where `now_ms` equals `timestamp_ms - max_clock_skew_secs * 1_000`
    (exactly at the tolerance boundary).
  - Assert the result is `Ok(ReplayStatus::Fresh)`.

### 2.3 Replay-status distinction

These tests use `ReplayCache::check_and_record_status` directly with a fixed `SessionId` and
`MsgId` to isolate the replay logic from full encode/decode overhead.

- [x] `first_check_returns_fresh`
  - Create a fresh `ReplayCache`.
  - Call `check_and_record_status` for `(sender_kid, msg_id, session_id)`.
  - Assert `Ok(ReplayStatus::Fresh)`.

- [x] `second_check_same_session_returns_duplicate_same_session`
  - Record the same `(sender_kid, msg_id)` for session S1 twice.
  - First call: `Fresh`.
  - Second call: `Ok(ReplayStatus::DuplicateSameSession)`.

- [x] `second_check_different_session_returns_duplicate_different_session`
  - Record `(sender_kid, msg_id)` for session S1.
  - Record the same `(sender_kid, msg_id)` for session S2 (different `SessionId`).
  - Assert second call returns `Ok(ReplayStatus::DuplicateDifferentSession)`.

- [x] `different_sender_kid_same_msg_id_is_fresh`
  - Record `(alice_kid, msg_id_X)` for session S1.
  - Record `(bob_kid, msg_id_X)` for session S1.
  - Assert second call returns `Fresh` (different sender → not a replay).

### 2.4 EndOfCandidates codec roundtrip

- [x] `end_of_candidates_encodes_and_decodes`
  - Use `make_two_peer_codecs()`.
  - Build an `EndOfCandidates` `InnerMessage` via `InnerMessageBuilder`.
  - Encode via Alice's `SignalCodec::encode_for_peer`.
  - Decode via Bob's `SignalCodec::decode`.
  - Assert `message.message_type == MessageType::EndOfCandidates`.

- [x] `end_of_candidates_does_not_require_ack`
  - After encoding, assert `envelope.flags.ack_required == false`.
  - Assert `MessageType::EndOfCandidates.requires_ack()` returns `false`.

### 2.5 ACK-required and non-ACK-required message-type flag coverage

- [x] `ack_required_flag_set_for_all_ack_requiring_types`
  - For each of `Offer`, `Answer`, `IceCandidate`, `Close`, `Error`, `IceRestartRequest`,
    `RenegotiateRequest`: assert `message_type.requires_ack()` is `true`.

- [x] `ack_required_flag_clear_for_non_ack_types`
  - For each of `Ack`, `Ping`, `Pong`, `Hello`, `EndOfCandidates`:
    assert `message_type.requires_ack()` is `false`.

### 2.6 Phase validation

- [x] Run `cargo test -p p2p-signaling` and confirm all new tests pass.
- [x] Run `cargo clippy -p p2p-signaling --all-targets --all-features -- -D warnings` with zero warnings.
- [x] Run `cargo test --workspace --all-targets` to confirm no regressions.
- [x] Commit and push.

---

## Phase 3 — `p2p-tunnel` answer-side stream authorization and frame protocol

**New file:** `crates/p2p-tunnel/tests/answer_frame_handling.rs`

**Why these are NOT already covered:** The inline `multiplex.rs` tests cover the happy-path OPEN
handshake, target TCP connect failure, and multi-forward isolation but do NOT test (a) what happens
when the answer side receives an OPEN frame for a `forward_id` that is completely absent from the
`ForwardTable`, or (b) what happens when the connecting `remote_peer_id` is not in the allowlist
for the requested forward. Both cases should produce a stream-local `Error` frame and leave the
WebRTC session alive for subsequent streams.

### 3.1 WebRTC data-channel helper for external test file

- [x] Implement `connected_channels()` helper in the test file:
  - Creates a `WebRtcPeer` pair (offer + answer) with a minimal `WebRtcConfig`.
  - Creates a data channel on the offer peer, exchanges SDP, and waits for both sides to be open.
  - Returns `(offer_channel: DataChannelHandle, answer_channel: DataChannelHandle)` plus the two `WebRtcPeer` handles to keep them alive.

- [x] Implement `spawn_echo_target() -> (u16, JoinHandle<()>)`:
  - Binds a `TcpListener` on `127.0.0.1:0`.
  - Spawns a task that accepts one connection and echoes received bytes back.
  - Returns the bound port and the task handle.

- [x] Implement `forward_table_for(forward_id: &str, port: u16, authorized_peers: &[&str]) -> ForwardTable`:
  - Creates a `ForwardTable` with a single `ForwardRule` for the given `forward_id`
    targeting `127.0.0.1:<port>` with the provided peer allowlist.
  - Empty slice means all peers are allowed (open allowlist).

### 3.2 Unknown forward_id → stream-local error, session survives

- [x] `unknown_forward_id_sends_error_frame_and_session_remains_alive`
  - Build channels via `connected_channels()`.
  - Build a `ForwardTable` containing only `forward_id = "ssh"` (no `"web"` entry).
  - Spawn `run_multiplex_answer(answer_channel, ...)` in a task.
  - On the offer channel, manually encode and send `TunnelFrame::open(1, OpenPayload { forward_id: "web".into() })`.
  - Assert the offer channel receives a `TunnelFrameType::Error` frame for stream 1 within a timeout.
  - After receiving the error, send `TunnelFrame::open(2, OpenPayload { forward_id: "ssh".into() })` for the known forward (with a real TCP target listening).
  - Assert stream 2 receives a `TunnelFrameType::Open` ACK (empty payload) within a timeout.
  - Assert the `run_multiplex_answer` task has not exited yet (session still alive).

### 3.3 Unauthorized peer → stream-local error, session survives

- [x] `unauthorized_peer_for_forward_sends_error_frame_and_session_remains_alive`
  - Build a `ForwardTable` with `forward_id = "ssh"` and `authorized_peers = ["alice"]`.
  - Spawn `run_multiplex_answer` with `remote_peer_id = "bob"` (not in allowlist).
  - Send `TunnelFrame::open(1, OpenPayload { forward_id: "ssh".into() })`.
  - Assert the answer side sends back a `TunnelFrameType::Error` frame for stream 1.
  - Assert the session channel is still open (no `DataChannelEvent::Closed`).
  - Assert `run_multiplex_answer` task has not exited.

### 3.4 One authorized stream + one unauthorized stream: only unauthorized fails

- [x] `authorized_and_unauthorized_streams_are_independently_handled`
  - Build a `ForwardTable` with two entries:
    - `"ssh"` with `authorized_peers = ["alice"]`
    - `"web"` not present (unknown forward).
  - Spawn `run_multiplex_answer` with `remote_peer_id = "alice"`.
  - Spawn a real echo target for the `"ssh"` forward.
  - Send OPEN for stream 1 (`"ssh"`) — authorized.
  - Send OPEN for stream 2 (`"web"`) — unknown.
  - Assert stream 1 receives OPEN ACK within a timeout.
  - Assert stream 2 receives an `Error` frame within a timeout.
  - Assert the session remains alive after both events.
  - Send a `TunnelFrameType::Data` frame for stream 1 and assert the echo reply comes back.

### 3.5 Phase validation

- [x] Run `cargo test -p p2p-tunnel` and confirm all new tests pass.
- [x] Run `cargo clippy -p p2p-tunnel --all-targets --all-features -- -D warnings` with zero warnings.
- [x] Run `cargo test --workspace --all-targets` to confirm no regressions.
- [x] Commit and push.

---

## Phase 4 — `p2p-core` config TOML parse and validate integration tests

**New file:** `crates/p2p-core/tests/config_parsing.rs`

**Why these are NOT already covered:** There is no external test file for `p2p-core`.
`AppConfig::validate()` enforces many security invariants that are not exercised by any test today.
Config parsing tests catch regressions introduced by TOML format changes and ensure the fail-closed
behavior of security toggles is enforced.

### 4.1 Test fixture infrastructure

- [x] Add `tempfile` as a dev-dependency to `crates/p2p-core/Cargo.toml` if not already present.
- [x] Add `p2p-crypto` as a dev-dependency to `crates/p2p-core/Cargo.toml` (to generate real identity and public-identity lines for the authorized_keys fixture).
- [x] Implement `write_temp_file(dir: &Path, name: &str, content: &str) -> PathBuf` helper.
- [x] Implement `make_config_fixture()` returning a `TempDir` and a minimal valid TOML config string with:
  - `format = "p2ptunnel-config-v3"` (or current version string from `config.rs`).
  - `paths.identity` pointing to a real 0600 temp file containing a generated identity.
  - `paths.authorized_keys` pointing to a real temp file with one public-identity line.
  - `paths.state_dir` and `paths.log_dir` pointing to real existing temp directories.
  - `logging.log_file` pointing to a path under `log_dir`.
  - `health.status_file` pointing to a path under `state_dir`.
  - `broker.url = "mqtts://test.example.com:8883"`.
  - `broker.tls.ca_file` pointing to a real temp file.
  - All security toggles at their required-enabled values.
  - `logging.log_rotation = "none"`, `health.status_socket = ""`.
  - A valid `node.role`, `node.peer_id`, `node.topic_prefix`.

### 4.2 Valid config parses and validates cleanly

- [x] `valid_config_parses_without_error`
  - Write a minimal valid config via `make_config_fixture()` to a temp file.
  - Call `AppConfig::load_from_file(&path)` and assert `Ok`.

- [x] `valid_config_validates_without_error`
  - `load_from_file` then `config.validate()` — assert `Ok`.

- [x] `config_format_field_must_match_expected_version`
  - Replace the `format` value with `"p2ptunnel-config-v2"`.
  - Assert `validate()` returns `Err` containing a message about the format.

### 4.3 Unknown keys with strict mode rejected

- [x] `unknown_top_level_key_is_rejected_at_parse_time`
  - Append `unknown_key = "value"` to the valid config TOML.
  - Call `AppConfig::load_from_file` and assert `Err`
    (rejected at deserialization by `deny_unknown_fields`).

- [x] `unknown_nested_key_is_rejected`
  - Add `[broker]\nunknown_field = true` to the TOML.
  - Assert `load_from_file` returns `Err`.

### 4.4 Security toggles cannot be disabled (fail-closed)

Each test takes the minimal valid config, overrides one security field to its disabled value,
calls `validate()`, and asserts `Err`.

- [x] `require_mqtt_tls_false_is_rejected`
- [x] `require_message_encryption_false_is_rejected`
- [x] `require_message_signatures_false_is_rejected`
- [x] `require_authorized_keys_false_is_rejected`
- [x] `reject_unknown_config_keys_false_is_rejected`
- [x] `replay_cache_size_zero_is_rejected`
- [x] `insecure_skip_verify_is_rejected`

### 4.5 Broker URL and TLS validation

- [x] `non_mqtts_url_is_rejected`
  - Set `broker.url = "mqtt://broker.example.com:1883"` (plaintext).
  - Assert `validate()` returns `Err`.

- [x] `password_file_without_username_is_rejected`
  - Set `broker.password_file` to a real temp path; leave `broker.username` empty.
  - Assert `validate()` returns `Err`.

- [x] `client_cert_without_client_key_is_rejected`
  - Set `broker.tls.client_cert_file` to a real path; leave `broker.tls.client_key_file` empty.
  - Assert `Err`.

- [x] `client_key_without_client_cert_is_rejected`
  - Converse: set client key but not cert; assert `Err`.

### 4.6 Frozen v0.2/v0.3 runtime knob validation

- [x] `non_none_log_rotation_is_rejected`
  - Set `logging.log_rotation = "daily"`; assert `Err`.

- [x] `non_empty_status_socket_is_rejected`
  - Set `health.status_socket = "/tmp/p2p.sock"`; assert `Err`.

- [x] `hold_local_client_during_reconnect_true_is_rejected`
  - Set `reconnect.hold_local_client_during_reconnect = true`; assert `Err`.

- [x] `nonzero_local_client_hold_secs_is_rejected`
  - Set `reconnect.local_client_hold_secs = 5`; assert `Err`.

### 4.7 ForwardTable authorization behavior

- [x] `forward_table_with_no_forwards_returns_empty_offer_listeners`
  - Build `ForwardTable::new(&[])`.
  - Assert `offer_listeners()` returns empty list without error.

- [x] `forward_table_unknown_id_returns_none`
  - Build a `ForwardTable` with one rule for `"ssh"`.
  - Call `get("web")` and assert `None`.

- [x] `forward_table_unauthorized_peer_returns_error`
  - Build a rule for `"ssh"` with `authorized_peers = ["alice"]`.
  - Call `target_for("ssh", &bob_peer_id)` and assert `Err(ForwardLookupError::UnauthorizedPeer)`.

- [x] `forward_table_authorized_peer_returns_target`
  - Same rule; call `target_for("ssh", &alice_peer_id)` and assert `Ok` with expected host/port.

### 4.8 Phase validation

- [x] Run `cargo test -p p2p-core` and confirm all new tests pass.
- [x] Run `cargo clippy -p p2p-core --all-targets --all-features -- -D warnings` with zero warnings.
- [x] Run `cargo test --workspace --all-targets` to confirm no regressions.
- [x] Commit and push.

---

## Cross-cutting completion tasks

### C.1 Final workspace validation

- [x] Run `cargo fmt --check`.
- [x] Run `cargo clippy --workspace --all-targets --all-features -- -D warnings` — zero warnings.
- [x] Run `cargo test --workspace --all-targets` — all tests pass.

### C.2 Status updates

- [x] Mark all completed subtasks `[x]` in this file as they are implemented.
- [x] Update `memory.md` with a timestamped entry summarizing what was added.

### C.3 Commit and push

- [x] Stage all new test files and any `Cargo.toml` dev-dependency additions.
- [x] Commit with message summarizing the four new integration test files and their scope.
- [x] Push to `origin/android-app`.

---

## Acceptance checklist

Mark complete only when all are true:

- [x] `crates/p2p-crypto/tests/crypto_roundtrip.rs` exists; all tests pass; covers identity roundtrip, two-party key agreement, encrypt/decrypt, sign/verify, and authorized-keys trust chain.
- [x] `crates/p2p-signaling/tests/timestamp_and_replay.rs` exists; all tests pass; covers stale rejection, future-skewed rejection, `ReplayStatus::Fresh` / `DuplicateSameSession` / `DuplicateDifferentSession` distinction, EndOfCandidates codec, and ACK-flag correctness for every `MessageType`.
- [x] `crates/p2p-tunnel/tests/answer_frame_handling.rs` exists; all tests pass; covers unknown-forward-id stream-local error, unauthorized-peer stream-local error, and session survival after both.
- [x] `crates/p2p-core/tests/config_parsing.rs` exists; all tests pass; covers valid config roundtrip, unknown-key rejection, every security-toggle fail-closed check, broker/TLS validation, frozen knob validation, and `ForwardTable` authorization.
- [x] Full workspace `clippy` and `test` clean with zero warnings.
- [x] Changes committed and pushed.
