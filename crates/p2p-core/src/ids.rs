use std::fmt::{Display, Formatter};
use std::str::FromStr;

use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};

use crate::error::ProtocolError;

#[derive(Clone, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct PeerId(String);

impl PeerId {
    pub fn new(value: impl Into<String>) -> Result<Self, ProtocolError> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(ProtocolError::InvalidPeerId("peer_id cannot be empty".to_owned()));
        }

        Ok(Self(value))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl Display for PeerId {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for PeerId {
    type Err = ProtocolError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Self::new(s)
    }
}

macro_rules! define_fixed_id {
    ($name:ident, $size:expr) => {
        #[derive(Copy, Clone, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
        #[serde(transparent)]
        pub struct $name([u8; $size]);

        impl $name {
            pub fn new(bytes: [u8; $size]) -> Self {
                Self(bytes)
            }

            pub fn random() -> Self {
                let mut bytes = [0_u8; $size];
                OsRng.fill_bytes(&mut bytes);
                Self(bytes)
            }

            pub fn as_bytes(&self) -> &[u8; $size] {
                &self.0
            }

            pub fn into_bytes(self) -> [u8; $size] {
                self.0
            }

            pub fn to_hex(self) -> String {
                self.0.iter().map(|byte| format!("{byte:02x}")).collect::<String>()
            }
        }

        impl Display for $name {
            fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
                f.write_str(&self.to_hex())
            }
        }
    };
}

define_fixed_id!(SessionId, 16);
define_fixed_id!(MsgId, 16);
define_fixed_id!(Kid, 32);
