//! Atomic, collision-safe status-file writes: a same-directory temp file plus
//! rename, so a reader never observes a partially-written status file.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use tokio::io::AsyncWriteExt;

/// Distinguishes concurrent same-process writers to the same status path (e.g.
/// two forwards, or a session status update racing a steady-state one), so their
/// temp files never collide even though `std::process::id()` alone is identical
/// for all of them.
pub(super) static STATUS_TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(0);

/// Bounds `open_unique_temp_file`'s collision retry so a persistently broken
/// directory (not just ordinary stale debris) fails loudly instead of looping
/// forever (P1-006).
const MAX_TEMP_NAME_ATTEMPTS: u32 = 16;

/// Opens a not-yet-existing `.{file_name}.tmp-{pid}-{sequence}` file under `parent`,
/// drawing `sequence` from `sequence_source`. On `AlreadyExists` (most likely stale
/// debris left behind by a crashed prior process reusing this PID) this retries with
/// a fresh sequence rather than failing the whole status write outright — the stale
/// file is never touched, since a fresh, never-before-used sequence value always
/// picks a different name (P1-006). `sequence_source` is a parameter (rather than
/// always the process-wide [`STATUS_TEMP_SEQUENCE`]) so a test can supply its own
/// freshly-zeroed counter and stay deterministic regardless of what value the real,
/// process-shared counter happens to be at when other tests run concurrently.
pub(super) async fn open_unique_temp_file(
    parent: &Path,
    file_name: &str,
    sequence_source: &AtomicU64,
) -> Result<(tokio::fs::File, PathBuf), std::io::Error> {
    let mut last_error: Option<std::io::Error> = None;
    for _ in 0..MAX_TEMP_NAME_ATTEMPTS {
        let sequence = sequence_source.fetch_add(1, Ordering::Relaxed);
        let temp_path = parent.join(format!(".{file_name}.tmp-{}-{sequence}", std::process::id()));
        // create_new (O_EXCL) rather than create/truncate: a colliding path fails
        // loudly instead of silently truncating whatever (possibly another writer's
        // in-flight, possibly stale) file already occupies that name.
        match tokio::fs::OpenOptions::new().write(true).create_new(true).open(&temp_path).await {
            Ok(file) => return Ok((file, temp_path)),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                last_error = Some(error);
            }
            Err(error) => return Err(error),
        }
    }
    Err(last_error.expect("loop runs at least once since MAX_TEMP_NAME_ATTEMPTS > 0"))
}

/// Replaces `path`'s contents atomically: writes to a same-directory temporary
/// file, flushes it, then renames it over `path`. A reader can therefore only
/// ever see the previous complete content or the new complete content — never a
/// partially-written file — even under concurrent writer/reader stress. Staying
/// in the same directory keeps the rename on one filesystem (required for
/// `rename` to be atomic on Linux/macOS).
pub(super) async fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), std::io::Error> {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    tokio::fs::create_dir_all(parent).await?;

    let file_name = path.file_name().and_then(|name| name.to_str()).unwrap_or("status.json");
    let (mut file, temp_path) =
        open_unique_temp_file(parent, file_name, &STATUS_TEMP_SEQUENCE).await?;

    let write_result = async {
        file.write_all(bytes).await?;
        file.flush().await?;
        drop(file);
        tokio::fs::rename(&temp_path, path).await
    }
    .await;

    if write_result.is_err()
        && let Err(cleanup_error) = tokio::fs::remove_file(&temp_path).await
        && cleanup_error.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(
            reason = %cleanup_error,
            path = %temp_path.display(),
            "failed to remove status temporary file",
        );
    }

    write_result
}
