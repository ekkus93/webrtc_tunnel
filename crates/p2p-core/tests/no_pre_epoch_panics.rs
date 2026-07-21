//! FIX7 P0-010-G: `workspaceContainsNoPreEpochExpectOrUnwrapOrZeroFallback`.
//!
//! Not a brittle grep-only guard by itself — it is a regression tripwire on top of the manual
//! call-site inventory recorded as FIX7 P0-010-A evidence, which classified every wall-clock
//! call site in the workspace and fixed each one to either propagate a typed error
//! (correctness-sensitive) or degrade via `Option`/reused-last-known (diagnostics-only) instead
//! of panicking (`.expect`/`.unwrap` on `duration_since(UNIX_EPOCH)`) or inventing a zero
//! timestamp (the old FIX6 `resolve_unix_ms`, replaced by `resolve_optional_unix_ms`). This test
//! exists so a *future* pre-epoch panic or zero-fallback reintroduced by a careless edit fails
//! CI immediately, rather than depending solely on a human noticing during review.

use std::fs;
use std::path::{Path, PathBuf};

fn workspace_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("..")
        .canonicalize()
        .expect("workspace root must exist")
}

fn collect_rs_files(dir: &Path, files: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    for entry in entries {
        let Ok(entry) = entry else { continue };
        let path = entry.path();
        if path.is_dir() {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
            if name == "target" || name.starts_with('.') {
                continue;
            }
            collect_rs_files(&path, files);
        } else if path.extension().and_then(|e| e.to_str()) == Some("rs") {
            files.push(path);
        }
    }
}

/// Strips whitespace so a multi-line `.duration_since(UNIX_EPOCH)\n.expect(...)` chain (the
/// exact shape of every pre-epoch panic this task fixed) is caught regardless of line breaks or
/// indentation between the call and its `.expect`/`.unwrap`.
fn whitespace_stripped(contents: &str) -> String {
    contents.chars().filter(|c| !c.is_whitespace()).collect()
}

#[test]
fn workspace_contains_no_pre_epoch_expect_or_unwrap_or_zero_fallback() {
    let root = workspace_root();
    let mut files = Vec::new();
    for subdir in ["crates", "bins"] {
        collect_rs_files(&root.join(subdir), &mut files);
    }
    assert!(
        files.len() > 20,
        "sanity check: expected to find many workspace source files, found {}. \
         The workspace layout may have changed under this test's assumptions.",
        files.len()
    );

    let self_path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("no_pre_epoch_panics.rs")
        .canonicalize()
        .expect("this test file must exist");

    let mut violations = Vec::new();
    for file in &files {
        // Skip this check's own source: it necessarily names the forbidden patterns in prose
        // and string literals while explaining/reporting them.
        if file.canonicalize().map(|p| p == self_path).unwrap_or(false) {
            continue;
        }
        let contents = fs::read_to_string(file).unwrap_or_default();
        let stripped = whitespace_stripped(&contents);

        if stripped.contains("duration_since(UNIX_EPOCH).expect(")
            || stripped.contains("duration_since(UNIX_EPOCH).unwrap()")
        {
            violations.push(format!(
                "{}: pre-epoch panic (.expect/.unwrap on duration_since(UNIX_EPOCH))",
                file.display()
            ));
        }
        // The old FIX6 API this task replaced: returned a bare u64 that silently became 0 on
        // the very first clock failure. Its replacement, resolve_optional_unix_ms, is fine.
        if contents.contains("resolve_unix_ms") && !contents.contains("resolve_optional_unix_ms") {
            violations.push(format!(
                "{}: references the removed zero-fallback resolve_unix_ms API",
                file.display()
            ));
        }
    }

    assert!(
        violations.is_empty(),
        "found reintroduced pre-epoch panic sites or the removed zero-fallback clock API:\n{}",
        violations.join("\n")
    );
}
