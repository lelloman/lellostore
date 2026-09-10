use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::response::Response;
use serde::Serialize;
use tokio::sync::broadcast;

use crate::auth::AuthenticatedUser;

#[derive(Clone)]
pub struct CatalogEventHub {
    sender: broadcast::Sender<CatalogEvent>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum CatalogEvent {
    CatalogChanged,
}

impl Default for CatalogEventHub {
    fn default() -> Self {
        let (sender, _) = broadcast::channel(64);
        Self { sender }
    }
}

impl CatalogEventHub {
    pub fn notify_catalog_changed(&self) {
        let _ = self.sender.send(CatalogEvent::CatalogChanged);
    }

    fn subscribe(&self) -> broadcast::Receiver<CatalogEvent> {
        self.sender.subscribe()
    }
}

pub async fn catalog_events(
    _user: AuthenticatedUser,
    ws: WebSocketUpgrade,
    axum::extract::State(state): axum::extract::State<super::AppState>,
) -> Response {
    let receiver = state.catalog_events.subscribe();
    ws.on_upgrade(move |socket| serve_events(socket, receiver))
}

async fn serve_events(mut socket: WebSocket, mut receiver: broadcast::Receiver<CatalogEvent>) {
    loop {
        tokio::select! {
            event = receiver.recv() => {
                let event = match event {
                    Ok(event) => event,
                    Err(broadcast::error::RecvError::Lagged(_)) => CatalogEvent::CatalogChanged,
                    Err(broadcast::error::RecvError::Closed) => break,
                };
                let Ok(payload) = serde_json::to_string(&event) else { continue };
                if socket.send(Message::Text(payload)).await.is_err() {
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
