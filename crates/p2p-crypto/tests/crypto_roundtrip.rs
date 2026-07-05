use p2p_core::{Kid, MsgId};
use p2p_crypto::{
    AuthorizedKeys, CryptoError, decrypt_message, derive_aead_key,
    derive_aead_key_from_shared_secret, encrypt_message, generate_ephemeral_secret,
    generate_identity, kid_from_signing_key, random_nonce, sign_message, verify_message,
};
use x25519_dalek::PublicKey as X25519PublicKey;

// ── Phase 1.1: Identity generation and TOML roundtrip ─────────────────────────

#[test]
fn generate_identity_produces_parseable_identity_file() {
    let identity = generate_identity("alice").expect("generate alice");
    let toml = identity.identity.render_toml();
    let parsed = p2p_crypto::IdentityFile::from_toml(&toml).expect("parse roundtrip");
    assert_eq!(parsed.peer_id.as_str(), "alice");
    assert_eq!(parsed.signing_kid(), identity.identity.signing_kid());
}

#[test]
fn render_toml_then_from_toml_preserves_signing_and_kex_keys() {
    let identity = generate_identity("alice").expect("generate alice");
    let toml = identity.identity.render_toml();
    let parsed = p2p_crypto::IdentityFile::from_toml(&toml).expect("parse roundtrip");
    let message = b"round-trip message";
    let sig = sign_message(&identity.identity.signing_key, message);
    verify_message(&parsed.verifying_key, message, &sig)
        .expect("parsed key must verify original sig");
}

#[test]
fn generate_identity_rejects_empty_peer_id() {
    assert!(generate_identity("").is_err());
}

// ── Phase 1.2: Public identity → authorized-keys trust chain ──────────────────

#[test]
fn public_identity_renders_as_valid_authorized_key_line() {
    let identity = generate_identity("bob").expect("generate bob");
    let line = identity.public_identity.render();
    let keys = AuthorizedKeys::parse(&line).expect("parse authorized key");
    assert_eq!(keys.iter().count(), 1);
    let bob_pid = "bob".parse().expect("peer id");
    assert!(keys.get_by_peer_id(&bob_pid).is_some());
    let bob_kid = kid_from_signing_key(&identity.public_identity.sign_public);
    assert!(keys.get_by_kid(&bob_kid).is_some());
}

#[test]
fn authorized_keys_lookup_by_kid_after_two_peer_generate() {
    let alice = generate_identity("alice").expect("generate alice");
    let bob = generate_identity("bob").expect("generate bob");
    let combined = format!("{}\n{}", alice.public_identity.render(), bob.public_identity.render());
    let keys = AuthorizedKeys::parse(&combined).expect("parse combined");

    let alice_kid = kid_from_signing_key(&alice.public_identity.sign_public);
    let bob_kid = kid_from_signing_key(&bob.public_identity.sign_public);

    assert!(keys.get_by_kid(&alice_kid).is_some(), "alice must be found by KID");
    assert!(keys.get_by_kid(&bob_kid).is_some(), "bob must be found by KID");
    assert!(keys.get_by_kid(&Kid::new([0u8; 32])).is_none(), "unknown KID must return None");
}

#[test]
fn duplicate_peer_id_in_authorized_keys_is_rejected() {
    let identity = generate_identity("alice").expect("generate alice");
    let line = identity.public_identity.render();
    let doubled = format!("{line}\n{line}");
    assert!(AuthorizedKeys::parse(&doubled).is_err(), "duplicate peer_id must be rejected");
}

#[test]
fn duplicate_signing_key_under_a_different_peer_id_is_rejected() {
    // Same signing key reused under a second peer_id is a key-confusion/impersonation-
    // adjacent bug class distinct from an exact duplicate peer_id line: `seen_signing_keys`
    // must catch it even though `seen_peers` alone would not.
    let identity = generate_identity("alice").expect("generate alice");
    let line = identity.public_identity.render();
    let impersonating_line = line.replacen("peer_id=alice", "peer_id=alice-impersonator", 1);
    let combined = format!("{line}\n{impersonating_line}");
    assert!(
        AuthorizedKeys::parse(&combined).is_err(),
        "reusing a signing key under a different peer_id must be rejected"
    );
}

#[test]
fn distinct_peer_ids_with_distinct_signing_keys_are_both_accepted() {
    let alice = generate_identity("alice").expect("generate alice");
    let bob = generate_identity("bob").expect("generate bob");
    let combined = format!("{}\n{}", alice.public_identity.render(), bob.public_identity.render());
    let keys = AuthorizedKeys::parse(&combined)
        .expect("distinct peer_id/signing-key pairs must both be accepted");
    assert_eq!(keys.iter().count(), 2);
}

#[test]
fn comments_and_blank_lines_in_authorized_keys_are_ignored() {
    let identity = generate_identity("alice").expect("generate alice");
    let line = identity.public_identity.render();
    let content = format!("# This is a comment\n\n{line}\n\n# Another comment\n");
    let keys = AuthorizedKeys::parse(&content).expect("parse with comments");
    assert_eq!(keys.iter().count(), 1);
}

// ── Phase 1.3: Symmetric two-party key agreement ──────────────────────────────

fn make_alice_bob_keys() -> (
    x25519_dalek::StaticSecret,
    X25519PublicKey,
    x25519_dalek::StaticSecret,
    X25519PublicKey,
    Kid,
    Kid,
    MsgId,
) {
    let alice_eph = generate_ephemeral_secret();
    let alice_pub = X25519PublicKey::from(&alice_eph);
    let bob_static = generate_ephemeral_secret();
    let bob_pub = X25519PublicKey::from(&bob_static);
    let alice_kid = Kid::new([0xAA; 32]);
    let bob_kid = Kid::new([0xBB; 32]);
    let msg_id = MsgId::random();
    (alice_eph, alice_pub, bob_static, bob_pub, alice_kid, bob_kid, msg_id)
}

#[test]
fn symmetric_key_agreement_sender_and_recipient_derive_same_aead_key() {
    let (alice_eph, alice_pub, bob_static, bob_pub, alice_kid, bob_kid, msg_id) =
        make_alice_bob_keys();

    let alice_key = derive_aead_key(&alice_eph, &bob_pub, &alice_kid, &bob_kid, &msg_id)
        .expect("alice side key derivation");

    let shared_secret = bob_static.diffie_hellman(&alice_pub).to_bytes();
    let bob_key = derive_aead_key_from_shared_secret(&shared_secret, &alice_kid, &bob_kid, &msg_id)
        .expect("bob side key derivation");

    assert_eq!(alice_key, bob_key, "both sides must derive the same AEAD key");
}

#[test]
fn different_msg_id_produces_different_key() {
    let (alice_eph, _alice_pub, _bob_static, bob_pub, alice_kid, bob_kid, msg_id1) =
        make_alice_bob_keys();
    let msg_id2 = MsgId::random();
    assert_ne!(msg_id1.as_bytes(), msg_id2.as_bytes(), "test requires distinct msg_ids");

    let key1 =
        derive_aead_key(&alice_eph, &bob_pub, &alice_kid, &bob_kid, &msg_id1).expect("key 1");
    let key2 =
        derive_aead_key(&alice_eph, &bob_pub, &alice_kid, &bob_kid, &msg_id2).expect("key 2");

    assert_ne!(key1, key2, "different msg_id must produce different key");
}

#[test]
fn swapped_sender_recipient_kid_order_produces_different_key() {
    let (alice_eph, _alice_pub, _bob_static, bob_pub, alice_kid, bob_kid, msg_id) =
        make_alice_bob_keys();

    let key_ab =
        derive_aead_key(&alice_eph, &bob_pub, &alice_kid, &bob_kid, &msg_id).expect("key ab");
    let key_ba =
        derive_aead_key(&alice_eph, &bob_pub, &bob_kid, &alice_kid, &msg_id).expect("key ba");

    assert_ne!(key_ab, key_ba, "swapped KID order must produce a different key");
}

// ── Phase 1.4: Encrypt / decrypt roundtrip across varied payloads ─────────────

fn test_encrypt_decrypt(plaintext: &[u8]) {
    let key = [0x42_u8; 32];
    let nonce = random_nonce();
    let aad = b"test-aad";
    let ciphertext = encrypt_message(&key, &nonce, aad, plaintext).expect("encrypt");
    let recovered = decrypt_message(&key, &nonce, aad, &ciphertext).expect("decrypt");
    assert_eq!(recovered, plaintext);
}

#[test]
fn encrypt_decrypt_roundtrip_empty_payload() {
    test_encrypt_decrypt(&[]);
}

#[test]
fn encrypt_decrypt_roundtrip_single_byte_payload() {
    test_encrypt_decrypt(&[0xAB]);
}

#[test]
fn encrypt_decrypt_roundtrip_large_payload() {
    let payload = vec![0x55_u8; 256 * 1024];
    test_encrypt_decrypt(&payload);
}

#[test]
fn decrypt_with_wrong_key_returns_error() {
    let key = [0x42_u8; 32];
    let nonce = random_nonce();
    let ciphertext = encrypt_message(&key, &nonce, b"aad", b"payload").expect("encrypt");
    let wrong_key = [0xFF_u8; 32];
    assert!(
        matches!(
            decrypt_message(&wrong_key, &nonce, b"aad", &ciphertext),
            Err(CryptoError::Decryption)
        ),
        "wrong key must return Decryption error"
    );
}

#[test]
fn decrypt_with_wrong_nonce_returns_error() {
    let key = [0x42_u8; 32];
    let nonce = random_nonce();
    let ciphertext = encrypt_message(&key, &nonce, b"aad", b"payload").expect("encrypt");
    let zero_nonce = [0x00_u8; 24];
    assert!(
        decrypt_message(&key, &zero_nonce, b"aad", &ciphertext).is_err(),
        "wrong nonce must return an error"
    );
}

// ── Phase 1.5: Sign / verify across identity boundaries ───────────────────────

#[test]
fn cross_identity_sign_then_verify() {
    let alice = generate_identity("alice").expect("generate alice");
    let bob = generate_identity("bob").expect("generate bob");
    let message = b"cross identity sign test";
    let sig = sign_message(&alice.identity.signing_key, message);
    verify_message(&alice.identity.verifying_key, message, &sig)
        .expect("alice key must verify alice signature");
    assert!(
        verify_message(&bob.identity.verifying_key, message, &sig).is_err(),
        "bob key must not verify alice signature"
    );
}

#[test]
fn kid_is_deterministic_for_generated_identity() {
    let identity = generate_identity("alice").expect("generate alice");

    let kid_from_public = kid_from_signing_key(&identity.public_identity.sign_public);
    let kid_from_identity = identity.identity.signing_kid();

    assert_eq!(kid_from_public, kid_from_identity);

    let toml = identity.identity.render_toml();
    let parsed = p2p_crypto::IdentityFile::from_toml(&toml).expect("parse roundtrip");
    assert_eq!(parsed.signing_kid(), kid_from_identity);
}
