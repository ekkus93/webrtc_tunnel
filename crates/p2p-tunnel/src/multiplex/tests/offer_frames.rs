use super::support::*;

#[tokio::test]
async fn offer_register_after_zero_streams_does_not_reuse_stream_id() {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("listener");
    let local_addr = listener.local_addr().expect("local addr");
    let first_client = TcpStream::connect(local_addr).await.expect("first client connect");
    let (first_stream, _) = listener.accept().await.expect("first accept");
    let second_client = TcpStream::connect(local_addr).await.expect("second client connect");
    let (second_stream, _) = listener.accept().await.expect("second accept");
    let mut manager = StreamManager::new();
    let mut opening_streams = HashMap::new();
    let (frame_tx, mut frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);

    register_offer_client(
        OfferClient::new("ssh", first_stream),
        &sample_tunnel_config(),
        &frame_tx,
        &tcp_frame_tx,
        &mut manager,
        &mut opening_streams,
    )
    .await
    .expect("first client should register");
    let first_open = frame_rx.recv().await.expect("first OPEN");
    assert_eq!(first_open.stream_id, 1);
    manager.remove(1);
    opening_streams.remove(&1);

    register_offer_client(
        OfferClient::new("ssh", second_stream),
        &sample_tunnel_config(),
        &frame_tx,
        &tcp_frame_tx,
        &mut manager,
        &mut opening_streams,
    )
    .await
    .expect("second client should register");
    let second_open = frame_rx.recv().await.expect("second OPEN");
    assert_eq!(second_open.stream_id, 2);
    assert!(manager.get(1).is_err());
    assert!(manager.get(2).is_ok());

    drop(first_client);
    drop(second_client);
}

#[tokio::test]
async fn offer_open_ack_transitions_opening_stream_to_open() {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("listener");
    let client = TcpStream::connect(listener.local_addr().expect("local addr"))
        .await
        .expect("client connect");
    let (tcp_stream, _) = listener.accept().await.expect("accept");
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("register stream");
    let mut opening_streams = HashMap::from([(1_u32, tcp_stream)]);
    let mut streams = HashMap::new();
    let (frame_tx, _frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

    handle_offer_frame(
        TunnelFrame::open_ack(1),
        &OfferIo {
            tunnel_config: &sample_tunnel_config(),
            frame_tx: &frame_tx,
            tcp_frame_tx: &tcp_frame_tx,
            stream_event_tx: &stream_event_tx,
        },
        &mut manager,
        &mut opening_streams,
        &mut streams,
    )
    .await
    .expect("ack should handle");

    assert_eq!(manager.get(1).expect("stream").lifecycle, StreamLifecycle::Open);
    assert!(opening_streams.is_empty());
    assert!(streams.contains_key(&1));
    drop(client);
}

#[tokio::test]
async fn offer_open_ack_rejects_non_empty_payload() {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("listener");
    let client = TcpStream::connect(listener.local_addr().expect("local addr"))
        .await
        .expect("client connect");
    let (tcp_stream, _) = listener.accept().await.expect("accept");
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("register stream");
    let mut opening_streams = HashMap::from([(1_u32, tcp_stream)]);
    let mut streams = HashMap::new();
    let (frame_tx, mut frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

    handle_offer_frame(
        TunnelFrame::open(1, OpenPayload { forward_id: "ssh".to_owned() })
            .expect("malformed ack frame"),
        &OfferIo {
            tunnel_config: &sample_tunnel_config(),
            frame_tx: &frame_tx,
            tcp_frame_tx: &tcp_frame_tx,
            stream_event_tx: &stream_event_tx,
        },
        &mut manager,
        &mut opening_streams,
        &mut streams,
    )
    .await
    .expect("malformed ack should be handled as stream error");

    let error = frame_rx.recv().await.expect("protocol error");
    assert_eq!(error.stream_id, 1);
    assert_eq!(error.error_payload().expect("error payload").code, "protocol_error");
    assert!(manager.get(1).is_err());
    assert!(opening_streams.is_empty());
    assert!(streams.is_empty());
    drop(client);
}

#[tokio::test]
async fn duplicate_empty_open_ack_is_ignored() {
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("register stream");
    manager.get_mut(1).expect("stream").lifecycle = StreamLifecycle::Open;
    let mut opening_streams = HashMap::new();
    let mut streams = HashMap::new();
    let (frame_tx, mut frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

    handle_offer_frame(
        TunnelFrame::open_ack(1),
        &OfferIo {
            tunnel_config: &sample_tunnel_config(),
            frame_tx: &frame_tx,
            tcp_frame_tx: &tcp_frame_tx,
            stream_event_tx: &stream_event_tx,
        },
        &mut manager,
        &mut opening_streams,
        &mut streams,
    )
    .await
    .expect("duplicate ack should be ignored");

    assert_eq!(manager.get(1).expect("stream").lifecycle, StreamLifecycle::Open);
    assert!(frame_rx.try_recv().is_err());
}

#[tokio::test]
async fn closed_stream_write_queue_is_stream_local() {
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("stream 1");
    manager.get_mut(1).expect("stream 1").lifecycle = StreamLifecycle::Open;
    manager.register(stream(2, "web-ui")).expect("stream 2");
    manager.get_mut(2).expect("stream 2").lifecycle = StreamLifecycle::Open;
    let (closed_tx, closed_rx) = mpsc::channel(1);
    drop(closed_rx);
    let (other_tx, mut other_rx) = mpsc::channel(1);
    let mut streams = HashMap::from([
        (1_u32, RuntimeStream::open(closed_tx, Vec::new())),
        (2_u32, RuntimeStream::open(other_tx, Vec::new())),
    ]);
    let mut opening_streams = HashMap::new();
    let (frame_tx, mut frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

    handle_offer_frame(
        TunnelFrame::data(1, b"payload".to_vec()),
        &OfferIo {
            tunnel_config: &sample_tunnel_config(),
            frame_tx: &frame_tx,
            tcp_frame_tx: &tcp_frame_tx,
            stream_event_tx: &stream_event_tx,
        },
        &mut manager,
        &mut opening_streams,
        &mut streams,
    )
    .await
    .expect("closed queue should be stream-local");

    let error = frame_rx.recv().await.expect("closed queue error");
    assert_eq!(error.stream_id, 1);
    assert_eq!(error.error_payload().expect("error payload").code, "local_io_error");
    assert!(manager.get(1).is_err());
    assert!(manager.get(2).is_ok());
    assert!(!streams.contains_key(&1));
    assert!(streams.contains_key(&2));

    handle_offer_frame(
        TunnelFrame::data(2, b"still-open".to_vec()),
        &OfferIo {
            tunnel_config: &sample_tunnel_config(),
            frame_tx: &frame_tx,
            tcp_frame_tx: &tcp_frame_tx,
            stream_event_tx: &stream_event_tx,
        },
        &mut manager,
        &mut opening_streams,
        &mut streams,
    )
    .await
    .expect("other stream should remain usable");
    assert!(
        matches!(other_rx.recv().await, Some(TcpWriteCommand::Data(payload)) if payload == b"still-open")
    );
}

#[tokio::test]
async fn late_data_close_and_error_after_cleanup_are_harmless() {
    let mut manager = StreamManager::new();
    let mut opening_streams = HashMap::new();
    let mut streams = HashMap::new();
    let (frame_tx, mut frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

    for frame in [
        TunnelFrame::data(1, b"late".to_vec()),
        TunnelFrame::close(1),
        TunnelFrame::error(
            1,
            ErrorPayload { code: "local_io_error".to_owned(), message: "late".to_owned() },
        )
        .expect("error frame"),
    ] {
        handle_offer_frame(
            frame,
            &OfferIo {
                tunnel_config: &sample_tunnel_config(),
                frame_tx: &frame_tx,
                tcp_frame_tx: &tcp_frame_tx,
                stream_event_tx: &stream_event_tx,
            },
            &mut manager,
            &mut opening_streams,
            &mut streams,
        )
        .await
        .expect("late frame should be harmless");
    }

    assert_eq!(manager.active_count(), 0);
    assert!(streams.is_empty());
    assert!(frame_rx.try_recv().is_err());
}

#[tokio::test]
async fn data_to_unknown_stream_is_ignored() {
    let mut manager = StreamManager::new();
    let mut opening_streams = HashMap::new();
    let mut streams = HashMap::new();
    let (frame_tx, mut frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

    handle_offer_frame(
        TunnelFrame::data(99, b"lost".to_vec()),
        &OfferIo {
            tunnel_config: &sample_tunnel_config(),
            frame_tx: &frame_tx,
            tcp_frame_tx: &tcp_frame_tx,
            stream_event_tx: &stream_event_tx,
        },
        &mut manager,
        &mut opening_streams,
        &mut streams,
    )
    .await
    .expect("unknown data should be ignored");

    assert!(frame_rx.try_recv().is_err());
}

#[tokio::test]
async fn duplicate_close_is_harmless() {
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("register stream");
    let mut opening_streams = HashMap::new();
    let mut streams = HashMap::new();
    let (frame_tx, _frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

    for _ in 0..2 {
        handle_offer_frame(
            TunnelFrame::close(1),
            &OfferIo {
                tunnel_config: &sample_tunnel_config(),
                frame_tx: &frame_tx,
                tcp_frame_tx: &tcp_frame_tx,
                stream_event_tx: &stream_event_tx,
            },
            &mut manager,
            &mut opening_streams,
            &mut streams,
        )
        .await
        .expect("close should be idempotent");
    }

    assert_eq!(manager.active_count(), 0);
}

#[tokio::test]
async fn queue_overflow_fails_only_target_stream() {
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("stream 1");
    manager.register(stream(2, "web-ui")).expect("stream 2");
    let (full_tx, mut full_rx) = mpsc::channel(1);
    assert!(full_tx.try_send(TcpWriteCommand::Data(b"queued".to_vec())).is_ok());
    let (other_tx, _other_rx) = mpsc::channel(1);
    let mut streams = HashMap::from([
        (1_u32, RuntimeStream::open(full_tx, Vec::new())),
        (2_u32, RuntimeStream::open(other_tx, Vec::new())),
    ]);
    let mut opening_streams = HashMap::new();
    let (frame_tx, mut frame_rx) = mpsc::channel(4);
    let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

    handle_offer_frame(
        TunnelFrame::data(1, b"overflow".to_vec()),
        &OfferIo {
            tunnel_config: &sample_tunnel_config(),
            frame_tx: &frame_tx,
            tcp_frame_tx: &tcp_frame_tx,
            stream_event_tx: &stream_event_tx,
        },
        &mut manager,
        &mut opening_streams,
        &mut streams,
    )
    .await
    .expect("overflow should be stream-local");

    let error = frame_rx.recv().await.expect("queue overflow error");
    assert_eq!(error.stream_id, 1);
    assert_eq!(error.error_payload().expect("error payload").code, "queue_overflow");
    assert!(manager.get(1).is_err());
    assert!(manager.get(2).is_ok());
    assert!(streams.contains_key(&2));
    assert!(matches!(full_rx.recv().await, Some(TcpWriteCommand::Data(_))));
}
