pub mod models;

use sqlx::sqlite::{SqlitePool, SqlitePoolOptions};
use std::path::Path;

use crate::error::AppError;
use models::{App, AppVersion};

pub async fn init_pool(database_url: &str) -> Result<SqlitePool, AppError> {
    // Ensure the parent directory exists
    if let Some(path) = database_url
        .strip_prefix("sqlite:")
        .and_then(|s| s.split('?').next())
    {
        if let Some(parent) = Path::new(path).parent() {
            std::fs::create_dir_all(parent).map_err(|e| {
                AppError::Internal(format!("Failed to create database directory: {}", e))
            })?;
        }
    }

    SqlitePoolOptions::new()
        .max_connections(5)
        .connect(database_url)
        .await
        .map_err(AppError::Database)
}

pub async fn run_migrations(pool: &SqlitePool) -> Result<(), AppError> {
    sqlx::migrate!("./migrations")
        .run(pool)
        .await
        .map_err(|e| AppError::Internal(format!("Migration failed: {}", e)))
}

pub async fn get_all_apps(pool: &SqlitePool) -> Result<Vec<App>, AppError> {
    sqlx::query_as::<_, App>("SELECT * FROM apps ORDER BY name")
        .fetch_all(pool)
        .await
        .map_err(AppError::Database)
}

pub async fn get_app(pool: &SqlitePool, package_name: &str) -> Result<Option<App>, AppError> {
    sqlx::query_as::<_, App>("SELECT * FROM apps WHERE package_name = ?")
        .bind(package_name)
        .fetch_optional(pool)
        .await
        .map_err(AppError::Database)
}

pub async fn get_app_versions(
    pool: &SqlitePool,
    package_name: &str,
) -> Result<Vec<AppVersion>, AppError> {
    sqlx::query_as::<_, AppVersion>(
        "SELECT * FROM app_versions WHERE package_name = ? ORDER BY version_code DESC",
    )
    .bind(package_name)
    .fetch_all(pool)
    .await
    .map_err(AppError::Database)
}

pub async fn get_latest_version(
    pool: &SqlitePool,
    package_name: &str,
) -> Result<Option<AppVersion>, AppError> {
    sqlx::query_as::<_, AppVersion>(
        "SELECT * FROM app_versions WHERE package_name = ? ORDER BY version_code DESC LIMIT 1",
    )
    .bind(package_name)
    .fetch_optional(pool)
    .await
    .map_err(AppError::Database)
}
