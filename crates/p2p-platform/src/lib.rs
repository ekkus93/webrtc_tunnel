//! The operating-system seam for the tunnel.
//!
//! Everything in the rest of the workspace is portable logic: protocol framing,
//! crypto, config *schema* and validation rules, the WebRTC data plane. The
//! handful of things that genuinely differ per OS -- where a user's home
//! directory lives, and how "only I can read this private key" is expressed --
//! live here, behind one small interface with a per-OS implementation.
//!
//! Android is a unix and uses the unix implementation unchanged.
//!
//! Note on the security functions: [`ensure_private_file_permissions`] and
//! [`ensure_not_writable_by_others`] back the `security.refuse_world_readable_identity`
//! and `security.refuse_world_writable_paths` config knobs, which
//! `AppConfig::validate` refuses to let you disable. Not every platform can
//! enforce them; [`ENFORCES_FILE_PERMISSIONS`] says whether this one does, so
//! callers can be honest about it rather than silently reporting success.

use std::path::{Path, PathBuf};

#[cfg(unix)]
#[path = "unix.rs"]
mod imp;
#[cfg(windows)]
#[path = "windows.rs"]
mod imp;

/// Whether this platform actually enforces the private-file permission checks
/// that [`ensure_private_file_permissions`] and [`ensure_not_writable_by_others`]
/// describe.
///
/// `true` on unix (mode bits) and on Windows (DACL inspection). `false` would
/// mean those two functions are advisory no-ops and the protection has to come
/// from somewhere else. Callers that promise
/// these guarantees to a user should surface this rather than imply enforcement
/// that is not happening.
pub const ENFORCES_FILE_PERMISSIONS: bool = imp::ENFORCES_FILE_PERMISSIONS;

/// The current user's home directory, or `None` if it cannot be determined.
///
/// Unix reads `HOME`. Windows prefers `USERPROFILE` -- the native value -- and
/// only falls back to `HOME`, because POSIX-emulation shells (Git Bash, MSYS)
/// set `HOME` to a unix-shaped path like `/c/Users/name` that is not a valid
/// Windows path.
pub fn home_dir() -> Option<PathBuf> {
    imp::home_dir()
}

/// The config directory an *interactive user* gets when no `--config` was given.
///
/// `$HOME/.config/p2ptunnel` on unix, `%USERPROFILE%\.config\p2ptunnel` on
/// Windows.
///
/// This never returns a service path and never tries to work out whether it is
/// running as a service. Supervisors pass `--config` explicitly on every
/// platform -- the shipped systemd unit and launchd plist both already do -- so
/// there is nothing to detect. Guessing from the process token or from
/// `USERNAME` would mean a mis-guess silently pointing a service at a
/// user-writable config, which is exactly the failure this avoids.
pub fn default_config_dir() -> Result<PathBuf, PlatformError> {
    imp::default_config_dir()
}

/// The config directory a *supervised service* uses for `role`.
///
/// `%ProgramData%\p2ptunnel\<role>` on Windows; on unix, the directory the
/// shipped units already name (`/etc/p2ptunnel/<role>`, or
/// `/Library/Application Support/P2PTunnel/<role>` on macOS).
///
/// This is a path constructor, not a detector: it exists so the service wrapper
/// and the installer agree on one path instead of hardcoding the same string in
/// two places.
pub fn service_config_dir(role: &str) -> Result<PathBuf, PlatformError> {
    validate_role(role)?;
    imp::service_config_dir(role)
}

/// `role` becomes a path component, so keep it to a shape that cannot climb out
/// of the service tree or mean something exotic to the filesystem.
fn validate_role(role: &str) -> Result<(), PlatformError> {
    let well_formed =
        !role.is_empty() && role.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    if !well_formed {
        return Err(PlatformError::Unresolved(format!(
            "service role '{role}' must be a non-empty name of ASCII letters, digits, '-', or '_'"
        )));
    }
    Ok(())
}

/// Rejects a private key file that other users on this machine can read.
///
/// A no-op on platforms where [`ENFORCES_FILE_PERMISSIONS`] is `false`.
pub fn ensure_private_file_permissions(path: &Path) -> Result<(), PlatformError> {
    imp::ensure_private_file_permissions(path)
}

/// Rejects a path that other users on this machine can write to.
///
/// Checks the nearest existing ancestor when `path` itself does not exist yet,
/// so a not-yet-created file inside a world-writable directory is still caught.
/// A no-op on platforms where [`ENFORCES_FILE_PERMISSIONS`] is `false`.
pub fn ensure_not_writable_by_others(path: &Path) -> Result<(), PlatformError> {
    imp::ensure_not_writable_by_others(path)
}

/// Creates (or replaces) a file holding private key material.
///
/// On unix the file is created `0600` -- so it is never briefly readable under
/// the process umask -- and `0600` is re-applied afterwards, because a creation
/// mode does not affect a file that already existed. On Windows the file
/// inherits its directory's DACL, which is the protection there (the service
/// installer restricts the config directory), and
/// [`ensure_private_file_permissions`] rejects it at load if that DACL is loose.
pub fn create_private_file(path: &Path, contents: &[u8]) -> Result<(), PlatformError> {
    imp::create_private_file(path, contents)
}

#[derive(Debug, thiserror::Error)]
pub enum PlatformError {
    /// The path exists but its permissions are weaker than required.
    #[error("{0}")]
    Permission(String),
    /// A well-known directory could not be determined from the environment, or
    /// the caller asked for one by a name that is not well-formed.
    #[error("{0}")]
    Unresolved(String),
    /// The path could not be inspected.
    #[error("failed to inspect '{path}': {source}")]
    Io {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },
}

impl PlatformError {
    pub(crate) fn io(path: &Path, source: std::io::Error) -> Self {
        PlatformError::Io { path: path.to_path_buf(), source }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn home_dir_is_absolute_when_present() {
        // Whatever this platform reports, it must be usable as a base path to
        // join onto -- a relative or unix-shaped value would silently produce
        // wrong paths on Windows.
        if let Some(home) = home_dir() {
            assert!(home.is_absolute(), "home_dir() returned a non-absolute path: {home:?}");
        }
    }

    #[test]
    fn default_config_dir_is_under_the_home_directory() {
        // Skipped rather than failed when the environment names no home: that is
        // a property of the test host, not of the code under test.
        let Some(home) = home_dir() else { return };
        let config_dir = default_config_dir().expect("home is set, so this must resolve");
        assert_eq!(config_dir, home.join(".config").join("p2ptunnel"));
    }

    #[test]
    fn service_config_dir_ends_with_the_role() {
        let dir = service_config_dir("answer").expect("service config dir should resolve");
        assert!(dir.is_absolute(), "service config dir must be absolute, got {dir:?}");
        assert_eq!(dir.file_name().and_then(|role| role.to_str()), Some("answer"));
    }

    #[test]
    fn service_config_dir_rejects_roles_that_would_escape_the_tree() {
        for role in ["", "..", "../offer", "answer/../..", r"answer\..", "an swer"] {
            assert!(
                service_config_dir(role).is_err(),
                "role {role:?} should have been rejected as a path component",
            );
        }
    }

    #[test]
    fn create_private_file_writes_and_replaces_contents() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("identity");
        create_private_file(&path, b"first").expect("create");
        create_private_file(&path, b"second").expect("replace");
        assert_eq!(std::fs::read(&path).expect("read back"), b"second");
        ensure_private_file_permissions(&path).expect("a freshly created private file is private");
    }

    #[test]
    fn create_private_file_reports_the_path_on_failure() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("missing-dir").join("identity");
        match create_private_file(&path, b"x") {
            Err(PlatformError::Io { path: reported, .. }) => assert_eq!(reported, path),
            other => panic!("expected an Io error naming {path:?}, got {other:?}"),
        }
    }

    #[test]
    fn empty_path_is_accepted_by_the_writability_check() {
        // An unset optional config path is not a permission failure.
        ensure_not_writable_by_others(Path::new("")).expect("empty path should be accepted");
    }
}
