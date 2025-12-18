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

/// Insert a new app
pub async fn insert_app(
    pool: &SqlitePool,
    package_name: &str,
    name: &str,
    description: Option<&str>,
    icon_path: Option<&str>,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        INSERT INTO apps (package_name, name, description, icon_path, created_at, updated_at)
        VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))
        "#,
    )
    .bind(package_name)
    .bind(name)
    .bind(description)
    .bind(icon_path)
    .execute(pool)
    .await
    .map_err(AppError::Database)?;

    Ok(())
}

/// Update app metadata
pub async fn update_app(
    pool: &SqlitePool,
    package_name: &str,
    name: Option<&str>,
    description: Option<&str>,
    icon_path: Option<&str>,
) -> Result<(), AppError> {
    // Build dynamic query based on which fields are provided
    let mut updates = Vec::new();

    if name.is_some() {
        updates.push("name = ?");
    }
    if description.is_some() {
        updates.push("description = ?");
    }
    if icon_path.is_some() {
        updates.push("icon_path = ?");
    }

    if updates.is_empty() {
        return Ok(());
    }

    updates.push("updated_at = datetime('now')");

    let query = format!(
        "UPDATE apps SET {} WHERE package_name = ?",
        updates.join(", ")
    );

    let mut q = sqlx::query(&query);

    if let Some(n) = name {
        q = q.bind(n);
    }
    if let Some(d) = description {
        q = q.bind(d);
    }
    if let Some(i) = icon_path {
        q = q.bind(i);
    }
    q = q.bind(package_name);

    q.execute(pool).await.map_err(AppError::Database)?;

    Ok(())
}

/// Insert a new app version
#[allow(clippy::too_many_arguments)]
pub async fn insert_app_version(
    pool: &SqlitePool,
    package_name: &str,
    version_code: i64,
    version_name: &str,
    apk_path: &str,
    size: i64,
    sha256: &str,
    min_sdk: i64,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        INSERT INTO app_versions (package_name, version_code, version_name, apk_path, size, sha256, min_sdk, uploaded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
        "#,
    )
    .bind(package_name)
    .bind(version_code)
    .bind(version_name)
    .bind(apk_path)
    .bind(size)
    .bind(sha256)
    .bind(min_sdk)
    .execute(pool)
    .await
    .map_err(AppError::Database)?;

    Ok(())
}

/// Delete an app version
pub async fn delete_app_version(
    pool: &SqlitePool,
    package_name: &str,
    version_code: i64,
) -> Result<(), AppError> {
    sqlx::query("DELETE FROM app_versions WHERE package_name = ? AND version_code = ?")
        .bind(package_name)
        .bind(version_code)
        .execute(pool)
        .await
        .map_err(AppError::Database)?;

    Ok(())
}

/// Delete an app and all its versions
pub async fn delete_app(pool: &SqlitePool, package_name: &str) -> Result<(), AppError> {
    // Versions will be deleted by CASCADE
    sqlx::query("DELETE FROM apps WHERE package_name = ?")
        .bind(package_name)
        .execute(pool)
        .await
        .map_err(AppError::Database)?;

    Ok(())
}

/// Check if a version exists
pub async fn version_exists(
    pool: &SqlitePool,
    package_name: &str,
    version_code: i64,
) -> Result<bool, AppError> {
    let count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM app_versions WHERE package_name = ? AND version_code = ?",
    )
    .bind(package_name)
    .bind(version_code)
    .fetch_one(pool)
    .await
    .map_err(AppError::Database)?;

    Ok(count > 0)
}

/// Count versions for an app
pub async fn count_versions(pool: &SqlitePool, package_name: &str) -> Result<i64, AppError> {
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM app_versions WHERE package_name = ?")
        .bind(package_name)
        .fetch_one(pool)
        .await
        .map_err(AppError::Database)?;

    Ok(count)
}
