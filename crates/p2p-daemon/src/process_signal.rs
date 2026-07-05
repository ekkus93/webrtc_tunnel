//! Translates OS process-termination signals into one generic observable event
//! for the CLI binaries. Daemon state machines never see a signal number or
//! platform detail — only the [`crate::ShutdownToken`] the binary derives from
//! this. Shared by Linux and macOS (`cfg(unix)`), not Linux-only.

/// Waits for a process shutdown request. Returns the signal name once observed.
/// A closed signal stream is treated as a real I/O error, never as a successful
/// shutdown signal.
#[cfg(unix)]
pub async fn wait_for_process_shutdown_signal() -> Result<&'static str, std::io::Error> {
    use tokio::signal::unix::{SignalKind, signal};

    let mut interrupt = signal(SignalKind::interrupt())?;
    let mut terminate = signal(SignalKind::terminate())?;

    tokio::select! {
        received = interrupt.recv() => {
            received
                .map(|()| "SIGINT")
                .ok_or_else(|| std::io::Error::other("SIGINT signal stream closed"))
        }
        received = terminate.recv() => {
            received
                .map(|()| "SIGTERM")
                .ok_or_else(|| std::io::Error::other("SIGTERM signal stream closed"))
        }
    }
}

#[cfg(not(unix))]
pub async fn wait_for_process_shutdown_signal() -> Result<&'static str, std::io::Error> {
    tokio::signal::ctrl_c().await?;
    Ok("Ctrl-C")
}

#[cfg(all(test, unix))]
mod tests {
    use super::wait_for_process_shutdown_signal;

    /// Sends a real OS signal to the current test process (via the `kill` utility,
    /// present on both Linux and macOS test runners) and asserts the adapter reports
    /// it. This exercises the actual `tokio::signal::unix` registration rather than
    /// only the daemon-side `ShutdownToken`, which a direct in-process cancellation
    /// test cannot cover.
    async fn assert_signal_is_observed(flag: &str, expected_name: &str) {
        let pid = std::process::id().to_string();
        let waiter = tokio::spawn(wait_for_process_shutdown_signal());

        // Give the signal handler a moment to register before sending.
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        let status = std::process::Command::new("kill")
            .arg(flag)
            .arg(&pid)
            .status()
            .expect("kill command should run");
        assert!(status.success(), "kill {flag} {pid} should succeed");

        let observed = tokio::time::timeout(std::time::Duration::from_secs(5), waiter)
            .await
            .expect("signal should be observed before the test timeout")
            .expect("waiter task should not panic")
            .expect("signal stream should not close");
        assert_eq!(observed, expected_name);
    }

    // Both signal kinds are raced together inside a single `wait_for_process_shutdown_signal`
    // call, and `kill` targets the whole test-binary process — running SIGTERM and SIGINT
    // cases as separate `#[tokio::test]` functions lets cargo's test-thread parallelism
    // interleave them, so one test's signal can be observed by the other's waiter. Run both
    // sequentially in one test to keep each signal delivery unambiguous.
    #[tokio::test]
    async fn sigterm_and_sigint_are_observed_and_named() {
        assert_signal_is_observed("-TERM", "SIGTERM").await;
        assert_signal_is_observed("-INT", "SIGINT").await;
    }
}
