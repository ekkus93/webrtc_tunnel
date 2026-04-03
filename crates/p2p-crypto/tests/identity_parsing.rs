use std::io::Write;

use p2p_crypto::{AuthorizedKeys, IdentityFile, generate_identity};

#[test]
fn identity_parsing_accepts_valid_file() {
    let generated = generate_identity("offer-home").expect("identity generation should succeed");
    let parsed = IdentityFile::from_toml(&generated.identity.render_toml())
        .expect("identity parsing should succeed");
    assert_eq!(parsed.peer_id.as_str(), "offer-home");
}

#[test]
fn identity_parsing_rejects_mismatched_public_key() {
    let invalid = r#"
format = "p2ptunnel-identity-v1"
peer_id = "offer-home"

[sign]
alg = "ed25519"
private = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
public = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="

[kex]
alg = "x25519"
private = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
public = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
"#;

    assert!(IdentityFile::from_toml(invalid).is_err());
}

#[test]
fn identity_file_rejects_weak_permissions() {
    let generated = generate_identity("offer-home").expect("identity generation should succeed");
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("identity");
    let mut file = std::fs::File::create(&path).expect("create file");
    file.write_all(generated.identity.render_toml().as_bytes()).expect("write identity");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;

        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644))
            .expect("set permissions");
        assert!(IdentityFile::from_file(&path).is_err());
    }
}

#[test]
fn authorized_keys_parse_valid_and_invalid_files() {
    let generated = generate_identity("answer-office").expect("identity generation should succeed");
    let valid = generated.public_identity.render();
    let parsed = AuthorizedKeys::parse(&valid).expect("authorized keys parse should succeed");
    assert!(parsed.get_by_peer_id(&generated.identity.peer_id).is_some());

    let duplicated = format!("{valid}\n{valid}");
    assert!(AuthorizedKeys::parse(&duplicated).is_err());
}
