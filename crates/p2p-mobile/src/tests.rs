use std::ffi::CStr;
use std::os::raw::c_char;

use p2p_crypto::generate_identity;
use serde_json::Value;

use super::*;

fn read_and_free(ptr: *mut c_char) -> String {
    assert!(!ptr.is_null(), "bridge returned a null pointer");
    let value = unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned();
    unsafe { p2ptunnel_free_string(ptr) };
    value
}

#[test]
fn destroy_runtime_handles_null_pointer() {
    unsafe {
        p2ptunnel_destroy_runtime(std::ptr::null_mut());
    }
}

#[test]
fn destroy_runtime_is_safe_for_fresh_handle() {
    let handle = p2ptunnel_create_runtime();
    unsafe {
        p2ptunnel_destroy_runtime(handle);
    }
}

#[test]
fn status_json_shape_is_stable_and_parseable() {
    let handle = p2ptunnel_create_runtime();
    let raw = unsafe { p2ptunnel_status_json(handle) };
    let parsed: Value = serde_json::from_str(&read_and_free(raw)).expect("status json");
    assert_eq!(parsed.get("state").and_then(Value::as_str), Some("stopped"));
    assert!(parsed.get("active").and_then(Value::as_bool).is_some());
    unsafe { p2ptunnel_destroy_runtime(handle) };
}

#[test]
fn recent_logs_json_is_stable_and_side_effect_free() {
    let handle = p2ptunnel_create_runtime();
    let first: Value =
        serde_json::from_str(&read_and_free(unsafe { p2ptunnel_recent_logs_json(handle, 4) }))
            .expect("first logs json");
    let second: Value =
        serde_json::from_str(&read_and_free(unsafe { p2ptunnel_recent_logs_json(handle, 4) }))
            .expect("second logs json");
    assert_eq!(first, second);
    assert!(first.as_array().is_some());
    unsafe { p2ptunnel_destroy_runtime(handle) };
}

#[test]
fn recent_logs_json_surfaces_a_synthetic_error_entry_when_state_mutex_is_poisoned() {
    let handle = p2ptunnel_create_runtime();
    super::with_controller(handle, |controller| controller.poison_state_mutex_for_test())
        .expect("controller present");
    let raw = unsafe { p2ptunnel_recent_logs_json(handle, 4) };
    let parsed: Value = serde_json::from_str(&read_and_free(raw)).expect("logs json");
    let events = parsed.as_array().expect("logs json is an array");
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].get("level").and_then(Value::as_str), Some("error"));
    let message = events[0].get("message").and_then(Value::as_str).expect("message field");
    assert!(message.contains("runtime mutex poisoned"), "got: {message}");
    unsafe { p2ptunnel_destroy_runtime(handle) };
}

#[test]
fn generate_identity_returns_expected_json_fields() {
    let peer_id = CString::new("android-test").expect("peer id cstring");
    let raw = unsafe { p2ptunnel_generate_identity(peer_id.as_ptr()) };
    let parsed: Value = serde_json::from_str(&read_and_free(raw)).expect("identity json");
    assert_eq!(parsed.get("valid").and_then(Value::as_bool), Some(true));
    assert!(parsed.get("peer_id").and_then(Value::as_str).is_some());
    assert!(parsed.get("canonical_public_identity").and_then(Value::as_str).is_some());
    assert!(parsed.get("canonical_private_identity").and_then(Value::as_str).is_some());
}

#[test]
fn generate_identity_reports_invalid_utf8_peer_id_input() {
    let bytes = [0xFF_u8, 0_u8];
    let ptr = bytes.as_ptr() as *const c_char;
    let message = read_and_free(unsafe { p2ptunnel_generate_identity(ptr) });
    assert!(message.contains("peer_id was not valid UTF-8"));
}

#[test]
fn validate_config_with_identity_rejects_invalid_utf8_identity_bytes() {
    let config_path = CString::new("/definitely/missing/config.toml").expect("config cstring");
    let identity_bytes = [0xFF_u8];
    let message = read_and_free(unsafe {
        p2ptunnel_validate_config_with_identity(
            config_path.as_ptr(),
            identity_bytes.as_ptr(),
            identity_bytes.len(),
        )
    });
    assert!(message.contains("identity bytes were not valid UTF-8"));
}

#[test]
fn validate_config_with_identity_missing_config_returns_failure_payload() {
    let config_path = CString::new("/definitely/missing/config.toml").expect("config cstring");
    let identity = generate_identity("android-test").expect("identity");
    let identity_toml = identity.identity.render_toml();
    let raw = unsafe {
        p2ptunnel_validate_config_with_identity(
            config_path.as_ptr(),
            identity_toml.as_bytes().as_ptr(),
            identity_toml.len(),
        )
    };
    let parsed: Value = serde_json::from_str(&read_and_free(raw)).expect("validation json");
    assert_eq!(parsed.get("valid").and_then(Value::as_bool), Some(false));
    assert!(parsed.get("message").is_some());
}

#[test]
fn last_error_path_reports_unknown_then_runtime_error() {
    let handle = p2ptunnel_create_runtime();
    assert_eq!(super::last_error_for_handle(handle), "unknown error");

    let config_path = CString::new("/definitely/missing/config.toml").expect("config cstring");
    assert_eq!(unsafe { p2ptunnel_start_offer(handle, config_path.as_ptr()) }, -1);

    let last_error = super::last_error_for_handle(handle);
    assert_ne!(last_error, "unknown error");
    let status: Value =
        serde_json::from_str(&read_and_free(unsafe { p2ptunnel_status_json(handle) }))
            .expect("status json after error");
    assert!(status.get("state").is_some());
    let generate = serde_json::from_str::<Value>(&read_and_free(unsafe {
        p2ptunnel_generate_identity(CString::new("android-test").expect("peer id").as_ptr())
    }))
    .expect("identity after error");
    assert_eq!(generate.get("valid").and_then(Value::as_bool), Some(true));

    unsafe { p2ptunnel_destroy_runtime(handle) };
}

#[test]
fn null_runtime_handle_returns_error_message_for_status_json() {
    let message = read_and_free(unsafe { p2ptunnel_status_json(std::ptr::null_mut()) });
    assert!(message.contains("runtime handle was null"));
}

#[test]
fn last_error_for_null_handle_reports_invalid_handle_not_unknown_error() {
    // An invalid handle must surface its own specific reason, distinct from the
    // "unknown error" sentinel reserved for a valid handle with nothing recorded
    // yet (see last_error_path_reports_unknown_then_runtime_error above).
    let last_error = super::last_error_for_handle(std::ptr::null_mut());
    assert_ne!(last_error, "unknown error");
    assert!(last_error.contains("runtime handle was null"), "got: {last_error}");
}

#[test]
fn start_offer_with_null_config_path_records_specific_error() {
    // A pre-controller failure (null path) must still leave a specific, Kotlin-visible error,
    // not the generic "unknown error".
    let handle = p2ptunnel_create_runtime();
    assert_eq!(unsafe { p2ptunnel_start_offer(handle, std::ptr::null()) }, -1);
    let last_error = super::last_error_for_handle(handle);
    assert!(last_error.contains("config path was null"), "got: {last_error}");
    unsafe { p2ptunnel_destroy_runtime(handle) };
}

#[test]
fn start_offer_with_invalid_utf8_config_path_records_error() {
    let handle = p2ptunnel_create_runtime();
    let bytes = [0xFF_u8, 0_u8];
    let ptr = bytes.as_ptr() as *const c_char;
    assert_eq!(unsafe { p2ptunnel_start_offer(handle, ptr) }, -1);
    let last_error = super::last_error_for_handle(handle);
    assert!(last_error.contains("invalid config path"), "got: {last_error}");
    unsafe { p2ptunnel_destroy_runtime(handle) };
}

#[test]
fn record_bridge_error_surfaces_via_last_error() {
    // The recorder the JNI marshalling paths use must make the message retrievable.
    let handle = p2ptunnel_create_runtime();
    super::with_controller(handle, |controller| {
        controller
            .record_bridge_error("marshalling boom".to_owned())
            .expect("state mutex is not poisoned");
    })
    .expect("controller present");
    assert_eq!(super::last_error_for_handle(handle), "marshalling boom");
    unsafe { p2ptunnel_destroy_runtime(handle) };
}
