use super::support::*;

#[tokio::test]
async fn answer_open_starts_target_connect_without_blocking_dispatcher() {
    let table = forward_table_with_target("10.255.255.1", 65_000);
    let mut manager = StreamManager::new();
    let mut streams = HashMap::new();
    let (frame_tx, _frame_rx) = mpsc::channel(4);
    let (target_connect_tx, _target_connect_rx) = mpsc::channel(4);

    timeout(
        Duration::from_millis(100),
        handle_answer_frame(
            TunnelFrame::open(7, OpenPayload { forward_id: "ssh".to_owned() }).expect("open frame"),
            &table,
            &"offer-home".parse().expect("peer id"),
            &frame_tx,
            &target_connect_tx,
            &mut manager,
            &mut streams,
        ),
    )
    .await
    .expect("OPEN handling must not wait for target connect")
    .expect("OPEN should be accepted");

    assert_eq!(manager.get(7).expect("stream").lifecycle, StreamLifecycle::Opening);
    assert!(streams.contains_key(&7));
    close_stream(7, &mut manager, &mut streams).await.expect("abort pending connect");
}

#[tokio::test]
async fn malformed_answer_open_payload_is_stream_local_protocol_error() {
    for payload in [Vec::new(), b"{".to_vec(), b"{}".to_vec(), br#"{"target_port":22}"#.to_vec()] {
        let table = forward_table_with_target("10.255.255.1", 65_000);
        let mut manager = StreamManager::new();
        let mut streams = HashMap::new();
        let (frame_tx, mut frame_rx) = mpsc::channel(4);
        let (target_connect_tx, mut target_connect_rx) = mpsc::channel(4);

        handle_answer_frame(
            TunnelFrame::new(TunnelFrameType::Open, 7, payload),
            &table,
            &"offer-home".parse().expect("peer id"),
            &frame_tx,
            &target_connect_tx,
            &mut manager,
            &mut streams,
        )
        .await
        .expect("malformed OPEN should be stream-local");

        let error = frame_rx.recv().await.expect("protocol error frame");
        assert_eq!(error.stream_id, 7);
        assert_eq!(error.error_payload().expect("error payload").code, "protocol_error");
        assert!(manager.get(7).is_err());
        assert!(streams.is_empty());
        assert!(target_connect_rx.try_recv().is_err());
    }
}

#[tokio::test]
async fn malformed_answer_open_preserves_existing_stream_and_later_valid_open() {
    let table = forward_table_with_target("10.255.255.1", 65_000);
    let mut manager = StreamManager::new();
    manager.register(stream(2, "ssh")).expect("stream 2");
    manager.get_mut(2).expect("stream 2").lifecycle = StreamLifecycle::Open;
    let (stream_b_tx, mut stream_b_rx) = mpsc::channel(1);
    let mut streams = HashMap::from([(2_u32, RuntimeStream::open(stream_b_tx, Vec::new()))]);
    let (frame_tx, mut frame_rx) = mpsc::channel(4);
    let (target_connect_tx, _target_connect_rx) = mpsc::channel(4);

    handle_answer_frame(
        TunnelFrame::new(TunnelFrameType::Open, 1, b"{".to_vec()),
        &table,
        &"offer-home".parse().expect("peer id"),
        &frame_tx,
        &target_connect_tx,
        &mut manager,
        &mut streams,
    )
    .await
    .expect("malformed OPEN should be handled");
    let error = frame_rx.recv().await.expect("protocol error frame");
    assert_eq!(error.stream_id, 1);
    assert_eq!(error.error_payload().expect("error payload").code, "protocol_error");

    handle_answer_frame(
        TunnelFrame::data(2, b"still-open".to_vec()),
        &table,
        &"offer-home".parse().expect("peer id"),
        &frame_tx,
        &target_connect_tx,
        &mut manager,
        &mut streams,
    )
    .await
    .expect("stream B should remain usable");
    assert!(
        matches!(stream_b_rx.recv().await, Some(TcpWriteCommand::Data(payload)) if payload == b"still-open")
    );

    handle_answer_frame(
        TunnelFrame::open(3, OpenPayload { forward_id: "ssh".to_owned() }).expect("open frame"),
        &table,
        &"offer-home".parse().expect("peer id"),
        &frame_tx,
        &target_connect_tx,
        &mut manager,
        &mut streams,
    )
    .await
    .expect("valid OPEN should still work");
    assert_eq!(manager.get(3).expect("stream 3").lifecycle, StreamLifecycle::Opening);
    assert!(streams.contains_key(&3));
    close_stream(3, &mut manager, &mut streams).await.expect("abort pending connect");
}
