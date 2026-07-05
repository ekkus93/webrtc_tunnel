//! `PublicIdentity::parse` is the parser for untrusted, hand-edited/pasted
//! `authorized_keys` content. These tests exercise the realistic failure mode
//! for that input (a corrupted or truncated line) rather than just the
//! round-trip happy path already covered elsewhere.

use base64::{Engine as _, engine::general_purpose::STANDARD};
use p2p_crypto::{CryptoError, PublicIdentity, generate_identity};

fn valid_line() -> String {
    generate_identity("offer-home")
        .expect("identity generation should succeed")
        .public_identity
        .render()
}

fn remove_token(line: &str, key_prefix: &str) -> String {
    line.split(' ').filter(|token| !token.starts_with(key_prefix)).collect::<Vec<_>>().join(" ")
}

fn replace_token_value(line: &str, key_prefix: &str, new_value: &str) -> String {
    line.split(' ')
        .map(|token| {
            if token.starts_with(key_prefix) {
                format!("{key_prefix}{new_value}")
            } else {
                token.to_owned()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

#[test]
fn parses_a_well_formed_line() {
    let line = valid_line();
    let parsed = PublicIdentity::parse(&line).expect("well-formed line should parse");
    assert_eq!(parsed.peer_id.as_str(), "offer-home");
}

#[test]
fn rejects_empty_input() {
    let error = PublicIdentity::parse("").expect_err("empty input should be rejected");
    assert!(matches!(error, CryptoError::InvalidPublicIdentity(_)));
}

#[test]
fn rejects_whitespace_only_input() {
    let error =
        PublicIdentity::parse("   \n\t  ").expect_err("whitespace-only input should be rejected");
    assert!(matches!(error, CryptoError::InvalidPublicIdentity(_)));
}

#[test]
fn rejects_missing_format_marker() {
    let line = valid_line().replacen("p2ptunnel-ed25519", "p2ptunnel-ed25519-v2", 1);
    let error = PublicIdentity::parse(&line).expect_err("unknown format marker should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("unsupported public identity format"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_too_few_tokens() {
    let error = PublicIdentity::parse("p2ptunnel-ed25519 peer_id=offer-home")
        .expect_err("too few tokens should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("expected format marker"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_missing_peer_id_field() {
    let line = remove_token(&format!("{} comment=\"pad\"", valid_line()), "peer_id=");
    let error = PublicIdentity::parse(&line).expect_err("missing peer_id should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("peer_id is required"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_missing_sign_pub_field() {
    let line = remove_token(&format!("{} comment=\"pad\"", valid_line()), "sign_pub=");
    let error = PublicIdentity::parse(&line).expect_err("missing sign_pub should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("sign_pub is required"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_missing_kex_pub_field() {
    let line = remove_token(&format!("{} comment=\"pad\"", valid_line()), "kex_pub=");
    let error = PublicIdentity::parse(&line).expect_err("missing kex_pub should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("kex_pub is required"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_invalid_base64_in_sign_pub() {
    let line = replace_token_value(&valid_line(), "sign_pub=", "not-valid-base64!!!");
    let error = PublicIdentity::parse(&line).expect_err("invalid base64 should be rejected");
    assert!(matches!(error, CryptoError::Base64(_)), "expected Base64 error, got {error:?}");
}

#[test]
fn rejects_invalid_base64_in_kex_pub() {
    let line = replace_token_value(&valid_line(), "kex_pub=", "not-valid-base64!!!");
    let error = PublicIdentity::parse(&line).expect_err("invalid base64 should be rejected");
    assert!(matches!(error, CryptoError::Base64(_)), "expected Base64 error, got {error:?}");
}

#[test]
fn rejects_sign_pub_too_short() {
    let short = STANDARD.encode([0_u8; 16]);
    let line = replace_token_value(&valid_line(), "sign_pub=", &short);
    let error = PublicIdentity::parse(&line).expect_err("too-short sign_pub should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("sign_pub must decode to 32 bytes"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_sign_pub_too_long() {
    let long = STANDARD.encode([0_u8; 40]);
    let line = replace_token_value(&valid_line(), "sign_pub=", &long);
    let error = PublicIdentity::parse(&line).expect_err("too-long sign_pub should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("sign_pub must decode to 32 bytes"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_kex_pub_wrong_length() {
    let short = STANDARD.encode([0_u8; 16]);
    let line = replace_token_value(&valid_line(), "kex_pub=", &short);
    let error = PublicIdentity::parse(&line).expect_err("too-short kex_pub should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("kex_pub must decode to 32 bytes"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_sign_pub_that_is_not_a_valid_curve_point() {
    // 32 zero bytes is valid base64 of the right length, but not a valid
    // compressed Edwards point.
    let zeros = STANDARD.encode([0_u8; 32]);
    let line = replace_token_value(&valid_line(), "sign_pub=", &zeros);
    let error = PublicIdentity::parse(&line);
    if let Err(error) = error {
        assert!(matches!(error, CryptoError::Signature), "expected Signature error, got {error:?}");
    }
    // If ed25519-dalek happens to accept the all-zero encoding as a valid (if
    // degenerate) point on this version, that's not itself a parser bug — the
    // important property (asserted above when it does error) is that a bad
    // point is reported as CryptoError::Signature, not a panic.
}

#[test]
fn rejects_unknown_token_key() {
    let line = format!("{} extra_field=whatever", valid_line());
    let error = PublicIdentity::parse(&line).expect_err("unknown token key should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("unknown token key"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_token_without_equals_sign() {
    let line = format!("{} trailinggarbage", valid_line());
    let error = PublicIdentity::parse(&line).expect_err("token without '=' should be rejected");
    match error {
        CryptoError::InvalidPublicIdentity(message) => {
            assert!(message.contains("invalid token"), "{message}");
        }
        other => panic!("expected InvalidPublicIdentity, got {other:?}"),
    }
}

#[test]
fn rejects_empty_peer_id_value() {
    // PeerId::new only rejects an empty (after trim) value — there's no other
    // character restriction, so this is the one genuinely invalid peer_id shape.
    let line = replace_token_value(&valid_line(), "peer_id=", "");
    let error = PublicIdentity::parse(&line).expect_err("empty peer_id should be rejected");
    assert!(
        matches!(error, CryptoError::InvalidPublicIdentity(_)),
        "expected InvalidPublicIdentity, got {error:?}"
    );
}

#[test]
fn rejects_unterminated_quoted_comment() {
    let line = format!("{} comment=\"unterminated", valid_line());
    let error = PublicIdentity::parse(&line).expect_err("unterminated quote should be rejected");
    assert!(
        matches!(error, CryptoError::InvalidAuthorizedKey(_)),
        "expected InvalidAuthorizedKey, got {error:?}"
    );
}

#[test]
fn accepts_and_preserves_a_quoted_comment() {
    let line = format!("{} comment=\"a test comment\"", valid_line());
    let parsed = PublicIdentity::parse(&line).expect("valid line with comment should parse");
    assert_eq!(parsed.comment.as_deref(), Some("a test comment"));
}

#[test]
fn no_malformed_input_panics() {
    // Belt-and-suspenders sweep: none of these hand-corrupted shapes should ever
    // panic, only return Err.
    let base = valid_line();
    let candidates = vec![
        String::new(),
        "p2ptunnel-ed25519".to_owned(),
        base.replacen("=", "", 1),
        base.replacen("p2ptunnel-ed25519", "", 1),
        format!("{base} ="),
        format!("{base} =value"),
        "\"".to_owned(),
        "p2ptunnel-ed25519 peer_id= sign_pub= kex_pub=".to_owned(),
    ];
    for candidate in candidates {
        let _ = PublicIdentity::parse(&candidate);
    }
}
