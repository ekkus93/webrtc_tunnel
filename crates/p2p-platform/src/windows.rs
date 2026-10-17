//! Windows implementation of the platform seam.
//!
//! # Permission checks
//!
//! NTFS expresses "who can use this file" as a DACL -- a list of
//! access-control entries (ACEs) keyed by SID -- not as POSIX mode bits, so the
//! unix `mode & 0o077` test becomes an inspection of the file's DACL. Both
//! security checks are real here, and [`ENFORCES_FILE_PERMISSIONS`] is `true`.
//!
//! The DACL and owner are read with `GetNamedSecurityInfo` through the
//! `windows-permissions` crate's safe wrappers, so this crate still contains no
//! `unsafe` (the workspace forbids it). The rules, from P2-004:
//!
//! * A path passes when every *allow* ACE that grants a checked right names one
//!   of: the object's owner, SYSTEM (`S-1-5-18`), Administrators
//!   (`S-1-5-32-544`), or the user the current process runs as -- under the
//!   service that is `NT SERVICE\p2ptunnel`, which the installer's ACE grants,
//!   without this crate hardcoding the service name. Principals are matched by
//!   SID, never by account name.
//! * Inherited ACEs count: the DACL `GetNamedSecurityInfo` returns already
//!   includes them. `INHERIT_ONLY` ACEs are skipped only on a directory, where
//!   they describe its future children rather than the directory itself.
//! * Only allow ACEs are considered, and deny ACEs earn no credit: a grant to a
//!   stranger is rejected even if a deny ACE ordered before it would cancel it.
//!   That over-rejects some unusual DACLs, deliberately.
//! * "Read" is `FILE_READ_DATA`, `FILE_READ_EA`, `GENERIC_READ`, `GENERIC_ALL`
//!   and `MAXIMUM_ALLOWED`. "Write" is `FILE_WRITE_DATA`, `FILE_APPEND_DATA`,
//!   `FILE_WRITE_EA`, `FILE_DELETE_CHILD`, `DELETE`, `WRITE_DAC`,
//!   `WRITE_OWNER`, `GENERIC_WRITE` and `GENERIC_ALL`.
//! * A missing or NULL DACL grants everyone everything, and is rejected.
//! * An owner that is a broad group (Everyone, Users, Authenticated Users, ...)
//!   is rejected outright: every member holds the owner's implicit right to
//!   rewrite the DACL, so no ACE list can make such a file private.
//! * Any error or panic inside the inspection is a rejection, never a pass.
//!
//! The install-time DACL on `%ProgramData%\p2ptunnel` (P0-008) remains the
//! primary protection for the service's files. These checks verify it at load
//! time, and catch a key placed somewhere looser -- such as directly under
//! `%ProgramData%`, whose default DACL grants `BUILTIN\Users` read access.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use crate::PlatformError;

#[path = "windows_dacl.rs"]
mod dacl;

pub(crate) const ENFORCES_FILE_PERMISSIONS: bool = true;

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

pub(crate) fn create_private_file(path: &Path, contents: &[u8]) -> Result<(), PlatformError> {
    // No per-file ACL is set: the file inherits its directory's DACL, which is
    // what protects it (see the module docs). Loading it later runs
    // `ensure_private_file_permissions`, which rejects that inherited DACL if it
    // lets anyone else in.
    fs::write(path, contents).map_err(|error| PlatformError::io(path, error))
}

pub(crate) fn ensure_private_file_permissions(path: &Path) -> Result<(), PlatformError> {
    // The unix model is `mode & 0o077 == 0`: nobody but the owner may read *or*
    // write. Write is checked too, so a stranger cannot swap the key out.
    match dacl::find_violation(path, dacl::Access::ReadOrWrite)? {
        None => Ok(()),
        Some(violation) => Err(PlatformError::Permission(format!(
            "identity file '{}' must be accessible only to its owner, SYSTEM, Administrators, \
             and the current user, but {violation}",
            path.display()
        ))),
    }
}

pub(crate) fn ensure_not_writable_by_others(path: &Path) -> Result<(), PlatformError> {
    if path.as_os_str().is_empty() {
        return Ok(());
    }

    // A file that does not exist yet is still unsafe if it will be created
    // inside a directory others can write to, so walk up to the nearest
    // ancestor that does exist and check that instead.
    let mut candidate = path;
    while !candidate.exists() {
        candidate = candidate.parent().ok_or_else(|| {
            PlatformError::Permission(format!(
                "'{}' must be inside an existing directory for path security checks",
                path.display()
            ))
        })?;
    }

    match dacl::find_violation(candidate, dacl::Access::Write)? {
        None => Ok(()),
        Some(violation) => Err(PlatformError::Permission(format!(
            "path '{}' must not be writable by anyone but its owner, SYSTEM, Administrators, \
             and the current user, but {violation}",
            candidate.display()
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::{Command, Stdio};

    const EVERYONE: &str = "S-1-1-0";
    const BUILTIN_USERS: &str = "S-1-5-32-545";

    /// Adds an ACE with `icacls`, which is how an operator would loosen a file.
    /// `windows-permissions` 0.2.4 has no safe ACL builder, and SID syntax
    /// (`*S-1-...`) keeps this independent of the system language.
    fn grant(path: &Path, sid: &str, rights: &str) {
        let status = Command::new("icacls")
            .arg(path)
            .arg("/grant")
            .arg(format!("*{sid}:{rights}"))
            .stdout(Stdio::null())
            .status()
            .expect("icacls should run");
        assert!(status.success(), "icacls /grant *{sid}:{rights} failed on {path:?}");
    }

    /// Gives `NT SERVICE\p2ptunnel` Modify on `path`. `icacls` cannot do this: it
    /// refuses a service SID that no installed service resolves, whereas .NET's ACL
    /// API stores the raw SID.
    fn grant_service_account_modify(path: &Path) {
        // Temp paths contain no single quote, so a single-quoted literal is safe.
        let script = format!(
            "$sid = New-Object System.Security.Principal.SecurityIdentifier(                'S-1-5-80-1253224334-2237551498-3137952417-4040730996-425943683');             $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(                $sid, 'Modify', 'Allow');             $acl = Get-Acl -LiteralPath '{0}'; $acl.AddAccessRule($rule);             Set-Acl -LiteralPath '{0}' -AclObject $acl",
            path.display()
        );
        // Started from PowerShell 7 (as the hosted CI runner does), the child inherits
        // pwsh's `PSModulePath`, so Windows PowerShell 5.1 finds PowerShell 7's
        // incompatible `Microsoft.PowerShell.Security` first and cannot load it. Dropping
        // the variable makes it use its own default module path.
        let status = Command::new("powershell")
            .env_remove("PSModulePath")
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .stdout(Stdio::null())
            .status()
            .expect("powershell should run");
        assert!(status.success(), "granting the service account Modify failed on {path:?}");
    }

    fn temp_file(dir: &Path) -> PathBuf {
        let path = dir.join("identity");
        fs::write(&path, "x").expect("write");
        path
    }

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

    #[test]
    fn file_with_default_temp_acl_is_accepted() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = temp_file(dir.path());
        ensure_private_file_permissions(&path).expect("default %TEMP% ACL should be private");
        ensure_not_writable_by_others(&path).expect("default %TEMP% ACL should not be shared");
        ensure_not_writable_by_others(dir.path()).expect("the temp dir itself should pass");
    }

    #[test]
    fn file_readable_by_everyone_is_rejected() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = temp_file(dir.path());
        grant(&path, EVERYONE, "R");
        match ensure_private_file_permissions(&path) {
            Err(PlatformError::Permission(message)) => {
                assert!(message.contains(EVERYONE), "message should name the SID: {message}");
            }
            other => panic!("expected a Permission error, got {other:?}"),
        }
    }

    #[test]
    fn file_readable_by_builtin_users_is_rejected() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = temp_file(dir.path());
        grant(&path, BUILTIN_USERS, "R");
        assert!(matches!(
            ensure_private_file_permissions(&path),
            Err(PlatformError::Permission(_))
        ));
    }

    #[test]
    fn file_writable_by_users_is_rejected_by_both_checks() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = temp_file(dir.path());
        grant(&path, BUILTIN_USERS, "(W)");
        assert!(matches!(
            ensure_private_file_permissions(&path),
            Err(PlatformError::Permission(_))
        ));
        assert!(matches!(ensure_not_writable_by_others(&path), Err(PlatformError::Permission(_))));
    }

    #[test]
    fn grants_to_the_service_account_are_accepted_by_any_process() {
        // The installer gives NT SERVICE\p2ptunnel read access to the identity and
        // Modify on state\. A different account inspecting those files, for example
        // an administrator running `p2pctl check-config`, must accept them.
        let dir = tempfile::tempdir().expect("temp dir");
        let path = temp_file(dir.path());
        grant_service_account_modify(&path);
        ensure_private_file_permissions(&path).expect("the service account's ACE is expected");
        ensure_not_writable_by_others(&path).expect("the service account's ACE is expected");
        // Only that one account is exempt.
        grant(&path, EVERYONE, "R");
        assert!(matches!(
            ensure_private_file_permissions(&path),
            Err(PlatformError::Permission(_))
        ));
    }

    #[test]
    fn read_only_grant_does_not_fail_the_writability_check() {
        // Mirrors unix, where 0644 fails the private-file check but is not
        // world-writable.
        let dir = tempfile::tempdir().expect("temp dir");
        let path = temp_file(dir.path());
        grant(&path, EVERYONE, "R");
        ensure_not_writable_by_others(&path).expect("read access is not write access");
    }

    #[test]
    fn inherited_read_grant_is_rejected() {
        // The grant sits on the directory and reaches the file only by
        // inheritance; inherited ACEs must count.
        let dir = tempfile::tempdir().expect("temp dir");
        grant(dir.path(), EVERYONE, "(OI)(R)");
        let path = temp_file(dir.path());
        assert!(matches!(
            ensure_private_file_permissions(&path),
            Err(PlatformError::Permission(_))
        ));
    }

    #[test]
    fn directory_writable_by_everyone_rejects_a_not_yet_existing_child() {
        let dir = tempfile::tempdir().expect("temp dir");
        grant(dir.path(), EVERYONE, "(W)");
        let child = dir.path().join("not-yet").join("created.sock");
        match ensure_not_writable_by_others(&child) {
            Err(PlatformError::Permission(message)) => {
                assert!(message.contains(EVERYONE), "message should name the SID: {message}");
            }
            other => panic!("expected a Permission error, got {other:?}"),
        }
    }

    #[test]
    fn inherit_only_grant_does_not_apply_to_the_directory_itself() {
        // `(IO)` describes what future children inherit, not who can write to
        // the directory now. A child created afterwards is checked against its
        // own DACL, where the grant then shows up as inherited.
        let dir = tempfile::tempdir().expect("temp dir");
        grant(dir.path(), EVERYONE, "(OI)(IO)(W)");
        ensure_not_writable_by_others(dir.path()).expect("an inherit-only ACE is not a grant here");
        let path = temp_file(dir.path());
        assert!(ensure_not_writable_by_others(&path).is_err(), "but the child inherits it");
    }

    #[test]
    fn missing_file_is_an_io_error_not_a_pass() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("missing");
        assert!(matches!(ensure_private_file_permissions(&path), Err(PlatformError::Io { .. })));
    }
}
