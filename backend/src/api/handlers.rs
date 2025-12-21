use axum::{
    extract::{Multipart, Path, State},
    http::{header::RANGE, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::auth::AdminUser;
use crate::db::{self, models::AppVersion};
use crate::error::AppError;

use super::file_response::serve_file;
use super::AppState;

// ============================================================================
// API Response Types (snake_case format)
// ============================================================================

/// Version info in list response (subset of full version)
#[derive(Debug, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct LatestVersionInfo {
    pub version_code: i64,
    pub version_name: String,
    pub size: i64,
}

/// App info for list endpoint
#[derive(Debug, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct AppListItem {
    pub package_name: String,
    pub name: String,
    pub description: Option<String>,
    pub icon_url: String,
    pub latest_version: Option<LatestVersionInfo>,
}

/// Version info with URLs for detail endpoint
#[derive(Debug, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct AppVersionInfo {
    pub version_code: i64,
    pub version_name: String,
    pub apk_url: String,
    pub size: i64,
    pub sha256: String,
    pub min_sdk: i64,
    pub uploaded_at: String,
}

/// App detail response
#[derive(Debug, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct AppDetailResponse {
    pub package_name: String,
    pub name: String,
    pub description: Option<String>,
    pub icon_url: String,
    pub versions: Vec<AppVersionInfo>,
}

/// Apps list response
#[derive(Debug, Serialize)]
pub struct AppsListResponse {
    pub apps: Vec<AppListItem>,
}

// ============================================================================
// Helper Functions
// ============================================================================

fn make_icon_url(package_name: &str) -> String {
    format!("/api/apps/{}/icon", package_name)
}

fn make_apk_url(package_name: &str, version_code: i64) -> String {
    format!("/api/apps/{}/versions/{}/apk", package_name, version_code)
}

fn to_version_info(v: &AppVersion) -> AppVersionInfo {
    AppVersionInfo {
        version_code: v.version_code,
        version_name: v.version_name.clone(),
        apk_url: make_apk_url(&v.package_name, v.version_code),
        size: v.size,
        sha256: v.sha256.clone(),
        min_sdk: v.min_sdk,
        uploaded_at: v.uploaded_at.clone(),
    }
}

// ============================================================================
// Public Handlers
// ============================================================================

pub async fn health_check() -> Json<Value> {
    Json(json!({ "status": "healthy" }))
}

pub async fn list_apps(State(state): State<AppState>) -> Result<Json<AppsListResponse>, AppError> {
    let apps = db::get_all_apps(&state.db).await?;

    let mut items = Vec::new();
    for app in apps {
        // Get latest version for this app
        let versions = db::get_app_versions(&state.db, &app.package_name).await?;
        let latest = versions.into_iter().max_by_key(|v| v.version_code);

        items.push(AppListItem {
            package_name: app.package_name.clone(),
            name: app.name,
            description: app.description,
            icon_url: make_icon_url(&app.package_name),
            latest_version: latest.map(|v| LatestVersionInfo {
                version_code: v.version_code,
                version_name: v.version_name,
                size: v.size,
            }),
        });
    }

    Ok(Json(AppsListResponse { apps: items }))
}

pub async fn get_app(
    State(state): State<AppState>,
    Path(package_name): Path<String>,
) -> Result<Json<AppDetailResponse>, AppError> {
    let app = db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{}' not found", package_name)))?;

    let versions = db::get_app_versions(&state.db, &package_name).await?;
    let version_infos: Vec<AppVersionInfo> = versions.iter().map(to_version_info).collect();

    Ok(Json(AppDetailResponse {
        package_name: app.package_name.clone(),
        name: app.name,
        description: app.description,
        icon_url: make_icon_url(&app.package_name),
        versions: version_infos,
    }))
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

// ============================================================================
// Admin Handlers
// ============================================================================

/// Response for successful upload
#[derive(Debug, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct UploadResponse {
    pub package_name: String,
    pub name: String,
    pub description: Option<String>,
    pub icon_url: String,
    pub version: AppVersionInfo,
}

/// Upload a new app or version (multipart form)
pub async fn upload_app(
    _admin: AdminUser,
    State(state): State<AppState>,
    mut multipart: Multipart,
) -> Result<Response, AppError> {
    let mut file_data: Option<(String, Vec<u8>)> = None;
    let mut override_name: Option<String> = None;
    let mut override_description: Option<String> = None;

    // Process multipart fields
    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::BadRequest(format!("Failed to read multipart field: {}", e)))?
    {
        let field_name = field.name().map(|s| s.to_string());

        match field_name.as_deref() {
            Some("file") => {
                let filename = field
                    .file_name()
                    .map(|s| s.to_string())
                    .unwrap_or_else(|| "upload.apk".to_string());

                // Stream chunks to vec, checking size
                let mut data = Vec::new();
                let max_size = state.config.max_upload_size;

                let bytes = field
                    .bytes()
                    .await
                    .map_err(|e| AppError::BadRequest(format!("Failed to read file: {}", e)))?;

                if bytes.len() as u64 > max_size {
                    return Err(AppError::PayloadTooLarge);
                }

                data.extend_from_slice(&bytes);
                file_data = Some((filename, data));
            }
            Some("name") => {
                let text = field
                    .text()
                    .await
                    .map_err(|e| AppError::BadRequest(format!("Failed to read name: {}", e)))?;
                if !text.is_empty() {
                    override_name = Some(text);
                }
            }
            Some("description") => {
                let text = field.text().await.map_err(|e| {
                    AppError::BadRequest(format!("Failed to read description: {}", e))
                })?;
                if !text.is_empty() {
                    override_description = Some(text);
                }
            }
            _ => {
                // Ignore unknown fields
            }
        }
    }

    // Ensure file was provided
    let (filename, data) = file_data.ok_or_else(|| {
        AppError::BadRequest(
            "No file provided. Expected 'file' field in multipart form.".to_string(),
        )
    })?;

    // Process the upload using UploadService
    let result = state
        .upload_service
        .process_upload(&filename, data, override_name, override_description)
        .await
        .map_err(|e| match e {
            crate::services::UploadError::FileTooLarge { .. } => AppError::PayloadTooLarge,
            crate::services::UploadError::InvalidFileType => AppError::InvalidFileType,
            crate::services::UploadError::VersionExists {
                package_name,
                version_code,
            } => AppError::Conflict(format!(
                "Version {} already exists for {}",
                version_code, package_name
            )),
            crate::services::UploadError::AabNotSupported(msg) => AppError::BadRequest(msg),
            other => AppError::Internal(other.to_string()),
        })?;

    // Get the uploaded version details
    let versions = db::get_app_versions(&state.db, &result.package_name).await?;
    let version = versions
        .iter()
        .find(|v| v.version_code == result.version_code)
        .ok_or_else(|| AppError::Internal("Uploaded version not found".to_string()))?;

    // Get app details for description
    let app = db::get_app(&state.db, &result.package_name).await?;

    let response = UploadResponse {
        package_name: result.package_name.clone(),
        name: result.app_name,
        description: app.and_then(|a| a.description),
        icon_url: make_icon_url(&result.package_name),
        version: to_version_info(version),
    };

    Ok((StatusCode::CREATED, Json(response)).into_response())
}

/// Request body for updating app metadata
#[derive(Debug, Deserialize)]
pub struct UpdateAppRequest {
    pub name: Option<String>,
    pub description: Option<String>,
}

/// Update app metadata
pub async fn update_app(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(package_name): Path<String>,
    Json(request): Json<UpdateAppRequest>,
) -> Result<Json<AppDetailResponse>, AppError> {
    // Verify app exists
    db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{}' not found", package_name)))?;

    // Update if there's anything to update
    if request.name.is_some() || request.description.is_some() {
        db::update_app(
            &state.db,
            &package_name,
            request.name.as_deref(),
            request.description.as_deref(),
            None, // Don't change icon
        )
        .await?;
    }

    // Fetch and return updated app (reuse get_app logic)
    let app = db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::Internal("App disappeared after update".to_string()))?;

    let versions = db::get_app_versions(&state.db, &package_name).await?;
    let version_infos: Vec<AppVersionInfo> = versions.iter().map(to_version_info).collect();

    Ok(Json(AppDetailResponse {
        package_name: app.package_name.clone(),
        name: app.name,
        description: app.description,
        icon_url: make_icon_url(&app.package_name),
        versions: version_infos,
    }))
}

/// Delete an app and all its versions
pub async fn delete_app(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(package_name): Path<String>,
) -> Result<StatusCode, AppError> {
    // Verify app exists
    let _app = db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{}' not found", package_name)))?;

    // Delete all storage files
    state
        .storage
        .delete_package(&package_name)
        .map_err(|e| AppError::Internal(format!("Failed to delete files: {}", e)))?;

    // Delete from database (cascades to versions due to FK)
    db::delete_app(&state.db, &package_name).await?;

    Ok(StatusCode::NO_CONTENT)
}

/// Delete a specific version of an app
pub async fn delete_version(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path((package_name, version_code)): Path<(String, i64)>,
) -> Result<StatusCode, AppError> {
    // Verify version exists
    let versions = db::get_app_versions(&state.db, &package_name).await?;
    let _version = versions
        .iter()
        .find(|v| v.version_code == version_code)
        .ok_or_else(|| {
            AppError::NotFound(format!(
                "Version {} not found for '{}'",
                version_code, package_name
            ))
        })?;

    // Check if this is the last version
    let is_last_version = versions.len() == 1;

    // Delete APK file
    state
        .storage
        .delete_apk(&package_name, version_code)
        .map_err(|e| AppError::Internal(format!("Failed to delete APK: {}", e)))?;

    // Delete from database
    db::delete_app_version(&state.db, &package_name, version_code).await?;

    // If this was the last version, also delete the app
    if is_last_version {
        state
            .storage
            .delete_icon(&package_name)
            .map_err(|e| AppError::Internal(format!("Failed to delete icon: {}", e)))?;
        db::delete_app(&state.db, &package_name).await?;
    }

    Ok(StatusCode::NO_CONTENT)
}
