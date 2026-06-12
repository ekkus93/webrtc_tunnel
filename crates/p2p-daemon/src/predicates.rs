//! Small pure decision predicates for reconnect and session handling. Kept
//! separate so the policy is easy to read and unit-test in isolation.

use p2p_core::AppConfig;

use crate::{ActiveSession, BridgeSessionState};
pub(crate) fn should_attempt_offer_reconnect(
    config: &AppConfig,
    pending_stream_present: bool,
    bridge_state: BridgeSessionState,
) -> bool {
    config.reconnect.enable_auto_reconnect
        && pending_stream_present
        && matches!(bridge_state, BridgeSessionState::Pending | BridgeSessionState::Reconnecting)
}

pub(crate) fn should_ack_idle_offer(peer_allowed: bool, requires_ack: bool) -> bool {
    peer_allowed && requires_ack
}

pub(crate) fn should_continue_reconnect_attempt(max_attempts: u32, attempt: u32) -> bool {
    max_attempts == 0 || attempt < max_attempts
}

pub(crate) fn can_attempt_same_session_ice_restart(session: &ActiveSession) -> bool {
    session.data_channel.as_ref().is_some_and(|channel| channel.is_open())
}
