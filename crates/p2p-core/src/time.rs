//! Fallible wall-clock helpers (FIX6 P2-002).
//!
//! `SystemTime::now().duration_since(UNIX_EPOCH)` fails only if the system clock is set before
//! the Unix epoch. Callers previously either panicked (`.expect(...)`) or invented a misleading
//! zero timestamp (`.unwrap_or(0)`). These helpers make the failure explicit so each caller can
//! degrade safely instead.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, SystemTimeError, UNIX_EPOCH};

/// Current Unix time in milliseconds, or the [`SystemTimeError`] if the system clock is set
/// before the Unix epoch. The caller decides how to degrade — never a panic or an invented zero.
pub fn unix_time_ms() -> Result<u64, SystemTimeError> {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|duration| duration.as_millis() as u64)
}

/// Resolve a timestamp for diagnostics/timing that degrades safely on clock failure: on success
/// it records and returns the `fresh` value; on failure (`None`) it reuses the last known-good
/// value in `last` rather than inventing zero. `fresh` is [`unix_time_ms`]`().ok()` at the call
/// site, so the caller can log the underlying error before degrading.
pub fn resolve_unix_ms(fresh: Option<u64>, last: &AtomicU64) -> u64 {
    match fresh {
        Some(ms) => {
            last.store(ms, Ordering::Relaxed);
            ms
        }
        None => last.load(Ordering::Relaxed),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unix_time_ms_returns_a_plausible_recent_timestamp() {
        // 2020-01-01T00:00:00Z in ms; any real clock is well past this.
        let floor_ms = 1_577_836_800_000;
        let now = unix_time_ms().expect("a normal system clock is after the unix epoch");
        assert!(now > floor_ms, "expected a recent timestamp, got {now}");
    }

    #[test]
    fn resolve_unix_ms_records_and_returns_fresh_values() {
        let last = AtomicU64::new(0);
        assert_eq!(resolve_unix_ms(Some(1_000), &last), 1_000);
        assert_eq!(last.load(Ordering::Relaxed), 1_000);
    }

    #[test]
    fn resolve_unix_ms_reuses_last_known_value_on_failure_instead_of_zero() {
        let last = AtomicU64::new(0);
        resolve_unix_ms(Some(42), &last);
        // A subsequent clock failure must reuse 42, never invent 0.
        assert_eq!(resolve_unix_ms(None, &last), 42);
    }
}
