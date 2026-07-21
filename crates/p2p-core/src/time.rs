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
/// it records and returns `Some(fresh)`; on failure it reuses the last known-good value in
/// `last`, but only if one has actually been recorded — the very first clock failure (before
/// any success has ever stored a value) must yield `None`, never the atomic's zero
/// initializer masquerading as a real timestamp (FIX7 P0-010-B; this replaces the old
/// `resolve_unix_ms`, whose `u64` return had no way to distinguish "no prior value" from a
/// genuine zero timestamp and so returned 0 on a first-ever failure). `fresh` is
/// [`unix_time_ms`]`().ok()` at the call site, so the caller can log the underlying error before
/// degrading. Zero is a sentinel only inside `last` and never escapes as a timestamp.
pub fn resolve_optional_unix_ms(fresh: Option<u64>, last: &AtomicU64) -> Option<u64> {
    match fresh {
        Some(ms) => {
            last.store(ms, Ordering::Relaxed);
            Some(ms)
        }
        None => {
            let prior = last.load(Ordering::Relaxed);
            (prior != 0).then_some(prior)
        }
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
    fn resolve_optional_unix_ms_records_and_returns_fresh_values() {
        let last = AtomicU64::new(0);
        assert_eq!(resolve_optional_unix_ms(Some(1_000), &last), Some(1_000));
        assert_eq!(last.load(Ordering::Relaxed), 1_000);
    }

    #[test]
    fn subsequent_diagnostic_clock_failure_may_reuse_non_zero_known_timestamp() {
        let last = AtomicU64::new(0);
        resolve_optional_unix_ms(Some(42), &last);
        // A subsequent clock failure must reuse 42, never invent 0.
        assert_eq!(resolve_optional_unix_ms(None, &last), Some(42));
    }

    // FIX7 P0-010-B/P0-010-G: the very first clock failure (before any success has ever stored a
    // value) must yield None, never the atomic's zero initializer masquerading as a real
    // timestamp — this is the exact gap the old u64-returning resolve_unix_ms had.
    #[test]
    fn first_clock_failure_returns_none_for_diagnostic_timestamp_not_zero() {
        let last = AtomicU64::new(0);
        assert_eq!(resolve_optional_unix_ms(None, &last), None);
    }
}
