use simple_server::lifecycle::{BoxError, Lifecycle, ShutdownOptions, Signals};
use std::{sync::Arc, time::Duration};
mod logging;

use lellostore_backend::api::AppState;
use lellostore_backend::auth;
use lellostore_backend::config::Config;
use lellostore_backend::services::{AabConverter, ApkParser, StorageService, UploadService};
use lellostore_backend::{api, db, metrics};

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        tracing::error!(%error, "LelloStore stopped with an error");
        // A timed-out blocking task must not hold runtime teardown open forever.
        std::process::exit(1);
    }
}

async fn run() -> Result<(), BoxError> {
    // Load .env file
    dotenvy::dotenv().ok();

    // Initialize tracing
    logging::init()?;

    // Load configuration
    let config = Config::from_env()?;
    let signals = Signals::install()?;
    let mut lifecycle = Lifecycle::new(ShutdownOptions {
        grace_period: Duration::from_secs(config.shutdown_grace_secs),
    });
    let catalog_events = api::events::CatalogEventHub::new(lifecycle.shutdown());

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

    // Initialize services
    let storage = Arc::new(StorageService::new(config.storage_path.clone()));

    // APK parser - use configured path or auto-detect
    let aapt2_path = config
        .aapt2_path
        .clone()
        .or_else(|| ApkParser::detect_aapt2().ok());
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

    match upload_service.repair_outdated_icons().await {
        Ok(0) => {}
        Ok(count) => tracing::info!("Repaired icons for {} existing app(s)", count),
        Err(error) => tracing::warn!("Failed to scan for outdated app icons: {}", error),
    }

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
                tracing::info!(
                    "Authentication initialized with issuer: {}",
                    config.oidc.issuer_url
                );
                Some(auth.with_user_registry(db.clone()))
            }
            Err(e) => {
                tracing::warn!(
                    "Failed to initialize authentication: {}. API routes will remain unavailable.",
                    e
                );
                None
            }
        }
    } else {
        tracing::warn!("OIDC not configured (using default issuer). API routes are unavailable.");
        None
    };

    // Build application state
    let state = AppState {
        db: db.clone(),
        config: Arc::new(config.clone()),
        auth: auth_state,
        upload_service: upload_service.clone(),
        storage,
        catalog_events: catalog_events.clone(),
    };

    // Create router
    let app = api::routes::create_router(state);

    // Bind both ports before any serving or background work begins. A metrics
    // bind failure is a startup error, rather than a detached task failure.
    let listener = simple_server::http::bind(config.listen_addr).await?;
    let metrics_listener = simple_server::http::bind(config.metrics_addr).await?;
    tracing::info!("Server listening on {}", listener.local_addr()?);
    tracing::info!(
        "Metrics server listening on {}",
        metrics_listener.local_addr()?
    );

    lifecycle.service(
        "upload-validation",
        lellostore_backend::services::upload_jobs::run(
            db.clone(),
            upload_service,
            lifecycle.shutdown(),
        ),
    )?;
    lifecycle.service(
        "http",
        simple_server::http::serve(listener, app, lifecycle.shutdown()),
    )?;
    lifecycle.service(
        "metrics-http",
        simple_server::http::serve(metrics_listener, metrics::router(), lifecycle.shutdown()),
    )?;
    lifecycle.service(
        "metrics-updater",
        metrics::run_metrics_updater(
            db.clone(),
            config.storage_path.clone(),
            config.database_path.clone(),
            lifecycle.shutdown(),
        ),
    )?;
    lifecycle.service(
        "catalog-events",
        async move { catalog_events.drain().await },
    )?;

    let report = lifecycle
        .run(signals.wait(), async move {
            // Every registered DB user has drained before this future is polled.
            db.close().await;
            tracing::info!("Database pool closed");
            Ok::<_, std::io::Error>(())
        })
        .await?;
    tracing::info!(reason = ?report.reason, "LelloStore shutdown complete");
    Ok(())
}
