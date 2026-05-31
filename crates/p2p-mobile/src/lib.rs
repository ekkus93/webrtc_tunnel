mod runtime;

use std::ffi::CString;
use std::os::raw::c_char;
use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};

pub use runtime::{
    AndroidRuntimeStatus, AndroidTunnelController, AndroidTunnelMode, AndroidValidationResult,
};

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
    let controller = unsafe { Box::from_raw(handle) };
    controller.stop();
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
