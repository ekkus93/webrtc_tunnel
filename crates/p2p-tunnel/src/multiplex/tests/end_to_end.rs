use super::support::*;

#[tokio::test]
async fn multiplex_open_handshake_bridges_bytes_after_target_connect() {
    let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let table = forward_table(target_listener.local_addr().expect("target local addr").port());

    let target_task = tokio::spawn(async move {
        let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
        let mut received = [0_u8; 4];
        target_stream.read_exact(&mut received).await.expect("target read");
        assert_eq!(&received, b"ping");
        target_stream.write_all(b"pong").await.expect("target write");
        target_stream.shutdown().await.expect("target shutdown");
    });

    let local_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
    let local_addr = local_listener.local_addr().expect("local addr");
    let client_task = tokio::spawn(async move {
        let mut client = TcpStream::connect(local_addr).await.expect("client connect");
        client.write_all(b"ping").await.expect("client write");
        let mut response = [0_u8; 4];
        client.read_exact(&mut response).await.expect("client read");
        assert_eq!(&response, b"pong");
        client.shutdown().await.expect("client shutdown");
    });
    let (offer_stream, _) = local_listener.accept().await.expect("offer accept");

    let answer_tunnel = sample_tunnel_config();
    let answer_task = tokio::spawn(async move {
        run_multiplex_answer(
            answer_channel,
            &answer_tunnel,
            table,
            "offer-home".parse().expect("peer id"),
        )
        .await
    });
    let offer_tunnel = sample_tunnel_config();
    let offer_task = tokio::spawn(async move {
        let (tx, mut rx) = mpsc::channel(1);
        drop(tx);
        run_multiplex_offer(
            offer_channel,
            &offer_tunnel,
            OfferClient::new("ssh", offer_stream),
            &mut rx,
        )
        .await
    });

    timeout(Duration::from_secs(10), client_task)
        .await
        .expect("client task should finish")
        .expect("client task should succeed");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target task should finish")
        .expect("target task should succeed");
    timeout(Duration::from_secs(10), offer_task)
        .await
        .expect("offer mux should finish")
        .expect("offer mux join should succeed")
        .expect("offer mux should succeed");

    offer_peer.close().await.expect("offer peer should close");
    answer_peer.close().await.expect("answer peer should close");
    answer_task.abort();
}

#[tokio::test]
async fn target_connect_failure_closes_only_failed_offer_stream() {
    let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

    let probe = TcpListener::bind(("127.0.0.1", 0)).await.expect("probe should bind");
    let table = forward_table(probe.local_addr().expect("probe addr").port());
    drop(probe);

    let local_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
    let local_addr = local_listener.local_addr().expect("local addr");
    let mut client = TcpStream::connect(local_addr).await.expect("client connect");
    let (offer_stream, _) = local_listener.accept().await.expect("offer accept");

    let answer_tunnel = sample_tunnel_config();
    let answer_task = tokio::spawn(async move {
        run_multiplex_answer(
            answer_channel,
            &answer_tunnel,
            table,
            "offer-home".parse().expect("peer id"),
        )
        .await
    });
    let offer_tunnel = sample_tunnel_config();
    let offer_result = timeout(Duration::from_secs(10), async move {
        let (tx, mut rx) = mpsc::channel(1);
        drop(tx);
        run_multiplex_offer(
            offer_channel,
            &offer_tunnel,
            OfferClient::new("ssh", offer_stream),
            &mut rx,
        )
        .await
    })
    .await
    .expect("offer mux should finish");

    assert!(offer_result.is_ok());
    let mut buffer = [0_u8; 1];
    let read = timeout(Duration::from_secs(10), client.read(&mut buffer))
        .await
        .expect("client read should finish")
        .expect("client read should succeed");
    assert_eq!(read, 0);

    offer_peer.close().await.expect("offer peer should close");
    answer_peer.close().await.expect("answer peer should close");
    answer_task.abort();
}

#[tokio::test]
async fn two_forwards_share_one_data_channel_with_isolated_streams() {
    let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

    let ssh_target = TcpListener::bind(("127.0.0.1", 0)).await.expect("ssh target should bind");
    let web_target = TcpListener::bind(("127.0.0.1", 0)).await.expect("web target should bind");
    let table = multi_forward_table(
        ssh_target.local_addr().expect("ssh target addr").port(),
        web_target.local_addr().expect("web target addr").port(),
    );

    let ssh_target_task = tokio::spawn(async move {
        let (mut target_stream, _) = ssh_target.accept().await.expect("ssh target accept");
        let mut received = [0_u8; 3];
        target_stream.read_exact(&mut received).await.expect("ssh target read");
        assert_eq!(&received, b"ssh");
        target_stream.write_all(b"SSH").await.expect("ssh target write");
        target_stream.shutdown().await.expect("ssh target shutdown");
    });
    let web_target_task = tokio::spawn(async move {
        let (mut target_stream, _) = web_target.accept().await.expect("web target accept");
        let mut received = [0_u8; 3];
        target_stream.read_exact(&mut received).await.expect("web target read");
        assert_eq!(&received, b"web");
        target_stream.write_all(b"WEB").await.expect("web target write");
        target_stream.shutdown().await.expect("web target shutdown");
    });

    let ssh_listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("ssh listener should bind");
    let web_listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("web listener should bind");
    let ssh_addr = ssh_listener.local_addr().expect("ssh local addr");
    let web_addr = web_listener.local_addr().expect("web local addr");

    let ssh_client_task = tokio::spawn(async move {
        let mut client = TcpStream::connect(ssh_addr).await.expect("ssh client connect");
        client.write_all(b"ssh").await.expect("ssh client write");
        let mut response = [0_u8; 3];
        client.read_exact(&mut response).await.expect("ssh client read");
        assert_eq!(&response, b"SSH");
        client.shutdown().await.expect("ssh client shutdown");
    });
    let web_client_task = tokio::spawn(async move {
        let mut client = TcpStream::connect(web_addr).await.expect("web client connect");
        client.write_all(b"web").await.expect("web client write");
        let mut response = [0_u8; 3];
        client.read_exact(&mut response).await.expect("web client read");
        assert_eq!(&response, b"WEB");
        client.shutdown().await.expect("web client shutdown");
    });

    let (ssh_stream, _) = ssh_listener.accept().await.expect("ssh accept");
    let (web_stream, _) = web_listener.accept().await.expect("web accept");
    let (tx, mut rx) = mpsc::channel(4);
    tx.send(Ok(OfferClient::new("web-ui", web_stream))).await.expect("queue web client");
    drop(tx);

    let answer_tunnel = sample_tunnel_config();
    let answer_task = tokio::spawn(async move {
        run_multiplex_answer(
            answer_channel,
            &answer_tunnel,
            table,
            "offer-home".parse().expect("peer id"),
        )
        .await
    });
    let offer_tunnel = sample_tunnel_config();
    let offer_task = tokio::spawn(async move {
        run_multiplex_offer(
            offer_channel,
            &offer_tunnel,
            OfferClient::new("ssh", ssh_stream),
            &mut rx,
        )
        .await
    });

    timeout(Duration::from_secs(10), ssh_client_task)
        .await
        .expect("ssh client should finish")
        .expect("ssh client task should succeed");
    timeout(Duration::from_secs(10), web_client_task)
        .await
        .expect("web client should finish")
        .expect("web client task should succeed");
    timeout(Duration::from_secs(10), ssh_target_task)
        .await
        .expect("ssh target should finish")
        .expect("ssh target task should succeed");
    timeout(Duration::from_secs(10), web_target_task)
        .await
        .expect("web target should finish")
        .expect("web target task should succeed");
    timeout(Duration::from_secs(10), offer_task)
        .await
        .expect("offer mux should finish")
        .expect("offer mux join should succeed")
        .expect("offer mux should succeed");

    offer_peer.close().await.expect("offer peer should close");
    answer_peer.close().await.expect("answer peer should close");
    answer_task.abort();
}

#[tokio::test]
async fn browser_like_multiple_streams_on_one_forward_complete_independently() {
    const CLIENTS: usize = 5;
    let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let table = forward_table(target_listener.local_addr().expect("target local addr").port());
    let target_task = tokio::spawn(async move {
        for _ in 0..CLIENTS {
            let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
            tokio::spawn(async move {
                let mut received = [0_u8; 4];
                target_stream.read_exact(&mut received).await.expect("target read");
                assert_eq!(&received, b"ping");
                target_stream.write_all(b"pong").await.expect("target write");
                target_stream.shutdown().await.expect("target shutdown");
            });
        }
    });

    let local_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
    let local_addr = local_listener.local_addr().expect("local addr");
    let client_tasks = (0..CLIENTS)
        .map(|_| {
            tokio::spawn(async move {
                let mut client = TcpStream::connect(local_addr).await.expect("client connect");
                client.write_all(b"ping").await.expect("client write");
                let mut response = [0_u8; 4];
                client.read_exact(&mut response).await.expect("client read");
                assert_eq!(&response, b"pong");
                client.shutdown().await.expect("client shutdown");
            })
        })
        .collect::<Vec<_>>();

    let (first_stream, _) = local_listener.accept().await.expect("first accept");
    let (tx, mut rx) = mpsc::channel(CLIENTS);
    let accept_task = tokio::spawn(async move {
        for _ in 1..CLIENTS {
            let (stream, _) = local_listener.accept().await.expect("extra accept");
            tx.send(Ok(OfferClient::new("ssh", stream))).await.expect("queue extra client");
        }
    });

    let answer_tunnel = sample_tunnel_config();
    let answer_task = tokio::spawn(async move {
        run_multiplex_answer(
            answer_channel,
            &answer_tunnel,
            table,
            "offer-home".parse().expect("peer id"),
        )
        .await
    });
    let offer_tunnel = sample_tunnel_config();
    let offer_task = tokio::spawn(async move {
        run_multiplex_offer(
            offer_channel,
            &offer_tunnel,
            OfferClient::new("ssh", first_stream),
            &mut rx,
        )
        .await
    });

    for client_task in client_tasks {
        timeout(Duration::from_secs(10), client_task)
            .await
            .expect("client should finish")
            .expect("client task should succeed");
    }
    timeout(Duration::from_secs(10), accept_task)
        .await
        .expect("accept task should finish")
        .expect("accept task should succeed");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target task should finish")
        .expect("target task should succeed");
    timeout(Duration::from_secs(10), offer_task)
        .await
        .expect("offer mux should finish")
        .expect("offer mux join should succeed")
        .expect("offer mux should succeed");

    offer_peer.close().await.expect("offer peer should close");
    answer_peer.close().await.expect("answer peer should close");
    answer_task.abort();
}

#[tokio::test]
async fn persistent_offer_session_reuses_data_channel_after_zero_streams() {
    let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let table = forward_table(target_listener.local_addr().expect("target local addr").port());
    let target_task = tokio::spawn(async move {
        for _ in 0..2 {
            let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
            tokio::spawn(async move {
                let mut received = [0_u8; 4];
                target_stream.read_exact(&mut received).await.expect("target read");
                target_stream.write_all(&received).await.expect("target write");
                target_stream.shutdown().await.expect("target shutdown");
            });
        }
    });

    let local_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
    let local_addr = local_listener.local_addr().expect("local addr");
    let first_client = tokio::spawn(async move {
        let mut client = TcpStream::connect(local_addr).await.expect("first client connect");
        client.write_all(b"one!").await.expect("first client write");
        let mut response = [0_u8; 4];
        client.read_exact(&mut response).await.expect("first client read");
        assert_eq!(&response, b"one!");
        client.shutdown().await.expect("first client shutdown");
    });
    let (first_stream, _) = local_listener.accept().await.expect("first accept");

    let answer_tunnel = sample_tunnel_config();
    let answer_task = tokio::spawn(async move {
        run_multiplex_answer(
            answer_channel,
            &answer_tunnel,
            table,
            "offer-home".parse().expect("peer id"),
        )
        .await
    });
    let (tx, mut rx) = mpsc::channel(4);
    let offer_tunnel = sample_tunnel_config();
    let mut offer_task = tokio::spawn(async move {
        run_multiplex_offer(
            offer_channel,
            &offer_tunnel,
            OfferClient::new("ssh", first_stream),
            &mut rx,
        )
        .await
    });

    timeout(Duration::from_secs(10), first_client)
        .await
        .expect("first client should finish")
        .expect("first client task should succeed");
    assert!(
        timeout(Duration::from_millis(100), &mut offer_task).await.is_err(),
        "offer runtime must stay alive with zero active streams while accepting clients"
    );

    let second_client = tokio::spawn(async move {
        let mut client = TcpStream::connect(local_addr).await.expect("second client connect");
        client.write_all(b"two!").await.expect("second client write");
        let mut response = [0_u8; 4];
        client.read_exact(&mut response).await.expect("second client read");
        assert_eq!(&response, b"two!");
        client.shutdown().await.expect("second client shutdown");
    });
    let (second_stream, _) = local_listener.accept().await.expect("second accept");
    tx.send(Ok(OfferClient::new("ssh", second_stream))).await.expect("queue second client");

    timeout(Duration::from_secs(10), second_client)
        .await
        .expect("second client should finish")
        .expect("second client task should succeed");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target task should finish")
        .expect("target task should succeed");
    drop(tx);
    timeout(Duration::from_secs(10), offer_task)
        .await
        .expect("offer mux should finish after accept shutdown")
        .expect("offer mux join should succeed")
        .expect("offer mux should succeed");

    offer_peer.close().await.expect("offer peer should close");
    answer_peer.close().await.expect("answer peer should close");
    answer_task.abort();
}
