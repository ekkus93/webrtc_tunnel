//! Forward-table rules (multi-forward acceptance, duplicate/invalid rejection) and the
//! dead / removed-knob rejections.

use std::fs;

use super::support::*;

#[test]
fn config_rejects_empty_answer_allowlist() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("allow_remote_peers = [\"offer-home\"]", "allow_remote_peers = []");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_accepts_answer_config_with_two_forwards() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = append_answer_forward(sample_config(&config_dir, &state_dir), "web-ui", 8080);
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");

    let loaded = AppConfig::load_from_file(&config_path).expect("config should load");
    assert_eq!(loaded.forwards.len(), 2);
}

#[test]
fn config_accepts_offer_config_with_two_forwards() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = append_offer_forward(offer_config(&config_dir, &state_dir), "web-ui", 8080);
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");

    let loaded = AppConfig::load_from_file(&config_path).expect("config should load");
    assert_eq!(loaded.forwards.len(), 2);
}

#[test]
fn config_rejects_duplicate_forward_ids() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = append_answer_forward(sample_config(&config_dir, &state_dir), "ssh", 8080);
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_duplicate_offer_listen_sockets() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = append_offer_forward(offer_config(&config_dir, &state_dir), "web-ui", 2223);
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_invalid_forward_ids() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    for invalid in ["", "bad/id", "bad:id", "bad id", "bad\\id"] {
        let config = sample_config(&config_dir, &state_dir)
            .replace("id = \"ssh\"", &format!("id = \"{invalid}\""));
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err(), "{invalid}");
    }
}

#[test]
fn config_rejects_missing_role_specific_forward_ports() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    for config in [
        offer_config(&config_dir, &state_dir).replace("listen_port = 2223\n", ""),
        sample_config(&config_dir, &state_dir).replace("target_port = 22\n", ""),
    ] {
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }
}

#[test]
fn old_single_forward_config_is_rejected() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir).replace(
            "[tunnel]\nread_chunk_size = 16384",
            "[tunnel]\nread_chunk_size = 16384\n\n[tunnel.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = 2223\nremote_peer_id = \"answer-office\"",
        );
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_dead_knobs() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    for (from, to) in [
        ("log_rotation = \"none\"", "log_rotation = \"daily\""),
        ("status_socket = \"\"", "status_socket = \"/tmp/p2ptunnel.sock\""),
        ("hold_local_client_during_reconnect = false", "hold_local_client_during_reconnect = true"),
        ("local_client_hold_secs = 0", "local_client_hold_secs = 5"),
    ] {
        let config = sample_config(&config_dir, &state_dir).replace(from, to);
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err(), "{to}");
    }
}

#[test]
fn config_rejects_removed_v1_knobs() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    for (anchor, extra) in [
        ("stun_urls = [\"stun:stun.l.google.com:19302\"]", "\nice_gather_timeout_secs = 15"),
        ("enable_trickle_ice = true", "\nice_connection_timeout_secs = 20"),
        ("enable_ice_restart = true", "\nmax_message_size = 262144"),
        ("remote_eof_grace_ms = 250", "\nframe_version = 1"),
        ("read_chunk_size = 16384", "\nwrite_buffer_limit = 262144"),
        ("target_port = 22", "\nauto_open = true"),
        ("[health]", "\nheartbeat_interval_secs = 10\nping_timeout_secs = 30"),
    ] {
        let config =
            sample_config(&config_dir, &state_dir).replace(anchor, &format!("{anchor}{extra}"));
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err(), "{extra}");
    }
}
