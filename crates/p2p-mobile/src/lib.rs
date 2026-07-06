mod c_abi;
mod diagnostics;
mod jni_bridge;
mod runtime;

use std::ffi::CString;
use std::os::raw::c_char;
use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::sys::jstring;
use serde::Serialize;

// Re-exported for the unit-test module's `super::*` (the C-ABI entry points it drives).
#[cfg(test)]
pub(crate) use c_abi::*;

pub use runtime::{
    AndroidForwardRuntimeStatus, AndroidRuntimeStatus, AndroidTunnelController, AndroidTunnelMode,
    AndroidValidationResult,
};

#[derive(Serialize)]
pub(crate) struct IdentityValidationResult {
    pub(crate) valid: bool,
    pub(crate) message: Option<String>,
    pub(crate) canonical_public_identity: Option<String>,
    pub(crate) canonical_private_identity: Option<String>,
    pub(crate) peer_id: Option<String>,
}

pub(crate) fn into_c_string(value: String) -> *mut c_char {
    match CString::new(value) {
        Ok(value) => value.into_raw(),
        Err(_) => CString::new("ffi string contained interior NUL")
            .expect("static fallback string is valid")
            .into_raw(),
    }
}

pub(crate) fn with_controller<R>(
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

/// Run a control entry point under a panic boundary and record the failure message (and
/// panic) on the controller's `last_error` before returning the error code, so every
/// nonzero control return has correlated, Kotlin-visible error text — including
/// pre-controller failures (null/invalid path) that never reach the runtime's own error
/// recording. Returns `0` on success, `-1` on a handled error, `-2` on a panic.
pub(crate) fn catch_api_recording<F>(handle: *mut AndroidTunnelController, f: F) -> i32
where
    F: FnOnce() -> Result<(), String>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(())) => 0,
        Ok(Err(message)) => {
            record_or_log_bridge_error(handle, message);
            -1
        }
        Err(_) => {
            record_or_log_bridge_error(
                handle,
                "panic while handling Android bridge call".to_owned(),
            );
            -2
        }
    }
}

/// Record `message` as the controller's `last_error`. If the handle is invalid or the
/// controller's state mutex is poisoned, the failure to record is logged rather than
/// silently discarded, so a lost primary error is at least visible in the app's log feed.
fn record_or_log_bridge_error(handle: *mut AndroidTunnelController, message: String) {
    match with_controller(handle, |controller| controller.record_bridge_error(message.clone())) {
        Ok(Ok(())) => {}
        Ok(Err(reason)) => {
            tracing::error!(%reason, %message, "failed to record bridge error: state mutex poisoned");
        }
        Err(reason) => {
            tracing::error!(%reason, %message, "failed to record bridge error: invalid handle");
        }
    }
}

pub(crate) fn catch_api_string<F>(f: F) -> *mut c_char
where
    F: FnOnce() -> Result<String, String>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(value)) => into_c_string(value),
        Ok(Err(error)) => into_c_string(error),
        Err(_) => into_c_string("panic while handling Android bridge call".to_owned()),
    }
}

pub(crate) fn to_jstring(env: &mut JNIEnv<'_>, value: String) -> jstring {
    env.new_string(value).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut())
}

pub(crate) fn last_error_for_handle(handle: *mut AndroidTunnelController) -> String {
    with_controller(handle, |controller| controller.last_error())
        .ok()
        .flatten()
        .unwrap_or_else(|| "unknown error".to_owned())
}

#[cfg(test)]
mod tests;
