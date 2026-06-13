//! Guards that the canonical specs/README describe the current multi-peer answer routing and status policy.

use p2p_core::NodeRole;

use super::support::*;

#[test]
fn steady_state_matches_v1_role_policy() {
    assert_eq!(steady_state_for_role(&NodeRole::Offer), DaemonState::WaitingForLocalClient);
    assert_eq!(steady_state_for_role(&NodeRole::Answer), DaemonState::Serving);
}

#[test]
fn canonical_specs_do_not_present_stale_single_session_rules_as_current() {
    let specs = include_str!("../../../../docs/SPECS.md");
    assert!(
        !specs.contains("One active peer tunnel session at a time"),
        "canonical specs must not present the old global single-session rule as current"
    );
    assert!(
        !specs.contains("Multiple simultaneous WebRTC peer sessions"),
        "canonical specs must not list current v0.3 multi-peer sessions as out of scope"
    );
    assert!(
        specs.contains("One active peer tunnel session per authenticated `peer_id`."),
        "canonical specs should document the current per-peer session limit"
    );
    assert!(
        specs.contains("multiple simultaneous authorized `p2p-offer` peers")
            || specs.contains("Multiple simultaneous authorized offer peer sessions"),
        "canonical specs should document multiple authorized offer peers per answer daemon"
    );
    assert!(
        specs.contains("If the `session_id` is unknown and the message is not an `offer`"),
        "canonical specs should document unknown-session non-offer routing policy"
    );
    assert!(
        specs.contains(
            "daemon-level `current_state` reports `serving` with zero or more active sessions"
        ),
        "canonical specs should document answer Serving status semantics"
    );
}

#[test]
fn canonical_readme_documents_current_multi_peer_answer_behavior() {
    let readme = include_str!("../../../../README.md");
    assert!(
        readme.contains("One answer daemon can serve multiple authorized offer peers concurrently"),
        "README should document current multi-peer answer behavior"
    );
    assert!(
        readme.contains("at most one active WebRTC session per `peer_id`"),
        "README should document the per-peer active session limit"
    );
    assert!(
        !readme.contains("Multiple simultaneous WebRTC peer sessions"),
        "README must not present multi-peer sessions as out of scope"
    );
    assert!(
        !readme.contains("One active peer tunnel session at a time"),
        "README must not present the stale global single-session rule as current"
    );
}

#[test]
fn canonical_v03_spec_documents_current_answer_routing_and_status_policy() {
    let spec = include_str!("../../../../docs/archive/V03_SPEC.md");
    assert!(
        spec.contains(
            "one `p2p-answer` process to host multiple simultaneous active peer sessions"
        ),
        "V03 spec should retain multi-session answer behavior"
    );
    assert!(
        spec.contains(
            "daemon-level `current_state` reports `serving` with zero or more active sessions"
        ),
        "V03 spec should document answer serving with zero or more sessions"
    );
    assert!(
        spec.contains("If it does not match an existing session and the message type is `offer`")
            && spec.contains("If it does not match and is not a valid new-session entry point"),
        "V03 spec should document unknown-session non-offer routing policy"
    );
}
