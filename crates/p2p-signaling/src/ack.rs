use std::collections::HashMap;

use p2p_core::{ACK_RETRY_LIMIT, ACK_RETRY_TIMEOUT_SECS, MessageType, MsgId};

#[derive(Clone, Debug)]
pub struct PendingAck {
    pub payload: Vec<u8>,
    pub sent_at_ms: u64,
    pub retries: u8,
}

#[derive(Debug, Default)]
pub struct AckTracker {
    pending: HashMap<MsgId, PendingAck>,
}

impl AckTracker {
    pub fn register(
        &mut self,
        msg_id: MsgId,
        message_type: MessageType,
        payload: Vec<u8>,
        sent_at_ms: u64,
    ) {
        if !message_type.requires_ack() {
            return;
        }
        self.pending.insert(msg_id, PendingAck { payload, sent_at_ms, retries: 0 });
    }

    pub fn acknowledge(&mut self, msg_id: &MsgId) -> Option<PendingAck> {
        self.pending.remove(msg_id)
    }

    pub fn retry_due(&mut self, now_ms: u64) -> Vec<(MsgId, Vec<u8>)> {
        let retry_timeout_ms = ACK_RETRY_TIMEOUT_SECS * 1_000;
        self.pending
            .iter_mut()
            .filter_map(|(msg_id, pending)| {
                if pending.retries >= ACK_RETRY_LIMIT {
                    return None;
                }
                if pending.sent_at_ms.saturating_add(retry_timeout_ms) > now_ms {
                    return None;
                }

                pending.retries += 1;
                pending.sent_at_ms = now_ms;
                Some((*msg_id, pending.payload.clone()))
            })
            .collect()
    }

    pub fn expired(&self) -> Vec<MsgId> {
        self.pending
            .iter()
            .filter_map(|(msg_id, pending)| (pending.retries >= ACK_RETRY_LIMIT).then_some(*msg_id))
            .collect()
    }
}
