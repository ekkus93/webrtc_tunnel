//! Bridges `tracing` events emitted by the shared daemon/WebRTC code into the
//! Android in-app log feed.
//!
//! The desktop binaries install a `tracing` subscriber via
//! `p2p_daemon::setup_logging`, but the mobile runtime never did, so every
//! `tracing` event — including the ICE diagnostics — was silently dropped on
//! Android and the Logs screen only ever showed lifecycle messages. This installs
//! a process-global subscriber exactly once whose only layer appends each event to
//! a shared [`LogBuffer`] that `recent_logs` reads, so daemon/ICE diagnostics show
//! up in the app.
//!
//! Only events emitted through `tracing` are captured. The candidate/SDP redaction
//! the daemon applies before emitting still holds: the ICE candidate diagnostic
//! only includes an address when `logging.redact_candidates = false`.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, OnceLock};

use tracing::field::{Field, Visit};
use tracing::{Event, Level, Subscriber};
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::layer::{Context, Layer};
use tracing_subscriber::prelude::*;

use super::state::unix_ms;
use super::types::AndroidLogEvent;

const MAX_LOG_EVENTS: usize = 256;

/// A bounded, cheaply cloneable ring buffer of log events shared between the
/// runtime controller and the `tracing` bridge layer.
#[derive(Clone, Default)]
pub(crate) struct LogBuffer {
    events: Arc<Mutex<VecDeque<AndroidLogEvent>>>,
}

impl LogBuffer {
    /// Append an event, evicting the oldest once the cap is exceeded.
    pub(crate) fn push(&self, event: AndroidLogEvent) -> Result<(), String> {
        let mut events = self.events.lock().map_err(|_| "log buffer mutex poisoned".to_owned())?;
        events.push_back(event);
        while events.len() > MAX_LOG_EVENTS {
            events.pop_front();
        }
        Ok(())
    }

    /// Most-recent-first, up to `max_events` (always at least 1).
    pub(crate) fn recent(&self, max_events: usize) -> Result<Vec<AndroidLogEvent>, String> {
        let max_events = max_events.max(1);
        self.events
            .lock()
            .map(|events| events.iter().rev().take(max_events).cloned().collect())
            .map_err(|_| "log buffer mutex poisoned".to_owned())
    }
}

static INSTALL_RESULT: OnceLock<Result<(), String>> = OnceLock::new();

/// Install the global `tracing` subscriber exactly once, routing events at or above
/// `level` into `buffer`. Later calls are no-ops that return the first call's result,
/// matching the once-per-process nature of the global default subscriber; in
/// production there is a single runtime controller, so its buffer is the one that
/// receives events.
pub(crate) fn install_tracing_once(buffer: LogBuffer, level: &str) -> Result<(), String> {
    INSTALL_RESULT
        .get_or_init(|| {
            let layer = AndroidLogLayer { buffer }.with_filter(level_filter(level));
            tracing_subscriber::registry()
                .with(layer)
                .try_init()
                .map_err(|error| format!("failed to install Android tracing bridge: {error}"))
        })
        .clone()
}

fn level_filter(level: &str) -> LevelFilter {
    match level.to_ascii_lowercase().as_str() {
        "trace" => LevelFilter::TRACE,
        "debug" => LevelFilter::DEBUG,
        "warn" => LevelFilter::WARN,
        "error" => LevelFilter::ERROR,
        _ => LevelFilter::INFO,
    }
}

struct AndroidLogLayer {
    buffer: LogBuffer,
}

impl<S: Subscriber> Layer<S> for AndroidLogLayer {
    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let metadata = event.metadata();
        let mut visitor = MessageVisitor::new(metadata.target());
        event.record(&mut visitor);
        let result = self.buffer.push(AndroidLogEvent {
            unix_ms: unix_ms(),
            level: level_str(*metadata.level()).to_owned(),
            message: visitor.finish(),
        });
        // Deliberately not `tracing::error!` here: this runs inside the installed
        // subscriber's own event handler, and the buffer's mutex being poisoned means
        // a re-entrant tracing call would just hit this same failure again. Write
        // directly to stderr instead so the failure is still visible somewhere.
        if let Err(reason) = result {
            eprintln!("android log buffer mutex poisoned, dropping tracing event: {reason}");
        }
    }
}

fn level_str(level: Level) -> &'static str {
    match level {
        Level::TRACE => "trace",
        Level::DEBUG => "debug",
        Level::INFO => "info",
        Level::WARN => "warn",
        Level::ERROR => "error",
    }
}

/// Flattens an event's `message` plus any structured fields into one line, e.g.
/// `ice: gathered local ICE candidate (session_id=… candidate=typ=host …)`.
struct MessageVisitor {
    target: String,
    message: String,
    fields: Vec<String>,
}

impl MessageVisitor {
    fn new(target: &str) -> Self {
        Self { target: target.to_owned(), message: String::new(), fields: Vec::new() }
    }

    fn finish(self) -> String {
        let mut out = if self.target.is_empty() || self.message.is_empty() {
            self.message
        } else {
            format!("{}: {}", self.target, self.message)
        };
        if !self.fields.is_empty() {
            out.push_str(" (");
            out.push_str(&self.fields.join(" "));
            out.push(')');
        }
        out
    }
}

impl Visit for MessageVisitor {
    // The typed `record_*` methods all default to `record_debug`, so capturing the
    // message and every field here is sufficient.
    fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
        if field.name() == "message" {
            self.message = format!("{value:?}");
        } else {
            self.fields.push(format!("{}={:?}", field.name(), value));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn log_buffer_caps_and_returns_most_recent_first() {
        let buffer = LogBuffer::default();
        for i in 0..(MAX_LOG_EVENTS + 5) {
            buffer
                .push(AndroidLogEvent {
                    unix_ms: i as u64,
                    level: "info".to_owned(),
                    message: format!("event {i}"),
                })
                .expect("buffer mutex is not poisoned");
        }
        let recent = buffer.recent(3).expect("buffer mutex is not poisoned");
        assert_eq!(recent.len(), 3);
        // Newest first.
        assert_eq!(recent[0].message, format!("event {}", MAX_LOG_EVENTS + 4));
        // Oldest events were evicted, so the buffer never exceeds the cap.
        let all = buffer.recent(usize::MAX).expect("buffer mutex is not poisoned");
        assert_eq!(all.len(), MAX_LOG_EVENTS);
    }

    #[test]
    fn log_buffer_reports_poison_instead_of_dropping_or_defaulting() {
        let buffer = LogBuffer::default();
        let inner = Arc::clone(&buffer.events);
        let result = std::thread::spawn(move || {
            let _guard = inner.lock().expect("mutex is not yet poisoned");
            panic!("deliberately poisoning the log buffer mutex for a test");
        })
        .join();
        assert!(result.is_err(), "spawned thread should have panicked");

        assert_eq!(
            buffer
                .push(AndroidLogEvent {
                    unix_ms: 0,
                    level: "info".to_owned(),
                    message: "should not be silently dropped".to_owned(),
                })
                .expect_err("mutex is poisoned"),
            "log buffer mutex poisoned"
        );
        assert_eq!(buffer.recent(10).expect_err("mutex is poisoned"), "log buffer mutex poisoned");
    }

    #[test]
    fn level_filter_defaults_to_info_for_unknown() {
        assert_eq!(level_filter("debug"), LevelFilter::DEBUG);
        assert_eq!(level_filter("WARN"), LevelFilter::WARN);
        assert_eq!(level_filter("nonsense"), LevelFilter::INFO);
    }

    #[test]
    fn layer_captures_tracing_event_into_buffer() {
        let buffer = LogBuffer::default();
        let subscriber =
            tracing_subscriber::registry().with(AndroidLogLayer { buffer: buffer.clone() });
        // Scope the layer to this thread only — no global subscriber is touched.
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(target: "ice", state = "connected", "ICE connection state changed");
        });
        let recent = buffer.recent(10).expect("buffer mutex is not poisoned");
        assert_eq!(recent.len(), 1);
        assert_eq!(recent[0].level, "info");
        assert!(
            recent[0].message.contains("ICE connection state changed"),
            "message was: {}",
            recent[0].message,
        );
        assert!(recent[0].message.contains("state="), "message was: {}", recent[0].message);
    }
}
