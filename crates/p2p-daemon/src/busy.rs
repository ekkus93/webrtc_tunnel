//! Bounded de-duplication caches for "busy" replies and duplicate-active acks,
//! plus the classification of an incoming offer that arrives during an active
//! answer session. These guard against replaying a busy/ack response to the same
//! offer more than once.

use std::collections::{HashSet, VecDeque};

#[cfg(test)]
use p2p_core::SessionId;
use p2p_core::{AppConfig, Kid, MsgId, PeerId};
#[cfg(test)]
use p2p_crypto::AuthorizedKey;
#[cfg(test)]
use p2p_signaling::{MessageBody, OuterEnvelope, SignalCodec};
#[derive(Clone, Debug)]
#[cfg(test)]
pub(crate) enum ActiveBusyOfferAction {
    Ignore,
    ReplyBusy { key: ActiveBusyOfferKey, session_id: SessionId, sender: Box<AuthorizedKey> },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub(crate) struct ActiveBusyOfferKey {
    pub(crate) sender_kid: Kid,
    pub(crate) msg_id: MsgId,
}

#[derive(Debug)]
pub(crate) struct ActiveBusyOfferCache {
    capacity: usize,
    order: VecDeque<ActiveBusyOfferKey>,
    seen: HashSet<ActiveBusyOfferKey>,
}

impl ActiveBusyOfferCache {
    pub(crate) fn new(capacity: usize) -> Self {
        Self { capacity: capacity.max(1), order: VecDeque::new(), seen: HashSet::new() }
    }

    pub(crate) fn record_if_new(&mut self, key: ActiveBusyOfferKey) -> bool {
        if self.seen.contains(&key) {
            return false;
        }
        if self.order.len() == self.capacity {
            if let Some(expired) = self.order.pop_front() {
                self.seen.remove(&expired);
            }
        }
        self.order.push_back(key);
        self.seen.insert(key);
        true
    }

    #[cfg(test)]
    fn contains(&self, key: &ActiveBusyOfferKey) -> bool {
        self.seen.contains(key)
    }
}

#[derive(Debug)]
pub(crate) struct DuplicateActiveAckCache {
    capacity: usize,
    order: VecDeque<MsgId>,
    seen: HashSet<MsgId>,
}

impl DuplicateActiveAckCache {
    pub(crate) fn new(capacity: usize) -> Self {
        Self { capacity: capacity.max(1), order: VecDeque::new(), seen: HashSet::new() }
    }

    pub(crate) fn record_if_new(&mut self, msg_id: MsgId) -> bool {
        if self.seen.contains(&msg_id) {
            return false;
        }
        if self.order.len() == self.capacity {
            if let Some(expired) = self.order.pop_front() {
                self.seen.remove(&expired);
            }
        }
        self.order.push_back(msg_id);
        self.seen.insert(msg_id);
        true
    }
}

#[cfg(test)]
pub(crate) fn replayed_active_busy_offer_key(
    payload: &[u8],
    active_busy_offers: &ActiveBusyOfferCache,
) -> Option<ActiveBusyOfferKey> {
    let envelope = OuterEnvelope::decode(payload).ok()?;
    let key = ActiveBusyOfferKey { sender_kid: envelope.sender_kid, msg_id: envelope.msg_id };
    active_busy_offers.contains(&key).then_some(key)
}

#[cfg(test)]
pub(crate) fn classify_active_busy_offer(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    payload: &[u8],
    active_session_id: SessionId,
    replay_cache_size: usize,
) -> Option<ActiveBusyOfferAction> {
    let mut replay_cache = p2p_signaling::ReplayCache::new(replay_cache_size);
    let Ok((envelope, message, sender)) = codec.decode(payload, &mut replay_cache, None) else {
        return None;
    };
    if !matches!(message.body, MessageBody::Offer(_)) || message.session_id == active_session_id {
        return None;
    }
    if !is_peer_allowed_for_active_busy_reply(config, &sender.peer_id) {
        tracing::warn!(
            peer_id = %sender.peer_id,
            active_session_id = %active_session_id,
            "ignoring new offer during active answer session because peer is not allowlisted"
        );
        return Some(ActiveBusyOfferAction::Ignore);
    }
    Some(ActiveBusyOfferAction::ReplyBusy {
        key: ActiveBusyOfferKey { sender_kid: envelope.sender_kid, msg_id: envelope.msg_id },
        session_id: message.session_id,
        sender: Box::new(sender),
    })
}

pub(crate) fn is_peer_allowed_for_active_busy_reply(
    config: &AppConfig,
    sender_peer_id: &PeerId,
) -> bool {
    config
        .forwards
        .iter()
        .filter_map(|forward| forward.answer.as_ref())
        .any(|answer| answer.allow_remote_peers.contains(sender_peer_id))
}
