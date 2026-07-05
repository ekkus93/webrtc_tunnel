//! Optional systemd `sd_notify` (`Type=notify`) readiness/stopping signaling.
//!
//! This is deliberately isolated from the rest of the daemon lifecycle: it is
//! compiled in only when the `sd-notify` Cargo feature is enabled (off by
//! default — a default build neither depends on nor requires systemd), and
//! even then, sending a notification is a harmless no-op unless `$NOTIFY_SOCKET`
//! is set in the environment (i.e. the process is actually supervised by a
//! systemd `Type=notify` unit). Callers may call these functions
//! unconditionally in any environment (manual shell, Docker, `launchd`,
//! `Type=simple` systemd) without checking the feature or the environment
//! themselves.
//!
//! "Ready" here means the binary has finished its fallible startup sequence
//! (config load/validate, identity/authorized-keys load, runtime directories,
//! logging) and is about to hand control to the daemon's run loop — not that
//! the daemon has necessarily subscribed to MQTT or bound every listener yet.
//! That finer-grained signal would need a readiness channel threaded through
//! the daemon core; this coarser one was chosen to keep `sd_notify` fully
//! decoupled from the generic lifecycle, per the project's requirement that
//! this integration stay optional and non-invasive.

#[cfg(feature = "sd-notify")]
pub fn notify_ready() {
    if let Err(error) = sd_notify::notify(&[sd_notify::NotifyState::Ready]) {
        tracing::debug!(reason = %error, "sd_notify READY=1 not sent (not running under Type=notify?)");
    }
}

#[cfg(not(feature = "sd-notify"))]
pub fn notify_ready() {}

#[cfg(feature = "sd-notify")]
pub fn notify_stopping() {
    if let Err(error) = sd_notify::notify(&[sd_notify::NotifyState::Stopping]) {
        tracing::debug!(reason = %error, "sd_notify STOPPING=1 not sent (not running under Type=notify?)");
    }
}

#[cfg(not(feature = "sd-notify"))]
pub fn notify_stopping() {}

#[cfg(all(test, feature = "sd-notify"))]
mod tests {
    use super::{notify_ready, notify_stopping};
    use std::os::unix::net::UnixDatagram;

    /// Exercises the real protocol end-to-end without any `unsafe` code (this
    /// workspace forbids `unsafe_code`, and setting a process-wide env var via
    /// `std::env::set_var` is `unsafe` as of Rust 1.82). Instead, re-execs this
    /// same test binary as a child process with `NOTIFY_SOCKET` set only for
    /// that child (`Command::env`, which is always safe — it never mutates the
    /// current process's environment). The child branch calls the real public
    /// functions, which send real datagrams that the parent asserts on.
    #[test]
    fn notify_ready_and_stopping_send_real_datagrams() {
        const CHILD_MARKER: &str = "P2P_NOTIFY_TEST_CHILD";

        if std::env::var(CHILD_MARKER).is_ok() {
            notify_ready();
            notify_stopping();
            return;
        }

        let socket_path =
            std::env::temp_dir().join(format!("p2ptunnel-notify-test-{}.sock", std::process::id()));
        let _ = std::fs::remove_file(&socket_path);
        let listener = UnixDatagram::bind(&socket_path).expect("bind notify socket");
        listener
            .set_read_timeout(Some(std::time::Duration::from_secs(5)))
            .expect("set read timeout");

        let exe = std::env::current_exe().expect("current test executable");
        let status = std::process::Command::new(exe)
            .arg("notify::tests::notify_ready_and_stopping_send_real_datagrams")
            .arg("--exact")
            .env(CHILD_MARKER, "1")
            .env("NOTIFY_SOCKET", &socket_path)
            .status()
            .expect("spawn child test process");
        assert!(status.success(), "child process should exit successfully");

        let mut buf = [0_u8; 256];
        let n = listener.recv(&mut buf).expect("recv READY datagram");
        assert_eq!(&buf[..n], b"READY=1\n");

        let n = listener.recv(&mut buf).expect("recv STOPPING datagram");
        assert_eq!(&buf[..n], b"STOPPING=1\n");

        let _ = std::fs::remove_file(&socket_path);
    }

    #[test]
    fn notify_functions_are_harmless_without_notify_socket() {
        // In the ambient test environment NOTIFY_SOCKET is not set (nothing here
        // runs under a real systemd Type=notify unit), so both calls must be
        // silent, non-panicking no-ops.
        notify_ready();
        notify_stopping();
    }
}
