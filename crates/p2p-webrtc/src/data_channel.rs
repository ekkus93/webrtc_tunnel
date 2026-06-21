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
}
