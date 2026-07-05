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

#[test]
fn identity_file_missing_error_names_path() {
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("missing-identity");

    let error = IdentityFile::from_file(&path).err().expect("missing identity should fail");

    assert!(error.to_string().contains(path.to_string_lossy().as_ref()));
}

/// Replaces the `occurrence`-th line starting with `prefix` (0-indexed) with `new_line`,
/// leaving every other line untouched — used to corrupt exactly one of the TOML's two
/// `private = "..."` (or `public = "..."`) lines without disturbing the other section.
fn replace_nth_line_starting_with(
    content: &str,
    prefix: &str,
    occurrence: usize,
    new_line: &str,
) -> String {
    let mut seen = 0;
    content
        .lines()
        .map(|line| {
            if line.starts_with(prefix) {
                let replaced = if seen == occurrence { new_line } else { line };
                seen += 1;
                replaced
            } else {
                line
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[test]
fn identity_parsing_rejects_unsupported_format_version() {
    let generated = generate_identity("offer-home").expect("identity generation should succeed");
    let toml = generated
        .identity
        .render_toml()
        .replace("format = \"p2ptunnel-identity-v1\"", "format = \"p2ptunnel-identity-v2\"");

    let error =
        IdentityFile::from_toml(&toml).err().expect("unsupported format version must be rejected");
    assert!(error.to_string().contains("unsupported identity format"), "{error}");
}

#[test]
fn identity_parsing_rejects_missing_format_field() {
    let generated = generate_identity("offer-home").expect("identity generation should succeed");
    let toml = generated
        .identity
        .render_toml()
        .lines()
        .filter(|line| !line.starts_with("format ="))
        .collect::<Vec<_>>()
        .join("\n");

    assert!(IdentityFile::from_toml(&toml).is_err(), "missing format field must be rejected");
}

#[test]
fn identity_parsing_rejects_unknown_sign_alg() {
    let generated = generate_identity("offer-home").expect("identity generation should succeed");
    let toml = generated.identity.render_toml().replace("alg = \"ed25519\"", "alg = \"ed448\"");

    let error = IdentityFile::from_toml(&toml).err().expect("unknown sign.alg must be rejected");
    assert!(error.to_string().contains("sign.alg must be ed25519"), "{error}");
}

#[test]
fn identity_parsing_rejects_unknown_kex_alg() {
    let generated = generate_identity("offer-home").expect("identity generation should succeed");
    let toml = generated.identity.render_toml().replace("alg = \"x25519\"", "alg = \"x448\"");

    let error = IdentityFile::from_toml(&toml).err().expect("unknown kex.alg must be rejected");
    assert!(error.to_string().contains("kex.alg must be x25519"), "{error}");
}

#[test]
fn identity_parsing_rejects_non_base64_sign_private() {
    let generated = generate_identity("offer-home").expect("identity generation should succeed");
    let toml = replace_nth_line_starting_with(
        &generated.identity.render_toml(),
        "private = ",
        0,
        "private = \"not valid base64!!\"",
    );

    assert!(IdentityFile::from_toml(&toml).is_err(), "non-base64 sign.private must be rejected");
}

#[test]
fn identity_parsing_rejects_wrong_length_sign_private() {
    let generated = generate_identity("offer-home").expect("identity generation should succeed");
    // "AAAA" is valid base64 but decodes to 3 bytes, not the required 32.
    let toml = replace_nth_line_starting_with(
        &generated.identity.render_toml(),
        "private = ",
        0,
        "private = \"AAAA\"",
    );

    let error =
        IdentityFile::from_toml(&toml).err().expect("wrong-length sign.private must be rejected");
    assert!(error.to_string().contains("sign.private must decode to exactly 32 bytes"), "{error}");
}
