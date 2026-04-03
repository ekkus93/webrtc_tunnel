use chacha20poly1305::{
    KeyInit, XChaCha20Poly1305, XNonce,
    aead::{Aead, Payload},
};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use hkdf::Hkdf;
use p2p_core::{Kid, MsgId, PROTOCOL_SUITE};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};

use crate::error::CryptoError;

pub fn kid_from_signing_key(verifying_key: &VerifyingKey) -> Kid {
    let digest = Sha256::digest(verifying_key.to_bytes());
    let mut bytes = [0_u8; 32];
    bytes.copy_from_slice(digest.as_slice());
    Kid::new(bytes)
}

pub fn sign_message(signing_key: &SigningKey, message: &[u8]) -> [u8; 64] {
    signing_key.sign(message).to_bytes()
}

pub fn verify_message(
    verifying_key: &VerifyingKey,
    message: &[u8],
    signature: &[u8; 64],
) -> Result<(), CryptoError> {
    let signature = Signature::from_bytes(signature);
    verifying_key.verify(message, &signature).map_err(|_| CryptoError::Signature)
}

pub fn generate_ephemeral_secret() -> StaticSecret {
    StaticSecret::random_from_rng(OsRng)
}

pub fn derive_aead_key(
    sender_eph_secret: &StaticSecret,
    recipient_static_public: &X25519PublicKey,
    sender_kid: &Kid,
    recipient_kid: &Kid,
    msg_id: &MsgId,
) -> Result<[u8; 32], CryptoError> {
    let shared_secret = sender_eph_secret.diffie_hellman(recipient_static_public);

    let mut salt = Vec::with_capacity(64);
    salt.extend_from_slice(sender_kid.as_bytes());
    salt.extend_from_slice(recipient_kid.as_bytes());

    let mut info = Vec::with_capacity(26);
    info.extend_from_slice(b"p2ts/v1/msg");
    info.extend_from_slice(msg_id.as_bytes());
    info.push(PROTOCOL_SUITE);

    let hkdf = Hkdf::<Sha256>::new(Some(&salt), shared_secret.as_bytes());
    let mut key = [0_u8; 32];
    hkdf.expand(&info, &mut key).map_err(|error| CryptoError::Kdf(error.to_string()))?;
    Ok(key)
}

pub fn derive_aead_key_from_shared_secret(
    shared_secret: &[u8; 32],
    sender_kid: &Kid,
    recipient_kid: &Kid,
    msg_id: &MsgId,
) -> Result<[u8; 32], CryptoError> {
    let mut salt = Vec::with_capacity(64);
    salt.extend_from_slice(sender_kid.as_bytes());
    salt.extend_from_slice(recipient_kid.as_bytes());

    let mut info = Vec::with_capacity(26);
    info.extend_from_slice(b"p2ts/v1/msg");
    info.extend_from_slice(msg_id.as_bytes());
    info.push(PROTOCOL_SUITE);

    let hkdf = Hkdf::<Sha256>::new(Some(&salt), shared_secret);
    let mut key = [0_u8; 32];
    hkdf.expand(&info, &mut key).map_err(|error| CryptoError::Kdf(error.to_string()))?;
    Ok(key)
}

pub fn encrypt_message(
    key: &[u8; 32],
    nonce: &[u8; 24],
    aad: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let cipher = XChaCha20Poly1305::new(key.into());
    cipher
        .encrypt(XNonce::from_slice(nonce), Payload { msg: plaintext, aad })
        .map_err(|_| CryptoError::Encryption)
}

pub fn decrypt_message(
    key: &[u8; 32],
    nonce: &[u8; 24],
    aad: &[u8],
    ciphertext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let cipher = XChaCha20Poly1305::new(key.into());
    cipher
        .decrypt(XNonce::from_slice(nonce), Payload { msg: ciphertext, aad })
        .map_err(|_| CryptoError::Decryption)
}

pub fn random_nonce() -> [u8; 24] {
    let mut nonce = [0_u8; 24];
    OsRng.fill_bytes(&mut nonce);
    nonce
}

#[cfg(test)]
mod tests {
    use ed25519_dalek::SigningKey;
    use p2p_core::{Kid, MsgId};

    use super::{
        decrypt_message, derive_aead_key, encrypt_message, generate_ephemeral_secret,
        kid_from_signing_key, random_nonce, sign_message, verify_message,
    };

    #[test]
    fn signature_verification_round_trip() {
        let signing_key = SigningKey::generate(&mut rand_core::OsRng);
        let message = b"hello world";
        let signature = sign_message(&signing_key, message);
        verify_message(&signing_key.verifying_key(), message, &signature)
            .expect("signature should verify");
    }

    #[test]
    fn signature_verification_fails_on_tamper() {
        let signing_key = SigningKey::generate(&mut rand_core::OsRng);
        let signature = sign_message(&signing_key, b"hello world");
        assert!(verify_message(&signing_key.verifying_key(), b"tampered", &signature).is_err());
    }

    #[test]
    fn kdf_is_deterministic() {
        let sender_secret = generate_ephemeral_secret();
        let recipient_secret = generate_ephemeral_secret();
        let recipient_public = x25519_dalek::PublicKey::from(&recipient_secret);
        let sender_public = x25519_dalek::PublicKey::from(&sender_secret);
        let msg_id = MsgId::random();
        let sender_kid = Kid::new([1_u8; 32]);
        let recipient_kid = Kid::new([2_u8; 32]);

        let first = derive_aead_key(
            &sender_secret,
            &recipient_public,
            &sender_kid,
            &recipient_kid,
            &msg_id,
        )
        .expect("first derivation");
        let second_shared = recipient_secret.diffie_hellman(&sender_public);
        let mut salt = Vec::new();
        salt.extend_from_slice(sender_kid.as_bytes());
        salt.extend_from_slice(recipient_kid.as_bytes());
        let mut info = Vec::new();
        info.extend_from_slice(b"p2ts/v1/msg");
        info.extend_from_slice(msg_id.as_bytes());
        info.push(1);
        let hkdf = hkdf::Hkdf::<sha2::Sha256>::new(Some(&salt), second_shared.as_bytes());
        let mut second = [0_u8; 32];
        hkdf.expand(&info, &mut second).expect("expand");

        assert_eq!(first, second);
    }

    #[test]
    fn decrypt_fails_on_tampered_aad() {
        let key = [3_u8; 32];
        let nonce = random_nonce();
        let ciphertext = encrypt_message(&key, &nonce, b"aad", b"payload").expect("encrypt");
        assert!(decrypt_message(&key, &nonce, b"wrong", &ciphertext).is_err());
    }

    #[test]
    fn decrypt_fails_on_tampered_ciphertext() {
        let key = [3_u8; 32];
        let nonce = random_nonce();
        let mut ciphertext = encrypt_message(&key, &nonce, b"aad", b"payload").expect("encrypt");
        ciphertext[0] ^= 0x01;
        assert!(decrypt_message(&key, &nonce, b"aad", &ciphertext).is_err());
    }

    #[test]
    fn kid_is_sha256_of_signing_key() {
        let signing_key = SigningKey::generate(&mut rand_core::OsRng);
        let kid = kid_from_signing_key(&signing_key.verifying_key());
        assert_eq!(kid.as_bytes().len(), 32);
    }
}
