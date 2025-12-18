use axum::{
    extract::Request,
    http::StatusCode,
    middleware::Next,
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use lazy_static::lazy_static;
use prometheus::{HistogramOpts, HistogramVec, IntGauge, IntGaugeVec, Opts, Registry, TextEncoder};
use sqlx::SqlitePool;
use std::net::SocketAddr;
use std::path::Path;
use std::path::PathBuf;
use std::time::{Duration, Instant};

lazy_static! {
    pub static ref REGISTRY: Registry = Registry::new();

    // HTTP Metrics
    pub static ref HTTP_REQUESTS_TOTAL: prometheus::CounterVec = prometheus::CounterVec::new(
        Opts::new("lellostore_http_requests_total", "Total HTTP requests"),
        &["method", "path", "status"]
    ).unwrap();

    pub static ref HTTP_REQUEST_DURATION: HistogramVec = HistogramVec::new(
        HistogramOpts::new(
            "lellostore_http_request_duration_seconds",
            "HTTP request duration in seconds"
        ).buckets(vec![0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0]),
        &["method", "path"]
    ).unwrap();

    // Business Metrics
    pub static ref APPS_TOTAL: IntGauge = IntGauge::new(
        "lellostore_apps_total",
        "Total number of apps in catalog"
    ).unwrap();

    pub static ref APP_VERSIONS_TOTAL: IntGauge = IntGauge::new(
        "lellostore_app_versions_total",
        "Total number of app versions"
    ).unwrap();

    // Homelab Storage Metrics (standard format)
    pub static ref STORAGE_BYTES: IntGaugeVec = IntGaugeVec::new(
        Opts::new("homelab_storage_bytes", "Storage usage in bytes"),
        &["service", "path"]
    ).unwrap();
}

pub fn register_metrics() {
    REGISTRY
        .register(Box::new(HTTP_REQUESTS_TOTAL.clone()))
        .unwrap();
    REGISTRY
        .register(Box::new(HTTP_REQUEST_DURATION.clone()))
        .unwrap();
    REGISTRY.register(Box::new(APPS_TOTAL.clone())).unwrap();
    REGISTRY
        .register(Box::new(APP_VERSIONS_TOTAL.clone()))
        .unwrap();
    REGISTRY.register(Box::new(STORAGE_BYTES.clone())).unwrap();
}

pub fn encode_metrics() -> String {
    let encoder = TextEncoder::new();
    let metric_families = REGISTRY.gather();
    encoder
        .encode_to_string(&metric_families)
        .unwrap_or_default()
}

pub fn record_http_request(method: &str, path: &str, status: u16, duration: Duration) {
    HTTP_REQUESTS_TOTAL
        .with_label_values(&[method, path, &status.to_string()])
        .inc();
    HTTP_REQUEST_DURATION
        .with_label_values(&[method, path])
        .observe(duration.as_secs_f64());
}

pub fn update_storage_metrics(storage_path: &Path, db_path: &Path) {
    let mut total: u64 = 0;

    // APKs storage
    if let Ok(apks) = calculate_dir_size(&storage_path.join("apks")) {
        STORAGE_BYTES
            .with_label_values(&["lellostore", "/apks"])
            .set(apks as i64);
        total += apks;
    }

    // Icons storage
    if let Ok(icons) = calculate_dir_size(&storage_path.join("icons")) {
        STORAGE_BYTES
            .with_label_values(&["lellostore", "/icons"])
            .set(icons as i64);
        total += icons;
    }

    // Database storage
    if let Ok(metadata) = std::fs::metadata(db_path) {
        let db_size = metadata.len();
        STORAGE_BYTES
            .with_label_values(&["lellostore", "/db"])
            .set(db_size as i64);
        total += db_size;
    }

    // Total storage
    STORAGE_BYTES
        .with_label_values(&["lellostore", "/"])
        .set(total as i64);
}

pub fn update_catalog_metrics(apps_count: i64, versions_count: i64) {
    APPS_TOTAL.set(apps_count);
    APP_VERSIONS_TOTAL.set(versions_count);
}

fn calculate_dir_size(path: &Path) -> std::io::Result<u64> {
    let mut total = 0;
    if path.is_dir() {
        for entry in std::fs::read_dir(path)? {
            let entry = entry?;
            let metadata = entry.metadata()?;
            if metadata.is_file() {
                total += metadata.len();
            } else if metadata.is_dir() {
                total += calculate_dir_size(&entry.path())?;
            }
        }
    }
    Ok(total)
}

pub async fn metrics_handler() -> impl IntoResponse {
    (
        StatusCode::OK,
        [("content-type", "text/plain; version=0.0.4")],
        encode_metrics(),
    )
}

pub async fn track_metrics(request: Request, next: Next) -> Response {
    let method = request.method().to_string();
    let path = request.uri().path().to_string();
    let start = Instant::now();

    let response = next.run(request).await;

    let duration = start.elapsed();
    let status = response.status().as_u16();

    let normalized_path = normalize_path(&path);
    record_http_request(&method, &normalized_path, status, duration);

    response
}

fn normalize_path(path: &str) -> String {
    let segments: Vec<&str> = path.split('/').collect();
    let mut result = Vec::new();
    let mut i = 0;

    while i < segments.len() {
        let segment = segments[i];

        // After "apps" segment, the next non-empty segment is a package name
        if segment == "apps" && i + 1 < segments.len() {
            result.push("apps");
            let next = segments[i + 1];
            if !next.is_empty() && next != "admin" {
                result.push(":package_name");
                i += 2;
                continue;
            }
        }

        // After "versions" segment, the next segment is a version code (numeric)
        if segment == "versions" && i + 1 < segments.len() {
            result.push("versions");
            let next = segments[i + 1];
            if !next.is_empty() && next.chars().all(|c| c.is_ascii_digit()) {
                result.push(":version_code");
                i += 2;
                continue;
            }
        }

        result.push(segment);
        i += 1;
    }

    result.join("/")
}

pub fn spawn_metrics_updater(db: SqlitePool, storage_path: PathBuf, db_path: PathBuf) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(60));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

        loop {
            interval.tick().await;

            update_storage_metrics(&storage_path, &db_path);

            match sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM apps")
                .fetch_one(&db)
                .await
            {
                Ok(apps_count) => {
                    match sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM app_versions")
                        .fetch_one(&db)
                        .await
                    {
                        Ok(versions_count) => {
                            update_catalog_metrics(apps_count, versions_count);
                        }
                        Err(e) => {
                            tracing::warn!("Failed to count app versions: {}", e);
                        }
                    }
                }
                Err(e) => {
                    tracing::warn!("Failed to count apps: {}", e);
                }
            }
        }
    });
}

pub async fn start_metrics_server(
    addr: SocketAddr,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let app = Router::new().route("/metrics", get(metrics_handler));

    let listener = tokio::net::TcpListener::bind(addr).await?;
    tracing::info!("Metrics server listening on {}", addr);
    axum::serve(listener, app).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_normalize_path() {
        assert_eq!(normalize_path("/health"), "/health");
        assert_eq!(normalize_path("/api/apps"), "/api/apps");
        assert_eq!(
            normalize_path("/api/apps/com.example.app"),
            "/api/apps/:package_name"
        );
        assert_eq!(
            normalize_path("/api/apps/com.example.app/icon"),
            "/api/apps/:package_name/icon"
        );
        assert_eq!(
            normalize_path("/api/apps/com.example.app/versions/10"),
            "/api/apps/:package_name/versions/:version_code"
        );
        assert_eq!(
            normalize_path("/api/apps/com.example.app/versions/10/apk"),
            "/api/apps/:package_name/versions/:version_code/apk"
        );
        assert_eq!(normalize_path("/api/admin/apps"), "/api/admin/apps");
    }
}
