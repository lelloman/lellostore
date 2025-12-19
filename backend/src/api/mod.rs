pub mod file_response;
pub mod handlers;
pub mod routes;
pub mod static_files;

use sqlx::SqlitePool;
use std::sync::Arc;

use crate::auth::AuthState;
use crate::config::Config;
use crate::services::{StorageService, UploadService};

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Arc<Config>,
    pub auth: Option<AuthState>,
    pub upload_service: Arc<UploadService>,
    pub storage: Arc<StorageService>,
}
