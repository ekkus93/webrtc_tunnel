//! Generic cooperative shutdown signal shared by every daemon lifecycle
//! (process signals, tests, and future supervisor adapters). This token must
//! never learn about `systemd`, `launchd`, Docker, Unix PIDs, or Android.

use tokio::sync::watch;

/// Cloneable cancellation handle. Every clone keeps the underlying channel
/// alive, so a compatibility wrapper can create a token and never externally
/// cancel it without the receiver hanging on a dropped sender.
#[derive(Clone, Debug)]
pub struct ShutdownToken {
    sender: watch::Sender<bool>,
    receiver: watch::Receiver<bool>,
}

impl Default for ShutdownToken {
    fn default() -> Self {
        Self::new()
    }
}

impl ShutdownToken {
    pub fn new() -> Self {
        let (sender, receiver) = watch::channel(false);
        Self { sender, receiver }
    }

    pub fn request_shutdown(&self) {
        // Every `ShutdownToken` clone keeps its own `receiver` alive (see the struct doc),
        // so this channel can never actually be closed and this send cannot fail.
        let _ = self.sender.send(true);
    }

    pub fn is_shutdown_requested(&self) -> bool {
        *self.receiver.borrow()
    }

    pub async fn cancelled(&mut self) {
        if self.is_shutdown_requested() {
            return;
        }

        while self.receiver.changed().await.is_ok() {
            if self.is_shutdown_requested() {
                return;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::ShutdownToken;
    use std::time::Duration;

    #[tokio::test]
    async fn shutdown_request_wakes_waiter() {
        let token = ShutdownToken::new();
        let mut waiter = token.clone();

        let task = tokio::spawn(async move {
            waiter.cancelled().await;
        });

        token.request_shutdown();
        task.await.expect("waiter task");
    }

    #[tokio::test]
    async fn request_before_wait_returns_immediately() {
        let token = ShutdownToken::new();
        token.request_shutdown();

        let mut waiter = token.clone();
        tokio::time::timeout(Duration::from_millis(100), waiter.cancelled())
            .await
            .expect("already-cancelled token should resolve");
    }

    #[tokio::test]
    async fn every_clone_observes_shutdown() {
        let token = ShutdownToken::new();
        let mut first = token.clone();
        let mut second = token.clone();

        token.request_shutdown();

        first.cancelled().await;
        second.cancelled().await;
    }

    #[test]
    fn repeated_shutdown_requests_are_idempotent() {
        let token = ShutdownToken::new();
        token.request_shutdown();
        token.request_shutdown();
        assert!(token.is_shutdown_requested());
    }

    #[tokio::test]
    async fn uncancelled_token_remains_pending() {
        let mut token = ShutdownToken::new();

        let result = tokio::time::timeout(Duration::from_millis(25), token.cancelled()).await;

        assert!(result.is_err(), "uncancelled token unexpectedly resolved");
    }
}
