//! Android JNI bridge (`Java_*`). These `#[no_mangle] extern "system"` functions
//! marshal JNI arguments (handles, jstrings, byte arrays) and delegate to the
//! C-ABI surface in [`crate::c_abi`], returning results as Java primitives or
//! jstrings. Two Java classes are served: NativeControlLib (runtime control) and
//! RustValidationBridge (config/identity validation).

use std::ffi::CString;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};

use crate::c_abi::*;

use super::{AndroidTunnelController, last_error_for_handle, to_jstring, with_controller};

/// Record a JNI-marshalling failure (which happens before the controller can run) on the
/// handle's `last_error`, so Kotlin's `nativeLastError()` surfaces the real cause instead of
/// the generic "unknown error". If recording itself fails (invalid handle or a poisoned
/// state mutex), that failure is logged rather than silently discarded.
fn record_marshalling_error(handle: jlong, message: String) {
    match with_controller(handle as *mut AndroidTunnelController, |controller| {
        controller.record_bridge_error(message.clone())
    }) {
        Ok(Ok(())) => {}
        Ok(Err(reason)) => {
            tracing::error!(%reason, %message, "failed to record marshalling error: state mutex poisoned");
        }
        Err(reason) => {
            tracing::error!(%reason, %message, "failed to record marshalling error: invalid handle");
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeCreateRuntime(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    p2ptunnel_create_runtime() as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeDestroyRuntime(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    unsafe { p2ptunnel_destroy_runtime(handle as *mut AndroidTunnelController) };
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeStartOffer(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    config_path: JString<'_>,
) -> jint {
    let config_path = match env.get_string(&config_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            record_marshalling_error(handle, format!("failed to read config path string: {error}"));
            return -1;
        }
    };
    let c_path = match CString::new(config_path) {
        Ok(value) => value,
        Err(error) => {
            record_marshalling_error(
                handle,
                format!("config path contained interior NUL: {error}"),
            );
            return -1;
        }
    };
    match unsafe { p2ptunnel_start_offer(handle as *mut AndroidTunnelController, c_path.as_ptr()) }
    {
        0 => 0,
        value => value as jint,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeStartOfferWithIdentity(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    config_path: JString<'_>,
    identity_bytes: jni::objects::JByteArray<'_>,
) -> jint {
    let config_path = match env.get_string(&config_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            record_marshalling_error(handle, format!("failed to read config path string: {error}"));
            return -1;
        }
    };
    let c_path = match CString::new(config_path) {
        Ok(value) => value,
        Err(error) => {
            record_marshalling_error(
                handle,
                format!("config path contained interior NUL: {error}"),
            );
            return -1;
        }
    };
    let identity = match env.convert_byte_array(&identity_bytes) {
        Ok(bytes) => bytes,
        Err(error) => {
            record_marshalling_error(
                handle,
                format!("failed to read identity byte array: {error}"),
            );
            return -1;
        }
    };
    match unsafe {
        p2ptunnel_start_offer_with_identity(
            handle as *mut AndroidTunnelController,
            c_path.as_ptr(),
            identity.as_ptr(),
            identity.len(),
        )
    } {
        0 => 0,
        value => value as jint,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeStartAnswer(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    config_path: JString<'_>,
) -> jint {
    let config_path = match env.get_string(&config_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            record_marshalling_error(handle, format!("failed to read config path string: {error}"));
            return -1;
        }
    };
    let c_path = match CString::new(config_path) {
        Ok(value) => value,
        Err(error) => {
            record_marshalling_error(
                handle,
                format!("config path contained interior NUL: {error}"),
            );
            return -1;
        }
    };
    match unsafe { p2ptunnel_start_answer(handle as *mut AndroidTunnelController, c_path.as_ptr()) }
    {
        0 => 0,
        value => value as jint,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeStop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    unsafe { p2ptunnel_stop(handle as *mut AndroidTunnelController) as jint }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeStatusJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let ptr = unsafe { p2ptunnel_status_json(handle as *mut AndroidTunnelController) };
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: the pointer was allocated by `p2ptunnel_status_json`.
    // Invalid native UTF-8 must surface as a visible error status, not a silent empty object.
    let value = unsafe { CString::from_raw(ptr) }.into_string().unwrap_or_else(|_| {
        r#"{"state":"error","last_error":"native returned invalid UTF-8 for status JSON"}"#
            .to_owned()
    });
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeRecentLogsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    max_events: jint,
) -> jstring {
    let ptr = unsafe {
        p2ptunnel_recent_logs_json(handle as *mut AndroidTunnelController, max_events as usize)
    };
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: the pointer was allocated by `p2ptunnel_recent_logs_json`.
    // Invalid native UTF-8 must surface as a visible error log entry, not an empty list that
    // looks like "no logs".
    let value = unsafe { CString::from_raw(ptr) }.into_string().unwrap_or_else(|_| {
        r#"[{"unix_ms":0,"level":"error","message":"native returned invalid UTF-8 for log JSON"}]"#
            .to_owned()
    });
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustValidationBridge_nativeValidateConfig(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config_path: JString<'_>,
) -> jstring {
    let config_path = match env.get_string(&config_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let ptr = match CString::new(config_path) {
        Ok(c_path) => unsafe { p2ptunnel_validate_config(c_path.as_ptr()) },
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: the pointer was allocated by `p2ptunnel_validate_config`.
    let value = unsafe { CString::from_raw(ptr) }
        .into_string()
        .unwrap_or_else(|_| r#"{"valid":false,"message":"invalid utf-8"}"#.to_owned());
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustValidationBridge_nativeValidateConfigWithIdentity(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config_path: JString<'_>,
    identity_bytes: jni::objects::JByteArray<'_>,
) -> jstring {
    let config_path = match env.get_string(&config_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let identity = match env.convert_byte_array(&identity_bytes) {
        Ok(bytes) => bytes,
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let c_path = match CString::new(config_path) {
        Ok(value) => value,
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let ptr = unsafe {
        p2ptunnel_validate_config_with_identity(c_path.as_ptr(), identity.as_ptr(), identity.len())
    };
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: pointer allocated by `p2ptunnel_validate_config_with_identity`.
    let value = unsafe { CString::from_raw(ptr) }
        .into_string()
        .unwrap_or_else(|_| r#"{"valid":false,"message":"invalid utf-8"}"#.to_owned());
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustValidationBridge_nativeValidatePrivateIdentity(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    private_identity_toml: JString<'_>,
) -> jstring {
    let identity = match env.get_string(&private_identity_toml) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let c_identity = match CString::new(identity) {
        Ok(value) => value,
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let ptr = unsafe { p2ptunnel_validate_private_identity(c_identity.as_ptr()) };
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: pointer allocated by `p2ptunnel_validate_private_identity`.
    let value = unsafe { CString::from_raw(ptr) }
        .into_string()
        .unwrap_or_else(|_| r#"{"valid":false,"message":"invalid utf-8"}"#.to_owned());
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustValidationBridge_nativeValidatePublicIdentity(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    public_identity_line: JString<'_>,
) -> jstring {
    let line = match env.get_string(&public_identity_line) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let c_line = match CString::new(line) {
        Ok(value) => value,
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let ptr = unsafe { p2ptunnel_validate_public_identity(c_line.as_ptr()) };
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: pointer allocated by `p2ptunnel_validate_public_identity`.
    let value = unsafe { CString::from_raw(ptr) }
        .into_string()
        .unwrap_or_else(|_| r#"{"valid":false,"message":"invalid utf-8"}"#.to_owned());
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustValidationBridge_nativeGenerateIdentity(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    peer_id: JString<'_>,
) -> jstring {
    let peer_id = match env.get_string(&peer_id) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let c_peer_id = match CString::new(peer_id) {
        Ok(value) => value,
        Err(error) => {
            return to_jstring(
                &mut env,
                serde_json::json!({"valid": false, "message": error.to_string()}).to_string(),
            );
        }
    };
    let ptr = unsafe { p2ptunnel_generate_identity(c_peer_id.as_ptr()) };
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: pointer allocated by `p2ptunnel_generate_identity`.
    let value = unsafe { CString::from_raw(ptr) }
        .into_string()
        .unwrap_or_else(|_| r#"{"valid":false,"message":"invalid utf-8"}"#.to_owned());
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustWebRtcProbe_nativeWebrtcProbe(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    timeout_secs: jlong,
) -> jstring {
    let timeout_secs = timeout_secs.max(0) as u64;
    let ptr = p2ptunnel_webrtc_probe_json(timeout_secs);
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: the pointer was allocated by `p2ptunnel_webrtc_probe_json`.
    // Invalid native UTF-8 must surface as an explicit probe error, not a silent empty object.
    let value = unsafe { CString::from_raw(ptr) }.into_string().unwrap_or_else(|_| {
        r#"{"error":"native returned invalid UTF-8 for probe JSON"}"#.to_owned()
    });
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_NativeControlLib_nativeLastError(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let error = last_error_for_handle(handle as *mut AndroidTunnelController);
    to_jstring(&mut env, error)
}
