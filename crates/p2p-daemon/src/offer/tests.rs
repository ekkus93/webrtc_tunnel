use super::*;

#[tokio::test]
async fn stop_and_join_reports_ok_when_every_monitor_joins_cleanly() {
    let monitors = vec![
        OfferAcceptMonitor { forward_id: "a".to_owned(), handle: tokio::spawn(async {}) },
        OfferAcceptMonitor { forward_id: "b".to_owned(), handle: tokio::spawn(async {}) },
    ];

    stop_and_join_offer_accept_runtime(monitors)
        .await
        .expect("every monitor joining cleanly must not be an error");
}

#[tokio::test]
async fn stop_and_join_returns_monitor_join_failure_instead_of_warning_and_success() {
    let panicking = tokio::spawn(async { panic!("simulated monitor panic") });
    // Give the panic a chance to actually land before we join it, so this isn't
    // relying on join() itself racing the panic.
    while !panicking.is_finished() {
        tokio::task::yield_now().await;
    }
    let monitors = vec![OfferAcceptMonitor { forward_id: "ssh".to_owned(), handle: panicking }];

    let result = stop_and_join_offer_accept_runtime(monitors).await;
    match result {
        Err(DaemonError::OfferAcceptMonitorJoinFailed { forward_id, reason }) => {
            assert_eq!(forward_id, "ssh");
            assert!(reason.contains("simulated monitor panic"), "reason was: {reason}");
        }
        other => panic!(
            "a panicked monitor must surface as OfferAcceptMonitorJoinFailed, not a \
             warning-and-success, got {other:?}"
        ),
    }
}

#[test]
fn merge_prefers_primary_run_error_over_cleanup_and_closed_errors() {
    let primary = DaemonError::AckTimeout;
    let result = merge_offer_run_and_cleanup_results(
        Err(primary),
        Err(DaemonError::Logging("cleanup failed".to_owned())),
        Err(DaemonError::Logging("closed write failed".to_owned())),
        true,
    );
    match result {
        Err(DaemonError::AckTimeout) => {}
        other => panic!("expected the primary run error to win, got {other:?}"),
    }
}

#[test]
fn offer_shutdown_after_primary_failure_still_returns_primary_failure() {
    let result =
        merge_offer_run_and_cleanup_results(Err(DaemonError::AckTimeout), Ok(()), Ok(()), true);
    assert!(
        matches!(result, Err(DaemonError::AckTimeout)),
        "a real primary run-loop failure must not be hidden by a later shutdown request"
    );
}

#[test]
fn offer_shutdown_cleanup_failure_still_returns_failure() {
    let result = merge_offer_run_and_cleanup_results(
        Ok(()),
        Err(DaemonError::OfferAcceptMonitorJoinFailed {
            forward_id: "ssh".to_owned(),
            reason: "panicked".to_owned(),
        }),
        Ok(()),
        true,
    );
    assert!(
        matches!(result, Err(DaemonError::OfferAcceptMonitorJoinFailed { .. })),
        "a genuine cleanup failure during cooperative shutdown must not be hidden, got {result:?}"
    );
}

#[test]
fn merge_returns_closed_write_failure_when_run_and_cleanup_both_succeed() {
    let result = merge_offer_run_and_cleanup_results(
        Ok(()),
        Ok(()),
        Err(DaemonError::Logging("closed write failed".to_owned())),
        true,
    );
    assert!(
        matches!(result, Err(DaemonError::Logging(_))),
        "a terminal status write failure must not be hidden, got {result:?}"
    );
}

#[test]
fn offer_shutdown_while_listening_without_peer_returns_ok_for_pure_merge() {
    let result = merge_offer_run_and_cleanup_results(Ok(()), Ok(()), Ok(()), true);
    assert!(result.is_ok(), "a genuine cooperative shutdown must return Ok, got {result:?}");
}

/// FIX7 P0-008/RESPONSES item 4: an unrequested, error-free run-loop exit must
/// be treated as an invariant violation, not folded into success — the current
/// run loop's `Ok(())` exits are all shutdown-gated, so this combination
/// should be unreachable today, but a future accidental early return or
/// worker-supervisor defect must not silently become a false clean shutdown.
#[test]
fn unrequested_clean_offer_exit_is_failure() {
    let result = merge_offer_run_and_cleanup_results(Ok(()), Ok(()), Ok(()), false);
    match result {
        Err(DaemonError::Logging(message)) => {
            assert!(
                message.contains("without a shutdown request"),
                "unexpected message: {message}"
            );
        }
        other => panic!(
            "an unrequested, error-free offer-loop exit must be an invariant-violation \
             error, not {other:?}"
        ),
    }
}

#[tokio::test]
async fn stop_and_join_reports_the_first_failure_and_still_joins_the_rest() {
    let panicking = tokio::spawn(async { panic!("first monitor panic") });
    while !panicking.is_finished() {
        tokio::task::yield_now().await;
    }
    let also_panicking = tokio::spawn(async { panic!("second monitor panic") });
    while !also_panicking.is_finished() {
        tokio::task::yield_now().await;
    }
    let monitors = vec![
        OfferAcceptMonitor { forward_id: "first".to_owned(), handle: panicking },
        OfferAcceptMonitor { forward_id: "second".to_owned(), handle: also_panicking },
    ];

    let result = stop_and_join_offer_accept_runtime(monitors).await;
    match result {
        Err(DaemonError::OfferAcceptMonitorJoinFailed { forward_id, .. }) => {
            assert_eq!(forward_id, "first", "the first failure encountered must be primary");
        }
        other => panic!("expected the first monitor's join failure, got {other:?}"),
    }
}
