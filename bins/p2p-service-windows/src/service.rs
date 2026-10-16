//! The Service Control Manager side: dispatcher, control handler, and status
//! reporting around one daemon run.

use std::ffi::OsString;
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use clap::Parser;
use p2p_core::AppConfig;
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_daemon::{
    DaemonError, ShutdownToken, apply_env_overrides, report_unenforced_platform_checks,
    run_answer_daemon_with_shutdown, run_offer_daemon_with_shutdown, setup_logging,
};
use windows_service::service::{
    ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::{define_windows_service, service_dispatcher};

use crate::cli::{Invocation, Role};

/// The service's registered name. It must match the installer's `sc.exe create`
/// name exactly, because Windows names the virtual account `NT SERVICE\p2ptunnel`
/// after it (spec §2.6).
const SERVICE_NAME: &str = "p2ptunnel";

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
            // A service daemon has no reason to finish on its own. Reporting that
            // as a clean stop would keep the SCM's restart-on-failure recovery
            // actions from ever firing.
            return result.and(Err(Failure::new(
                FailurePhase::Daemon,
                "the daemon exited without a stop request",
            )));
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

struct Prepared {
    config: AppConfig,
    identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
}

/// Mirrors the startup sequence of `p2p-answer` / `p2p-offer` `run()`, minus the
/// `--broker-url` override, which a service has no use for.
fn prepare(invocation: &Invocation) -> Result<Prepared, Failure> {
    let config_failure = |error: &dyn std::fmt::Display| Failure::new(FailurePhase::Config, error);

    let path = invocation.config_path().map_err(|error| config_failure(&error))?;
    let mut config = AppConfig::load_from_file(&path)
        .map_err(|error| config_failure(&format!("{}: {error}", path.display())))?;
    apply_env_overrides(&mut config).map_err(|error| config_failure(&error))?;
    if config.node.role != invocation.role.node_role() {
        return Err(config_failure(&format!(
            "{} is a {:?} config, but this service was installed with --role {}",
            path.display(),
            config.node.role,
            invocation.role.as_str()
        )));
    }
    config.validate().map_err(|error| config_failure(&error))?;
    config.ensure_runtime_dirs().map_err(|error| config_failure(&error))?;

    setup_logging(&config.logging).map_err(|error| Failure::new(FailurePhase::Logging, error))?;
    report_unenforced_platform_checks();
    tracing::info!(role = invocation.role.as_str(), config = %path.display(), "p2ptunnel service starting");

    let identity_failure =
        |error: &dyn std::fmt::Display| Failure::new(FailurePhase::Identity, error);
    let identity = IdentityFile::from_file(&config.paths.identity)
        .map_err(|error| identity_failure(&error))?;
    config.validate_identity_peer(&identity.peer_id).map_err(|error| identity_failure(&error))?;
    let authorized_keys = AuthorizedKeys::from_file(&config.paths.authorized_keys)
        .map_err(|error| identity_failure(&error))?;

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
