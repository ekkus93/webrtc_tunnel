use std::collections::HashSet;
use std::fs;
use std::path::Path;

use p2p_core::{Kid, PeerId};

use crate::error::CryptoError;
use crate::kid_from_signing_key;
use crate::public_identity::PublicIdentity;

#[derive(Clone, Debug)]
pub struct AuthorizedKey {
    pub peer_id: PeerId,
    pub public_identity: PublicIdentity,
}

#[derive(Clone, Debug, Default)]
pub struct AuthorizedKeys {
    keys: Vec<AuthorizedKey>,
}

impl AuthorizedKeys {
    pub fn from_file(path: &Path) -> Result<Self, CryptoError> {
        let content = fs::read_to_string(path)?;
        Self::parse(&content)
    }

    pub fn parse(content: &str) -> Result<Self, CryptoError> {
        let mut keys = Vec::new();
        let mut seen_peers = HashSet::new();
        let mut seen_signing_keys = HashSet::new();

        for (line_number, raw_line) in content.lines().enumerate() {
            let line = raw_line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }

            let public_identity = PublicIdentity::parse(line).map_err(|error| {
                CryptoError::InvalidAuthorizedKey(format!("line {}: {}", line_number + 1, error))
            })?;
            if !seen_peers.insert(public_identity.peer_id.clone()) {
                return Err(CryptoError::InvalidAuthorizedKey(format!(
                    "duplicate peer_id '{}' is not allowed",
                    public_identity.peer_id
                )));
            }
            if !seen_signing_keys.insert(public_identity.sign_public.to_bytes()) {
                return Err(CryptoError::InvalidAuthorizedKey(format!(
                    "duplicate signing key for '{}'",
                    public_identity.peer_id
                )));
            }

            keys.push(AuthorizedKey { peer_id: public_identity.peer_id.clone(), public_identity });
        }

        Ok(Self { keys })
    }

    pub fn iter(&self) -> impl Iterator<Item = &AuthorizedKey> {
        self.keys.iter()
    }

    pub fn get_by_peer_id(&self, peer_id: &PeerId) -> Option<&AuthorizedKey> {
        self.keys.iter().find(|key| &key.peer_id == peer_id)
    }

    pub fn get_by_kid(&self, kid: &Kid) -> Option<&AuthorizedKey> {
        self.keys.iter().find(|key| kid_from_signing_key(&key.public_identity.sign_public) == *kid)
    }
}
