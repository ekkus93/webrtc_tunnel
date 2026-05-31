mod runtime;

use std::ffi::CString;
use std::mem::ManuallyDrop;
use std::os::raw::c_char;
use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use p2p_crypto::{IdentityFile, PublicIdentity, generate_identity};
use serde::Serialize;
use tracing::error;

pub use runtime::{
    AndroidRuntimeStatus, AndroidTunnelController, AndroidTunnelMode, AndroidValidationResult,
};

#[derive(Serialize)]
struct IdentityValidationResult {
    valid: bool,
    message: Option<String>,
    canonical_public_identity: Option<String>,
    canonical_private_identity: Option<String>,
    peer_id: Option<String>,
}

fn into_c_string(value: String) -> *mut c_char {
    match CString::new(value) {
        Ok(value) => value.into_raw(),
        Err(_) => CString::new("ffi string contained interior NUL")
            .expect("static fallback string is valid")
            .into_raw(),
    }
}

fn with_controller<R>(
    handle: *mut AndroidTunnelController,
    f: impl FnOnce(&AndroidTunnelController) -> R,
) -> Result<R, String> {
    if handle.is_null() {
        return Err("runtime handle was null".to_owned());
    }
    // SAFETY: the pointer comes from `p2ptunnel_create_runtime` and remains owned by the caller.
    let controller = unsafe { &*handle };
    Ok(f(controller))
}

fn catch_api<F>(f: F) -> i32
where
    F: FnOnce() -> Result<(), String>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(())) => 0,
        Ok(Err(_)) => -1,
        Err(_) => -2,
    }
}

fn catch_api_string<F>(f: F) -> *mut c_char
where
    F: FnOnce() -> Result<String, String>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(value)) => into_c_string(value),
        Ok(Err(error)) => into_c_string(error),
        Err(_) => into_c_string("panic while handling Android bridge call".to_owned()),
    }
}

fn to_jstring(env: &mut JNIEnv<'_>, value: String) -> jstring {
    env.new_string(value).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn p2ptunnel_create_runtime() -> *mut AndroidTunnelController {
    Box::into_raw(Box::new(AndroidTunnelController::new()))
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `handle` must come from `p2ptunnel_create_runtime` and not be used after destroy.
pub unsafe extern "C" fn p2ptunnel_destroy_runtime(handle: *mut AndroidTunnelController) {
    if handle.is_null() {
        return;
    }
    // SAFETY: the pointer was allocated by `p2ptunnel_create_runtime`.
    let mut controller = ManuallyDrop::new(unsafe { Box::from_raw(handle) });
    let stop_result = catch_unwind(AssertUnwindSafe(|| (*controller).stop()));
    if stop_result.is_err() {
        error!("panic while stopping Android runtime during destroy");
    }
    let drop_result = catch_unwind(AssertUnwindSafe(|| unsafe {
        ManuallyDrop::drop(&mut controller);
    }));
    if drop_result.is_err() {
        error!("panic while dropping Android runtime during destroy");
    }
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `handle` must be a valid runtime pointer from `p2ptunnel_create_runtime` and
/// `config_path` must point to a valid, NUL-terminated UTF-8 string.
pub unsafe extern "C" fn p2ptunnel_start_offer(
    handle: *mut AndroidTunnelController,
    config_path: *const c_char,
) -> i32 {
    catch_api(|| {
        if config_path.is_null() {
            return Err("config path was null".to_owned());
        }
        // SAFETY: `config_path` is expected to be a valid, NUL-terminated string from JNI.
        let config_path = unsafe { std::ffi::CStr::from_ptr(config_path) }
            .to_str()
            .map_err(|error| format!("invalid config path: {error}"))?;
        with_controller(handle, |controller| controller.start_offer(config_path))?
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `handle` must be a valid runtime pointer from `p2ptunnel_create_runtime`.
/// `config_path` must point to a valid, NUL-terminated UTF-8 string.
/// `identity_ptr` must point to valid UTF-8 bytes with length `identity_len`.
pub unsafe extern "C" fn p2ptunnel_start_offer_with_identity(
    handle: *mut AndroidTunnelController,
    config_path: *const c_char,
    identity_ptr: *const u8,
    identity_len: usize,
) -> i32 {
    catch_api(|| {
        if config_path.is_null() {
            return Err("config path was null".to_owned());
        }
        if identity_ptr.is_null() {
            return Err("identity bytes pointer was null".to_owned());
        }
        // SAFETY: pointers are expected to reference valid memory for this call.
        let config_path = unsafe { std::ffi::CStr::from_ptr(config_path) }
            .to_str()
            .map_err(|error| format!("invalid config path: {error}"))?;
        // SAFETY: JNI passes pointer and length to an owned byte array for the duration of call.
        let identity_bytes = unsafe { std::slice::from_raw_parts(identity_ptr, identity_len) };
        let identity_toml = std::str::from_utf8(identity_bytes)
            .map_err(|error| format!("identity bytes were not valid UTF-8: {error}"))?;
        with_controller(handle, |controller| {
            controller.start_offer_with_identity(config_path, identity_toml)
        })?
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `handle` must be a valid runtime pointer from `p2ptunnel_create_runtime` and
/// `config_path` must point to a valid, NUL-terminated UTF-8 string.
pub unsafe extern "C" fn p2ptunnel_start_answer(
    handle: *mut AndroidTunnelController,
    config_path: *const c_char,
) -> i32 {
    catch_api(|| {
        if config_path.is_null() {
            return Err("config path was null".to_owned());
        }
        // SAFETY: `config_path` is expected to be a valid, NUL-terminated string from JNI.
        let config_path = unsafe { std::ffi::CStr::from_ptr(config_path) }
            .to_str()
            .map_err(|error| format!("invalid config path: {error}"))?;
        with_controller(handle, |controller| controller.start_answer(config_path))?
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `handle` must be a valid runtime pointer from `p2ptunnel_create_runtime`.
pub unsafe extern "C" fn p2ptunnel_stop(handle: *mut AndroidTunnelController) -> i32 {
    catch_api(|| {
        with_controller(handle, |controller| controller.stop())?;
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `handle` must be a valid runtime pointer from `p2ptunnel_create_runtime`.
pub unsafe extern "C" fn p2ptunnel_status_json(
    handle: *mut AndroidTunnelController,
) -> *mut c_char {
    catch_api_string(|| {
        with_controller(handle, |controller| {
            serde_json::to_string(&controller.status()).map_err(|error| error.to_string())
        })?
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `handle` must be a valid runtime pointer from `p2ptunnel_create_runtime`.
pub unsafe extern "C" fn p2ptunnel_recent_logs_json(
    handle: *mut AndroidTunnelController,
    max_events: usize,
) -> *mut c_char {
    catch_api_string(|| {
        with_controller(handle, |controller| {
            serde_json::to_string(&controller.recent_logs(max_events))
                .map_err(|error| error.to_string())
        })?
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `config_path` must point to a valid, NUL-terminated UTF-8 string.
pub unsafe extern "C" fn p2ptunnel_validate_config(config_path: *const c_char) -> *mut c_char {
    catch_api_string(|| {
        if config_path.is_null() {
            return Err("config path was null".to_owned());
        }
        // SAFETY: `config_path` is expected to be a valid, NUL-terminated string from JNI.
        let config_path = unsafe { std::ffi::CStr::from_ptr(config_path) }
            .to_str()
            .map_err(|error| format!("invalid config path: {error}"))?;
        let result = AndroidTunnelController::validate_config(config_path);
        serde_json::to_string(&result).map_err(|error| error.to_string())
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `config_path` must point to a valid NUL-terminated UTF-8 string.
/// `identity_ptr` must be valid for `identity_len` bytes and contain UTF-8 TOML.
pub unsafe extern "C" fn p2ptunnel_validate_config_with_identity(
    config_path: *const c_char,
    identity_ptr: *const u8,
    identity_len: usize,
) -> *mut c_char {
    catch_api_string(|| {
        if config_path.is_null() {
            return Err("config path was null".to_owned());
        }
        if identity_ptr.is_null() {
            return Err("identity bytes pointer was null".to_owned());
        }
        // SAFETY: caller guarantees valid NUL-terminated config path string.
        let config_path = unsafe { std::ffi::CStr::from_ptr(config_path) }
            .to_str()
            .map_err(|error| format!("invalid config path: {error}"))?;
        // SAFETY: caller guarantees pointer and length validity for this call.
        let identity_bytes = unsafe { std::slice::from_raw_parts(identity_ptr, identity_len) };
        let identity_toml = std::str::from_utf8(identity_bytes)
            .map_err(|error| format!("identity bytes were not valid UTF-8: {error}"))?;
        let result =
            AndroidTunnelController::validate_config_with_identity(config_path, identity_toml);
        serde_json::to_string(&result).map_err(|error| error.to_string())
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `identity_toml` must point to a valid NUL-terminated UTF-8 string.
pub unsafe extern "C" fn p2ptunnel_validate_private_identity(
    identity_toml: *const c_char,
) -> *mut c_char {
    catch_api_string(|| {
        if identity_toml.is_null() {
            return Err("identity text was null".to_owned());
        }
        // SAFETY: caller guarantees valid NUL-terminated identity string.
        let identity_toml = unsafe { std::ffi::CStr::from_ptr(identity_toml) }
            .to_str()
            .map_err(|error| format!("identity text was not valid UTF-8: {error}"))?;
        let payload = match IdentityFile::from_toml(identity_toml) {
            Ok(identity) => IdentityValidationResult {
                valid: true,
                message: None,
                canonical_public_identity: Some(identity.public_identity().render()),
                canonical_private_identity: Some(identity.render_toml()),
                peer_id: Some(identity.peer_id.to_string()),
            },
            Err(error) => IdentityValidationResult {
                valid: false,
                message: Some(error.to_string()),
                canonical_public_identity: None,
                canonical_private_identity: None,
                peer_id: None,
            },
        };
        serde_json::to_string(&payload).map_err(|error| error.to_string())
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `public_identity` must point to a valid NUL-terminated UTF-8 string.
pub unsafe extern "C" fn p2ptunnel_validate_public_identity(
    public_identity: *const c_char,
) -> *mut c_char {
    catch_api_string(|| {
        if public_identity.is_null() {
            return Err("public identity text was null".to_owned());
        }
        // SAFETY: caller guarantees valid NUL-terminated public identity string.
        let public_identity = unsafe { std::ffi::CStr::from_ptr(public_identity) }
            .to_str()
            .map_err(|error| format!("public identity text was not valid UTF-8: {error}"))?;
        let normalized = public_identity.replace("\r\n", "\n").trim().to_owned();
        let payload = match PublicIdentity::parse(&normalized) {
            Ok(identity) => IdentityValidationResult {
                valid: true,
                message: None,
                canonical_public_identity: Some(identity.render()),
                canonical_private_identity: None,
                peer_id: Some(identity.peer_id.to_string()),
            },
            Err(error) => IdentityValidationResult {
                valid: false,
                message: Some(error.to_string()),
                canonical_public_identity: None,
                canonical_private_identity: None,
                peer_id: None,
            },
        };
        serde_json::to_string(&payload).map_err(|error| error.to_string())
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `peer_id` must point to a valid NUL-terminated UTF-8 string.
pub unsafe extern "C" fn p2ptunnel_generate_identity(peer_id: *const c_char) -> *mut c_char {
    catch_api_string(|| {
        if peer_id.is_null() {
            return Err("peer_id was null".to_owned());
        }
        // SAFETY: caller guarantees valid NUL-terminated peer_id string.
        let peer_id = unsafe { std::ffi::CStr::from_ptr(peer_id) }
            .to_str()
            .map_err(|error| format!("peer_id was not valid UTF-8: {error}"))?;
        let generated = generate_identity(peer_id).map_err(|error| error.to_string())?;
        let payload = IdentityValidationResult {
            valid: true,
            message: None,
            canonical_public_identity: Some(generated.public_identity.render()),
            canonical_private_identity: Some(generated.identity.render_toml()),
            peer_id: Some(generated.identity.peer_id.to_string()),
        };
        serde_json::to_string(&payload).map_err(|error| error.to_string())
    })
}

#[unsafe(no_mangle)]
/// # Safety
///
/// `ptr` must have been returned by one of the bridge string functions and must
/// not be used after this call.
pub unsafe extern "C" fn p2ptunnel_free_string(ptr: *mut c_char) {
    if ptr.is_null() {
        return;
    }
    // SAFETY: pointer must have been returned by `into_c_string`.
    let _ = unsafe { CString::from_raw(ptr) };
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeCreateRuntime(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    p2ptunnel_create_runtime() as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeDestroyRuntime(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    unsafe { p2ptunnel_destroy_runtime(handle as *mut AndroidTunnelController) };
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeStartOffer(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    config_path: JString<'_>,
) -> jint {
    let config_path = match env.get_string(&config_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => return -1,
    };
    let c_path = match CString::new(config_path) {
        Ok(value) => value,
        Err(_) => return -1,
    };
    match unsafe { p2ptunnel_start_offer(handle as *mut AndroidTunnelController, c_path.as_ptr()) }
    {
        0 => 0,
        value => value as jint,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeStartOfferWithIdentity(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    config_path: JString<'_>,
    identity_bytes: jni::objects::JByteArray<'_>,
) -> jint {
    let config_path = match env.get_string(&config_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => return -1,
    };
    let c_path = match CString::new(config_path) {
        Ok(value) => value,
        Err(_) => return -1,
    };
    let identity = match env.convert_byte_array(&identity_bytes) {
        Ok(bytes) => bytes,
        Err(_) => return -1,
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
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeStartAnswer(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    config_path: JString<'_>,
) -> jint {
    let config_path = match env.get_string(&config_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => return -1,
    };
    let c_path = match CString::new(config_path) {
        Ok(value) => value,
        Err(_) => return -1,
    };
    match unsafe { p2ptunnel_start_answer(handle as *mut AndroidTunnelController, c_path.as_ptr()) }
    {
        0 => 0,
        value => value as jint,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeStop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    unsafe { p2ptunnel_stop(handle as *mut AndroidTunnelController) as jint }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeStatusJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let ptr = unsafe { p2ptunnel_status_json(handle as *mut AndroidTunnelController) };
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: the pointer was allocated by `p2ptunnel_status_json`.
    let value = unsafe { CString::from_raw(ptr) }.into_string().unwrap_or_else(|_| "{}".to_owned());
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeRecentLogsJson(
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
    let value = unsafe { CString::from_raw(ptr) }.into_string().unwrap_or_else(|_| "[]".to_owned());
    to_jstring(&mut env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeValidateConfig(
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
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeValidateConfigWithIdentity(
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
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeValidatePrivateIdentity(
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
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeValidatePublicIdentity(
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
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeGenerateIdentity(
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
pub extern "system" fn Java_com_phillipchin_webrtctunnel_RustTunnelBridge_nativeLastError(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let handle = handle as *mut AndroidTunnelController;
    let error = with_controller(handle, |controller| controller.last_error())
        .ok()
        .flatten()
        .unwrap_or_else(|| "unknown error".to_owned());
    to_jstring(&mut env, error)
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
