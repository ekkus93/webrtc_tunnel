use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::Path;
use std::sync::{Arc, Mutex};

use crate::DaemonError;
use p2p_core::LoggingConfig;
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::fmt::writer::BoxMakeWriter;

const REDACTED: &str = "<redacted>";

pub fn setup_logging(config: &LoggingConfig) -> Result<(), DaemonError> {
    let writer = build_writer(config)?;
    let builder = tracing_subscriber::fmt()
        .with_max_level(parse_level(&config.level)?)
        .with_target(false)
        .with_writer(writer);

    match config.format.as_str() {
        "json" => builder.json().try_init(),
        _ => builder.try_init(),
    }
    .map_err(|error| DaemonError::Logging(error.to_string()))
}

pub fn redact_secret(_value: &str) -> String {
    REDACTED.to_owned()
}

pub fn redact_sdp(config: &LoggingConfig, value: &str) -> String {
    if config.redact_sdp {
        format!("{REDACTED}:sdp:{}-bytes", value.len())
    } else {
        value.to_owned()
    }
}

pub fn redact_candidate(config: &LoggingConfig, value: &str) -> String {
    if config.redact_candidates {
        format!("{REDACTED}:candidate:{}-bytes", value.len())
    } else {
        value.to_owned()
    }
}

fn build_writer(config: &LoggingConfig) -> Result<BoxMakeWriter, DaemonError> {
    let file = if config.file_logging {
        ensure_parent_exists(&config.log_file)?;
        Some(Arc::new(Mutex::new(
            OpenOptions::new().create(true).append(true).open(&config.log_file)?,
        )))
    } else {
        None
    };
    let write_stdout = config.stdout_logging;

    Ok(BoxMakeWriter::new(move || MultiWriter {
        stdout: write_stdout.then(io::stdout),
        file: file.as_ref().map(Arc::clone),
    }))
}

fn ensure_parent_exists(path: &Path) -> Result<(), DaemonError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    Ok(())
}

fn parse_level(level: &str) -> Result<LevelFilter, DaemonError> {
    match level {
        "trace" => Ok(LevelFilter::TRACE),
        "debug" => Ok(LevelFilter::DEBUG),
        "info" => Ok(LevelFilter::INFO),
        "warn" => Ok(LevelFilter::WARN),
        "error" => Ok(LevelFilter::ERROR),
        other => Err(DaemonError::Logging(format!("unsupported log level '{other}'"))),
    }
}

struct MultiWriter {
    stdout: Option<io::Stdout>,
    file: Option<Arc<Mutex<std::fs::File>>>,
}

impl Write for MultiWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if let Some(stdout) = self.stdout.as_mut() {
            stdout.write_all(buf)?;
        }
        if let Some(file) = self.file.as_ref() {
            let mut file = file.lock().map_err(|_| io::Error::other("log file mutex poisoned"))?;
            file.write_all(buf)?;
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        if let Some(stdout) = self.stdout.as_mut() {
            stdout.flush()?;
        }
        if let Some(file) = self.file.as_ref() {
            let mut file = file.lock().map_err(|_| io::Error::other("log file mutex poisoned"))?;
            file.flush()?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use p2p_core::LoggingConfig;

    use super::{redact_candidate, redact_sdp, redact_secret};

    fn config() -> LoggingConfig {
        LoggingConfig {
            level: "info".to_owned(),
            format: "text".to_owned(),
            file_logging: false,
            stdout_logging: true,
            log_file: PathBuf::from("/tmp/log.txt"),
            redact_secrets: true,
            redact_sdp: true,
            redact_candidates: true,
            log_rotation: "none".to_owned(),
        }
    }

    #[test]
    fn secrets_are_always_redacted() {
        assert_eq!(redact_secret("super-secret"), "<redacted>");
    }

    #[test]
    fn sdp_is_redacted_by_default() {
        let redacted = redact_sdp(&config(), "v=0\r\na=candidate");
        assert!(redacted.starts_with("<redacted>:sdp:"));
        assert!(!redacted.contains("candidate"));
    }

    #[test]
    fn candidates_are_redacted_by_default() {
        let redacted = redact_candidate(&config(), "candidate:1 1 UDP 2122252543 192.0.2.1 12345");
        assert!(redacted.starts_with("<redacted>:candidate:"));
        assert!(!redacted.contains("192.0.2.1"));
    }
}
