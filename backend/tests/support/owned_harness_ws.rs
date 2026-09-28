//! Small assertion helpers for LelloStore's owned WebSocket test fixtures.
use serde::{de::DeserializeOwned, Serialize};
use simple_server::{
    testing::{TestWebSocket, WebSocketOutcome},
    web::{http::StatusCode, ws::Message},
};

#[allow(dead_code)] // Each integration crate exercises a different protocol subset.
pub trait OwnedUpgradeExt {
    async fn into_websocket(self) -> TestWebSocket;
    fn assert_status(self, status: StatusCode);
    fn assert_header(&self, name: &str, value: &str);
}
impl OwnedUpgradeExt for WebSocketOutcome {
    async fn into_websocket(self) -> TestWebSocket {
        match self {
            WebSocketOutcome::Connected(socket) => socket,
            WebSocketOutcome::Rejected(response) => {
                panic!("WebSocket rejected: {}", response.status_code())
            }
        }
    }
    fn assert_status(self, status: StatusCode) {
        match self {
            WebSocketOutcome::Rejected(response) => response.assert_status(status),
            WebSocketOutcome::Connected(_) => panic!("expected WebSocket rejection: {status}"),
        }
    }
    fn assert_header(&self, name: &str, value: &str) {
        let headers = match self {
            WebSocketOutcome::Connected(socket) => socket.headers(),
            WebSocketOutcome::Rejected(response) => response.headers(),
        };
        assert_eq!(headers[name], value);
    }
}

#[allow(dead_code)] // Shared by catalog and Paravoid test crates.
pub trait OwnedSocketExt {
    async fn send_message(&mut self, message: Message);
    async fn receive_message(&mut self) -> Message;
    async fn send_json<T: Serialize + Sync>(&mut self, value: &T);
    async fn receive_json<T: DeserializeOwned>(&mut self) -> T;
    async fn receive_text(&mut self) -> String;
}
impl OwnedSocketExt for TestWebSocket {
    async fn send_message(&mut self, message: Message) {
        self.send(message).await.unwrap();
    }
    async fn receive_message(&mut self) -> Message {
        self.receive()
            .await
            .unwrap()
            .expect("WebSocket closed before the next frame")
    }
    async fn send_json<T: Serialize + Sync>(&mut self, value: &T) {
        self.send(Message::text(serde_json::to_string(value).unwrap()))
            .await
            .unwrap();
    }
    async fn receive_json<T: DeserializeOwned>(&mut self) -> T {
        match self.receive_message().await {
            Message::Text(value) => serde_json::from_str(value.as_str()).unwrap(),
            unexpected => panic!("expected WebSocket JSON text, received {unexpected:?}"),
        }
    }
    async fn receive_text(&mut self) -> String {
        match self.receive().await.unwrap() {
            Some(Message::Text(value)) => value.as_str().to_owned(),
            Some(Message::Close(_)) | None => String::new(),
            unexpected => panic!("expected WebSocket text/close, received {unexpected:?}"),
        }
    }
}
