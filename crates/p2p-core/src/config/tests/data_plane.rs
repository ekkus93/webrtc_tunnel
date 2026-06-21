//! The data-plane / ICE-mode config fields: `android_ice_mode`, `advertised_local_ipv4`,
//! TURN rejection, and the probe/heartbeat range validation.

use super::support::*;

#[test]
fn missing_new_fields_default_to_auto_and_5000() {
    // The base sample omits both new fields, proving backward compatibility: existing
    // configs still parse and the documented defaults apply.
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    let config = load_config(&sample_config(&config_dir, &state_dir), &config_dir, &state_dir)
        .expect("base config should load");
    assert_eq!(config.webrtc.android_ice_mode, AndroidIceMode::Auto);
    assert_eq!(config.tunnel.data_plane_probe_timeout_ms, 5000);
}

#[test]
fn android_ice_mode_parses_all_variants() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    for (toml_value, expected) in [
        ("auto", AndroidIceMode::Auto),
        ("native", AndroidIceMode::Native),
        ("vnet", AndroidIceMode::Vnet),
        ("vnet_mux", AndroidIceMode::VnetMux),
    ] {
        let config_dir = temp_dir.path().join(format!("config-{toml_value}"));
        let state_dir = temp_dir.path().join(format!("state-{toml_value}"));
        let config = sample_config(&config_dir, &state_dir).replace(
            "enable_ice_restart = true",
            &format!("enable_ice_restart = true\nandroid_ice_mode = \"{toml_value}\""),
        );
        let loaded = load_config(&config, &config_dir, &state_dir)
            .unwrap_or_else(|error| panic!("{toml_value} should load: {error}"));
        assert_eq!(loaded.webrtc.android_ice_mode, expected);
    }
}

#[test]
fn invalid_android_ice_mode_is_rejected() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    let config = sample_config(&config_dir, &state_dir).replace(
        "enable_ice_restart = true",
        "enable_ice_restart = true\nandroid_ice_mode = \"turn\"",
    );
    assert!(load_config(&config, &config_dir, &state_dir).is_err());
}

#[test]
fn advertised_local_ipv4_valid_is_accepted() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    let config = sample_config(&config_dir, &state_dir).replace(
        "enable_ice_restart = true",
        "enable_ice_restart = true\nadvertised_local_ipv4 = \"10.1.3.11\"",
    );
    let loaded = load_config(&config, &config_dir, &state_dir).expect("valid address should load");
    assert_eq!(loaded.webrtc.advertised_local_ipv4.as_deref(), Some("10.1.3.11"));
}

#[test]
fn advertised_local_ipv4_invalid_is_rejected() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    // Garbage, loopback, unspecified, multicast, and out-of-range octets must all fail
    // config validation rather than be advertised as a host candidate.
    for value in ["garbage", "127.0.0.1", "0.0.0.0", "224.0.0.1", "256.1.1.1", "::1"] {
        let config_dir = temp_dir.path().join(format!("config-{value}"));
        let state_dir = temp_dir.path().join(format!("state-{value}"));
        let config = sample_config(&config_dir, &state_dir).replace(
            "enable_ice_restart = true",
            &format!("enable_ice_restart = true\nadvertised_local_ipv4 = \"{value}\""),
        );
        assert!(
            load_config(&config, &config_dir, &state_dir).is_err(),
            "advertised_local_ipv4 = {value} should be rejected"
        );
    }
}

#[test]
fn turn_url_is_rejected_by_config_validation() {
    // TURN must fail at config-validation time (before tunnel startup), not only at
    // WebRTC-construction time.
    let temp_dir = tempfile::tempdir().expect("temp dir");
    for url in ["turn:relay.example.com:3478", "turns:relay.example.com:5349"] {
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        let config = sample_config(&config_dir, &state_dir)
            .replace("\"stun:stun.l.google.com:19302\"", &format!("\"{url}\""));
        assert!(
            load_config(&config, &config_dir, &state_dir).is_err(),
            "{url} should be rejected by config validation"
        );
    }
}

#[test]
fn probe_timeout_out_of_range_is_rejected() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    for value in ["0", "50", "60001", "120000"] {
        let config_dir = temp_dir.path().join(format!("config-{value}"));
        let state_dir = temp_dir.path().join(format!("state-{value}"));
        let config = sample_config(&config_dir, &state_dir).replace(
            "remote_eof_grace_ms = 250",
            &format!("remote_eof_grace_ms = 250\ndata_plane_probe_timeout_ms = {value}"),
        );
        assert!(
            load_config(&config, &config_dir, &state_dir).is_err(),
            "{value} ms should be rejected"
        );
    }
}

#[test]
fn probe_timeout_in_range_is_accepted() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    for value in ["100", "5000", "60000"] {
        let config_dir = temp_dir.path().join(format!("config-{value}"));
        let state_dir = temp_dir.path().join(format!("state-{value}"));
        let config = sample_config(&config_dir, &state_dir).replace(
            "remote_eof_grace_ms = 250",
            &format!("remote_eof_grace_ms = 250\ndata_plane_probe_timeout_ms = {value}"),
        );
        let loaded = load_config(&config, &config_dir, &state_dir)
            .unwrap_or_else(|error| panic!("{value} ms should load: {error}"));
        let expected = value.parse::<u64>().expect("test value parses as u64");
        assert_eq!(loaded.tunnel.data_plane_probe_timeout_ms, expected);
    }
}

#[test]
fn heartbeat_settings_out_of_range_are_rejected() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let cases = [
        ("data_plane_heartbeat_interval_ms", ["0", "499", "60001", "120000"].as_slice()),
        ("data_plane_heartbeat_max_misses", ["0", "101", "1000"].as_slice()),
    ];
    for (field, values) in cases {
        for value in values {
            let config_dir = temp_dir.path().join(format!("cfg-{field}-{value}"));
            let state_dir = temp_dir.path().join(format!("st-{field}-{value}"));
            let config = sample_config(&config_dir, &state_dir).replace(
                "remote_eof_grace_ms = 250",
                &format!("remote_eof_grace_ms = 250\n{field} = {value}"),
            );
            assert!(
                load_config(&config, &config_dir, &state_dir).is_err(),
                "{field} = {value} should be rejected"
            );
        }
    }
}

#[test]
fn heartbeat_settings_default_and_parse() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    // Defaults apply when omitted (backward-compatible).
    let cfg_default = sample_config(&temp_dir.path().join("c-def"), &temp_dir.path().join("s-def"));
    let loaded =
        load_config(&cfg_default, &temp_dir.path().join("c-def"), &temp_dir.path().join("s-def"))
            .expect("default config should load");
    assert_eq!(loaded.tunnel.data_plane_heartbeat_interval_ms, 5000);
    assert_eq!(loaded.tunnel.data_plane_heartbeat_max_misses, 3);

    // In-range overrides are honored.
    let cfg = sample_config(&temp_dir.path().join("c-set"), &temp_dir.path().join("s-set")).replace(
        "remote_eof_grace_ms = 250",
        "remote_eof_grace_ms = 250\ndata_plane_heartbeat_interval_ms = 2000\ndata_plane_heartbeat_max_misses = 5",
    );
    let loaded = load_config(&cfg, &temp_dir.path().join("c-set"), &temp_dir.path().join("s-set"))
        .expect("overridden config should load");
    assert_eq!(loaded.tunnel.data_plane_heartbeat_interval_ms, 2000);
    assert_eq!(loaded.tunnel.data_plane_heartbeat_max_misses, 5);
}
