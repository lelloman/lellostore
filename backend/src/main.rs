use std::sync::Arc;
use tracing_subscriber::EnvFilter;

use lellostore_backend::api::AppState;
use lellostore_backend::auth;
use lellostore_backend::config::Config;
use lellostore_backend::services::{AabConverter, ApkParser, StorageService, UploadService};
use lellostore_backend::{api, db, metrics};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Load .env file
    dotenvy::dotenv().ok();

    // Initialize tracing
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .init();

    // Load configuration
    let config = Config::from_env()?;
    tracing::info!("Starting lellostore backend on {}", config.listen_addr);

    // Initialize metrics
    metrics::register_metrics();
    tracing::info!("Metrics registered");

    // Initialize database
    let db = db::init_pool(&config.database_url).await?;
    db::run_migrations(&db).await?;
    tracing::info!("Database initialized");

    // Create storage directories
    std::fs::create_dir_all(&config.storage_path)?;
    std::fs::create_dir_all(config.storage_path.join("apks"))?;
    std::fs::create_dir_all(config.storage_path.join("icons"))?;

    // Start background metrics updater
    metrics::spawn_metrics_updater(
        db.clone(),
        config.storage_path.clone(),
        config.database_path.clone(),
    );

    // Initialize services
    let storage = Arc::new(StorageService::new(config.storage_path.clone()));

    // APK parser - use configured path or auto-detect
    let aapt2_path = config.aapt2_path.clone().or_else(|| {
        ApkParser::detect_aapt2().ok()
    });
    let apk_parser = match aapt2_path {
        Some(path) => {
            tracing::info!("Using aapt2 at {:?}", path);
            ApkParser::new(path)
        }
        None => {
            tracing::warn!("aapt2 not found - APK metadata extraction will use fallback values");
            ApkParser::new(std::path::PathBuf::from("aapt2")) // Will fail gracefully at runtime
        }
    };

    // AAB converter is optional - requires both bundletool and java paths
    let aab_converter = match (&config.bundletool_path, &config.java_path) {
        (Some(bundletool), Some(java)) => {
            tracing::info!("AAB conversion enabled (bundletool: {:?})", bundletool);
            Some(AabConverter::new(bundletool.clone(), java.clone()))
        }
        _ => {
            tracing::info!("AAB conversion disabled (bundletool or java not configured)");
            None
        }
    };

    let upload_service = Arc::new(UploadService::new(
        (*storage).clone(),
        apk_parser,
        aab_converter,
        db.clone(),
        config.max_upload_size,
    ));

    tracing::info!("Services initialized");

    // Initialize authentication (optional - skip if issuer URL is placeholder)
    let auth_state = if config.oidc.issuer_url != "https://example.com" {
        match auth::init_auth(
            &config.oidc.issuer_url,
            &config.oidc.audience,
            &config.oidc.role_claim_path,
            &config.oidc.admin_role,
        )
        .await
        {
            Ok(auth) => {
                tracing::info!("Authentication initialized with issuer: {}", config.oidc.issuer_url);
                Some(auth)
            }
            Err(e) => {
                tracing::warn!("Failed to initialize authentication: {}. Protected routes will be disabled.", e);
                None
            }
        }
    } else {
        tracing::info!("OIDC not configured (using default issuer). Protected routes disabled.");
        None
    };

    // Build application state
    let state = AppState {
        db,
        config: Arc::new(config.clone()),
        auth: auth_state,
        upload_service,
        storage,
    };

    // Create router
    let app = api::routes::create_router(state);

    // Start metrics server (separate port for Prometheus scraping)
    let metrics_addr = config.metrics_addr;
    tokio::spawn(async move {
        if let Err(e) = metrics::start_metrics_server(metrics_addr).await {
            tracing::error!("Metrics server failed: {}", e);
        }
    });

    // Start main server
    let listener = tokio::net::TcpListener::bind(&config.listen_addr).await?;
    tracing::info!("Server listening on {}", config.listen_addr);
    axum::serve(listener, app).await?;

    Ok(())
}
