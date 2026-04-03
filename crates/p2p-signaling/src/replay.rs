use std::collections::{HashMap, VecDeque};

use p2p_core::{Kid, MsgId, SessionId};

use crate::error::SignalingError;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
struct ReplayKey {
    sender_kid: Kid,
    msg_id: MsgId,
}

#[derive(Clone, Copy, Debug)]
struct ReplayEntry {
    session_id: SessionId,
    timestamp_ms: u64,
}

#[derive(Debug)]
pub struct ReplayCache {
    entries: HashMap<ReplayKey, ReplayEntry>,
    order: VecDeque<(ReplayKey, u64)>,
    capacity: usize,
}

#[derive(Clone, Copy, Debug)]
pub struct ReplayCheck {
    pub session_id: SessionId,
    pub timestamp_ms: u64,
    pub now_ms: u64,
    pub max_clock_skew_secs: u64,
    pub max_message_age_secs: u64,
    pub expected_session: Option<SessionId>,
}

impl ReplayCache {
    pub fn new(capacity: usize) -> Self {
        Self { entries: HashMap::new(), order: VecDeque::new(), capacity }
    }

    pub fn check_and_record(
        &mut self,
        sender_kid: Kid,
        msg_id: MsgId,
        check: ReplayCheck,
    ) -> Result<(), SignalingError> {
        let max_clock_skew_ms = check.max_clock_skew_secs.saturating_mul(1_000);
        let max_message_age_ms = check.max_message_age_secs.saturating_mul(1_000);
        if check.timestamp_ms.saturating_add(max_message_age_ms) < check.now_ms {
            return Err(SignalingError::Protocol("message is too old".to_owned()));
        }
        if check.timestamp_ms > check.now_ms.saturating_add(max_clock_skew_ms) {
            return Err(SignalingError::Protocol(
                "message timestamp is too far in the future".to_owned(),
            ));
        }
        if let Some(expected_session) = check.expected_session
            && expected_session != check.session_id
        {
            return Err(SignalingError::Protocol(
                "message session does not match the active session".to_owned(),
            ));
        }

        let key = ReplayKey { sender_kid, msg_id };
        if let Some(existing) = self.entries.get(&key) {
            if existing.session_id == check.session_id {
                return Err(SignalingError::Protocol("duplicate message detected".to_owned()));
            }
            return Err(SignalingError::Protocol(
                "duplicate msg_id received for a different session".to_owned(),
            ));
        }

        self.entries.insert(
            key,
            ReplayEntry { session_id: check.session_id, timestamp_ms: check.timestamp_ms },
        );
        self.order.push_back((key, check.timestamp_ms));
        self.prune(check.now_ms, max_message_age_ms);
        Ok(())
    }

    fn prune(&mut self, now_ms: u64, max_message_age_ms: u64) {
        while self.entries.len() > self.capacity {
            if let Some((key, _)) = self.order.pop_front() {
                self.entries.remove(&key);
            }
        }

        while let Some((key, recorded_at)) = self.order.front().copied() {
            if recorded_at.saturating_add(max_message_age_ms) >= now_ms {
                break;
            }
            self.order.pop_front();
            if self.entries.get(&key).is_some_and(|entry| entry.timestamp_ms == recorded_at) {
                self.entries.remove(&key);
            }
        }
    }
}
