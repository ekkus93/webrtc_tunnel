use super::support::*;

#[test]
fn stream_id_allocator_starts_at_one_and_does_not_reuse() {
    let mut allocator = StreamIdAllocator::new();
    assert_eq!(allocator.allocate().expect("stream 1"), 1);
    assert_eq!(allocator.allocate().expect("stream 2"), 2);
    assert_eq!(allocator.allocate().expect("stream 3"), 3);
}

#[test]
fn stream_manager_rejects_duplicate_registration() {
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("first register");
    assert!(matches!(manager.register(stream(1, "ssh")), Err(TunnelError::StreamAlreadyExists(1))));
}

#[test]
fn stream_manager_rejects_stream_zero() {
    let mut manager = StreamManager::new();
    assert!(matches!(manager.register(stream(0, "ssh")), Err(TunnelError::ReservedStreamId)));
}

#[test]
fn closing_one_stream_does_not_remove_another() {
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("stream 1");
    manager.register(stream(2, "web-ui")).expect("stream 2");
    manager.remove(1).expect("stream 1 removed");
    assert_eq!(manager.active_count(), 1);
    assert_eq!(manager.get(2).expect("stream 2").forward_id, "web-ui");
}

#[test]
fn unknown_stream_lookup_returns_error() {
    let manager = StreamManager::new();
    assert!(matches!(manager.get(99), Err(TunnelError::StreamNotFound(99))));
}

#[test]
fn forward_table_lookups_targets_and_permissions() {
    let table = ForwardTable::new(&[
        ForwardRule {
            id: "ssh".to_owned(),
            offer: Some(ForwardOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: 2223,
            }),
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port: 22,
                allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
            }),
        },
        ForwardRule {
            id: "web-ui".to_owned(),
            offer: Some(ForwardOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: 8080,
            }),
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port: 8080,
                allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
            }),
        },
    ]);

    let target =
        table.target_for("web-ui", &"offer-home".parse().expect("peer id")).expect("target");
    assert_eq!(target.port, 8080);
    assert!(table.target_for("missing", &"offer-home".parse().expect("peer id")).is_err());
    assert!(table.target_for("ssh", &"other-peer".parse().expect("peer id")).is_err());
}
