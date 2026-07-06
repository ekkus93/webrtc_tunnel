//! Translates OS process-termination signals into one generic observable event
//! for the CLI binaries. Daemon state machines never see a signal number or
//! platform detail — only the [`crate::ShutdownToken`] the binary derives from
//! this. Shared by Linux and macOS (`cfg(unix)`), not Linux-only.

/// Waits for a process shutdown request. Returns the signal name once observed.
/// A closed signal stream is treated as a real I/O error, never as a successful
/// shutdown signal.
#[cfg(unix)]
pub async fn wait_for_process_shutdown_signal() -> Result<&'static str, std::io::Error> {
    ProcessShutdownSignals::install()?.wait().await
}

#[cfg(not(unix))]
pub async fn wait_for_process_shutdown_signal() -> Result<&'static str, std::io::Error> {
    tokio::signal::ctrl_c().await?;
    Ok("Ctrl-C")
}

/// Splits signal registration from waiting so a test can deterministically
/// observe "handlers are now registered" (via [`ProcessShutdownSignals::install`]
/// returning) before a signal can possibly race the wait.
#[cfg(unix)]
pub struct ProcessShutdownSignals {
    interrupt: tokio::signal::unix::Signal,
    terminate: tokio::signal::unix::Signal,
}

#[cfg(unix)]
impl ProcessShutdownSignals {
    pub fn install() -> Result<Self, std::io::Error> {
        use tokio::signal::unix::{SignalKind, signal};

        Ok(Self {
            interrupt: signal(SignalKind::interrupt())?,
            terminate: signal(SignalKind::terminate())?,
        })
    }

    pub async fn wait(&mut self) -> Result<&'static str, std::io::Error> {
        tokio::select! {
            received = self.interrupt.recv() => {
                received
                    .map(|()| "SIGINT")
                    .ok_or_else(|| std::io::Error::other("SIGINT signal stream closed"))
            }
            received = self.terminate.recv() => {
                received
                    .map(|()| "SIGTERM")
                    .ok_or_else(|| std::io::Error::other("SIGTERM signal stream closed"))
            }
        }
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::ProcessShutdownSignals;

    const CHILD_MODE: &str = "P2P_SIGNAL_TEST_CHILD";
    const READY_PATH: &str = "P2P_SIGNAL_TEST_READY";
    const RESULT_PATH: &str = "P2P_SIGNAL_TEST_RESULT";

    /// Re-execs this same test binary as a child process (never signals the
    /// Cargo test runner itself), sends it a real OS signal, and asserts the
    /// adapter reports it. This exercises the actual `tokio::signal::unix`
    /// registration rather than only the daemon-side `ShutdownToken`, which a
    /// direct in-process cancellation test cannot cover.
    ///
    /// The child signals "handlers are registered" by writing a ready-marker
    /// file right after `ProcessShutdownSignals::install()` returns — the
    /// parent waits on that file instead of guessing with a sleep, so the
    /// signal can never race an unregistered handler.
    fn assert_child_observes_signal(flag: &str, expected_name: &str) {
        if std::env::var_os(CHILD_MODE).is_some() {
            let ready_path =
                std::env::var_os(READY_PATH).expect("child should receive a ready-marker path");
            let result_path =
                std::env::var_os(RESULT_PATH).expect("child should receive a result path");

            let runtime = tokio::runtime::Runtime::new().expect("child runtime should build");
            let observed = runtime.block_on(async {
                let mut signals = ProcessShutdownSignals::install()
                    .expect("child should be able to install signal handlers");
                std::fs::write(&ready_path, b"ready").expect("child should write ready marker");
                signals
                    .wait()
                    .await
                    .expect("child should observe the signal without the stream closing")
            });
            std::fs::write(&result_path, observed).expect("child should write its result");
            // Exit immediately: falling back into the caller would re-enter this
            // same child-mode branch for the *next* flag in
            // `sigterm_and_sigint_are_observed_and_named`, leaving this process
            // stuck waiting for a second signal nobody will ever send.
            std::process::exit(0);
        }

        let unique = format!("{}-{}", std::process::id(), flag.trim_start_matches('-'));
        let ready_path = std::env::temp_dir().join(format!("p2ptunnel-signal-test-ready-{unique}"));
        let result_path =
            std::env::temp_dir().join(format!("p2ptunnel-signal-test-result-{unique}"));
        let _ = std::fs::remove_file(&ready_path);
        let _ = std::fs::remove_file(&result_path);

        let exe = std::env::current_exe().expect("current test executable");
        let mut child = std::process::Command::new(exe)
            .arg("process_signal::tests::sigterm_and_sigint_are_observed_and_named")
            .arg("--exact")
            .env(CHILD_MODE, "1")
            .env(READY_PATH, &ready_path)
            .env(RESULT_PATH, &result_path)
            .spawn()
            .expect("spawn child test process");
        let pid = child.id();

        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while !ready_path.exists() {
            assert!(
                std::time::Instant::now() < deadline,
                "child did not report ready before the deadline"
            );
            std::thread::sleep(std::time::Duration::from_millis(5));
        }

        let status = std::process::Command::new("kill")
            .arg(flag)
            .arg(pid.to_string())
            .status()
            .expect("kill command should run");
        assert!(status.success(), "kill {flag} {pid} should succeed");

        let exit = child.wait().expect("child process should be waitable");
        assert!(exit.success(), "child process should exit successfully, got {exit:?}");

        let observed =
            std::fs::read_to_string(&result_path).expect("child result file should be readable");
        assert_eq!(observed, expected_name);

        let _ = std::fs::remove_file(&ready_path);
        let _ = std::fs::remove_file(&result_path);
    }

    // Both signal kinds run in one test function so the child re-exec always
    // targets the exact same `--exact` test path; each call spawns its own
    // separate child process, so the two signal deliveries never interfere.
    #[test]
    fn sigterm_and_sigint_are_observed_and_named() {
        assert_child_observes_signal("-TERM", "SIGTERM");
        assert_child_observes_signal("-INT", "SIGINT");
    }
}
