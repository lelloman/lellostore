pub mod acquisitions;
pub mod events;
pub mod file_response;
pub mod handlers;
pub mod paravoid;
pub mod publications;
pub mod routes;
pub mod static_files;
pub mod uploads;

use sqlx::SqlitePool;
use std::sync::Arc;

use crate::auth::AuthState;
use crate::config::Config;
use crate::services::{StorageService, UploadService};

#[derive(Clone)]
pub struct AppState {
    pub personalizer: Option<Arc<crate::services::personalization::Personalizer>>,
    pub paravoid_signing: Option<Arc<crate::paravoid::signing::OnlineSigning>>,
    pub db: SqlitePool,
    pub config: Arc<Config>,
    pub auth: Option<AuthState>,
    pub upload_service: Arc<UploadService>,
    pub storage: Arc<StorageService>,
    pub catalog_events: events::CatalogEventHub,
}

pub mod vpks;

pub mod delivery;

pub mod transitions;

pub mod archives;
