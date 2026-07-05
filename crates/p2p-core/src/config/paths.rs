//! Path expansion and file-security validation helpers used while loading and
//! validating an [`AppConfig`](super::AppConfig): `~/` home expansion, required/
//! optional file existence checks, and world-writable permission rejection.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use crate::error::ConfigError;
pub fn expand_home(path: &Path) -> Result<PathBuf, ConfigError> {
    let path_string = path.to_string_lossy();
    if !path_string.starts_with("~/") {
        return Ok(path.to_path_buf());
    }

    let home = env::var_os("HOME").ok_or_else(|| {
        ConfigError::InvalidConfig("HOME environment variable is not set".to_owned())
    })?;

    let relative = path_string.trim_start_matches("~/");
    Ok(PathBuf::from(home).join(relative))
}

pub(crate) fn expand_optional_path(path: &Path) -> Result<PathBuf, ConfigError> {
    if path.as_os_str().is_empty() {
        return Ok(PathBuf::new());
    }

    expand_home(path)
}

pub(crate) fn validate_required_file(
    path: &Path,
    field_name: &'static str,
) -> Result<(), ConfigError> {
    validate_optional_file(path, field_name, true)
}

pub(crate) fn validate_optional_file(
    path: &Path,
    field_name: &'static str,
    required: bool,
) -> Result<(), ConfigError> {
    if path.as_os_str().is_empty() {
        if required {
            return Err(ConfigError::InvalidConfig(format!("{field_name} must be set")));
        }
        return Ok(());
    }
    if !path.is_file() {
        return Err(ConfigError::InvalidConfig(format!(
            "{field_name} file '{}' does not exist",
            path.display()
        )));
    }
    Ok(())
}
#[cfg(unix)]
pub(crate) fn validate_non_world_writable(
    path: &Path,
    field_name: &'static str,
) -> Result<(), ConfigError> {
    use std::os::unix::fs::PermissionsExt;

    if path.as_os_str().is_empty() {
        return Ok(());
    }

    let mut candidate = path;
    while !candidate.exists() {
        candidate = candidate.parent().ok_or_else(|| {
            ConfigError::InvalidConfig(format!(
                "{field_name} must be inside an existing directory for path security checks"
            ))
        })?;
    }

    let metadata =
        fs::metadata(candidate).map_err(|error| ConfigError::io_path(candidate, error))?;
    if metadata.permissions().mode() & 0o002 != 0 {
        return Err(ConfigError::InvalidConfig(format!(
            "{field_name} path '{}' must not be world-writable",
            candidate.display()
        )));
    }
    Ok(())
}

#[cfg(not(unix))]
pub(crate) fn validate_non_world_writable(
    _path: &Path,
    _field_name: &'static str,
) -> Result<(), ConfigError> {
    Ok(())
}

#[cfg(all(test, unix))]
mod tests {
    use std::fs;
    use std::os::unix::fs::PermissionsExt;

    use super::validate_non_world_writable;

    #[test]
    fn accepts_a_non_world_writable_file() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("config.toml");
        fs::write(&path, b"").expect("write file");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).expect("set permissions");

        validate_non_world_writable(&path, "paths.test").expect("0o644 file should be accepted");
    }

    #[test]
    fn rejects_a_world_writable_file() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("config.toml");
        fs::write(&path, b"").expect("write file");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o646)).expect("set permissions");

        let error = validate_non_world_writable(&path, "paths.test")
            .expect_err("world-writable file should be rejected");
        let message = error.to_string();
        assert!(message.contains("must not be world-writable"), "{message}");
        assert!(message.contains("paths.test"), "{message}");
    }

    #[test]
    fn group_writable_but_not_world_writable_is_accepted() {
        // Proves the check targets the world/"other" bit (0o002) specifically, not
        // the group-writable bit (0o020).
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("config.toml");
        fs::write(&path, b"").expect("write file");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o664)).expect("set permissions");

        validate_non_world_writable(&path, "paths.test")
            .expect("group-writable-only file should be accepted");
    }

    #[test]
    fn rejects_when_the_first_existing_ancestor_directory_is_world_writable() {
        // The target file itself doesn't exist yet, but its containing directory
        // does and is world-writable — the check must walk up to find it.
        let dir = tempfile::tempdir().expect("temp dir");
        fs::set_permissions(dir.path(), fs::Permissions::from_mode(0o777))
            .expect("set permissions");
        let missing_path = dir.path().join("not-yet-created.toml");

        let error = validate_non_world_writable(&missing_path, "paths.test")
            .expect_err("world-writable containing directory should be rejected");
        assert!(error.to_string().contains("must not be world-writable"));
    }

    #[test]
    fn accepts_when_the_first_existing_ancestor_directory_is_not_world_writable() {
        let dir = tempfile::tempdir().expect("temp dir");
        fs::set_permissions(dir.path(), fs::Permissions::from_mode(0o755))
            .expect("set permissions");
        let missing_path = dir.path().join("not-yet-created.toml");

        validate_non_world_writable(&missing_path, "paths.test")
            .expect("non-world-writable containing directory should be accepted");
    }

    #[test]
    fn empty_path_is_always_accepted() {
        validate_non_world_writable(std::path::Path::new(""), "paths.test")
            .expect("empty (unset) path should skip the check entirely");
    }
}
