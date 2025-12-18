use axum::routing::get;
use axum::Router;
use sqlx::sqlite::SqlitePoolOptions;
use std::sync::Arc;
use tempfile::TempDir;

use lellostore_backend::api::{routes::create_router, AppState};
use lellostore_backend::config::{Config, OidcConfig};
use lellostore_backend::metrics::{metrics_handler, register_metrics};

pub async fn create_test_app() -> (TempDir, Router) {
    let temp_dir = TempDir::new().expect("Failed to create temp dir");
    let db_path = temp_dir.path().join("test.db");
    let storage_path = temp_dir.path().join("storage");

    std::fs::create_dir_all(&storage_path).unwrap();
    std::fs::create_dir_all(storage_path.join("apks")).unwrap();
    std::fs::create_dir_all(storage_path.join("icons")).unwrap();

    let database_url = format!("sqlite:{}?mode=rwc", db_path.display());

    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect(&database_url)
        .await
        .expect("Failed to create pool");

    // Run migrations
    sqlx::migrate!("./migrations")
        .run(&pool)
        .await
        .expect("Failed to run migrations");

    let config = Config {
        listen_addr: "127.0.0.1:0".parse().unwrap(),
        metrics_addr: "127.0.0.1:0".parse().unwrap(),
        database_url,
        database_path: db_path,
        storage_path: storage_path.clone(),
        oidc: OidcConfig {
            issuer_url: "https://example.com".to_string(),
            audience: "test".to_string(),
            admin_role: "admin".to_string(),
            role_claim_path: "roles".to_string(),
        },
        aapt2_path: None,
        bundletool_path: None,
        java_path: None,
        max_upload_size: 100 * 1024 * 1024, // 100MB for tests
    };

    let state = AppState {
        db: pool,
        config: Arc::new(config),
        auth: None, // No auth for tests by default
    };

    let app = create_router(state);

    (temp_dir, app)
}

pub fn create_test_metrics_app() -> Router {
    // Register metrics (only once per process, may fail if already registered)
    // Ignore any errors from double registration
    let _ = std::panic::catch_unwind(|| {
        register_metrics();
    });

    Router::new().route("/metrics", get(metrics_handler))
}
