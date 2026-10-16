//! Windows implementation of the platform seam.
//!
//! # Why the permission checks are not enforced here yet
//!
//! NTFS expresses "who can read this file" as a DACL -- an ordered list of
//! access-control entries keyed by SID -- not as POSIX mode bits. There is no
//! meaningful `mode & 0o077` to test, so the unix checks cannot be ported
//! directly; the equivalent is inspecting the file's DACL and proving no ACE
//! grants read access to anyone but the owner, SYSTEM, and Administrators.
//!
//! Doing that from Rust means calling `GetNamedSecurityInfo` and walking the
//! ACL through raw Win32 FFI, which requires `unsafe`. This workspace sets
//! `unsafe_code = "forbid"`, with `p2p-mobile`'s JNI boundary as the single
//! documented exception, so adding a second exception is a deliberate policy
//! decision rather than an implementation detail.
//!
//! The protection itself does not depend on that decision. Where the identity
//! file lives determines the actual risk:
//!
//! * Under `%USERPROFILE%`, the default ACL already restricts access to that
//!   user plus SYSTEM and Administrators -- roughly equivalent to `0600`.
//! * Under `%ProgramData%`, the default inherited ACL commonly grants
//!   `BUILTIN\Users` read access, which *would* expose a private key to every
//!   local user.
//!
//! The planned fix is to apply a restrictive DACL to the config directory once,
//! at service-install time, granting only the service's virtual account
//! (`NT SERVICE\p2ptunnel`) and Administrators. That needs no `unsafe` and no
//! runtime inspection. Runtime DACL *validation* would be defense in depth on
//! top of it.
//!
//! Until then [`ENFORCES_FILE_PERMISSIONS`] is `false`, so callers can say so
//! plainly instead of reporting a check that did not happen.

use std::env;
use std::path::{Path, PathBuf};

use crate::PlatformError;

pub(crate) const ENFORCES_FILE_PERMISSIONS: bool = false;

pub(crate) fn home_dir() -> Option<PathBuf> {
    // `USERPROFILE` is the native Windows value and is checked first. `HOME` is
    // only a fallback: POSIX-emulation shells (Git Bash, MSYS, Cygwin) set it to
    // a unix-shaped path such as `/c/Users/name`, which `PathBuf` would treat as
    // a drive-relative path (`\c\Users\name`) and silently resolve wrong.
    env::var_os("USERPROFILE")
        .filter(|home| !home.is_empty())
        .or_else(|| env::var_os("HOME").filter(|home| !home.is_empty()))
        .map(PathBuf::from)
}

pub(crate) fn default_config_dir() -> Result<PathBuf, PlatformError> {
    let home = home_dir().ok_or_else(|| {
        PlatformError::Unresolved(
            "neither USERPROFILE nor HOME is set, so the default config directory cannot be \
             resolved"
                .to_string(),
        )
    })?;
    Ok(home.join(".config").join("p2ptunnel"))
}

pub(crate) fn service_config_dir(role: &str) -> Result<PathBuf, PlatformError> {
    // `%ProgramData%` rather than `%APPDATA%`: the service account is not a
    // logged-on user and has no roaming profile. This is the tree P0-008 locks
    // down with a DACL, which is what actually protects the identity file here.
    let program_data =
        env::var_os("ProgramData").filter(|value| !value.is_empty()).ok_or_else(|| {
            PlatformError::Unresolved(
                "ProgramData is not set, so the service config directory cannot be resolved"
                    .to_string(),
            )
        })?;
    Ok(PathBuf::from(program_data).join("p2ptunnel").join(role))
}

pub(crate) fn ensure_private_file_permissions(_path: &Path) -> Result<(), PlatformError> {
    // See the module docs: not enforceable without DACL inspection.
    // `ENFORCES_FILE_PERMISSIONS` is `false` so callers can report this honestly.
    Ok(())
}

pub(crate) fn ensure_not_writable_by_others(_path: &Path) -> Result<(), PlatformError> {
    // See the module docs: not enforceable without DACL inspection.
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn home_dir_prefers_userprofile_shape() {
        // On Windows the resolved home must be a real Windows path. A Git Bash
        // `HOME` of `/c/Users/name` would come back non-absolute-with-prefix and
        // silently produce wrong config paths, which is the bug this ordering
        // exists to avoid.
        if let Some(home) = home_dir() {
            assert!(
                home.components()
                    .next()
                    .is_some_and(|first| matches!(first, std::path::Component::Prefix(_))),
                "home_dir() must return a path with a drive prefix, got {home:?}",
            );
        }
    }

    #[test]
    fn service_config_dir_lives_under_program_data() {
        let Some(program_data) = env::var_os("ProgramData").filter(|value| !value.is_empty())
        else {
            return;
        };
        let dir = service_config_dir("answer").expect("ProgramData is set, so this must resolve");
        assert_eq!(dir, PathBuf::from(program_data).join("p2ptunnel").join("answer"));
    }
}
