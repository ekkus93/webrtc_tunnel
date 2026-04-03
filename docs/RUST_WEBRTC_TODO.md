# TODO.md - Secure Rust P2P Tunnel Implementation Plan

This TODO list is intended for implementation by GitHub Copilot or another coding agent. It assumes the design in `SPECS.md` is authoritative.

## 0. Ground rules

- Implement a **CLI-only** application
- Do **not** add any GUI code
- Treat all MQTT as untrusted transport
- All MQTT messages must be encrypted and signed
- Use SSH-like key management workflow
- Do not introduce PEM/PuTTY workflows in the normal path
- STUN only in v1; no TURN
- One active tunnel at a time
- Fail closed on any security error

---

## 1. Workspace bootstrap

### 1.1 Create Rust workspace
- [x] Create top-level Cargo workspace
- [x] Add crate members:
  - [x] `crates/p2p-core`
  - [x] `crates/p2p-crypto`
  - [x] `crates/p2p-signaling`
  - [x] `crates/p2p-webrtc`
  - [x] `crates/p2p-tunnel`
  - [x] `crates/p2p-daemon`
  - [x] `bins/p2p-offer`
  - [x] `bins/p2p-answer`
  - [x] `bins/p2pctl`
- [x] Set edition to current stable target
- [x] Add shared lint config
- [x] Add formatting config

### 1.2 Add dependencies
- [x] `tokio`
- [x] `webrtc`
- [x] `rumqttc`
- [x] `ed25519-dalek`
- [x] `x25519-dalek`
- [x] `chacha20poly1305`
- [x] `hkdf`
- [x] `sha2`
- [x] `serde`
- [x] `serde_cbor`
- [x] `toml`
- [x] `tracing`
- [x] `tracing-subscriber`
- [x] `secrecy`
- [x] `zeroize`
- [x] `bytes`
- [x] `rand_core`
- [x] `thiserror`
- [x] `clap`

---

## 2. `p2p-core`

### 2.1 Common identifiers and constants
- [x] Define `PeerId`
- [x] Define `SessionId` as 16-byte opaque value
- [x] Define `MsgId` as 16-byte opaque value
- [x] Define `Kid` as 32-byte SHA-256 output
- [x] Define protocol constants:
  - [x] magic `P2TS`
  - [x] version `1`
  - [x] suite `1`
  - [x] frame version `1`
  - [x] single stream ID `1`

### 2.2 Config types
- [x] Define complete `config.toml` Rust structs
- [x] Include strict deserialization
- [x] Reject unknown config keys
- [x] Add helpers for expanding `~`

### 2.3 State machine enums
- [x] Define daemon states:
  - [x] `Idle`
  - [x] `WaitingForLocalClient`
  - [x] `Negotiating`
  - [x] `ConnectingDataChannel`
  - [x] `TunnelOpen`
  - [x] `IceRestarting`
  - [x] `Renegotiating`
  - [x] `Backoff`
  - [x] `Closed`
- [x] Define failure codes enum
- [x] Define message type enum
- [x] Define tunnel frame type enum

### 2.4 Errors
- [x] Add unified error enums for config/protocol/crypto/runtime

---

## 3. `p2p-crypto`

### 3.1 Identity file parsing
- [x] Implement parser for `identity`
- [x] Validate format tag `p2ptunnel-identity-v1`
- [x] Validate Ed25519 key sizes
- [x] Validate X25519 key sizes
- [x] Validate public keys match private keys
- [x] Refuse weak file permissions

### 3.2 Public identity file parsing
- [x] Implement parser for `identity.pub`
- [x] Support single-line SSH-like public format
- [x] Validate peer_id presence
- [x] Validate sign/kex key presence

### 3.3 Authorized keys parsing
- [x] Implement parser for `authorized_keys`
- [x] Support comments
- [x] Support repeated whitespace robustly
- [x] Reject malformed lines
- [x] Reject duplicate peer IDs unless explicitly normalized
- [x] Reject duplicate signing keys

### 3.4 KID generation
- [x] Implement `kid = sha256(sign_pub_bytes)`
- [x] Add formatting helpers for logs/CLI

### 3.5 Signing and verification
- [x] Implement Ed25519 detached signature creation
- [x] Implement detached signature verification
- [x] Ensure constant-time compare where applicable

### 3.6 Encryption and decryption
- [x] Implement ephemeral X25519 key generation per message
- [x] Implement X25519 shared secret derivation
- [x] Implement HKDF-SHA256 key derivation
- [x] Implement XChaCha20-Poly1305 encrypt
- [x] Implement XChaCha20-Poly1305 decrypt
- [x] Implement AAD handling exactly as specified

### 3.7 Secret hygiene
- [x] Wrap private keys and derived keys in `secrecy` types where practical
- [x] Zeroize temporary secrets after use
- [x] Avoid accidental Debug/Display leakage of secrets

### 3.8 Tests
- [x] Test identity parsing valid/invalid
- [x] Test authorized_keys parsing valid/invalid
- [x] Test signature verify success/failure
- [x] Test decrypt failure on tampered AAD
- [x] Test decrypt failure on tampered ciphertext
- [x] Test KDF determinism

---

## 4. `p2p-signaling`

### 4.1 Wire format structs
- [ ] Define outer envelope struct matching exact wire layout
- [ ] Implement binary encode
- [ ] Implement binary decode
- [ ] Enforce exact field lengths
- [ ] Enforce max payload sizes

### 4.2 Inner message structs
- [ ] Define CBOR-serializable inner message structs
- [ ] Define all message body types:
  - [ ] hello
  - [ ] offer
  - [ ] answer
  - [ ] ice_candidate
  - [ ] ack
  - [ ] ping
  - [ ] pong
  - [ ] close
  - [ ] error
  - [ ] ice_restart_request
  - [ ] renegotiate_request

### 4.3 MQTT transport wrapper
- [ ] Implement broker connection with TLS requirement
- [ ] Implement stable client ID config
- [ ] Implement subscribe to own signal topic
- [ ] Implement publish to peer signal topic
- [ ] Set QoS from config
- [ ] Handle reconnect to broker cleanly

### 4.4 End-to-end signaling pipeline
- [ ] Build encrypt+sign send path
- [ ] Build verify+decrypt receive path
- [ ] Reject non-encrypted / non-signed messages by construction

### 4.5 Replay protection
- [ ] Implement in-memory replay cache keyed by `(sender_kid, msg_id)`
- [ ] Enforce max age
- [ ] Enforce clock skew limits
- [ ] Reject duplicates
- [ ] Reject stale session messages

### 4.6 Ack logic
- [ ] Implement `ack_required` flag handling
- [ ] Implement encrypted `ack` messages
- [ ] Handle duplicate receives idempotently

### 4.7 Tests
- [ ] Round-trip encode/decode outer envelope
- [ ] Round-trip encrypt/decrypt inner messages
- [ ] Reject wrong recipient KID
- [ ] Reject invalid signature
- [ ] Reject duplicate msg_id
- [ ] Reject stale timestamp

---

## 5. `p2p-webrtc`

### 5.1 PeerConnection wrapper
- [ ] Build config from `webrtc` config section
- [ ] Support STUN URL list
- [ ] Do not implement TURN in v1
- [ ] Create PeerConnection wrapper API usable by daemon layer

### 5.2 SDP flow
- [ ] Implement create offer
- [ ] Implement apply remote offer
- [ ] Implement create answer
- [ ] Implement apply remote answer

### 5.3 ICE flow
- [ ] Capture local ICE candidates
- [ ] Serialize local ICE candidates into signaling messages
- [ ] Apply remote ICE candidates from signaling messages
- [ ] Surface ICE state changes to daemon layer

### 5.4 Data channel
- [ ] Create data channel on offer side
- [ ] Accept data channel on answer side
- [ ] Enforce expected label `tunnel`
- [ ] Surface open/close/message events

### 5.5 ICE restart hooks
- [ ] Provide API for ICE restart attempt
- [ ] Surface timeout/failure conditions clearly

### 5.6 Tests / harnesses
- [ ] Unit-test signaling-to-webrtc mapping where practical
- [ ] Add integration hooks for later end-to-end tests

---

## 6. `p2p-tunnel`

### 6.1 Tunnel frame codec
- [ ] Implement frame encoder
- [ ] Implement frame decoder
- [ ] Enforce frame version
- [ ] Enforce single stream ID = 1
- [ ] Support types: OPEN, DATA, CLOSE, ERROR, PING, PONG

### 6.2 Offer-side TCP listener
- [ ] Implement local TCP listener
- [ ] Enforce max_concurrent_clients = 1
- [ ] Reject second client when busy

### 6.3 Answer-side TCP connector
- [ ] Implement connect to `target_host:target_port`
- [ ] Return clear `target_connect_failed` error on failure

### 6.4 Bridge logic
- [ ] Local TCP -> tunnel DATA
- [ ] Tunnel DATA -> local TCP
- [ ] EOF -> CLOSE
- [ ] Error -> ERROR
- [ ] Flush/drain handling

### 6.5 Tests
- [ ] Frame codec round-trip
- [ ] Reject invalid frame lengths
- [ ] Reject unsupported stream IDs

---

## 7. `p2p-daemon`

### 7.1 Session orchestration
- [ ] Create `ActiveSession` struct
- [ ] Track session_id, remote peer, state, handles
- [ ] Enforce one active session at a time

### 7.2 Offer daemon logic
- [ ] Wait for local TCP client
- [ ] Create new session on connect
- [ ] Create WebRTC offer
- [ ] Send encrypted `hello` and `offer`
- [ ] Send encrypted ICE candidates
- [ ] Handle encrypted `answer`
- [ ] Handle remote ICE candidates
- [ ] Open tunnel on data channel ready

### 7.3 Answer daemon logic
- [ ] Stay connected to MQTT continuously
- [ ] Wait for encrypted offer messages
- [ ] Verify sender authorized
- [ ] Create answer session
- [ ] Send encrypted `answer`
- [ ] Send encrypted ICE candidates
- [ ] Connect target TCP when tunnel opens
- [ ] Return to idle after session closes

### 7.4 Reconnect logic
- [ ] Detect WebRTC disconnect
- [ ] Attempt ICE restart first
- [ ] If restart fails, perform full renegotiation
- [ ] Generate new session_id on full renegotiation
- [ ] Apply exponential backoff with jitter
- [ ] Do not preserve local TCP client across reconnect in v1

### 7.5 Failure handling
- [ ] On ICE failure, send encrypted `error` over MQTT
- [ ] On decrypt/signature failure, reject and log locally
- [ ] On unauthorized peer, reject and log locally
- [ ] On target connect failure, send encrypted `error`
- [ ] Ensure clean teardown of failed sessions

### 7.6 State machine correctness
- [ ] Implement explicit state transitions
- [ ] Prevent stale events from old sessions mutating new sessions
- [ ] Bind all runtime callbacks to session IDs

---

## 8. CLI binaries

### 8.1 `p2pctl`
- [x] Implement `keygen --peer-id <id>`
  - [x] generate Ed25519 keypair
  - [x] generate X25519 keypair
  - [x] write `identity`
  - [x] write `identity.pub`
  - [x] enforce `0600` on private file
- [x] Implement `fingerprint <identity.pub>`
- [x] Implement `add-authorized-key <identity.pub>`
- [x] Implement `check-config`
- [x] Implement `status`

### 8.2 `p2p-offer`
- [ ] Implement `run`
- [ ] Parse config path override
- [ ] Parse broker override flags
- [ ] Parse listen-port override flags

### 8.3 `p2p-answer`
- [ ] Implement `run`
- [ ] Parse config path override
- [ ] Parse broker override flags
- [ ] Parse target override flags

---

## 9. Config and file validation

### 9.1 Config loading
- [x] Load config.toml
- [ ] Apply env overrides
- [ ] Apply CLI overrides
- [x] Expand paths

### 9.2 Validation rules
- [x] Reject non-`mqtts://` URL when TLS required
- [x] Reject missing `authorized_keys`
- [x] Reject peer_id mismatch between config and identity
- [x] Reject loose private key permissions
- [x] Reject unknown keys when strict mode enabled
- [x] Validate role-specific sections
- [x] Validate answer peer allowlist

---

## 10. Logging and status

### 10.1 Logging
- [ ] Set up `tracing`
- [ ] Support text/json output
- [ ] Support stdout/file logging
- [ ] Redact secrets always
- [ ] Redact SDP by default
- [ ] Redact ICE candidates by default

### 10.2 Status output
- [ ] Write local `status.json`
- [ ] Include peer_id, role, mqtt connected state, active session ID, current state
- [ ] Ensure status file contains no secrets

---

## 11. Test plan

### 11.1 Unit tests
- [x] crypto primitives
- [x] config parsing
- [x] file parsing
- [ ] wire encode/decode
- [ ] replay cache
- [ ] frame codec

### 11.2 Integration tests
- [ ] two-node signaling round trip over mocked MQTT
- [ ] offer/answer session setup
- [ ] candidate exchange
- [ ] ICE failure path sends encrypted error
- [ ] unauthorized peer rejected
- [ ] replayed message rejected

### 11.3 End-to-end manual test targets
- [ ] localhost broker + two local nodes
- [ ] answer daemon always-on idle wait
- [ ] local SSH-style tunnel through data channel
- [ ] disconnect and reconnect attempt

---

## 12. Documentation tasks

### 12.1 README
- [ ] Explain architecture
- [ ] Explain trust model
- [ ] Explain key workflow
- [ ] Explain config file
- [ ] Explain how to run offer and answer

### 12.2 Operator docs
- [ ] Example setup for always-on home answer daemon
- [ ] Example setup for laptop offer node
- [ ] Example broker config
- [ ] Example authorized key exchange

### 12.3 Security notes
- [ ] Explain why MQTT is untrusted
- [ ] Explain why Ed25519 + X25519 are both needed
- [ ] Explain why TURN is unsupported in v1
- [ ] Explain replay protection

---

## 13. Suggested implementation order

### Phase 1: foundations
- [x] workspace bootstrap
- [x] config types
- [x] key files and parsers
- [x] `p2pctl keygen`
- [x] crypto helpers

### Phase 2: signaling protocol
- [ ] outer wire format
- [ ] inner CBOR messages
- [ ] encrypt/sign send path
- [ ] verify/decrypt receive path
- [ ] replay cache

### Phase 3: WebRTC session plumbing
- [ ] PeerConnection wrapper
- [ ] SDP flow
- [ ] ICE flow
- [ ] data channel wrapper

### Phase 4: tunnel transport
- [ ] tunnel frame codec
- [ ] offer-side TCP listener
- [ ] answer-side TCP connector
- [ ] bridge loop

### Phase 5: daemons
- [ ] offer daemon orchestration
- [ ] answer daemon orchestration
- [ ] always-on answer idle mode
- [ ] encrypted error reporting on ICE failure

### Phase 6: reconnect and polish
- [ ] ICE restart attempt
- [ ] renegotiation fallback
- [ ] backoff and jitter
- [ ] status file
- [ ] logging polish
- [ ] integration tests

---

## 14. Explicit v1 constraints to preserve during implementation

- [ ] Do not add TURN support
- [ ] Do not add GUI code
- [ ] Do not switch to PEM/PuTTY-centric key management
- [ ] Do not allow plaintext MQTT messages
- [ ] Do not allow unsigned MQTT messages
- [ ] Do not allow more than one active tunnel session
- [ ] Do not trust MQTT broker contents
- [ ] Do not log secrets, decrypted payloads, SDP, or ICE candidates by default
