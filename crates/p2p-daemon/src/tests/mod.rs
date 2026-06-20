//! Daemon unit-test suite.
//!
//! The shared fixtures live in [`support`]; the test functions are grouped by
//! concern into the sibling modules below. The crate-root forward/validation
//! helpers a few tests reach through `super::` are re-exported here so the test
//! bodies keep their original paths.

pub(crate) use crate::{
    first_answer_forward, first_answer_forward_mut, first_offer_forward, first_offer_forward_mut,
    validate_config_authorized_peers,
};

mod support;

mod answer_admission;
mod answer_registry;
mod answer_routing;
mod busy_offer;
mod canonical_docs;
mod config_and_idle;
mod duplicate_reack;
mod reconnect;
mod session_lifecycle;
mod status_and_recovery;
