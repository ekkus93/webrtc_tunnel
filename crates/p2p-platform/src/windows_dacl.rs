//! DACL inspection behind the Windows permission checks.
//!
//! The rules are documented on the parent module. Everything that touches the
//! `windows-permissions` crate runs inside [`std::panic::catch_unwind`]: that
//! crate turns several "cannot happen" Win32 results into panics (an unknown ACE
//! type byte, a NULL DACL pointer, a failed SID-to-string conversion), and for a
//! security check any of those has to be a rejection, not a crash and never a
//! pass.

use std::any::Any;
use std::fs;
use std::io;
use std::panic;
use std::path::Path;

use windows_permissions::constants::{AceFlags, AceType, SeObjectType, SecurityInformation};
use windows_permissions::{LocalBox, Sid, utilities, wrappers};

use crate::PlatformError;

/// Which rights a check refuses to see granted to anyone outside the allowlist.
#[derive(Clone, Copy)]
pub(super) enum Access {
    Write,
    ReadOrWrite,
}

// `windows-permissions` names these only as anonymous `Bit<n>` flags, so they are
// spelled out here with their `winnt.h` values.
const FILE_READ_DATA: u32 = 0x0000_0001;
const FILE_WRITE_DATA: u32 = 0x0000_0002;
const FILE_APPEND_DATA: u32 = 0x0000_0004;
const FILE_READ_EA: u32 = 0x0000_0008;
const FILE_WRITE_EA: u32 = 0x0000_0010;
const FILE_DELETE_CHILD: u32 = 0x0000_0040;
const DELETE: u32 = 0x0001_0000;
const WRITE_DAC: u32 = 0x0004_0000;
const WRITE_OWNER: u32 = 0x0008_0000;
const MAXIMUM_ALLOWED: u32 = 0x0200_0000;
const GENERIC_ALL: u32 = 0x1000_0000;
const GENERIC_WRITE: u32 = 0x4000_0000;
const GENERIC_READ: u32 = 0x8000_0000;

const READ_RIGHTS: u32 =
    FILE_READ_DATA | FILE_READ_EA | GENERIC_READ | GENERIC_ALL | MAXIMUM_ALLOWED;
const WRITE_RIGHTS: u32 = FILE_WRITE_DATA
    | FILE_APPEND_DATA
    | FILE_WRITE_EA
    | FILE_DELETE_CHILD
    | DELETE
    | WRITE_DAC
    | WRITE_OWNER
    | GENERIC_WRITE
    | GENERIC_ALL;

/// The installed Windows service's name. Windows derives the virtual account
/// `NT SERVICE\<name>` from it, and the installer grants that account access to
/// the identity and state directory (spec 2.6), so its SID is always trusted.
const SERVICE_NAME: &str = "p2ptunnel";
const SYSTEM: &str = "S-1-5-18";
const ADMINISTRATORS: &str = "S-1-5-32-544";

/// Well-known groups that must never own a protected path: the owner implicitly
/// holds `WRITE_DAC`, so every member of such a group could grant themselves
/// access whatever the ACEs say. Everyone, LOCAL, INTERACTIVE, ANONYMOUS LOGON,
/// Authenticated Users, BUILTIN\Users, BUILTIN\Guests.
const BROAD_GROUPS: [&str; 7] =
    ["S-1-1-0", "S-1-2-0", "S-1-5-4", "S-1-5-7", "S-1-5-11", "S-1-5-32-545", "S-1-5-32-546"];

impl Access {
    fn checked_rights(self) -> u32 {
        match self {
            Access::Write => WRITE_RIGHTS,
            Access::ReadOrWrite => READ_RIGHTS | WRITE_RIGHTS,
        }
    }
}

/// Inspects `path`'s owner and DACL. `Ok(None)` means nothing outside the
/// allowlist holds a checked right; `Ok(Some(reason))` describes the first
/// violation found. A failure to inspect is an error, so it can never be read
/// as a pass.
pub(super) fn find_violation(path: &Path, access: Access) -> Result<Option<String>, PlatformError> {
    // `INHERIT_ONLY` ACEs are skipped on directories only, so the object kind is
    // needed up front. This also turns a missing path into the usual Io error.
    let is_dir = fs::metadata(path).map_err(|error| PlatformError::io(path, error))?.is_dir();
    let checked = access.checked_rights();
    match panic::catch_unwind(|| inspect(path, is_dir, checked)) {
        Ok(Ok(violation)) => Ok(violation),
        Ok(Err(error)) => Err(PlatformError::io(path, error)),
        Err(payload) => Err(PlatformError::io(
            path,
            io::Error::other(format!(
                "reading the security descriptor panicked: {}",
                panic_message(payload.as_ref())
            )),
        )),
    }
}

fn inspect(path: &Path, is_dir: bool, checked: u32) -> io::Result<Option<String>> {
    let descriptor = wrappers::GetNamedSecurityInfo(
        path.as_os_str(),
        SeObjectType::SE_FILE_OBJECT,
        SecurityInformation::Dacl | SecurityInformation::Owner,
    )?;

    let owner = descriptor.owner();
    if let Some(owner) = owner {
        for group in BROAD_GROUPS {
            if *owner == *parse_sid(group)? {
                return Ok(Some(format!("it is owned by the group {owner}")));
            }
        }
    }

    // No DACL at all means unrestricted access. A *present* NULL DACL means the
    // same; `windows-permissions` panics on that one, which `find_violation`
    // also reports as a rejection.
    let Some(dacl) = descriptor.dacl() else {
        return Ok(Some("it has no DACL, which grants everyone full access".to_string()));
    };

    // The service account is trusted whichever account is inspecting: the installer
    // deliberately grants it access, so an administrator running `p2pctl
    // check-config` or debugging in a console must not see that as a stranger.
    let trusted = [
        parse_sid(SYSTEM)?,
        parse_sid(ADMINISTRATORS)?,
        parse_sid(&service_account_sid())?,
        utilities::current_process_sid()?,
    ];

    let ace_count = dacl.len();
    for index in 0..ace_count {
        let ace = dacl.get_ace(index).ok_or_else(|| {
            io::Error::other(format!("ACE {index} of {ace_count} in the DACL could not be read"))
        })?;
        // Deny ACEs earn no credit; audit and label ACEs grant nothing.
        if !is_allow(ace.ace_type()) {
            continue;
        }
        if is_dir && ace.flags().contains(AceFlags::InheritOnly) {
            continue;
        }
        let granted = ace.mask().bits() & checked;
        if granted == 0 {
            continue;
        }
        let Some(sid) = ace.sid() else {
            return Ok(Some(format!("ACE {index} grants access without naming a SID")));
        };
        if owner == Some(sid) || trusted.iter().any(|trusted| **trusted == *sid) {
            continue;
        }
        return Ok(Some(format!(
            "an ACE grants {} access (mask 0x{granted:08x}) to {sid}",
            describe(granted)
        )));
    }
    Ok(None)
}

fn is_allow(ace_type: AceType) -> bool {
    matches!(
        ace_type,
        AceType::ACCESS_ALLOWED_ACE_TYPE
            | AceType::ACCESS_ALLOWED_CALLBACK_ACE_TYPE
            | AceType::ACCESS_ALLOWED_CALLBACK_OBJECT_ACE_TYPE
            | AceType::ACCESS_ALLOWED_OBJECT_ACE_TYPE
    )
}

/// The SID of `NT SERVICE\<SERVICE_NAME>`, in string form.
///
/// Windows derives a service SID as `S-1-5-80-` plus the five little-endian 32-bit
/// words of the SHA-1 of the upper-cased UTF-16LE service name. Deriving it avoids
/// resolving the account by name, whose `NT SERVICE` prefix is localised and
/// which only resolves once the service exists.
fn service_account_sid() -> String {
    use sha1::{Digest, Sha1};

    let name: Vec<u8> =
        SERVICE_NAME.to_uppercase().encode_utf16().flat_map(u16::to_le_bytes).collect();
    let words: Vec<String> = Sha1::digest(&name)
        .chunks_exact(4)
        .map(|word| u32::from_le_bytes([word[0], word[1], word[2], word[3]]).to_string())
        .collect();
    format!("S-1-5-80-{}", words.join("-"))
}

fn parse_sid(sid: &str) -> io::Result<LocalBox<Sid>> {
    sid.parse()
}

fn describe(granted: u32) -> &'static str {
    match (granted & READ_RIGHTS != 0, granted & WRITE_RIGHTS != 0) {
        (true, true) => "read and write",
        (true, false) => "read",
        _ => "write",
    }
}

fn panic_message(payload: &(dyn Any + Send)) -> &str {
    payload
        .downcast_ref::<&str>()
        .copied()
        .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
        .unwrap_or("non-string panic payload")
}

#[cfg(test)]
mod tests {
    use super::service_account_sid;

    #[test]
    fn service_sid_matches_the_value_windows_reports() {
        // From `sc.exe showsid p2ptunnel` on a real machine; the installer's check
        // script pins the same value.
        assert_eq!(
            service_account_sid(),
            "S-1-5-80-1253224334-2237551498-3137952417-4040730996-425943683"
        );
    }
}
