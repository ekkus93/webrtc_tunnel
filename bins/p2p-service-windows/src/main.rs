//! Hosts the offer or answer daemon as the Windows service `p2ptunnel`.
//!
//! This binary is Service Control Manager plumbing only: registration, the
//! control handler, and `SERVICE_STATUS` reporting. It contains no tunnel logic;
//! it calls the same daemon entry points as `p2p-offer` / `p2p-answer`, and a
//! `SERVICE_CONTROL_STOP` becomes the same generic `ShutdownToken` request a
//! console Ctrl-C produces there. See spec §6.4 for why this is a separate
//! binary rather than a supervisor-specific mode of the foreground daemons.

// The command line is plain logic, so it is compiled for tests on every host;
// only the SCM side is Windows-specific.
#[cfg(any(windows, test))]
mod cli;
#[cfg(windows)]
mod service;

#[cfg(windows)]
fn main() {
    if let Err(error) = service::run() {
        eprintln!("Error: {error}");
        std::process::exit(1);
    }
}

#[cfg(not(windows))]
fn main() {
    eprintln!(
        "p2p-service-windows only runs under the Windows Service Control Manager; \
         use p2p-offer or p2p-answer on this platform"
    );
    std::process::exit(1);
}
