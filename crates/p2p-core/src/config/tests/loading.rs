//! Loading/parsing, runtime-directory creation, broker-auth combinations, and path
//! expansion.

use std::fs;
use std::path::Path;

use super::support::*;

#[test]
fn config_loads_and_parses() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

    let config = AppConfig::load_from_file(&config_path).expect("config should load");
    assert_eq!(config.paths.identity, config_dir.join("identity"));
}

#[test]
fn documented_sample_configs_parse_and_validate() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    write_required_files(&config_dir);

    for sample in [
        include_str!("../../../../../docs/examples/offer-config.toml"),
        include_str!("../../../../../docs/examples/answer-config.toml"),
    ] {
        let content = render_documented_sample(sample, &config_dir, &state_dir);
        let mut config: AppConfig = toml::from_str(&content).expect("sample should parse");
        config.expand_paths().expect("sample paths should expand");
        config.validate().expect("sample should validate");
    }
}

#[test]
fn config_loads_when_runtime_dirs_are_missing() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    write_required_files(&config_dir);

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

    let config = AppConfig::load_from_file(&config_path).expect("config should load");
    assert!(!config.paths.state_dir.exists());
    assert!(!config.paths.log_dir.exists());
}

#[test]
fn ensure_runtime_dirs_creates_missing_directories() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    write_required_files(&config_dir);

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

    let config = AppConfig::load_from_file(&config_path).expect("config should load");
    config.ensure_runtime_dirs().expect("runtime dirs should be created");

    assert!(config.paths.state_dir.is_dir());
    assert!(config.paths.log_dir.is_dir());
    assert!(config.logging.log_file.parent().expect("log parent").is_dir());
    assert!(config.health.status_file.parent().expect("status parent").is_dir());
}

#[test]
fn expand_home_uses_current_home_directory() {
    let home = std::env::var_os("HOME").expect("HOME should be set for tests");
    let expanded = expand_home(Path::new("~/example")).expect("path should expand");
    assert_eq!(expanded, std::path::PathBuf::from(home).join("example"));
}

#[test]
fn config_allows_anonymous_broker_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("username = \"answer-office\"", "username = \"\"")
        .replace(
            &format!("password_file = \"{}\"", config_dir.join("mqtt_password").display()),
            "password_file = \"\"",
        );
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    AppConfig::load_from_file(&config_path).expect("anonymous config");
}

#[test]
fn config_allows_username_only_broker_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir).replace(
        &format!("password_file = \"{}\"", config_dir.join("mqtt_password").display()),
        "password_file = \"\"",
    );
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    AppConfig::load_from_file(&config_path).expect("username-only config");
}

#[test]
fn config_allows_mqtts_without_explicit_ca_file() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace(&format!("ca_file = \"{}\"", config_dir.join("ca.crt").display()), "");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    AppConfig::load_from_file(&config_path).expect("default-root TLS config");
}

#[test]
fn load_with_identity_override_does_not_require_identity_file() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);
    fs::remove_file(config_dir.join("identity")).expect("remove identity");

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, offer_config(&config_dir, &state_dir)).expect("write config");

    assert!(AppConfig::load_from_file(&config_path).is_err());
    AppConfig::load_from_file_with_identity_override(&config_path)
        .expect("identity override should allow loading without paths.identity");
}

#[test]
fn config_rejects_password_without_username() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("username = \"answer-office\"", "username = \"\"");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_partial_broker_client_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("client_key_file = \"\"", "client_key_file = \"/tmp/client.key\"");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_unsupported_session_expiry() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("session_expiry_secs = 0", "session_expiry_secs = 60");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_unsupported_connect_timeout() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("connect_timeout_secs = 5", "connect_timeout_secs = 10");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn missing_config_file_error_names_path() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let path = temp_dir.path().join("missing-config.toml");

    let error = AppConfig::load_from_file(&path).expect_err("config load should fail");

    assert!(error.to_string().contains(path.to_string_lossy().as_ref()));
}
