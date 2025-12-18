# Epic 1: Backend Foundation - Implementation Plan

## Overview

**Goal**: Establish the Rust/Axum project with database connectivity, basic API structure, configuration management, and Prometheus metrics for homelab monitoring integration.

**Deliverable**: A running Axum server that connects to SQLite, with health check endpoint, `/metrics` endpoint for Prometheus, and database migrations applied.

---

## Tasks

### 1. Project Initialization

#### 1.1 Create Cargo Project
- [x] Create `backend/` directory
- [x] Initialize Cargo project with `cargo init`
- [x] Configure `Cargo.toml` with:
  - Package metadata (name: `lellostore-backend`, version: `0.1.0`)
  - Rust edition 2021
  - LTO and optimization settings for release builds

#### 1.2 Add Dependencies
```toml
[dependencies]
# Web framework
axum = "0.7"
tokio = { version = "1", features = ["full"] }
tower = "0.4"
tower-http = { version = "0.5", features = ["cors", "trace", "fs"] }

# Database
sqlx = { version = "0.7", features = ["runtime-tokio", "sqlite", "migrate"] }

# Serialization
serde = { version = "1", features = ["derive"] }
serde_json = "1"

# Configuration
dotenvy = "0.15"

# Logging
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }

# Metrics (Prometheus)
prometheus = "0.13"
lazy_static = "1.4"

# Utilities
thiserror = "1"
chrono = { version = "0.4", features = ["serde"] }

[dev-dependencies]
tokio-test = "0.4"
axum-test = "14"
tempfile = "3"
```

#### 1.3 Create Project Structure
```
backend/
├── src/
│   ├── main.rs              # Entry point, server startup
│   ├── config.rs            # Configuration management
│   ├── error.rs             # Custom error types
│   ├── metrics.rs           # Prometheus metrics definitions and handlers
│   ├── db/
│   │   ├── mod.rs           # Database module exports
│   │   └── models.rs        # Database models (App, AppVersion)
│   ├── api/
│   │   ├── mod.rs           # API module exports
│   │   ├── routes.rs        # Route definitions
│   │   └── handlers.rs      # Request handlers
│   └── services/
│       └── mod.rs           # Business logic services
├── migrations/
│   └── 20251218_001_init.sql
├── .env.example
├── .gitignore
└── Cargo.toml
```

---

### 2. Configuration Management

#### 2.1 Environment Variables
Create `.env.example`:
```env
# Server
LISTEN_ADDR=127.0.0.1:8080
METRICS_ADDR=127.0.0.1:9091
RUST_LOG=info,tower_http=debug

# Database
DATABASE_URL=sqlite:data/lellostore.db?mode=rwc

# Storage
STORAGE_PATH=data/storage

# OIDC (placeholder for Epic 3)
OIDC_ISSUER_URL=https://example.com
OIDC_AUDIENCE=lellostore
OIDC_ADMIN_ROLE=admin
```

#### 2.2 Config Struct (`src/config.rs`)
```rust
use std::net::SocketAddr;
use std::path::PathBuf;

pub struct Config {
    pub listen_addr: SocketAddr,
    pub metrics_addr: SocketAddr,
    pub database_url: String,
    pub database_path: PathBuf,  // Extracted from database_url for metrics
    pub storage_path: PathBuf,
    pub oidc: OidcConfig,
}

pub struct OidcConfig {
    pub issuer_url: String,
    pub audience: String,
    pub admin_role: String,
}

impl Config {
    pub fn from_env() -> Result<Self, ConfigError> {
        // ... load from env ...

        // Extract database path from URL (e.g., "sqlite:data/lellostore.db?mode=rwc" -> "data/lellostore.db")
        let database_path = database_url
            .strip_prefix("sqlite:")
            .and_then(|s| s.split('?').next())
            .map(PathBuf::from)
            .ok_or_else(|| ConfigError::InvalidDatabaseUrl)?;

        // ...
    }
}
```

Implementation requirements:
- [ ] Load from environment variables using `std::env`
- [ ] Use `dotenvy` to load `.env` file in development
- [ ] Validate required fields, provide sensible defaults where appropriate
- [ ] Return meaningful errors for missing/invalid configuration

---

### 3. Database Setup

#### 3.1 SQLite Migration
Create `migrations/20251218_001_init.sql`:
```sql
-- Apps table
CREATE TABLE IF NOT EXISTS apps (
    package_name TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    icon_path TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- App versions table
CREATE TABLE IF NOT EXISTS app_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL REFERENCES apps(package_name) ON DELETE CASCADE,
    version_code INTEGER NOT NULL,
    version_name TEXT NOT NULL,
    apk_path TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    min_sdk INTEGER NOT NULL,
    uploaded_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(package_name, version_code)
);

-- Index for faster queries
CREATE INDEX IF NOT EXISTS idx_app_versions_package ON app_versions(package_name);
```

#### 3.2 Database Models (`src/db/models.rs`)
```rust
#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
pub struct App {
    pub package_name: String,
    pub name: String,
    pub description: Option<String>,
    pub icon_path: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
pub struct AppVersion {
    pub id: i64,
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    pub apk_path: String,
    pub size: i64,
    pub sha256: String,
    pub min_sdk: i64,
    pub uploaded_at: String,
}
```

#### 3.3 Database Operations (`src/db/mod.rs`)
Implement the following functions:
- [ ] `init_pool(database_url: &str) -> Result<SqlitePool>` - Create connection pool
- [ ] `run_migrations(pool: &SqlitePool) -> Result<()>` - Run SQLx migrations
- [ ] `get_all_apps(pool: &SqlitePool) -> Result<Vec<App>>`
- [ ] `get_app(pool: &SqlitePool, package_name: &str) -> Result<Option<App>>`
- [ ] `get_app_versions(pool: &SqlitePool, package_name: &str) -> Result<Vec<AppVersion>>`
- [ ] `get_latest_version(pool: &SqlitePool, package_name: &str) -> Result<Option<AppVersion>>`

---

### 4. Prometheus Metrics

#### 4.1 Metrics Registry (`src/metrics.rs`)

Define metrics using `lazy_static` and the `prometheus` crate:

```rust
use lazy_static::lazy_static;
use prometheus::{
    Counter, CounterVec, Gauge, GaugeVec, Histogram, HistogramOpts, HistogramVec,
    IntGauge, IntGaugeVec, Registry, TextEncoder, Opts,
};

lazy_static! {
    pub static ref REGISTRY: Registry = Registry::new();

    // HTTP Metrics
    pub static ref HTTP_REQUESTS_TOTAL: CounterVec = CounterVec::new(
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

/// Register all metrics with the registry
pub fn register_metrics() {
    REGISTRY.register(Box::new(HTTP_REQUESTS_TOTAL.clone())).unwrap();
    REGISTRY.register(Box::new(HTTP_REQUEST_DURATION.clone())).unwrap();
    REGISTRY.register(Box::new(APPS_TOTAL.clone())).unwrap();
    REGISTRY.register(Box::new(APP_VERSIONS_TOTAL.clone())).unwrap();
    REGISTRY.register(Box::new(STORAGE_BYTES.clone())).unwrap();
}

/// Encode metrics for Prometheus scraping
pub fn encode_metrics() -> String {
    let encoder = TextEncoder::new();
    let metric_families = REGISTRY.gather();
    encoder.encode_to_string(&metric_families).unwrap_or_default()
}
```

#### 4.2 Metrics Recording Functions

```rust
use std::path::Path;
use std::time::Duration;

/// Record an HTTP request
pub fn record_http_request(method: &str, path: &str, status: u16, duration: Duration) {
    HTTP_REQUESTS_TOTAL
        .with_label_values(&[method, path, &status.to_string()])
        .inc();
    HTTP_REQUEST_DURATION
        .with_label_values(&[method, path])
        .observe(duration.as_secs_f64());
}

/// Update storage metrics (call periodically)
pub fn update_storage_metrics(storage_path: &Path, db_path: &Path) {
    // Total storage (sum of all components)
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

/// Update catalog metrics from database counts
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
```

#### 4.3 Metrics HTTP Handler

```rust
use axum::{response::IntoResponse, http::StatusCode};

pub async fn metrics_handler() -> impl IntoResponse {
    (
        StatusCode::OK,
        [("content-type", "text/plain; version=0.0.4")],
        encode_metrics()
    )
}
```

#### 4.4 Metrics Middleware (Request Tracking)

Create middleware to automatically track HTTP requests:

```rust
use axum::{
    middleware::Next,
    extract::Request,
    response::Response,
};
use std::time::Instant;

pub async fn track_metrics(request: Request, next: Next) -> Response {
    let method = request.method().to_string();
    let path = request.uri().path().to_string();
    let start = Instant::now();

    let response = next.run(request).await;

    let duration = start.elapsed();
    let status = response.status().as_u16();

    // Normalize path to avoid high cardinality (e.g., /api/apps/com.example -> /api/apps/:package)
    let normalized_path = normalize_path(&path);
    record_http_request(&method, &normalized_path, status, duration);

    response
}

fn normalize_path(path: &str) -> String {
    // Replace dynamic segments with placeholders to avoid high cardinality
    // /api/apps/com.example.app -> /api/apps/:package_name
    // /api/apps/com.example.app/icon -> /api/apps/:package_name/icon
    // /api/apps/com.example.app/versions/10/apk -> /api/apps/:package_name/versions/:version_code/apk

    // Use regex-like pattern matching for cleaner implementation
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_normalize_path() {
        assert_eq!(normalize_path("/health"), "/health");
        assert_eq!(normalize_path("/api/apps"), "/api/apps");
        assert_eq!(normalize_path("/api/apps/com.example.app"), "/api/apps/:package_name");
        assert_eq!(normalize_path("/api/apps/com.example.app/icon"), "/api/apps/:package_name/icon");
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
```

#### 4.5 Background Metrics Update Task

```rust
use std::path::PathBuf;
use std::time::Duration;
use sqlx::SqlitePool;

/// Spawn a background task to update storage/catalog metrics periodically
pub fn spawn_metrics_updater(db: SqlitePool, storage_path: PathBuf, db_path: PathBuf) {
    tokio::spawn(async move {
        // Use interval that ticks immediately on first call
        let mut interval = tokio::time::interval(Duration::from_secs(60));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

        loop {
            interval.tick().await;

            // Update storage metrics
            update_storage_metrics(&storage_path, &db_path);

            // Update catalog metrics from DB
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
```

**Note**: The first `interval.tick()` returns immediately, so metrics are populated right after startup.

#### 4.6 Separate Metrics Server

Run metrics on a separate port (standard practice for Prometheus scraping):

```rust
use axum::{Router, routing::get};
use std::net::SocketAddr;

/// Start the metrics server on a separate port
pub async fn start_metrics_server(addr: SocketAddr) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let app = Router::new().route("/metrics", get(metrics_handler));

    let listener = tokio::net::TcpListener::bind(addr).await?;
    tracing::info!("Metrics server listening on {}", addr);
    axum::serve(listener, app).await?;
    Ok(())
}
```

---

### 5. API Setup

#### 5.1 Router Configuration (`src/api/routes.rs`)
```rust
use axum::middleware;
use crate::metrics::track_metrics;

pub fn create_router(state: AppState) -> Router {
    Router::new()
        // Health check
        .route("/health", get(handlers::health_check))
        // API routes (to be expanded in Epic 4)
        .nest("/api", api_routes())
        // Middleware
        .layer(middleware::from_fn(track_metrics))  // Metrics tracking
        .layer(TraceLayer::new_for_http())
        .layer(cors_layer())
        .with_state(state)
}

fn api_routes() -> Router<AppState> {
    Router::new()
        .route("/apps", get(handlers::list_apps))
        .route("/apps/:package_name", get(handlers::get_app))
}

fn cors_layer() -> CorsLayer {
    CorsLayer::new()
        .allow_origin(Any)  // Restrict in production
        .allow_methods([Method::GET, Method::POST, Method::PUT, Method::DELETE])
        .allow_headers(Any)
}
```

#### 5.2 Application State
```rust
#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Arc<Config>,
}
```

#### 5.3 Request Handlers (`src/api/handlers.rs`)
- [ ] `health_check()` - Returns `200 OK` with `{"status": "healthy"}`
- [ ] `list_apps()` - Returns list of all apps (placeholder, expand in Epic 4)
- [ ] `get_app()` - Returns single app details (placeholder, expand in Epic 4)

#### 5.4 Error Handling (`src/error.rs`)
```rust
#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("Database error: {0}")]
    Database(#[from] sqlx::Error),

    #[error("Not found: {0}")]
    NotFound(String),

    #[error("Configuration error: {0}")]
    Config(String),

    #[error("Internal error: {0}")]
    Internal(String),
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AppError::NotFound(_) => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::Database(_) => (StatusCode::INTERNAL_SERVER_ERROR, "Database error".to_string()),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, "Internal error".to_string()),
        };

        let body = Json(json!({
            "error": status.as_str(),
            "message": message
        }));

        (status, body).into_response()
    }
}
```

---

### 6. Server Entry Point

#### 6.1 Main Function (`src/main.rs`)
```rust
use std::sync::Arc;
use tracing_subscriber::EnvFilter;

mod api;
mod config;
mod db;
mod error;
mod metrics;

use config::Config;
use api::AppState;

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

    // Build application state
    let state = AppState {
        db,
        config: Arc::new(config.clone()),
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
```

---

### 7. Development Setup

#### 7.1 Git Configuration
Create `backend/.gitignore`:
```gitignore
/target/
/.env
/data/
*.db
*.db-journal
*.db-wal
*.db-shm
```

#### 7.2 SQLx Offline Mode (Optional)
For compile-time query checking without database:
```bash
cargo sqlx prepare --database-url sqlite:data/lellostore.db
```

---

### 8. Testing

#### 8.1 Unit Tests

The following unit tests are required:

**Path Normalization Tests** (`src/metrics.rs`):
- Tests are defined inline in Section 4.4 within the `#[cfg(test)]` module
- Covers: health, apps list, single app, app icon, app version, version APK, admin paths

**Configuration Tests** (`src/config.rs`):
```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_db_path_simple() {
        let url = "sqlite:data/lellostore.db";
        let path = extract_db_path(url).unwrap();
        assert_eq!(path, PathBuf::from("data/lellostore.db"));
    }

    #[test]
    fn test_extract_db_path_with_params() {
        let url = "sqlite:data/lellostore.db?mode=rwc";
        let path = extract_db_path(url).unwrap();
        assert_eq!(path, PathBuf::from("data/lellostore.db"));
    }

    #[test]
    fn test_extract_db_path_absolute() {
        let url = "sqlite:/var/data/lellostore.db?mode=rwc";
        let path = extract_db_path(url).unwrap();
        assert_eq!(path, PathBuf::from("/var/data/lellostore.db"));
    }
}
```

#### 8.2 Integration Tests

Create `tests/integration.rs`:

```rust
use axum::http::StatusCode;
use axum_test::TestServer;

#[tokio::test]
async fn test_health_endpoint() {
    let app = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/health").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let body: serde_json::Value = response.json();
    assert_eq!(body["status"], "healthy");
}

#[tokio::test]
async fn test_metrics_endpoint() {
    let app = create_test_metrics_app();
    let server = TestServer::new(app).unwrap();

    let response = server.get("/metrics").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let body = response.text();
    assert!(body.contains("lellostore_http_requests_total"));
    assert!(body.contains("lellostore_apps_total"));
    assert!(body.contains("homelab_storage_bytes"));
}

#[tokio::test]
async fn test_apps_list_empty() {
    let app = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/api/apps").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let body: serde_json::Value = response.json();
    assert_eq!(body["apps"], serde_json::json!([]));
}

#[tokio::test]
async fn test_app_not_found() {
    let app = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/api/apps/com.nonexistent").await;
    assert_eq!(response.status_code(), StatusCode::NOT_FOUND);
}
```

---

## Verification Checklist

### Build Verification
- [ ] `cargo build` completes without errors
- [ ] `cargo clippy` passes with no warnings
- [ ] `cargo fmt --check` passes
- [ ] `cargo test` passes (unit tests + integration tests)

### Runtime Verification
- [ ] Server starts with `cargo run`
- [ ] Server listens on configured address
- [ ] Health check endpoint responds:
  ```bash
  curl http://localhost:8080/health
  # Expected: {"status":"healthy"}
  ```
- [ ] Database file created at configured path
- [ ] Database has correct schema (apps and app_versions tables)
- [ ] Storage directories created

### Metrics Verification
- [ ] Metrics server starts on port 9091
- [ ] Metrics endpoint responds:
  ```bash
  curl http://localhost:9091/metrics
  # Expected: Prometheus text format with lellostore_* and homelab_storage_bytes metrics
  ```
- [ ] HTTP request metrics recorded after API calls
- [ ] Storage metrics updated (check `homelab_storage_bytes{service="lellostore"}`)
- [ ] Catalog metrics present (`lellostore_apps_total`, `lellostore_app_versions_total`)

### API Verification
- [ ] `GET /health` returns `200 OK`
- [ ] `GET /api/apps` returns `200 OK` with empty array
- [ ] `GET /api/apps/nonexistent` returns `404 Not Found`
- [ ] Request logging shows in console
- [ ] CORS headers present in responses

---

## Acceptance Criteria

1. **Server runs**: `cargo run` starts the server without errors
2. **Health endpoint works**: `GET /health` returns 200 with JSON status
3. **Database initialized**: SQLite database file exists with correct schema
4. **Storage ready**: `data/storage/apks/` and `data/storage/icons/` directories exist
5. **Logging works**: HTTP requests are logged to console
6. **CORS enabled**: Cross-origin requests are allowed (for frontend development)
7. **Configuration flexible**: Server can be configured via environment variables
8. **Metrics exposed**: `GET /metrics` on port 9091 returns Prometheus format
9. **HTTP metrics tracked**: Request count and duration recorded per endpoint
10. **Homelab compatible**: `homelab_storage_bytes{service="lellostore"}` metrics present

---

## Notes

- Authentication is NOT implemented in this epic (placeholder config only)
- Admin endpoints are NOT implemented in this epic
- File upload is NOT implemented in this epic
- This epic focuses solely on establishing the foundation for subsequent epics

---

## Homelab Integration

After the backend is deployed, add lellostore to the homelab Prometheus configuration:

### Prometheus Scrape Config

Add to `/home/lelloman/homelab/monitoring/prometheus.yml`:

```yaml
- job_name: 'lellostore'
  static_configs:
    - targets: ['lellostore:9091']
  metrics_path: '/metrics'
  scrape_interval: 10s
```

### Expected Metrics Output

```
# HELP lellostore_http_requests_total Total HTTP requests
# TYPE lellostore_http_requests_total counter
lellostore_http_requests_total{method="GET",path="/health",status="200"} 42
lellostore_http_requests_total{method="GET",path="/api/apps",status="200"} 15

# HELP lellostore_http_request_duration_seconds HTTP request duration in seconds
# TYPE lellostore_http_request_duration_seconds histogram
lellostore_http_request_duration_seconds_bucket{method="GET",path="/health",le="0.001"} 40
lellostore_http_request_duration_seconds_bucket{method="GET",path="/health",le="0.005"} 42
...

# HELP lellostore_apps_total Total number of apps in catalog
# TYPE lellostore_apps_total gauge
lellostore_apps_total 5

# HELP lellostore_app_versions_total Total number of app versions
# TYPE lellostore_app_versions_total gauge
lellostore_app_versions_total 12

# HELP homelab_storage_bytes Storage usage in bytes
# TYPE homelab_storage_bytes gauge
homelab_storage_bytes{service="lellostore",path="/"} 158334976
homelab_storage_bytes{service="lellostore",path="/apks"} 146800640
homelab_storage_bytes{service="lellostore",path="/icons"} 10485760
homelab_storage_bytes{service="lellostore",path="/db"} 1048576
```

### Useful Grafana Queries

```promql
# Request rate by endpoint
rate(lellostore_http_requests_total[5m])

# p95 latency
histogram_quantile(0.95, rate(lellostore_http_request_duration_seconds_bucket[5m]))

# Error rate
rate(lellostore_http_requests_total{status=~"5.."}[5m])

# Storage usage
homelab_storage_bytes{service="lellostore"}

# App catalog size
lellostore_apps_total
```

---

## Estimated Complexity

| Task | Complexity | Notes |
|------|------------|-------|
| Project setup | Low | Standard Cargo initialization |
| Configuration | Low | Straightforward env var loading |
| Database setup | Medium | SQLx migrations, connection pooling |
| Prometheus metrics | Medium | Registry, middleware, background task |
| API skeleton | Low | Basic Axum routing |
| Error handling | Medium | Custom error types, proper responses |
| Testing | Medium | Unit tests + integration tests |
| Integration | Low | Wiring everything together |

---

## Progress Tracking

| Task | Status | Notes |
|------|--------|-------|
| 1.1 Create Cargo project | Done | |
| 1.2 Add dependencies | Done | |
| 1.3 Create project structure | Done | |
| 2.1 Environment variables | Done | |
| 2.2 Config struct | Done | |
| 3.1 SQLite migration | Done | |
| 3.2 Database models | Done | |
| 3.3 Database operations | Done | |
| 4.1 Metrics registry | Done | |
| 4.2 Metrics recording functions | Done | |
| 4.3 Metrics HTTP handler | Done | |
| 4.4 Metrics middleware | Done | |
| 4.5 Background metrics updater | Done | |
| 4.6 Metrics server | Done | |
| 5.1 Router configuration | Done | |
| 5.2 Application state | Done | |
| 5.3 Request handlers | Done | |
| 5.4 Error handling | Done | |
| 6.1 Main function | Done | |
| 7.1 Git configuration | Done | |
| 8.1 Unit tests | Done | |
| 8.2 Integration tests | Done | |
| Verification | Done | |
