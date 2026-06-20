//! Unit coverage for the offer-side reconnect orchestration (`attempt_offer_reconnect`).
//!
//! The happy paths (ICE restart / renegotiate that actually recover) are covered by the
//! `two_node_daemon` integration tests over a real connected pair. These exercise the
//! branches that an integration test cannot easily reach: the disabled gate, and giving up
//! after the configured attempt budget is exhausted (every renegotiate times out because no
//! answer is present).

use std::sync::Arc;

use p2p_core::{AppConfig, SessionId};
use p2p_crypto::{AuthorizedKey, AuthorizedKeys, IdentityFile, generate_identity};
use p2p_signaling::SignalCodec;

use super::support::*;
use crate::attempt_offer_reconnect;

/// Shared setup: an offer that authorizes `answer-office`, plus a fresh (unconnected) peer
/// session and a recording transport. `mutate` tweaks the config before it is frozen.
struct ReconnectHarness {
    config: Arc<AppConfig>,
    identity: IdentityFile,
    keys: AuthorizedKeys,
    remote: AuthorizedKey,
    transport: RecordingTransport,
    session: ActiveSession,
}

async fn build_harness(mutate: impl FnOnce(&mut AppConfig)) -> ReconnectHarness {
    let mut config = sample_config();
    // Keep the test fully offline and side-effect free.
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;
    config.health.write_status_file = false;
    mutate(&mut config);
    let config = Arc::new(config);

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let remote =
        keys.get_by_peer_id(&answer.identity.peer_id).expect("answer authorized key").clone();

    let peer = WebRtcPeer::new(&config.webrtc).await.expect("offer peer builds");
    let session = ActiveSession::new(
        SessionId::random(),
        remote.clone(),
        peer,
        config.security.replay_cache_size,
    );

    ReconnectHarness {
        config,
        identity: offer.identity,
        keys,
        remote,
        transport: RecordingTransport::default(),
        session,
    }
}

#[tokio::test]
async fn attempt_offer_reconnect_short_circuits_when_auto_reconnect_disabled() {
    let mut h = build_harness(|config| {
        config.reconnect.enable_auto_reconnect = false;
    })
    .await;

    let codec = SignalCodec::new(&h.identity, &h.keys, 120, 300);
    let status = StatusWriter::new(&h.config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &h.config, status: &status, runtime: &mut runtime };

    let result =
        attempt_offer_reconnect(&mut ctx, &codec, &mut h.transport, &mut h.session, &h.remote)
            .await;

    assert!(
        matches!(result, Ok(false)),
        "disabled auto-reconnect must short-circuit to Ok(false), got {result:?}",
    );
    assert!(
        h.transport.published.lock().await.is_empty(),
        "a disabled reconnect must not publish any signaling",
    );
}

#[tokio::test]
async fn attempt_offer_reconnect_gives_up_after_exhausting_attempts() {
    let mut h = build_harness(|config| {
        config.reconnect.enable_auto_reconnect = true;
        config.reconnect.max_attempts = 1;
        config.reconnect.backoff_initial_ms = 10;
        config.reconnect.backoff_max_ms = 10;
        config.reconnect.jitter_ratio = 0.0;
        // Short renegotiate window so the (answer-less) attempt times out quickly.
        config.reconnect.renegotiate_timeout_secs = 1;
        // Skip the same-session ICE restart so the attempt goes straight to renegotiate.
        config.webrtc.enable_ice_restart = false;
    })
    .await;

    let codec = SignalCodec::new(&h.identity, &h.keys, 120, 300);
    let status = StatusWriter::new(&h.config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &h.config, status: &status, runtime: &mut runtime };

    // No answer exists, so the renegotiate offer is published but never completes; after the
    // single allowed attempt the orchestration gives up.
    let result =
        attempt_offer_reconnect(&mut ctx, &codec, &mut h.transport, &mut h.session, &h.remote)
            .await;

    assert!(
        matches!(result, Ok(false)),
        "reconnect must return Ok(false) once attempts are exhausted, got {result:?}",
    );
    assert!(
        !h.transport.published.lock().await.is_empty(),
        "the renegotiate attempt should have published a fresh offer",
    );
}
