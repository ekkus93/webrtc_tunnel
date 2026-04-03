use std::fs;
#[cfg(unix)]
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use ed25519_dalek::{SigningKey, VerifyingKey};
use p2p_core::PeerId;
use rand_core::OsRng;
use secrecy::{ExposeSecret, SecretBox};
use serde::Deserialize;
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};

use crate::error::CryptoError;
use crate::public_identity::PublicIdentity;
use crate::util::{decode_32, encode_32};

pub struct IdentityFile {
    pub peer_id: PeerId,
    pub signing_key: SigningKey,
    pub verifying_key: VerifyingKey,
    pub kex_secret: SecretBox<[u8; 32]>,
    pub kex_public: X25519PublicKey,
}

pub struct GeneratedIdentity {
    pub identity: IdentityFile,
    pub public_identity: PublicIdentity,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct IdentityDocument {
    format: String,
    peer_id: String,
    sign: IdentityKeySection,
    kex: IdentityKeySection,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct IdentityKeySection {
    alg: String,
    private: String,
    public: String,
}

impl IdentityFile {
    pub fn from_file(path: &Path) -> Result<Self, CryptoError> {
        validate_private_file_permissions(path)?;
        let content = fs::read_to_string(path)?;
        Self::from_toml(&content)
    }

    pub fn from_toml(content: &str) -> Result<Self, CryptoError> {
        let document: IdentityDocument = toml::from_str(content)?;
        if document.format != "p2ptunnel-identity-v1" {
            return Err(CryptoError::InvalidIdentity("unsupported identity format".to_owned()));
        }
        if document.sign.alg != "ed25519" {
            return Err(CryptoError::InvalidIdentity("sign.alg must be ed25519".to_owned()));
        }
        if document.kex.alg != "x25519" {
            return Err(CryptoError::InvalidIdentity("kex.alg must be x25519".to_owned()));
        }

        let sign_private = decode_32(&document.sign.private, "sign.private")?;
        let sign_public = decode_32(&document.sign.public, "sign.public")?;
        let kex_private = decode_32(&document.kex.private, "kex.private")?;
        let kex_public = decode_32(&document.kex.public, "kex.public")?;

        let signing_key = SigningKey::from_bytes(&sign_private);
        let verifying_key = signing_key.verifying_key();
        if verifying_key.to_bytes() != sign_public {
            return Err(CryptoError::InvalidIdentity(
                "sign.public does not match sign.private".to_owned(),
            ));
        }

        let static_secret = StaticSecret::from(kex_private);
        let derived_public = X25519PublicKey::from(&static_secret);
        if derived_public.as_bytes() != &kex_public {
            return Err(CryptoError::InvalidIdentity(
                "kex.public does not match kex.private".to_owned(),
            ));
        }

        Ok(Self {
            peer_id: document.peer_id.parse().map_err(|error: p2p_core::ProtocolError| {
                CryptoError::InvalidIdentity(error.to_string())
            })?,
            signing_key,
            verifying_key,
            kex_secret: SecretBox::new(Box::new(kex_private)),
            kex_public: derived_public,
        })
    }

    pub fn public_identity(&self) -> PublicIdentity {
        PublicIdentity {
            peer_id: self.peer_id.clone(),
            sign_public: self.verifying_key,
            kex_public: self.kex_public,
            comment: None,
        }
    }

    pub fn render_toml(&self) -> String {
        format!(
            "format = \"p2ptunnel-identity-v1\"\npeer_id = \"{}\"\n\n[sign]\nalg = \"ed25519\"\nprivate = \"{}\"\npublic = \"{}\"\n\n[kex]\nalg = \"x25519\"\nprivate = \"{}\"\npublic = \"{}\"\n",
            self.peer_id,
            encode_32(self.signing_key.to_bytes()),
            encode_32(self.verifying_key.to_bytes()),
            encode_32(*self.kex_secret.expose_secret()),
            encode_32(*self.kex_public.as_bytes()),
        )
    }
}

pub fn generate_identity(peer_id: impl Into<String>) -> Result<GeneratedIdentity, CryptoError> {
    let peer_id: PeerId = peer_id.into().parse().map_err(|error: p2p_core::ProtocolError| {
        CryptoError::InvalidIdentity(error.to_string())
    })?;
    let signing_key = SigningKey::generate(&mut OsRng);
    let verifying_key = signing_key.verifying_key();
    let kex_secret = StaticSecret::random_from_rng(OsRng);
    let kex_public = X25519PublicKey::from(&kex_secret);
    let identity = IdentityFile {
        peer_id: peer_id.clone(),
        signing_key,
        verifying_key,
        kex_secret: SecretBox::new(Box::new(kex_secret.to_bytes())),
        kex_public,
    };
    let public_identity = identity.public_identity();
    Ok(GeneratedIdentity { identity, public_identity })
}

#[cfg(unix)]
pub fn validate_private_file_permissions(path: &Path) -> Result<(), CryptoError> {
    let metadata = fs::metadata(path)?;
    let mode = metadata.permissions().mode() & 0o777;
    if mode & 0o077 != 0 {
        return Err(CryptoError::Permission(format!(
            "identity file '{}' must be 0600 or stricter, got {:o}",
            path.display(),
            mode
        )));
    }
    Ok(())
}

#[cfg(not(unix))]
pub fn validate_private_file_permissions(_path: &Path) -> Result<(), CryptoError> {
    Ok(())
}
