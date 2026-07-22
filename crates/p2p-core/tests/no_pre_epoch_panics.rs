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

/// FIX8 P0-010-C/D: `workspaceContainsNoProductionZeroTimestampDiagnosticFallback` — a static
/// tripwire on top of the direct behavior tests in `p2p-mobile` (which prove the actual fallback
/// functions never fabricate a zero/empty result). This test only catches a *reintroduced*
/// hand-written `unix_ms: 0` struct literal, a hand-written `"unix_ms":0` JSON string literal, or
/// a `None => Vec::new()` log-failure fallback slipping back into *production* code — legitimate
/// test data using `Some(0)` (explicitly testing deserialization/eviction, not a production
/// fallback) is deliberately not flagged, since it no longer matches these patterns once
/// `unix_ms` is `Option<u64>`.
#[test]
fn workspace_contains_no_production_zero_timestamp_diagnostic_fallback() {
    let root = workspace_root();
    let mut files = Vec::new();
    for subdir in ["crates", "bins"] {
        collect_rs_files(&root.join(subdir), &mut files);
    }

    let self_path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("no_pre_epoch_panics.rs")
        .canonicalize()
        .expect("this test file must exist");

    let mut violations = Vec::new();
    for file in &files {
        if file.canonicalize().map(|p| p == self_path).unwrap_or(false) {
            continue;
        }
        // Test modules legitimately construct `unix_ms: Some(0)`/similar to exercise
        // deserialization or a ring buffer's eviction order, not a production fallback — skip
        // `#[cfg(test)]` source by convention: this workspace always puts test modules in a
        // dedicated `tests` subdirectory or a file/module literally named `tests`.
        let path_str = file.to_string_lossy();
        if path_str.contains("/tests/") || path_str.ends_with("/tests.rs") {
            continue;
        }
        let contents = fs::read_to_string(file).unwrap_or_default();
        // Strip each `#[cfg(test)] mod ... { ... }` block first (this workspace commonly mixes
        // production and test code in the same file), so an in-file test module's legitimate
        // `Some(0)`/deserialization fixtures never false-positive this scan while its
        // surrounding production code is still checked.
        let production_only = strip_inline_test_modules(&contents);
        let stripped = whitespace_stripped(&production_only);

        if stripped.contains("unix_ms:0,")
            || stripped.contains("unix_ms:0}")
            || stripped.contains("unix_ms:0)")
        {
            violations.push(format!(
                "{}: production struct literal `unix_ms: 0` (must be `None` or `Some(real_value)`)",
                file.display()
            ));
        }
        if stripped.contains("\"unix_ms\":0") {
            violations.push(format!(
                "{}: production JSON fallback contains \"unix_ms\":0 (must be \"unix_ms\":null)",
                file.display()
            ));
        }
        if stripped.contains("None=>Vec::new()") {
            violations.push(format!(
                "{}: `None => Vec::new()` log-failure fallback (a double clock/read failure must \
                 still return one visible untimed error event, not an empty list)",
                file.display()
            ));
        }
    }

    assert!(
        violations.is_empty(),
        "found a reintroduced zero-timestamp/empty-list diagnostic fallback:\n{}",
        violations.join("\n")
    );
}

/// Removes every `#[cfg(test)] mod ... { ... }` block (brace-depth-matched) from `contents`, so
/// the zero-timestamp scan above only ever sees production code, regardless of how deeply a
/// legitimate test fixture nests braces.
fn strip_inline_test_modules(contents: &str) -> String {
    const MARKER: &str = "#[cfg(test)]";
    let mut out = String::with_capacity(contents.len());
    let mut rest = contents;
    while let Some(marker_at) = rest.find(MARKER) {
        out.push_str(&rest[..marker_at]);
        let after_marker = &rest[marker_at + MARKER.len()..];
        let brace_at = after_marker.find('{');
        let semicolon_at = after_marker.find(';');
        // A bare `#[cfg(test)] use ...;`-style statement (a `;` appears before any `{`, or there
        // is no `{` at all) has nothing to strip — stripping to the next unrelated `{` elsewhere
        // in the file would silently remove real production code. Only a genuine block —
        // `#[cfg(test)] mod tests { ... }` or `#[cfg(test)] fn helper() { ... }` — is stripped.
        let is_block = match (brace_at, semicolon_at) {
            (Some(brace), Some(semicolon)) => brace < semicolon,
            (Some(_), None) => true,
            (None, _) => false,
        };
        if !is_block {
            out.push_str(MARKER);
            rest = after_marker;
            continue;
        }
        let brace_at = brace_at.expect("is_block implies brace_at is Some");
        out.push_str(&after_marker[..brace_at]);
        let mut depth = 0usize;
        let mut end = None;
        for (index, ch) in after_marker[brace_at..].char_indices() {
            match ch {
                '{' => depth += 1,
                '}' => {
                    depth -= 1;
                    if depth == 0 {
                        end = Some(brace_at + index + 1);
                        break;
                    }
                }
                _ => {}
            }
        }
        rest = match end {
            Some(end) => &after_marker[end..],
            // Unbalanced braces (should not happen in valid Rust source): stop stripping rather
            // than risk skipping real production code past this point.
            None => break,
        };
    }
    out.push_str(rest);
    out
}
