//! Two-node daemon integration tests.
//!
//! Files directly under `tests/` each compile as their own test binary, so the
//! shared harness and the test groups live in the `two_node_daemon/` module
//! directory and are pulled in here via `#[path]`. This keeps everything in a
//! single test binary that shares one harness (and avoids the cross-binary
//! dead-code pitfalls of a `tests/common` layout).

#[path = "two_node_daemon/harness/mod.rs"]
mod harness;

#[path = "two_node_daemon/basics_tests.rs"]
mod basics_tests;
#[path = "two_node_daemon/isolation_tests.rs"]
mod isolation_tests;
#[path = "two_node_daemon/recovery_tests.rs"]
mod recovery_tests;
#[path = "two_node_daemon/shutdown_tests.rs"]
mod shutdown_tests;
#[path = "two_node_daemon/stream_tests/mod.rs"]
mod stream_tests;
