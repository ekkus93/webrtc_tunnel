//! `AppConfig::required_authorized_peer_ids`: the daemon preflight and `p2pctl
//! check-config` share this to enumerate which remote peers must have an
//! `authorized_keys` entry for a given role.

use std::fs;

use super::support::*;

#[test]
fn answer_role_returns_every_forward_allowed_peer() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    write_required_files(&config_dir);

    let config_path = temp_dir.path().join("config.toml");
    let config_toml = append_answer_forward(sample_config(&config_dir, &state_dir), "web", 8080);
    fs::write(&config_path, config_toml).expect("write config");

    let config = AppConfig::load_from_file(&config_path).expect("config should load");
    let required: Vec<String> = config
        .required_authorized_peer_ids()
        .expect("answer role always resolves")
        .into_iter()
        .map(ToString::to_string)
        .collect();
    // Both forwards allow "offer-home" in the shared fixture; the list may contain the
    // same peer id twice (once per forward) — the caller only needs "is it present".
    assert!(required.iter().all(|peer_id| peer_id == "offer-home"));
    assert!(!required.is_empty());
}

#[test]
fn offer_role_returns_the_single_configured_remote_peer() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    write_required_files(&config_dir);

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, offer_config(&config_dir, &state_dir)).expect("write config");

    let config = AppConfig::load_from_file(&config_path).expect("config should load");
    let required = config.required_authorized_peer_ids().expect("offer role always resolves");
    assert_eq!(required.len(), 1);
    assert_eq!(required[0].as_str(), "answer-office");
}
