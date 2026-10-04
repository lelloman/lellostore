pub mod api;
pub mod model;
pub mod store;
pub mod stream;

use serde_json::json;
use sha2::{Digest, Sha256};
use simple_server::web::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use std::{collections::HashMap, sync::Arc};
use tokio::sync::{Mutex, Notify};

pub type Result<T> = std::result::Result<T, Error>;
#[derive(Debug)]
pub struct Error(pub StatusCode, pub String);
impl Error {
    pub fn bad(message: &str) -> Self {
        Self(StatusCode::BAD_REQUEST, message.into())
    }
    pub fn denied() -> Self {
        Self(StatusCode::FORBIDDEN, "Notification access denied".into())
    }
    pub fn unauthorized() -> Self {
        Self(
            StatusCode::UNAUTHORIZED,
            "Notification credential required".into(),
        )
    }
    pub fn conflict(message: &str) -> Self {
        Self(StatusCode::CONFLICT, message.into())
    }
    pub fn full() -> Self {
        Self(
            StatusCode::TOO_MANY_REQUESTS,
            "Notification capacity exhausted; retry later".into(),
        )
    }
}
impl From<sqlx::Error> for Error {
    fn from(e: sqlx::Error) -> Self {
        tracing::error!(error = %e, "Notification storage failure");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Notification storage unavailable".into(),
        )
    }
}
impl From<serde_json::Error> for Error {
    fn from(_: serde_json::Error) -> Self {
        Self::bad("Invalid notification JSON")
    }
}
impl IntoResponse for Error {
    fn into_response(self) -> Response {
        crate::metrics::NOTIFICATION_REJECTIONS
            .with_label_values(&[self.0.as_str()])
            .inc();
        (
            self.0,
            [("cache-control", "no-store"), ("retry-after", "30")],
            Json(json!({"error":"notification_error","message":self.1})),
        )
            .into_response()
    }
}
pub fn now() -> i64 {
    chrono::Utc::now().timestamp()
}
pub fn hash(s: &str) -> String {
    hex::encode(Sha256::digest(s.as_bytes()))
}
pub fn secret() -> String {
    use ring::rand::SecureRandom;
    let mut bytes = [0u8; 32];
    ring::rand::SystemRandom::new()
        .fill(&mut bytes)
        .expect("OS random source unavailable");
    hex::encode(bytes)
}
pub fn validate_secret(s: &str) -> Result<()> {
    if s.len() != 64 || !s.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Err(Error::bad("256-bit hexadecimal credential required"));
    }
    Ok(())
}

#[derive(Clone)]
pub struct Connection {
    pub epoch: String,
    pub wake: Arc<Notify>,
    pub expires: i64,
}
pub struct Broker {
    pub db: sqlx::SqlitePool,
    pub connections: Mutex<HashMap<String, Connection>>,
    pub catalog_revision: std::sync::atomic::AtomicU64,
}
impl Broker {
    pub fn new(db: sqlx::SqlitePool) -> Arc<Self> {
        Arc::new(Self {
            db,
            connections: Mutex::new(HashMap::new()),
            catalog_revision: std::sync::atomic::AtomicU64::new(1),
        })
    }
    pub async fn wake(&self) {
        for c in self.connections.lock().await.values() {
            c.wake.notify_one();
        }
    }
    pub async fn online(&self, device: &str) -> bool {
        self.connections.lock().await.contains_key(device)
    }
}
