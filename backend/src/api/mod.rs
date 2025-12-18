pub mod handlers;
pub mod routes;

use sqlx::SqlitePool;
use std::sync::Arc;

use crate::auth::AuthState;
use crate::config::Config;

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Arc<Config>,
    pub auth: Option<AuthState>,
}
