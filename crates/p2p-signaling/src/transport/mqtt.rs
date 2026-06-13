//! MQTT signaling transport: connects to the broker over TLS, subscribes to the
//! node's own signal topic, publishes outgoing payloads, and pumps the event loop
//! to surface inbound signal payloads. Also holds the broker-options / TLS-config
//! construction and the signal-topic helper.

use std::collections::VecDeque;
use std::fs;
use std::sync::Arc;
use std::time::Duration;

use p2p_core::{AppConfig, PeerId};
use rumqttc::tokio_rustls::rustls::{ClientConfig, RootCertStore};
use rumqttc::{
    AsyncClient, Event, EventLoop, MqttOptions, Packet, QoS, TlsConfiguration, Transport,
};

use crate::error::SignalingError;
pub fn signal_topic(prefix: &str, peer_id: &PeerId) -> String {
    format!("{prefix}/v1/nodes/{peer_id}/signal")
}

pub struct MqttSignalingTransport {
    pub(crate) client: AsyncClient,
    pub(crate) event_loop: EventLoop,
    pub(crate) own_topic: String,
    pub(crate) qos: QoS,
    pub(crate) pending_payloads: VecDeque<Vec<u8>>,
}

impl MqttSignalingTransport {
    pub fn connect(config: &AppConfig) -> Result<Self, SignalingError> {
        let (options, qos, own_topic) = build_mqtt_options(config)?;
        let (client, event_loop) = AsyncClient::new(options, 10);

        Ok(Self { client, event_loop, own_topic, qos, pending_payloads: VecDeque::new() })
    }

    pub async fn subscribe_own_topic(&mut self) -> Result<(), SignalingError> {
        self.client
            .subscribe(self.own_topic.clone(), self.qos)
            .await
            .map_err(SignalingError::from)?;

        loop {
            let event = self.poll().await?;
            if matches!(event, Event::Incoming(Packet::SubAck(_))) {
                return Ok(());
            }
            buffer_pending_own_topic_publish(&event, &self.own_topic, &mut self.pending_payloads);
        }
    }

    pub async fn publish_signal(
        &mut self,
        peer_id: &PeerId,
        topic_prefix: &str,
        payload: Vec<u8>,
    ) -> Result<(), SignalingError> {
        self.client
            .publish(signal_topic(topic_prefix, peer_id), self.qos, false, payload)
            .await
            .map_err(SignalingError::from)?;
        self.pump_once().await?;
        Ok(())
    }

    pub async fn poll(&mut self) -> Result<Event, SignalingError> {
        self.event_loop.poll().await.map_err(SignalingError::from)
    }

    pub async fn poll_signal_payload(&mut self) -> Result<Option<Vec<u8>>, SignalingError> {
        loop {
            if let Some(payload) = self.pending_payloads.pop_front() {
                return Ok(Some(payload));
            }

            let event = self.poll().await?;
            if let Some(payload) = own_topic_publish_payload(&event, &self.own_topic) {
                return Ok(Some(payload));
            }
        }
    }

    async fn pump_once(&mut self) -> Result<(), SignalingError> {
        let event = self.poll().await?;
        buffer_pending_own_topic_publish(&event, &self.own_topic, &mut self.pending_payloads);
        Ok(())
    }
}

pub(crate) fn buffer_pending_own_topic_publish(
    event: &Event,
    own_topic: &str,
    pending_payloads: &mut VecDeque<Vec<u8>>,
) -> bool {
    let Some(payload) = own_topic_publish_payload(event, own_topic) else {
        return false;
    };
    pending_payloads.push_back(payload);
    true
}

pub(crate) fn own_topic_publish_payload(event: &Event, own_topic: &str) -> Option<Vec<u8>> {
    match event {
        Event::Incoming(Packet::Publish(publish)) if publish.topic == own_topic => {
            Some(publish.payload.to_vec())
        }
        _ => None,
    }
}

pub(crate) fn build_mqtt_options(
    config: &AppConfig,
) -> Result<(MqttOptions, QoS, String), SignalingError> {
    if !config.security.require_mqtt_tls {
        return Err(SignalingError::Protocol(
            "security.require_mqtt_tls must remain enabled in v1".to_owned(),
        ));
    }
    if !config.broker.url.starts_with("mqtts://") {
        return Err(SignalingError::Protocol(
            "broker.url must use mqtts:// when TLS is required".to_owned(),
        ));
    }
    if config.broker.connect_timeout_secs != 5 {
        return Err(SignalingError::Protocol(
            "broker.connect_timeout_secs must remain 5 in v1 because the current MQTT transport does not expose a configurable connect timeout".to_owned(),
        ));
    }
    if config.broker.session_expiry_secs != 0 {
        return Err(SignalingError::Protocol(
            "broker.session_expiry_secs must remain 0 in v1 because the current signaling transport uses MQTT v4 semantics".to_owned(),
        ));
    }

    let separator = if config.broker.url.contains('?') { '&' } else { '?' };
    let url = format!("{}{}client_id={}", config.broker.url, separator, config.broker.client_id);
    let mut options = MqttOptions::parse_url(url)?;
    options.set_keep_alive(Duration::from_secs(u64::from(config.broker.keepalive_secs)));
    options.set_clean_session(config.broker.clean_session);
    match (config.broker.username.is_empty(), config.broker.password_file.as_os_str().is_empty()) {
        (true, true) => {}
        (false, true) => {
            options.set_credentials(config.broker.username.clone(), String::new());
        }
        (false, false) => {
            let password = fs::read_to_string(&config.broker.password_file)
                .map_err(|error| SignalingError::io_path(&config.broker.password_file, error))?
                .trim()
                .to_owned();
            options.set_credentials(config.broker.username.clone(), password);
        }
        (true, false) => {
            return Err(SignalingError::Protocol(
                "broker.password_file requires broker.username in v1".to_owned(),
            ));
        }
    }

    if config.broker.url.starts_with("mqtts://") {
        options.set_transport(build_tls_transport(config)?);
    }

    let qos = qos_from_u8(config.broker.qos)?;
    let own_topic = signal_topic(&config.broker.topic_prefix, &config.node.peer_id);
    Ok((options, qos, own_topic))
}

fn build_tls_transport(config: &AppConfig) -> Result<Transport, SignalingError> {
    if config.broker.tls.insecure_skip_verify {
        return Err(SignalingError::Protocol(
            "broker.tls.insecure_skip_verify is unsupported in v1".to_owned(),
        ));
    }

    let ca = if config.broker.tls.ca_file.as_os_str().is_empty() {
        None
    } else {
        Some(
            fs::read(&config.broker.tls.ca_file)
                .map_err(|error| SignalingError::io_path(&config.broker.tls.ca_file, error))?,
        )
    };
    let client_cert_set = !config.broker.tls.client_cert_file.as_os_str().is_empty();
    let client_key_set = !config.broker.tls.client_key_file.as_os_str().is_empty();
    let client_auth = match (client_cert_set, client_key_set) {
        (false, false) => None,
        (true, true) => Some((
            fs::read(&config.broker.tls.client_cert_file).map_err(|error| {
                SignalingError::io_path(&config.broker.tls.client_cert_file, error)
            })?,
            fs::read(&config.broker.tls.client_key_file).map_err(|error| {
                SignalingError::io_path(&config.broker.tls.client_key_file, error)
            })?,
        )),
        _ => {
            return Err(SignalingError::Protocol(
                "broker TLS client certificate and key must be configured together".to_owned(),
            ));
        }
    };

    if let Some(ca) = ca {
        return Ok(Transport::tls(ca, client_auth, None));
    }
    if client_auth.is_some() {
        return Err(SignalingError::Protocol(
            "broker TLS client certificate auth requires broker.tls.ca_file in v1".to_owned(),
        ));
    }
    // No explicit CA: trust the compiled-in Mozilla root set (webpki-roots) instead
    // of rumqttc's default, whose OS-native trust store is empty on Android. This
    // keeps default trust working cross-platform; private CAs still use `ca_file`.
    Ok(Transport::tls_with_config(TlsConfiguration::Rustls(Arc::new(default_roots_tls_config()))))
}

/// rustls client config trusting the webpki-roots Mozilla CA set, with no client
/// auth. Mirrors how rumqttc builds its config (`ClientConfig::builder()`), so it
/// resolves the same process crypto provider.
pub(crate) fn default_roots_tls_config() -> ClientConfig {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    ClientConfig::builder().with_root_certificates(roots).with_no_client_auth()
}

fn qos_from_u8(value: u8) -> Result<QoS, SignalingError> {
    match value {
        0 => Ok(QoS::AtMostOnce),
        1 => Ok(QoS::AtLeastOnce),
        2 => Ok(QoS::ExactlyOnce),
        _ => Err(SignalingError::Protocol(format!("unsupported MQTT QoS {value}"))),
    }
}
