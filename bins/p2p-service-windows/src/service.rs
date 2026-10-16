//! The Service Control Manager side: dispatcher, control handler, and status
//! reporting around one daemon run.

use std::ffi::OsString;
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use clap::Parser;
use p2p_core::AppConfig;
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_daemon::{
    DaemonError, RuntimeConfigRequest, ShutdownToken, load_identity_and_keys, load_runtime_config,
    report_unenforced_platform_checks, run_answer_daemon_with_shutdown,
    run_offer_daemon_with_shutdown, setup_logging,
};
use windows_service::service::{
    ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::{define_windows_service, service_dispatcher};

use crate::cli::{Invocation, Role};

use p2p_platform::SERVICE_NAME;

/// How long sessions may drain after a stop request before the service gives up
/// and reports a failed stop. Matches `TimeoutStopSec=30s` in the shipped
/// systemd units, so a stop behaves the same under either supervisor.
const STOP_DRAIN_LIMIT: Duration = Duration::from_secs(30);

/// How often the stop checkpoint advances while draining. The SCM treats a
/// pending operation whose checkpoint stops moving for longer than its wait
/// hint as hung, so the hint is a little longer than this interval.
const STOP_PROGRESS_INTERVAL: Duration = Duration::from_secs(3);
const STOP_WAIT_HINT: Duration = Duration::from_secs(5);

/// Generous because startup includes reading the identity from disk; the SCM
/// only needs this to cover config and identity loading, not the broker connect.
const START_WAIT_HINT: Duration = Duration::from_secs(30);

/// `ERROR_FAILED_SERVICE_CONTROLLER_CONNECT`: the process was started from a
/// console rather than by the SCM.
const ERROR_FAILED_SERVICE_CONTROLLER_CONNECT: i32 = 1063;

/// `service_main` is called through an `extern "system"` trampoline, so it
/// cannot capture state. The arguments parsed from `binPath` reach it here.
static INVOCATION: OnceLock<Invocation> = OnceLock::new();

define_windows_service!(ffi_service_main, service_main);

pub(crate) fn run() -> Result<(), Box<dyn std::error::Error>> {
    // Parse before connecting to the SCM, so a bad `binPath` fails loudly when run
    // by hand instead of only as an opaque service start failure.
    let invocation = Invocation::parse();
    INVOCATION.set(invocation).map_err(|_| "service arguments were already initialized")?;

    service_dispatcher::start(SERVICE_NAME, ffi_service_main).map_err(|error| match &error {
        windows_service::Error::Winapi(io)
            if io.raw_os_error() == Some(ERROR_FAILED_SERVICE_CONTROLLER_CONNECT) =>
        {
            "p2p-service-windows must be started by the Service Control Manager \
                 (sc.exe start p2ptunnel); to run a daemon in this console, use p2p-answer \
                 or p2p-offer instead"
                .into()
        }
        _ => Box::new(error) as Box<dyn std::error::Error>,
    })?;
    Ok(())
}

fn service_main(_arguments: Vec<OsString>) {
    // `run` sets this before starting the dispatcher, which is the only caller.
    let Some(invocation) = INVOCATION.get() else {
        return;
    };
    // Nothing is listening for a returned error here: the SCM learns the outcome
    // from the final status that `run_service` reports, and the reason has
    // already been logged once logging is up.
    let _ = run_service(invocation);
}

/// Why the service stopped unsuccessfully. Each phase gets its own
/// service-specific exit code so `sc.exe query p2ptunnel` tells them apart even
/// when the failure happened before logging was available.
#[derive(Clone, Copy, Debug)]
enum FailurePhase {
    Config = 1,
    Logging = 2,
    Identity = 3,
    Daemon = 4,
    DrainTimeout = 5,
}

struct Failure {
    phase: FailurePhase,
    message: String,
}

impl Failure {
    fn new(phase: FailurePhase, error: impl std::fmt::Display) -> Self {
        Self { phase, message: error.to_string() }
    }
}

fn run_service(invocation: &Invocation) -> windows_service::Result<()> {
    let shutdown = ShutdownToken::new();
    let handler_token = shutdown.clone();
    let status_handle =
        service_control_handler::register(SERVICE_NAME, move |control| match control {
            // The handler only raises the generic shutdown request. Everything
            // downstream of the token is the same path a console Ctrl-C takes.
            ServiceControl::Stop | ServiceControl::Shutdown => {
                handler_token.request_shutdown();
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        })?;
    let mut status = StatusReporter { handle: status_handle, checkpoint: 0 };
    status.pending(ServiceState::StartPending, START_WAIT_HINT)?;

    let outcome = match tokio::runtime::Runtime::new() {
        Ok(runtime) => {
            let outcome = runtime.block_on(host_daemon(invocation, shutdown, &mut status));
            // A daemon task that ignores cancellation must not keep a service that
            // has already reported its result from exiting.
            runtime.shutdown_timeout(Duration::from_secs(1));
            outcome
        }
        Err(error) => Err(Failure::new(FailurePhase::Daemon, error)),
    };

    let exit_code = match outcome {
        Ok(()) => ServiceExitCode::NO_ERROR,
        Err(failure) => {
            if matches!(failure.phase, FailurePhase::Config | FailurePhase::Logging) {
                record_startup_error(invocation, &failure);
            }
            tracing::error!(phase = ?failure.phase, "p2ptunnel service failed: {}", failure.message);
            ServiceExitCode::ServiceSpecific(failure.phase as u32)
        }
    };
    status.stopped(exit_code)
}

async fn host_daemon(
    invocation: &Invocation,
    shutdown: ShutdownToken,
    status: &mut StatusReporter,
) -> Result<(), Failure> {
    let prepared = prepare(invocation)?;
    tracing::info!(role = invocation.role.as_str(), "p2ptunnel service running");
    status.running().map_err(|error| Failure::new(FailurePhase::Daemon, error))?;

    let daemon = async {
        let Prepared { config, identity, authorized_keys } = prepared;
        let result: Result<(), DaemonError> = match invocation.role {
            Role::Answer => {
                run_answer_daemon_with_shutdown(config, identity, authorized_keys, shutdown.clone())
                    .await
            }
            Role::Offer => {
                run_offer_daemon_with_shutdown(config, identity, authorized_keys, shutdown.clone())
                    .await
            }
        };
        result.map_err(|error| Failure::new(FailurePhase::Daemon, error))
    };
    tokio::pin!(daemon);

    let mut stop_requested = shutdown.clone();
    tokio::select! {
        result = &mut daemon => {
            // `select!` picks at random when both branches are ready, and a stop
            // request wakes the daemon and this watcher in the same poll, so the
            // flag, not the branch taken, says whether this exit was asked for.
            return judge_daemon_exit(result, shutdown.is_shutdown_requested());
        }
        () = stop_requested.cancelled() => {}
    }

    tracing::info!("service stop requested; draining sessions");
    let deadline = Instant::now() + STOP_DRAIN_LIMIT;
    loop {
        status
            .pending(ServiceState::StopPending, STOP_WAIT_HINT)
            .map_err(|error| Failure::new(FailurePhase::Daemon, error))?;
        tokio::select! {
            result = &mut daemon => return result,
            () = tokio::time::sleep(STOP_PROGRESS_INTERVAL) => {}
        }
        if Instant::now() >= deadline {
            return Err(Failure::new(
                FailurePhase::DrainTimeout,
                format!(
                    "sessions did not drain within {}s of the stop request",
                    STOP_DRAIN_LIMIT.as_secs()
                ),
            ));
        }
    }
}

/// A service daemon has no reason to finish on its own, so returning without a stop
/// request is a failure, however cleanly it exited: reporting it as a clean stop
/// would keep the SCM's restart-on-failure recovery actions from ever firing.
/// With a stop request outstanding, the daemon's own result stands.
fn judge_daemon_exit(result: Result<(), Failure>, stop_requested: bool) -> Result<(), Failure> {
    match result {
        Ok(()) if !stop_requested => {
            Err(Failure::new(FailurePhase::Daemon, "the daemon exited without a stop request"))
        }
        other => other,
    }
}

/// Leaves a trace of a failure that happened before the log file existed.
///
/// It goes under the role's service `state\` directory, the one place the
/// installer lets the service account write, and it is located without the
/// config, which may be what failed to load. Best-effort: if even this cannot be
/// written, the exit code is still reported.
fn record_startup_error(invocation: &Invocation, failure: &Failure) {
    use std::io::Write;

    let Ok(role_dir) = p2p_platform::service_config_dir(invocation.role.as_str()) else {
        return;
    };
    let unix_time = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs())
        .unwrap_or_default();
    let line = format!(
        "unix_time={unix_time} exit_code={} phase={:?}: {}\n",
        failure.phase as u32, failure.phase, failure.message
    );
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(role_dir.join("state").join("startup-error.log"))
    {
        let _ = file.write_all(line.as_bytes());
    }
}

struct Prepared {
    config: AppConfig,
    identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
}

/// The same startup sequence as `p2p-answer` / `p2p-offer` (shared through
/// `p2p_daemon`), minus the `--broker-url` override a service has no use for, plus the
/// check that the config's role is the one this service was installed with.
fn prepare(invocation: &Invocation) -> Result<Prepared, Failure> {
    let path =
        invocation.config_path().map_err(|error| Failure::new(FailurePhase::Config, error))?;
    let config = load_runtime_config(RuntimeConfigRequest {
        path: &path,
        role: invocation.role.node_role(),
        broker_url: None,
        require_matching_role: true,
    })
    .map_err(|error| Failure::new(FailurePhase::Config, format!("{}: {error}", path.display())))?;

    setup_logging(&config.logging).map_err(|error| Failure::new(FailurePhase::Logging, error))?;
    report_unenforced_platform_checks();
    tracing::info!(role = invocation.role.as_str(), config = %path.display(), "p2ptunnel service starting");

    let (identity, authorized_keys) = load_identity_and_keys(&config)
        .map_err(|error| Failure::new(FailurePhase::Identity, error))?;

    Ok(Prepared { config, identity, authorized_keys })
}

/// Reports `SERVICE_STATUS` and owns the checkpoint counter, which must advance
/// on every report during a pending operation and be zero otherwise.
struct StatusReporter {
    handle: service_control_handler::ServiceStatusHandle,
    checkpoint: u32,
}

impl StatusReporter {
    fn pending(&mut self, state: ServiceState, wait_hint: Duration) -> windows_service::Result<()> {
        self.checkpoint += 1;
        self.set(state, ServiceControlAccept::empty(), ServiceExitCode::NO_ERROR, wait_hint)
    }

    fn running(&mut self) -> windows_service::Result<()> {
        self.checkpoint = 0;
        self.set(
            ServiceState::Running,
            ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
            ServiceExitCode::NO_ERROR,
            Duration::ZERO,
        )
    }

    fn stopped(&mut self, exit_code: ServiceExitCode) -> windows_service::Result<()> {
        self.checkpoint = 0;
        self.set(ServiceState::Stopped, ServiceControlAccept::empty(), exit_code, Duration::ZERO)
    }

    fn set(
        &self,
        current_state: ServiceState,
        controls_accepted: ServiceControlAccept,
        exit_code: ServiceExitCode,
        wait_hint: Duration,
    ) -> windows_service::Result<()> {
        self.handle.set_service_status(ServiceStatus {
            service_type: ServiceType::OWN_PROCESS,
            current_state,
            controls_accepted,
            exit_code,
            checkpoint: self.checkpoint,
            wait_hint,
            process_id: None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::{Failure, FailurePhase, judge_daemon_exit};

    #[test]
    fn a_clean_exit_after_a_stop_request_is_a_clean_stop() {
        // The race: the daemon finishes in the same poll that observes the stop.
        assert!(judge_daemon_exit(Ok(()), true).is_ok());
    }

    #[test]
    fn a_clean_exit_with_no_stop_request_is_a_failure() {
        let outcome = judge_daemon_exit(Ok(()), false);
        assert!(matches!(outcome, Err(Failure { phase: FailurePhase::Daemon, .. })));
    }

    #[test]
    fn a_daemon_error_is_kept_whether_or_not_a_stop_was_requested() {
        for stop_requested in [false, true] {
            let failed = Err(Failure::new(FailurePhase::Daemon, "broker rejected the session"));
            match judge_daemon_exit(failed, stop_requested) {
                Err(failure) => assert_eq!(failure.message, "broker rejected the session"),
                Ok(()) => panic!("a daemon error must not become a clean stop"),
            }
        }
    }
}
