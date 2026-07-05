//! The reliable/ordered tunnel data channel wrapper and its event stream.

use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use tokio::sync::{Mutex, mpsc};
use webrtc::data_channel::RTCDataChannel;
use webrtc::data_channel::data_channel_message::DataChannelMessage;
use webrtc::data_channel::data_channel_state::RTCDataChannelState;

use crate::WebRtcError;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DataChannelEvent {
    Open,
    Closed,
    Message(Vec<u8>),
}

#[derive(Clone)]
pub struct DataChannelHandle {
    inner: Arc<RTCDataChannel>,
    events: Arc<Mutex<mpsc::Receiver<DataChannelEvent>>>,
}

impl DataChannelHandle {
    pub(crate) fn observe(inner: Arc<RTCDataChannel>) -> Self {
        let (events_tx, events_rx) = mpsc::channel(32);
        let open_tx = events_tx.clone();
        inner.on_open(Box::new(move || {
            let open_tx = open_tx.clone();
            Box::pin(async move {
                if open_tx.send(DataChannelEvent::Open).await.is_err() {
                    // The session loop dropped its receiver — expected only during teardown.
                    tracing::debug!(target: "tunnel", "data channel open event dropped; receiver gone");
                }
            })
        }));

        let close_tx = events_tx.clone();
        inner.on_close(Box::new(move || {
            let close_tx = close_tx.clone();
            Box::pin(async move {
                if close_tx.send(DataChannelEvent::Closed).await.is_err() {
                    tracing::debug!(target: "tunnel", "data channel close event dropped; receiver gone");
                }
            })
        }));

        let message_tx = events_tx.clone();
        inner.on_message(Box::new(move |message: DataChannelMessage| {
            let message_tx = message_tx.clone();
            let len = message.data.len();
            Box::pin(async move {
                if message_tx.send(DataChannelEvent::Message(message.data.to_vec())).await.is_err() {
                    // Receiver gone: the session loop stopped consuming. Warn — a dropped
                    // message during an active session loses real tunnel data, which is not a
                    // clean shutdown.
                    tracing::warn!(target: "tunnel", bytes = len, "data channel message dropped; receiver gone");
                }
            })
        }));

        Self { inner, events: Arc::new(Mutex::new(events_rx)) }
    }

    pub fn label(&self) -> String {
        self.inner.label().to_owned()
    }

    pub fn ordered(&self) -> bool {
        self.inner.ordered()
    }

    pub fn is_open(&self) -> bool {
        self.inner.ready_state() == RTCDataChannelState::Open
    }

    pub async fn send(&self, payload: &[u8]) -> Result<usize, WebRtcError> {
        self.inner.send(&Bytes::copy_from_slice(payload)).await.map_err(WebRtcError::from)
    }

    pub async fn next_event(&self) -> Option<DataChannelEvent> {
        self.events.lock().await.recv().await
    }

    pub async fn wait_for_open(&self, timeout: Duration) -> Result<(), WebRtcError> {
        tokio::time::timeout(timeout, async {
            loop {
                match self.next_event().await {
                    Some(DataChannelEvent::Open) => return Ok(()),
                    Some(_) => continue,
                    None => {
                        return Err(WebRtcError::InvalidConfig(
                            "data channel closed before open".to_owned(),
                        ));
                    }
                }
            }
        })
        .await
        .map_err(|_| WebRtcError::Timeout)?
    }

    /// Test-only seam: builds a handle around a real (but unconnected) `RTCDataChannel`
    /// without registering the `on_open`/`on_close`/`on_message` callbacks, and hands back
    /// the raw event sender so a test can drive `next_event`/`wait_for_open` deterministically
    /// instead of depending on an actual SCTP association forming.
    #[cfg(test)]
    pub(crate) fn observe_for_tests(
        inner: Arc<RTCDataChannel>,
    ) -> (Self, mpsc::Sender<DataChannelEvent>) {
        let (events_tx, events_rx) = mpsc::channel(32);
        (Self { inner, events: Arc::new(Mutex::new(events_rx)) }, events_tx)
    }
}

#[cfg(test)]
mod tests {
    use tokio::sync::mpsc::error::TrySendError;
    use webrtc::api::APIBuilder;
    use webrtc::peer_connection::configuration::RTCConfiguration;

    use super::{DataChannelEvent, DataChannelHandle, Duration};
    use crate::WebRtcError;

    /// A real (but never negotiated) data channel: enough to back a `DataChannelHandle`
    /// without a full peer-to-peer handshake, since `observe_for_tests` drives events
    /// directly rather than through the channel's own open/close/message callbacks.
    async fn unconnected_data_channel() -> std::sync::Arc<webrtc::data_channel::RTCDataChannel> {
        let api = APIBuilder::new().build();
        let peer_connection =
            api.new_peer_connection(RTCConfiguration::default()).await.expect("peer connection");
        peer_connection.create_data_channel("test", None).await.expect("data channel")
    }

    #[tokio::test]
    async fn wait_for_open_returns_promptly_once_open_event_is_dispatched() {
        let (handle, events_tx) =
            DataChannelHandle::observe_for_tests(unconnected_data_channel().await);
        events_tx.send(DataChannelEvent::Open).await.expect("send open event");

        handle
            .wait_for_open(Duration::from_secs(1))
            .await
            .expect("open event should resolve wait_for_open");
    }

    #[tokio::test]
    async fn wait_for_open_ignores_close_events_and_keeps_waiting_for_open() {
        // Matches the actual loop: a `Closed` event is not treated as terminal, only a
        // dropped sender (channel closed with no `Open` ever seen) is.
        let (handle, events_tx) =
            DataChannelHandle::observe_for_tests(unconnected_data_channel().await);
        events_tx.send(DataChannelEvent::Closed).await.expect("send close event");
        events_tx.send(DataChannelEvent::Open).await.expect("send open event");

        handle
            .wait_for_open(Duration::from_secs(1))
            .await
            .expect("open event after a close event should still resolve wait_for_open");
    }

    #[tokio::test]
    async fn wait_for_open_errors_when_the_channel_closes_before_ever_opening() {
        let (handle, events_tx) =
            DataChannelHandle::observe_for_tests(unconnected_data_channel().await);
        events_tx.send(DataChannelEvent::Closed).await.expect("send close event");
        drop(events_tx);

        let error = handle
            .wait_for_open(Duration::from_secs(1))
            .await
            .expect_err("dropped sender with no open event should error, not hang or succeed");
        assert!(matches!(error, WebRtcError::InvalidConfig(_)), "got {error:?}");
    }

    #[tokio::test]
    async fn wait_for_open_respects_its_timeout_when_no_event_ever_arrives() {
        let (handle, events_tx) =
            DataChannelHandle::observe_for_tests(unconnected_data_channel().await);

        let error = handle
            .wait_for_open(Duration::from_millis(50))
            .await
            .expect_err("no event within the timeout should time out");
        assert!(matches!(error, WebRtcError::Timeout), "got {error:?}");
        // Keep the sender alive for the whole wait so this is a real timeout, not an
        // incidental "sender dropped" error.
        drop(events_tx);
    }

    #[tokio::test]
    async fn next_event_delivers_messages_in_order() {
        let (handle, events_tx) =
            DataChannelHandle::observe_for_tests(unconnected_data_channel().await);
        events_tx.send(DataChannelEvent::Message(vec![1])).await.expect("send first");
        events_tx.send(DataChannelEvent::Message(vec![2])).await.expect("send second");

        assert_eq!(handle.next_event().await, Some(DataChannelEvent::Message(vec![1])));
        assert_eq!(handle.next_event().await, Some(DataChannelEvent::Message(vec![2])));
    }

    #[tokio::test]
    async fn sending_after_the_handle_is_dropped_fails_cleanly_instead_of_panicking() {
        let (handle, events_tx) =
            DataChannelHandle::observe_for_tests(unconnected_data_channel().await);
        drop(handle);

        let result = events_tx.send(DataChannelEvent::Open).await;
        assert!(result.is_err(), "send after the only receiver is dropped should fail, not panic");
    }

    #[tokio::test]
    async fn the_bounded_channel_applies_backpressure_instead_of_silently_dropping() {
        let (handle, events_tx) =
            DataChannelHandle::observe_for_tests(unconnected_data_channel().await);
        for i in 0..32u8 {
            events_tx
                .try_send(DataChannelEvent::Message(vec![i]))
                .expect("channel should accept up to its declared capacity of 32");
        }

        let overflow = events_tx.try_send(DataChannelEvent::Message(vec![255]));
        assert!(matches!(overflow, Err(TrySendError::Full(_))), "got {overflow:?}");

        // Draining one slot makes room again, proving this is real backpressure rather than
        // a silent drop of the newest (or oldest) event.
        assert_eq!(handle.next_event().await, Some(DataChannelEvent::Message(vec![0])));
        events_tx
            .try_send(DataChannelEvent::Message(vec![255]))
            .expect("room should reopen after the receiver drains a slot");
    }
}
