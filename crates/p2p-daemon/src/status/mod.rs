//! Daemon status: the [`DaemonStatus`] data model, [`StatusWriter`] (broadcasts/persists
//! it), and the atomic status-file write plumbing that backs the writer. Split across a
//! few files: this one holds only module wiring/re-exports, [`atomic`] holds the
//! collision-safe temp-file-plus-rename write primitive, [`types`] holds the status data
//! model plus the test/debug-only audit log, and [`writer`] holds [`StatusWriter`] itself.

mod atomic;
#[cfg(test)]
mod tests;
mod types;
mod writer;

#[cfg(any(test, debug_assertions))]
pub use types::StatusAuditLog;
pub use types::{DaemonStatus, ForwardListenState, ForwardRuntimeStatus, SessionStatus};
pub use writer::StatusWriter;
