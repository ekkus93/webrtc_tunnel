//! Unix (Linux, macOS, Android) implementation of the platform seam.
//!
//! Permissions are POSIX mode bits, so both security checks are real here.

use std::env;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use crate::PlatformError;

pub(crate) const ENFORCES_FILE_PERMISSIONS: bool = true;

pub(crate) fn home_dir() -> Option<PathBuf> {
    env::var_os("HOME").filter(|home| !home.is_empty()).map(PathBuf::from)
}

pub(crate) fn default_config_dir() -> Result<PathBuf, PlatformError> {
    let home = home_dir().ok_or_else(|| {
        PlatformError::Unresolved(
            "HOME is not set, so the default config directory cannot be resolved".to_string(),
        )
    })?;
    Ok(home.join(".config").join("p2ptunnel"))
}

pub(crate) fn service_config_dir(role: &str) -> Result<PathBuf, PlatformError> {
    // These are not new conventions -- they are the paths the shipped supervisor
    // definitions already pass with `--config` (`packaging/systemd/*.service`,
    // `packaging/launchd/*.plist`). Naming them here keeps the wrapper and the
    // installer from each hardcoding their own copy.
    #[cfg(target_os = "macos")]
    let root = PathBuf::from("/Library/Application Support/P2PTunnel");
    #[cfg(not(target_os = "macos"))]
    let root = PathBuf::from("/etc/p2ptunnel");
    Ok(root.join(role))
}

pub(crate) fn ensure_private_file_permissions(path: &Path) -> Result<(), PlatformError> {
    let metadata = fs::metadata(path).map_err(|error| PlatformError::io(path, error))?;
    let mode = metadata.permissions().mode() & 0o777;
    if mode & 0o077 != 0 {
        return Err(PlatformError::Permission(format!(
            "identity file '{}' must be 0600 or stricter, got {:o}",
            path.display(),
            mode
        )));
    }
    Ok(())
}

pub(crate) fn ensure_not_writable_by_others(path: &Path) -> Result<(), PlatformError> {
    if path.as_os_str().is_empty() {
        return Ok(());
    }

    // A file that does not exist yet is still unsafe if it will be created
    // inside a world-writable directory, so walk up to the nearest ancestor
    // that does exist and check that instead.
    let mut candidate = path;
    while !candidate.exists() {
        candidate = candidate.parent().ok_or_else(|| {
            PlatformError::Permission(format!(
                "'{}' must be inside an existing directory for path security checks",
                path.display()
            ))
        })?;
    }

    let metadata = fs::metadata(candidate).map_err(|error| PlatformError::io(candidate, error))?;
    if metadata.permissions().mode() & 0o002 != 0 {
        return Err(PlatformError::Permission(format!(
            "path '{}' must not be world-writable",
            candidate.display()
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn world_writable_file_is_rejected() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("loose");
        fs::write(&path, "x").expect("write");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o666)).expect("chmod");
        assert!(ensure_not_writable_by_others(&path).is_err());
    }

    #[test]
    fn owner_only_file_is_accepted() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("tight");
        fs::write(&path, "x").expect("write");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o600)).expect("chmod");
        ensure_not_writable_by_others(&path).expect("0600 should be accepted");
        ensure_private_file_permissions(&path).expect("0600 should be accepted as private");
    }

    #[test]
    fn group_readable_identity_is_rejected() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("identity");
        fs::write(&path, "x").expect("write");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o640)).expect("chmod");
        assert!(ensure_private_file_permissions(&path).is_err());
    }
}
