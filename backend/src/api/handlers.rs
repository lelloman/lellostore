use axum::{
    extract::{Path, State},
    http::{header::RANGE, HeaderMap},
    response::Response,
    Json,
};
use serde_json::{json, Value};

use crate::db;
use crate::error::AppError;

use super::file_response::serve_file;
use super::AppState;

pub async fn health_check() -> Json<Value> {
    Json(json!({ "status": "healthy" }))
}

pub async fn list_apps(State(state): State<AppState>) -> Result<Json<Value>, AppError> {
    let apps = db::get_all_apps(&state.db).await?;
    Ok(Json(json!({ "apps": apps })))
}

pub async fn get_app(
    State(state): State<AppState>,
    Path(package_name): Path<String>,
) -> Result<Json<Value>, AppError> {
    let app = db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{}' not found", package_name)))?;

    let versions = db::get_app_versions(&state.db, &package_name).await?;

    Ok(Json(json!({
        "app": app,
        "versions": versions
    })))
}

/// Serve app icon
pub async fn get_icon(
    State(state): State<AppState>,
    Path(package_name): Path<String>,
) -> Result<Response, AppError> {
    // Get app from database
    let app = db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{}' not found", package_name)))?;

    // Check if icon exists
    let icon_path = app
        .icon_path
        .ok_or_else(|| AppError::NotFound("Icon not found".to_string()))?;

    // Build full path
    let full_path = state.config.storage_path.join(&icon_path);

    serve_file(full_path, "image/png", None, None).await
}

/// Serve APK file with Range header support
pub async fn download_apk(
    State(state): State<AppState>,
    Path((package_name, version_code)): Path<(String, i64)>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    // Get version from database
    let versions = db::get_app_versions(&state.db, &package_name).await?;
    let version = versions
        .into_iter()
        .find(|v| v.version_code == version_code)
        .ok_or_else(|| {
            AppError::NotFound(format!(
                "Version {} not found for '{}'",
                version_code, package_name
            ))
        })?;

    // Build full path
    let full_path = state.config.storage_path.join(&version.apk_path);

    // Build filename for Content-Disposition
    let filename = format!("{}-{}.apk", package_name, version.version_name);

    // Get Range header if present
    let range_header = headers.get(RANGE).and_then(|h| h.to_str().ok());

    serve_file(
        full_path,
        "application/vnd.android.package-archive",
        Some(filename),
        range_header,
    )
    .await
}
