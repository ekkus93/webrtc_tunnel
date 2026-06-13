//! Two-node daemon stream / multi-session integration tests.
//!
//! Grouped by topology: a single offer/answer pair ([`single_pair`]), an answer
//! serving multiple concurrent offer peers ([`concurrent_peers`]), and delivery
//! fault injection that must stay route/peer isolated ([`fault_isolation`]).

mod concurrent_peers;
mod fault_isolation;
mod single_pair;
