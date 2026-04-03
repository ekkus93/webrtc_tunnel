use base64::{Engine as _, engine::general_purpose::STANDARD};

use crate::error::CryptoError;

pub fn decode_32(value: &str, field: &'static str) -> Result<[u8; 32], CryptoError> {
    let decoded = STANDARD.decode(value)?;
    decoded.try_into().map_err(|_| {
        CryptoError::InvalidIdentity(format!("{field} must decode to exactly 32 bytes"))
    })
}

pub fn encode_32(bytes: [u8; 32]) -> String {
    STANDARD.encode(bytes)
}

pub fn tokenize_line(line: &str) -> Result<Vec<String>, CryptoError> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    let mut in_quotes = false;

    for ch in line.chars() {
        match ch {
            '"' => {
                in_quotes = !in_quotes;
                current.push(ch);
            }
            c if c.is_whitespace() && !in_quotes => {
                if !current.is_empty() {
                    tokens.push(std::mem::take(&mut current));
                }
            }
            _ => current.push(ch),
        }
    }

    if in_quotes {
        return Err(CryptoError::InvalidAuthorizedKey("unterminated quoted value".to_owned()));
    }

    if !current.is_empty() {
        tokens.push(current);
    }

    Ok(tokens)
}

pub fn strip_quotes(value: &str) -> String {
    value.strip_prefix('"').and_then(|inner| inner.strip_suffix('"')).unwrap_or(value).to_owned()
}
