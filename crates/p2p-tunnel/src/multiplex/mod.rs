//! TCP-over-data-channel multiplexer.
//!
//! This module is a thin coordinator: it wires the multiplex submodules together
//! and re-exports the public API. The role loops live in [`offer`] and [`answer`],
//! over the shared data model in [`state`] and the per-stream runtime/bridge layer
//! in [`stream`]. The two `run_multiplex_*` entry points are used directly by the
//! daemon.

mod answer;
mod offer;
mod state;
mod stream;

pub use answer::run_multiplex_answer;
pub use offer::run_multiplex_offer;
pub use state::{
    DEFAULT_STREAM_QUEUE_MESSAGES, DEFAULT_WRITER_QUEUE_MESSAGES, StreamIdAllocator,
    StreamLifecycle, StreamManager, StreamState,
};

// Crate-internal symbols surfaced at the multiplex root so the unit-test module
// reaches them via `super::` without depending on each submodule's path. Glob
// re-exports keep this maintenance-free and do not warn when an item is unused.
#[cfg(test)]
pub(crate) use self::{answer::*, offer::*, state::*, stream::*};

#[cfg(test)]
mod tests;
