//! C ABI surface (`p2ptunnel_*`). These `#[no_mangle] extern "C"` functions are the
//! raw FFI entry points: runtime create/destroy, offer/answer start, stop, status
//! and log queries, config/identity validation, identity generation, and string
//! freeing. The JNI bridge in [`crate::jni`] is a thin wrapper over these.

use std::ffi::CString;
use std::mem::ManuallyDrop;
use std::os::raw::c_char;
use std::panic::{AssertUnwindSafe, catch_unwind};

use p2p_crypto::{IdentityFile, PublicIdentity, generate_identity};
use tracing::error;

use super::{
    AndroidTunnelController, IdentityValidationResult, catch_api_recording, catch_api_string,
    with_controller,
};
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
    match stop_result {
        Ok(Err(message)) => {
            error!(reason = %message, "runtime did not stop cleanly during destroy")
        }
        Err(_) => error!("panic while stopping Android runtime during destroy"),
        Ok(Ok(())) => {}
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
    catch_api_recording(handle, || {
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
    catch_api_recording(handle, || {
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
    catch_api_recording(handle, || {
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
    catch_api_recording(handle, || with_controller(handle, |controller| controller.stop())?)
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
/// Run the on-device WebRTC self-diagnostic and return a JSON report. Stateless;
/// takes no pointers. `timeout_secs` bounds candidate gathering and the handshake.
pub extern "C" fn p2ptunnel_webrtc_probe_json(timeout_secs: u64) -> *mut c_char {
    catch_api_string(|| crate::diagnostics::run_webrtc_probe(timeout_secs))
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
