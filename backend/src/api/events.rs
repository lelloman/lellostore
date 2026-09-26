use serde::Serialize;
use simple_server::lifecycle::Shutdown;
use simple_server::tasks::{WorkGuard, WorkTracker};
use simple_server::web::ws::{Message, WebSocket, WebSocketUpgrade};
use simple_server::web::{
    http::StatusCode,
    response::{IntoResponse, Response},
};
use tokio::sync::broadcast;

use crate::auth::AuthenticatedUser;

#[derive(Clone)]
pub struct CatalogEventHub {
    sender: broadcast::Sender<CatalogEvent>,
    pub(super) shutdown: Shutdown,
    connections: WorkTracker,
}

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub(super) enum CatalogEvent {
    CatalogChanged,
}

impl Default for CatalogEventHub {
    fn default() -> Self {
        Self::new(Shutdown::new())
    }
}

impl CatalogEventHub {
    pub fn new(shutdown: Shutdown) -> Self {
        let (sender, _) = broadcast::channel(64);
        Self {
            sender,
            shutdown,
            connections: WorkTracker::new(),
        }
    }

    pub(super) fn admit_connection(&self) -> Option<WorkGuard> {
        if self.shutdown.is_requested() {
            return None;
        }
        self.connections.try_acquire("catalog-websocket").ok()
    }

    /// Close admission and wait for all accepted upgrades and socket handlers.
    pub async fn drain(&self) -> std::io::Result<()> {
        self.shutdown.requested().await;
        self.connections.close();
        self.connections.wait().await;
        tracing::info!("Catalog event connections drained");
        Ok(())
    }

    pub fn notify_catalog_changed(&self) {
        let _ = self.sender.send(CatalogEvent::CatalogChanged);
    }

    pub(super) fn subscribe(&self) -> broadcast::Receiver<CatalogEvent> {
        self.sender.subscribe()
    }
}

pub async fn catalog_events(
    _user: AuthenticatedUser,
    ws: WebSocketUpgrade,
    simple_server::web::extract::State(state): simple_server::web::extract::State<super::AppState>,
) -> Response {
    let Some(guard) = state.catalog_events.admit_connection() else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    let receiver = state.catalog_events.subscribe();
    let shutdown = state.catalog_events.shutdown.clone();
    // Acquire the guard before scheduling the upgrade so pending handshakes are
    // included in draining. Failed upgrades drop the closure and its guard.
    ws.on_upgrade(move |socket| async move {
        let _guard = guard;
        serve_events(socket, receiver, shutdown).await;
    })
}

async fn serve_events(
    mut socket: WebSocket,
    mut receiver: broadcast::Receiver<CatalogEvent>,
    shutdown: Shutdown,
) {
    tokio::select! {
        biased;
        () = shutdown.requested() => {},
        () = forward_events(&mut socket, &mut receiver) => return,
    }
    // The coordinator also bounds a close-frame write to an unresponsive peer.
    let _ = socket.send(Message::Close(None)).await;
}

async fn forward_events(socket: &mut WebSocket, receiver: &mut broadcast::Receiver<CatalogEvent>) {
    loop {
        tokio::select! {
            event = receiver.recv() => {
                let event = match event {
                    Ok(event) => event,
                    Err(broadcast::error::RecvError::Lagged(_)) => CatalogEvent::CatalogChanged,
                    Err(broadcast::error::RecvError::Closed) => break,
                };
                let Ok(payload) = serde_json::to_string(&event) else { continue };
                if socket.send(Message::Text(payload.into())).await.is_err() {
                    break;
                }
            }
            message = socket.recv() => {
                match message {
                    Some(Ok(Message::Ping(payload))) => {
                        if socket.send(Message::Pong(payload)).await.is_err() { break; }
                    }
                    Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                    _ => {}
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn drain_tracks_pending_upgrades_and_closes_admission() {
        let shutdown = Shutdown::new();
        let hub = CatalogEventHub::new(shutdown.clone());
        let guard = hub.admit_connection().unwrap();
        let draining = hub.clone();
        let task = tokio::spawn(async move { draining.drain().await });
        shutdown.request();
        tokio::task::yield_now().await;
        assert!(hub.admit_connection().is_none());
        assert!(!task.is_finished(), "pending upgrade must keep drain open");
        drop(guard);
        tokio::time::timeout(std::time::Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert!(hub.admit_connection().is_none());
    }

    #[tokio::test]
    async fn interrupted_drain_retains_all_pending_upgrade_reservations() {
        let shutdown = Shutdown::new();
        let hub = CatalogEventHub::new(shutdown.clone());
        let first = hub.admit_connection().unwrap();
        let second = hub.clone().admit_connection().unwrap();
        shutdown.request();
        let budget = std::time::Duration::from_millis(10);
        assert!(tokio::time::timeout(budget, hub.drain()).await.is_err());
        assert!(hub.admit_connection().is_none());
        drop(first);
        assert!(tokio::time::timeout(budget, hub.drain()).await.is_err());
        drop(second);
        tokio::time::timeout(std::time::Duration::from_secs(1), hub.drain())
            .await
            .unwrap()
            .unwrap();
        assert!(hub.clone().admit_connection().is_none());
    }

    #[tokio::test]
    async fn subscribers_receive_catalog_changes() {
        let hub = CatalogEventHub::default();
        let mut receiver = hub.subscribe();
        hub.notify_catalog_changed();

        assert!(matches!(
            receiver.recv().await,
            Ok(CatalogEvent::CatalogChanged)
        ));
    }

    #[test]
    fn catalog_event_has_stable_json_shape() {
        assert_eq!(
            serde_json::to_string(&CatalogEvent::CatalogChanged).unwrap(),
            r#"{"type":"catalog_changed"}"#,
        );
    }
}
