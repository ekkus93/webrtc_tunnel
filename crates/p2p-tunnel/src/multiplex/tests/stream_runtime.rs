use super::support::*;

#[tokio::test]
async fn closing_stream_cancels_owned_tasks() {
    let (read_started_tx, read_started_rx) = oneshot::channel();
    let (read_done_tx, read_done_rx) = oneshot::channel();
    let read_task = tokio::spawn(async move {
        let _notify = DropNotifier(Some(read_done_tx));
        let _ = read_started_tx.send(());
        future::pending::<()>().await;
    });
    let (write_started_tx, write_started_rx) = oneshot::channel();
    let (write_done_tx, write_done_rx) = oneshot::channel();
    let (write_tx, mut write_rx) = mpsc::channel(1);
    let write_task = tokio::spawn(async move {
        let _notify = DropNotifier(Some(write_done_tx));
        let _ = write_started_tx.send(());
        let _ = write_rx.recv().await;
    });
    read_started_rx.await.expect("read task started");
    write_started_rx.await.expect("write task started");
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("register");
    let mut streams =
        HashMap::from([(1_u32, RuntimeStream::open(write_tx, vec![read_task, write_task]))]);

    close_stream(1, &mut manager, &mut streams).await.expect("close stream");

    timeout(Duration::from_secs(1), read_done_rx)
        .await
        .expect("read task should stop")
        .expect("read notify");
    timeout(Duration::from_secs(1), write_done_rx)
        .await
        .expect("write task should stop")
        .expect("write notify");
    assert_eq!(manager.active_count(), 0);
    assert!(streams.is_empty());
}

#[tokio::test]
async fn local_tcp_eof_sends_close_and_removes_only_that_stream() {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("listener");
    let client = TcpStream::connect(listener.local_addr().expect("local addr"))
        .await
        .expect("client connect");
    let (stream_a, _) = listener.accept().await.expect("accept stream a");
    let (tcp_frame_tx, mut tcp_frame_rx) = mpsc::channel(4);
    let (stream_event_tx, mut stream_event_rx) = mpsc::channel(4);
    let runtime_stream =
        spawn_tcp_bridge(1, stream_a, &sample_tunnel_config(), &tcp_frame_tx, &stream_event_tx);
    let (other_tx, _other_rx) = mpsc::channel(1);
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("stream 1");
    manager.get_mut(1).expect("stream 1").lifecycle = StreamLifecycle::Open;
    manager.register(stream(2, "web-ui")).expect("stream 2");
    manager.get_mut(2).expect("stream 2").lifecycle = StreamLifecycle::Open;
    let mut streams = HashMap::from([
        (1_u32, runtime_stream),
        (2_u32, RuntimeStream::open(other_tx, Vec::new())),
    ]);
    let (frame_tx, mut frame_rx) = mpsc::channel(4);

    drop(client);
    let close = timeout(Duration::from_secs(1), tcp_frame_rx.recv())
        .await
        .expect("close frame should arrive")
        .expect("close frame should be sent");
    assert_eq!(close.frame_type, TunnelFrameType::Close);
    assert_eq!(close.stream_id, 1);
    let event = timeout(Duration::from_secs(1), stream_event_rx.recv())
        .await
        .expect("local EOF event should arrive")
        .expect("local EOF event should be sent");
    handle_stream_runtime_event(event, &frame_tx, &mut manager, &mut streams)
        .await
        .expect("local EOF cleanup should succeed");

    assert!(manager.get(1).is_err());
    assert!(manager.get(2).is_ok());
    assert!(!streams.contains_key(&1));
    assert!(streams.contains_key(&2));
    assert!(frame_rx.try_recv().is_err());
    close_stream(1, &mut manager, &mut streams)
        .await
        .expect("duplicate cleanup should be harmless");
    assert!(manager.get(2).is_ok());
}

#[tokio::test]
async fn local_io_error_event_sends_error_and_removes_only_that_stream() {
    let (stream_a_tx, _stream_a_rx) = mpsc::channel(1);
    let (stream_b_tx, _stream_b_rx) = mpsc::channel(1);
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("stream 1");
    manager.get_mut(1).expect("stream 1").lifecycle = StreamLifecycle::Open;
    manager.register(stream(2, "web-ui")).expect("stream 2");
    manager.get_mut(2).expect("stream 2").lifecycle = StreamLifecycle::Open;
    let mut streams = HashMap::from([
        (1_u32, RuntimeStream::open(stream_a_tx, Vec::new())),
        (2_u32, RuntimeStream::open(stream_b_tx, Vec::new())),
    ]);
    let (frame_tx, mut frame_rx) = mpsc::channel(4);

    handle_stream_runtime_event(
        StreamRuntimeEvent::LocalIoError {
            stream_id: 1,
            message: "local tcp write failed: broken pipe".to_owned(),
            notify_peer: true,
        },
        &frame_tx,
        &mut manager,
        &mut streams,
    )
    .await
    .expect("local I/O failure should be stream-local");

    let error = frame_rx.recv().await.expect("local I/O error frame");
    assert_eq!(error.stream_id, 1);
    assert_eq!(error.error_payload().expect("error payload").code, "local_io_error");
    assert!(manager.get(1).is_err());
    assert!(manager.get(2).is_ok());
    assert!(!streams.contains_key(&1));
    assert!(streams.contains_key(&2));
}

#[tokio::test]
async fn session_cleanup_drains_and_aborts_stream_tasks() {
    let (read_started_tx, read_started_rx) = oneshot::channel();
    let (read_done_tx, read_done_rx) = oneshot::channel();
    let read_task = tokio::spawn(async move {
        let _notify = DropNotifier(Some(read_done_tx));
        let _ = read_started_tx.send(());
        future::pending::<()>().await;
    });
    let (write_started_tx, write_started_rx) = oneshot::channel();
    let (write_done_tx, write_done_rx) = oneshot::channel();
    let (write_tx, mut write_rx) = mpsc::channel(1);
    let write_task = tokio::spawn(async move {
        let _notify = DropNotifier(Some(write_done_tx));
        let _ = write_started_tx.send(());
        let _ = write_rx.recv().await;
    });
    read_started_rx.await.expect("read task started");
    write_started_rx.await.expect("write task started");
    let mut manager = StreamManager::new();
    manager.register(stream(1, "ssh")).expect("stream 1");
    let mut streams =
        HashMap::from([(1_u32, RuntimeStream::open(write_tx, vec![read_task, write_task]))]);

    cleanup_all_streams(&mut manager, &mut streams);
    cleanup_all_streams(&mut manager, &mut streams);

    timeout(Duration::from_secs(1), read_done_rx)
        .await
        .expect("read task should abort")
        .expect("read task drop notify");
    timeout(Duration::from_secs(1), write_done_rx)
        .await
        .expect("write task should abort")
        .expect("write task drop notify");
    assert_eq!(manager.active_count(), 0);
    assert!(streams.is_empty());
}

#[tokio::test]
async fn writer_encode_failure_notifies_runtime() {
    let (offer_peer, answer_peer, offer_channel, _answer_channel) = connected_channels().await;
    let (frame_tx, frame_rx) = mpsc::channel(1);
    let (failure_tx, mut failure_rx) = mpsc::channel(1);
    let writer = spawn_writer_only(offer_channel, frame_rx, failure_tx);

    frame_tx
        .send(TunnelFrame::new(TunnelFrameType::Data, 0, Vec::new()))
        .await
        .expect("send invalid frame to writer");
    let error = timeout(Duration::from_secs(1), failure_rx.recv())
        .await
        .expect("writer failure should be reported")
        .expect("writer failure should be present");
    assert!(matches!(error, TunnelError::ReservedStreamId));

    writer.abort();
    offer_peer.close().await.expect("offer close");
    answer_peer.close().await.expect("answer close");
}
