//! In-memory signaling transport with route-scoped fault injection: the mesh that
//! wires peers together, the per-peer `InMemoryTransport`, the fault controls
//! (drop/duplicate/delay/publish-fail/poll-fail), and the delivery trace that
//! assertions read back.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use p2p_daemon::DaemonSignalingTransport;
use tokio::sync::{mpsc, oneshot};
use tokio::time::sleep;

#[derive(Clone, Default)]
pub(crate) struct TransportTrace {
    attempts: Arc<Mutex<Vec<TransportAttempt>>>,
    payloads_by_recipient: Arc<Mutex<HashMap<String, Vec<Vec<u8>>>>>,
}

impl TransportTrace {
    pub(crate) fn record(
        &self,
        from_peer_id: &str,
        peer_id: &p2p_core::PeerId,
        payload: &[u8],
        delivered: bool,
    ) {
        self.attempts.lock().expect("trace mutex should lock").push(TransportAttempt {
            from_peer_id: from_peer_id.to_owned(),
            to_peer_id: peer_id.to_string(),
            payload: payload.to_vec(),
            delivered,
        });
        let mut payloads = self.payloads_by_recipient.lock().expect("trace mutex should lock");
        payloads.entry(peer_id.to_string()).or_default().push(payload.to_vec());
    }

    pub(crate) fn payloads_for(&self, peer_id: &str) -> Vec<Vec<u8>> {
        self.payloads_by_recipient
            .lock()
            .expect("trace mutex should lock")
            .get(peer_id)
            .cloned()
            .unwrap_or_default()
    }

    pub(crate) fn attempts(&self) -> Vec<TransportAttempt> {
        self.attempts.lock().expect("trace mutex should lock").clone()
    }
}

#[derive(Clone)]
pub(crate) struct TransportAttempt {
    pub(crate) from_peer_id: String,
    pub(crate) to_peer_id: String,
    pub(crate) payload: Vec<u8>,
    pub(crate) delivered: bool,
}

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
pub(crate) struct RouteKey {
    pub(crate) from_peer_id: String,
    pub(crate) to_peer_id: String,
}

impl RouteKey {
    pub(crate) fn new(from_peer_id: impl Into<String>, to_peer_id: impl Into<String>) -> Self {
        Self { from_peer_id: from_peer_id.into(), to_peer_id: to_peer_id.into() }
    }
}

#[derive(Default)]
pub(crate) struct TransportFaults {
    publish_failures: HashMap<RouteKey, usize>,
    dropped_deliveries: HashMap<RouteKey, usize>,
    duplicate_deliveries: HashMap<RouteKey, usize>,
    delayed_deliveries_ms: HashMap<RouteKey, u64>,
    publish_barriers: HashMap<RouteKey, PublishBarrier>,
}

/// A one-shot barrier that pauses a route's next `publish_signal` call mid-flight:
/// the transport signals `entered` once it has been reached, then awaits
/// `release` before actually delivering — so a test can deterministically prove a
/// publish is in flight (rather than guessing with a sleep) before continuing.
struct PublishBarrier {
    entered_tx: oneshot::Sender<()>,
    release_rx: oneshot::Receiver<()>,
}

/// Handed to the test by [`TransportFaultControl::block_next_publish`]: awaited to
/// learn the publish has actually reached the transport and is now blocked.
pub(crate) struct PublishBarrierEntered {
    entered_rx: oneshot::Receiver<()>,
}

impl PublishBarrierEntered {
    pub(crate) async fn wait(self) {
        self.entered_rx.await.expect("publish barrier sender should not be dropped before entry");
    }
}

/// Handed to the test by [`TransportFaultControl::block_next_publish`]: called to
/// let the blocked publish proceed.
pub(crate) struct PublishBarrierRelease {
    release_tx: oneshot::Sender<()>,
}

impl PublishBarrierRelease {
    pub(crate) fn release(self) {
        self.release_tx.send(()).expect("publish barrier observer must remain alive");
    }
}

#[derive(Clone, Default)]
pub(crate) struct TransportFaultControl {
    faults: Arc<Mutex<TransportFaults>>,
    routes: Arc<Mutex<HashMap<String, mpsc::UnboundedSender<InMemoryEvent>>>>,
}

impl TransportFaultControl {
    /// Blocks the next `publish_signal` call on this route mid-flight. Returns a
    /// waiter (resolves once the publish is actually in flight) and a releaser
    /// (lets it proceed) so a test can prove a publish is blocked before doing
    /// anything else, instead of racing a sleep against it.
    pub(crate) fn block_next_publish(
        &self,
        from_peer_id: &str,
        to_peer_id: &str,
    ) -> (PublishBarrierEntered, PublishBarrierRelease) {
        let (entered_tx, entered_rx) = oneshot::channel();
        let (release_tx, release_rx) = oneshot::channel();
        self.faults.lock().expect("fault mutex should lock").publish_barriers.insert(
            RouteKey::new(from_peer_id, to_peer_id),
            PublishBarrier { entered_tx, release_rx },
        );
        (PublishBarrierEntered { entered_rx }, PublishBarrierRelease { release_tx })
    }

    pub(crate) fn fail_next_publish(&self, from_peer_id: &str, to_peer_id: &str, count: usize) {
        self.faults
            .lock()
            .expect("fault mutex should lock")
            .publish_failures
            .insert(RouteKey::new(from_peer_id, to_peer_id), count);
    }

    pub(crate) fn drop_next_delivery(&self, from_peer_id: &str, to_peer_id: &str, count: usize) {
        self.faults
            .lock()
            .expect("fault mutex should lock")
            .dropped_deliveries
            .insert(RouteKey::new(from_peer_id, to_peer_id), count);
    }

    pub(crate) fn duplicate_next_delivery(
        &self,
        from_peer_id: &str,
        to_peer_id: &str,
        count: usize,
    ) {
        self.faults
            .lock()
            .expect("fault mutex should lock")
            .duplicate_deliveries
            .insert(RouteKey::new(from_peer_id, to_peer_id), count);
    }

    pub(crate) fn delay_next_delivery(&self, from_peer_id: &str, to_peer_id: &str, delay_ms: u64) {
        self.faults
            .lock()
            .expect("fault mutex should lock")
            .delayed_deliveries_ms
            .insert(RouteKey::new(from_peer_id, to_peer_id), delay_ms);
    }

    pub(crate) fn inject_poll_failure(&self, peer_id: &str) {
        let sender = self
            .routes
            .lock()
            .expect("routes mutex should lock")
            .get(peer_id)
            .cloned()
            .expect("poll failure route should exist");
        sender
            .send(InMemoryEvent::PollFailure("injected in-memory poll failure".to_owned()))
            .expect("poll failure receiver should be alive");
    }

    pub(crate) fn inject_payload(&self, peer_id: &str, payload: Vec<u8>) {
        let sender = self
            .routes
            .lock()
            .expect("routes mutex should lock")
            .get(peer_id)
            .cloned()
            .expect("payload route should exist");
        sender.send(InMemoryEvent::Payload(payload)).expect("payload receiver should be alive");
    }
}

#[derive(Clone)]
pub(crate) enum InMemoryEvent {
    Payload(Vec<u8>),
    PollFailure(String),
}

pub(crate) struct InMemoryTransport {
    peer_id: String,
    inbox: mpsc::UnboundedReceiver<InMemoryEvent>,
    routes: Arc<Mutex<HashMap<String, mpsc::UnboundedSender<InMemoryEvent>>>>,
    faults: Arc<Mutex<TransportFaults>>,
    trace: TransportTrace,
}

impl DaemonSignalingTransport for InMemoryTransport {
    async fn subscribe_own_topic(&mut self) -> Result<(), p2p_signaling::SignalingError> {
        Ok(())
    }

    async fn publish_signal(
        &mut self,
        peer_id: &p2p_core::PeerId,
        _topic_prefix: &str,
        payload: Vec<u8>,
    ) -> Result<(), p2p_signaling::SignalingError> {
        let route = self
            .routes
            .lock()
            .expect("routes mutex should lock")
            .get(peer_id.as_str())
            .cloned()
            .ok_or_else(|| {
                p2p_signaling::SignalingError::Protocol(format!(
                    "missing in-memory route for {}",
                    peer_id
                ))
            })?;
        let route_key = RouteKey::new(self.peer_id.clone(), peer_id.to_string());
        let barrier = self
            .faults
            .lock()
            .expect("fault mutex should lock")
            .publish_barriers
            .remove(&route_key);
        if let Some(barrier) = barrier {
            barrier.entered_tx.send(()).expect("publish barrier observer must remain alive");
            barrier.release_rx.await.expect("publish barrier release sender must remain alive");
        }
        let (fail_publish, drop_delivery, duplicate_count, delay_ms) = {
            let mut faults = self.faults.lock().expect("fault mutex should lock");
            let fail_publish = decrement_fault(&mut faults.publish_failures, &route_key);
            let drop_delivery = decrement_fault(&mut faults.dropped_deliveries, &route_key);
            let duplicate_count =
                faults.duplicate_deliveries.remove(&route_key).unwrap_or_default();
            let delay_ms = faults.delayed_deliveries_ms.remove(&route_key).unwrap_or_default();
            (fail_publish, drop_delivery, duplicate_count, delay_ms)
        };
        if fail_publish {
            self.trace.record(&self.peer_id, peer_id, &payload, false);
            return Err(p2p_signaling::SignalingError::Protocol(format!(
                "injected publish failure from {} to {}",
                self.peer_id, peer_id
            )));
        }
        self.trace.record(&self.peer_id, peer_id, &payload, !drop_delivery);
        if delay_ms > 0 {
            sleep(Duration::from_millis(delay_ms)).await;
        }
        if !drop_delivery {
            route.send(InMemoryEvent::Payload(payload.clone())).map_err(|_| {
                p2p_signaling::SignalingError::Protocol(format!(
                    "in-memory route for {} is closed",
                    peer_id
                ))
            })?;
            for _ in 0..duplicate_count {
                route.send(InMemoryEvent::Payload(payload.clone())).map_err(|_| {
                    p2p_signaling::SignalingError::Protocol(format!(
                        "in-memory duplicate route for {} is closed",
                        peer_id
                    ))
                })?;
            }
        }
        Ok(())
    }

    async fn poll_signal_payload(
        &mut self,
    ) -> Result<Option<Vec<u8>>, p2p_signaling::SignalingError> {
        match self.inbox.recv().await {
            Some(InMemoryEvent::Payload(payload)) => Ok(Some(payload)),
            Some(InMemoryEvent::PollFailure(error)) => {
                Err(p2p_signaling::SignalingError::Protocol(error))
            }
            None => Ok(None),
        }
    }
}

pub(crate) fn decrement_fault(faults: &mut HashMap<RouteKey, usize>, route_key: &RouteKey) -> bool {
    match faults.get_mut(route_key) {
        Some(remaining) if *remaining > 0 => {
            *remaining -= 1;
            if *remaining == 0 {
                faults.remove(route_key);
            }
            true
        }
        _ => false,
    }
}

pub(crate) struct InMemoryTransportMesh {
    routes: Arc<Mutex<HashMap<String, mpsc::UnboundedSender<InMemoryEvent>>>>,
    faults: Arc<Mutex<TransportFaults>>,
    trace: TransportTrace,
}

impl InMemoryTransportMesh {
    pub(crate) fn new() -> Self {
        Self {
            routes: Arc::new(Mutex::new(HashMap::new())),
            faults: Arc::new(Mutex::new(TransportFaults::default())),
            trace: TransportTrace::default(),
        }
    }

    pub(crate) fn add_transport(&self, peer_id: &str) -> InMemoryTransport {
        let (tx, rx) = mpsc::unbounded_channel();
        self.routes.lock().expect("routes mutex should lock").insert(peer_id.to_owned(), tx);
        InMemoryTransport {
            peer_id: peer_id.to_owned(),
            inbox: rx,
            routes: Arc::clone(&self.routes),
            faults: Arc::clone(&self.faults),
            trace: self.trace.clone(),
        }
    }

    pub(crate) fn control(&self) -> TransportFaultControl {
        TransportFaultControl { faults: Arc::clone(&self.faults), routes: Arc::clone(&self.routes) }
    }

    pub(crate) fn trace(&self) -> TransportTrace {
        self.trace.clone()
    }
}

pub(crate) fn transport_pair(
    duplicate_answer_to_offer_payloads: usize,
    delay_first_answer_to_offer_ms: u64,
) -> (InMemoryTransport, InMemoryTransport, TransportTrace) {
    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let control = mesh.control();
    if duplicate_answer_to_offer_payloads > 0 {
        control.duplicate_next_delivery(
            "answer-office",
            "offer-home",
            duplicate_answer_to_offer_payloads,
        );
    }
    if delay_first_answer_to_offer_ms > 0 {
        control.delay_next_delivery("answer-office", "offer-home", delay_first_answer_to_offer_ms);
    }
    (offer_transport, answer_transport, mesh.trace())
}

pub(crate) fn transport_mesh(peer_ids: &[&str]) -> HashMap<String, InMemoryTransport> {
    let mesh = InMemoryTransportMesh::new();
    peer_ids.iter().map(|peer_id| ((*peer_id).to_owned(), mesh.add_transport(peer_id))).collect()
}
