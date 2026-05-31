use p2p_core::{Kid, MessageType, MsgId, SessionId};
use p2p_signaling::{ReplayCache, ReplayCheck, ReplayStatus, SignalingError};

fn fresh_check(session_id: SessionId, timestamp_ms: u64, now_ms: u64) -> ReplayCheck {
    ReplayCheck {
        session_id,
        timestamp_ms,
        now_ms,
        max_clock_skew_secs: 120,
        max_message_age_secs: 300,
        expected_session: None,
    }
}

fn fresh_cache() -> ReplayCache {
    ReplayCache::new(64)
}

fn random_kid() -> Kid {
    Kid::random()
}

// ── Phase 2.1: Timestamp boundary tests ───────────────────────────────────────

#[test]
fn fresh_message_within_age_window_is_accepted() {
    let session_id = SessionId::random();
    let now_ms: u64 = 1_000_000;
    let check = fresh_check(session_id, now_ms - 60_000, now_ms); // 60 seconds old
    let mut cache = fresh_cache();
    assert_eq!(
        cache
            .check_and_record_status(random_kid(), MsgId::random(), check)
            .expect("check_and_record_status should succeed"),
        ReplayStatus::Fresh
    );
}

#[test]
fn message_just_at_max_age_boundary_is_accepted() {
    let session_id = SessionId::random();
    let now_ms: u64 = 1_000_000;
    // Exactly at the limit: timestamp + max_age_ms == now_ms → not less than
    let max_age_ms: u64 = 300 * 1_000;
    let check = fresh_check(session_id, now_ms - max_age_ms, now_ms);
    let mut cache = fresh_cache();
    assert_eq!(
        cache
            .check_and_record_status(random_kid(), MsgId::random(), check)
            .expect("check_and_record_status should succeed"),
        ReplayStatus::Fresh
    );
}

#[test]
fn message_one_ms_past_max_age_is_rejected_as_stale() {
    let session_id = SessionId::random();
    let now_ms: u64 = 1_000_000;
    let max_age_ms: u64 = 300 * 1_000;
    // timestamp + max_age_ms = now_ms - 1, which is strictly less than now_ms → stale
    let check = fresh_check(session_id, now_ms - max_age_ms - 1, now_ms);
    let mut cache = fresh_cache();
    assert!(matches!(
        cache.check_and_record_status(random_kid(), MsgId::random(), check),
        Err(SignalingError::Protocol(msg)) if msg.contains("too old")
    ));
}

#[test]
fn future_skewed_message_within_allowed_skew_is_accepted() {
    let session_id = SessionId::random();
    let now_ms: u64 = 1_000_000;
    let skew_ms: u64 = 120 * 1_000;
    // Exactly at the limit: timestamp == now_ms + max_skew_ms → not greater than
    let check = fresh_check(session_id, now_ms + skew_ms, now_ms);
    let mut cache = fresh_cache();
    assert_eq!(
        cache
            .check_and_record_status(random_kid(), MsgId::random(), check)
            .expect("check_and_record_status should succeed"),
        ReplayStatus::Fresh
    );
}

#[test]
fn message_one_ms_beyond_skew_window_is_rejected_as_future() {
    let session_id = SessionId::random();
    let now_ms: u64 = 1_000_000;
    let skew_ms: u64 = 120 * 1_000;
    let check = fresh_check(session_id, now_ms + skew_ms + 1, now_ms);
    let mut cache = fresh_cache();
    assert!(matches!(
        cache.check_and_record_status(random_kid(), MsgId::random(), check),
        Err(SignalingError::Protocol(msg)) if msg.contains("future")
    ));
}

// ── Phase 2.2: Session mismatch rejection ─────────────────────────────────────

#[test]
fn session_mismatch_is_rejected() {
    let active_session = SessionId::random();
    let other_session = SessionId::random();
    let now_ms: u64 = 1_000_000;
    let check = ReplayCheck {
        session_id: other_session,
        timestamp_ms: now_ms,
        now_ms,
        max_clock_skew_secs: 120,
        max_message_age_secs: 300,
        expected_session: Some(active_session),
    };
    let mut cache = fresh_cache();
    assert!(matches!(
        cache.check_and_record_status(random_kid(), MsgId::random(), check),
        Err(SignalingError::Protocol(msg)) if msg.contains("session")
    ));
}

#[test]
fn matching_expected_session_is_accepted() {
    let session_id = SessionId::random();
    let now_ms: u64 = 1_000_000;
    let check = ReplayCheck {
        session_id,
        timestamp_ms: now_ms,
        now_ms,
        max_clock_skew_secs: 120,
        max_message_age_secs: 300,
        expected_session: Some(session_id),
    };
    let mut cache = fresh_cache();
    assert_eq!(
        cache
            .check_and_record_status(random_kid(), MsgId::random(), check)
            .expect("check_and_record_status should succeed"),
        ReplayStatus::Fresh
    );
}

// ── Phase 2.3: Replay-status distinction tests ────────────────────────────────

#[test]
fn duplicate_same_session_returns_correct_status() {
    let session_id = SessionId::random();
    let sender_kid = random_kid();
    let msg_id = MsgId::random();
    let now_ms: u64 = 1_000_000;

    let mut cache = fresh_cache();
    // First: Fresh
    assert_eq!(
        cache
            .check_and_record_status(sender_kid, msg_id, fresh_check(session_id, now_ms, now_ms))
            .expect("check_and_record_status should succeed"),
        ReplayStatus::Fresh
    );
    // Second: DuplicateSameSession
    assert_eq!(
        cache
            .check_and_record_status(sender_kid, msg_id, fresh_check(session_id, now_ms, now_ms))
            .expect("check_and_record_status should succeed"),
        ReplayStatus::DuplicateSameSession
    );
}

#[test]
fn duplicate_different_session_returns_correct_status() {
    let session1 = SessionId::random();
    let session2 = SessionId::random();
    let sender_kid = random_kid();
    let msg_id = MsgId::random();
    let now_ms: u64 = 1_000_000;

    let mut cache = fresh_cache();
    // First with session1: Fresh
    assert_eq!(
        cache
            .check_and_record_status(sender_kid, msg_id, fresh_check(session1, now_ms, now_ms))
            .expect("check_and_record_status should succeed"),
        ReplayStatus::Fresh
    );
    // Same msg_id with session2: DuplicateDifferentSession
    assert_eq!(
        cache
            .check_and_record_status(sender_kid, msg_id, fresh_check(session2, now_ms, now_ms))
            .expect("check_and_record_status should succeed"),
        ReplayStatus::DuplicateDifferentSession
    );
}

#[test]
fn different_sender_kid_same_msg_id_is_treated_as_fresh() {
    let session_id = SessionId::random();
    let kid1 = random_kid();
    let kid2 = random_kid();
    let msg_id = MsgId::random();
    let now_ms: u64 = 1_000_000;

    let mut cache = fresh_cache();
    assert_eq!(
        cache
            .check_and_record_status(kid1, msg_id, fresh_check(session_id, now_ms, now_ms))
            .expect("check_and_record_status should succeed"),
        ReplayStatus::Fresh
    );
    // Different sender: not a replay
    assert_eq!(
        cache
            .check_and_record_status(kid2, msg_id, fresh_check(session_id, now_ms, now_ms))
            .expect("check_and_record_status should succeed"),
        ReplayStatus::Fresh
    );
}

// ── Phase 2.4: MessageType ACK flag table ─────────────────────────────────────

#[test]
fn message_types_that_require_ack_are_correct() {
    let needs_ack = [
        MessageType::Offer,
        MessageType::Answer,
        MessageType::IceCandidate,
        MessageType::Close,
        MessageType::Error,
        MessageType::IceRestartRequest,
        MessageType::RenegotiateRequest,
    ];
    for mt in needs_ack {
        assert!(mt.requires_ack(), "{mt:?} should require ACK");
    }
}

#[test]
fn message_types_that_do_not_require_ack_are_correct() {
    let no_ack = [MessageType::Ack, MessageType::Ping, MessageType::Pong];
    for mt in no_ack {
        assert!(!mt.requires_ack(), "{mt:?} should NOT require ACK");
    }
}

#[test]
fn hello_and_end_of_candidates_do_not_require_ack() {
    assert!(!MessageType::Hello.requires_ack(), "Hello should NOT require ACK");
    assert!(!MessageType::EndOfCandidates.requires_ack(), "EndOfCandidates should NOT require ACK");
}
