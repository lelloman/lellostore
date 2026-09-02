use axum::{
    extract::{multipart::Field, Multipart, Path, State},
    http::{header::RANGE, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::io::AsyncWriteExt;

use crate::auth::AdminUser;
use crate::db::access::AppAccessLevel;
use crate::db::admin::{GroupDetail, KnownUser, UserAccessDetail};
use crate::db::{self, models::AppVersion};
use crate::error::AppError;

use super::file_response::serve_file;
use super::AppState;

const MAX_METADATA_FIELD_SIZE: u64 = 64 * 1024;
const MAX_ICON_UPLOAD_SIZE: u64 = 5 * 1024 * 1024;

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
    pub min_sdk: i64,
    pub uploaded_at: String,
    pub is_beta: bool,
}

/// App info for list endpoint
#[derive(Debug, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct AppListItem {
    pub package_name: String,
    pub name: String,
    pub description: Option<String>,
    pub icon_url: String,
    pub total_size: i64,
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
    pub is_beta: bool,
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
        is_beta: v.is_beta,
    }
}

async fn read_bounded_field(mut field: Field<'_>, max_size: u64) -> Result<Vec<u8>, AppError> {
    let mut data = Vec::new();
    while let Some(chunk) = field
        .chunk()
        .await
        .map_err(|error| AppError::BadRequest(format!("Failed to read field: {error}")))?
    {
        let new_size = (data.len() as u64)
            .checked_add(chunk.len() as u64)
            .ok_or(AppError::PayloadTooLarge)?;
        if new_size > max_size {
            return Err(AppError::PayloadTooLarge);
        }
        data.extend_from_slice(&chunk);
    }
    Ok(data)
}

async fn read_metadata_text(field: Field<'_>) -> Result<String, AppError> {
    let data = read_bounded_field(field, MAX_METADATA_FIELD_SIZE).await?;
    String::from_utf8(data)
        .map_err(|error| AppError::BadRequest(format!("Metadata must be UTF-8: {error}")))
}

// ============================================================================
// Public Handlers
// ============================================================================

pub async fn health_check() -> Json<Value> {
    Json(json!({ "status": "healthy" }))
}

pub async fn auth_unavailable() -> (StatusCode, Json<Value>) {
    (
        StatusCode::SERVICE_UNAVAILABLE,
        Json(json!({
            "error": "Service Unavailable",
            "message": "Authentication service is unavailable"
        })),
    )
}

pub async fn list_apps(State(state): State<AppState>) -> Result<Json<AppsListResponse>, AppError> {
    let apps = db::get_all_apps(&state.db).await?;

    let mut items = Vec::new();
    for app in apps {
        // Get latest version for this app
        let versions = db::get_app_versions(&state.db, &app.package_name).await?;
        let total_size = versions.iter().map(|version| version.size).sum();
        let latest = versions.into_iter().max_by_key(|v| v.version_code);

        items.push(AppListItem {
            package_name: app.package_name.clone(),
            name: app.name,
            description: app.description,
            icon_url: make_icon_url(&app.package_name),
            total_size,
            latest_version: latest.map(|v| LatestVersionInfo {
                version_code: v.version_code,
                version_name: v.version_name,
                size: v.size,
                min_sdk: v.min_sdk,
                uploaded_at: v.uploaded_at,
                is_beta: v.is_beta,
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
    let upload_temp = state
        .storage
        .create_temp_dir()
        .map_err(|error| AppError::Internal(format!("Failed to prepare upload: {error}")))?;
    let mut uploaded_file: Option<(String, std::path::PathBuf)> = None;
    let mut override_name: Option<String> = None;
    let mut override_description: Option<String> = None;
    let mut is_beta = false;

    // Process multipart fields
    while let Some(mut field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::BadRequest(format!("Failed to read multipart field: {}", e)))?
    {
        let field_name = field.name().map(|s| s.to_string());

        match field_name.as_deref() {
            Some("file") => {
                if uploaded_file.is_some() {
                    return Err(AppError::BadRequest(
                        "Only one upload file is allowed".to_string(),
                    ));
                }
                let filename = field
                    .file_name()
                    .map(|s| s.to_string())
                    .unwrap_or_else(|| "upload.apk".to_string());

                let max_size = state.config.max_upload_size;
                let upload_path = upload_temp.path().join("upload.bin");
                let mut output = tokio::fs::File::create(&upload_path)
                    .await
                    .map_err(|error| {
                        AppError::Internal(format!("Failed to create upload file: {error}"))
                    })?;
                let mut bytes_written = 0_u64;

                while let Some(chunk) = field.chunk().await.map_err(|error| {
                    AppError::BadRequest(format!("Failed to read file: {error}"))
                })? {
                    bytes_written = bytes_written
                        .checked_add(chunk.len() as u64)
                        .ok_or(AppError::PayloadTooLarge)?;
                    if bytes_written > max_size {
                        return Err(AppError::PayloadTooLarge);
                    }
                    output.write_all(&chunk).await.map_err(|error| {
                        AppError::Internal(format!("Failed to store upload: {error}"))
                    })?;
                }
                output.flush().await.map_err(|error| {
                    AppError::Internal(format!("Failed to finish upload: {error}"))
                })?;
                uploaded_file = Some((filename, upload_path));
            }
            Some("name") => {
                let text = read_metadata_text(field).await?;
                if !text.is_empty() {
                    override_name = Some(text);
                }
            }
            Some("description") => {
                let text = read_metadata_text(field).await?;
                if !text.is_empty() {
                    override_description = Some(text);
                }
            }
            Some("is_beta") => {
                let text = read_metadata_text(field).await?;
                is_beta = match text.as_str() {
                    "true" => true,
                    "false" => false,
                    _ => {
                        return Err(AppError::BadRequest(
                            "is_beta must be 'true' or 'false'".to_string(),
                        ))
                    }
                };
            }
            _ => {
                // Ignore unknown fields
            }
        }
    }

    // Ensure file was provided
    let (filename, upload_path) = uploaded_file.ok_or_else(|| {
        AppError::BadRequest(
            "No file provided. Expected 'file' field in multipart form.".to_string(),
        )
    })?;

    // Process the upload using UploadService
    let result = state
        .upload_service
        .process_upload_file(
            &filename,
            &upload_path,
            override_name,
            override_description,
            is_beta,
        )
        .await
        .map_err(|e| match e {
            crate::services::UploadError::FileTooLarge { .. } => AppError::PayloadTooLarge,
            crate::services::UploadError::AabError(crate::services::AabError::OutputTooLarge {
                ..
            }) => AppError::PayloadTooLarge,
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

    // Delete from database (cascades to versions due to FK)
    db::delete_app(&state.db, &package_name).await?;

    // The database is authoritative. Clean files only after its atomic delete
    // succeeds so a database failure cannot leave broken download records.
    if let Err(error) = state.storage.delete_package(&package_name) {
        tracing::warn!(
            package_name,
            %error,
            "App was deleted from the catalog but its files could not be cleaned up"
        );
    }

    Ok(StatusCode::NO_CONTENT)
}

/// Upload or replace app icon
pub async fn upload_icon(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(package_name): Path<String>,
    mut multipart: Multipart,
) -> Result<Json<Value>, AppError> {
    // Verify app exists
    db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{}' not found", package_name)))?;

    // Extract file from multipart
    let mut file_data: Option<Vec<u8>> = None;

    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::BadRequest(format!("Failed to read multipart field: {}", e)))?
    {
        let field_name = field.name().map(|s| s.to_string());

        if field_name.as_deref() == Some("file") || field_name.as_deref() == Some("icon") {
            file_data = Some(read_bounded_field(field, MAX_ICON_UPLOAD_SIZE).await?);
        }
    }

    let data = file_data.ok_or_else(|| {
        AppError::BadRequest("No file provided. Expected 'file' or 'icon' field.".to_string())
    })?;

    // Process the icon (validate, resize, convert to PNG)
    let processed = process_uploaded_icon(&data)?;

    // Save the icon
    let icon_path = state
        .storage
        .save_icon(&package_name, &processed)
        .map_err(|e| AppError::Internal(format!("Failed to save icon: {}", e)))?;

    // Update database
    db::update_app(&state.db, &package_name, None, None, Some(&icon_path)).await?;

    Ok(Json(json!({
        "message": "Icon uploaded successfully",
        "icon_url": make_icon_url(&package_name)
    })))
}

/// Process uploaded icon: validate square dimensions, resize to 192x192, convert to PNG
fn process_uploaded_icon(data: &[u8]) -> Result<Vec<u8>, AppError> {
    use image::imageops::FilterType;
    use image::ImageFormat;
    use std::io::Cursor;

    // Load the image
    let img = image::load_from_memory(data)
        .map_err(|e| AppError::BadRequest(format!("Invalid image file: {}", e)))?;

    // Check if square
    let (width, height) = (img.width(), img.height());
    if width != height {
        return Err(AppError::BadRequest(format!(
            "Icon must be square. Got {}x{} pixels.",
            width, height
        )));
    }

    // Resize to 192x192 (standard launcher icon size)
    let resized = img.resize_exact(192, 192, FilterType::Lanczos3);

    // Convert to PNG
    let mut output = Vec::new();
    resized
        .write_to(&mut Cursor::new(&mut output), ImageFormat::Png)
        .map_err(|e| AppError::Internal(format!("Failed to encode PNG: {}", e)))?;

    Ok(output)
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

    // Commit the catalog change first. Deleting the app directly when this is
    // its final version lets the foreign-key cascade make it one DB operation.
    if is_last_version {
        db::delete_app(&state.db, &package_name).await?;
    } else {
        db::delete_app_version(&state.db, &package_name, version_code).await?;
    }

    if let Err(error) = state.storage.delete_apk(&package_name, version_code) {
        tracing::warn!(
            package_name,
            version_code,
            %error,
            "Version was deleted from the catalog but its APK could not be cleaned up"
        );
    }

    if is_last_version {
        if let Err(error) = state.storage.delete_icon(&package_name) {
            tracing::warn!(
                package_name,
                %error,
                "App was deleted from the catalog but its icon could not be cleaned up"
            );
        }
    }

    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize)]
pub struct AccessLevelRequest {
    pub access_level: AppAccessLevel,
}

#[derive(Debug, Deserialize)]
pub struct GroupNameRequest {
    pub name: String,
}

#[derive(Debug, Deserialize)]
pub struct ReleaseChannelRequest {
    pub is_beta: bool,
}

#[derive(Debug, Serialize)]
pub struct UsersResponse {
    pub users: Vec<KnownUser>,
}

#[derive(Debug, Serialize)]
pub struct GroupsResponse {
    pub groups: Vec<GroupDetail>,
}

pub async fn list_admin_users(
    _admin: AdminUser,
    State(state): State<AppState>,
) -> Result<Json<UsersResponse>, AppError> {
    Ok(Json(UsersResponse {
        users: db::admin::list_users(&state.db).await?,
    }))
}

pub async fn get_admin_user_access(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(subject): Path<String>,
) -> Result<Json<UserAccessDetail>, AppError> {
    Ok(Json(db::admin::get_user_access(&state.db, &subject).await?))
}

pub async fn set_admin_direct_grant(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((subject, package_name)): Path<(String, String)>,
    Json(request): Json<AccessLevelRequest>,
) -> Result<StatusCode, AppError> {
    db::admin::set_direct_grant(
        &state.db,
        &admin.0.subject,
        &subject,
        &package_name,
        request.access_level,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn remove_admin_direct_grant(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((subject, package_name)): Path<(String, String)>,
) -> Result<StatusCode, AppError> {
    db::admin::remove_direct_grant(&state.db, &admin.0.subject, &subject, &package_name).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn list_admin_groups(
    _admin: AdminUser,
    State(state): State<AppState>,
) -> Result<Json<GroupsResponse>, AppError> {
    Ok(Json(GroupsResponse {
        groups: db::admin::list_groups(&state.db).await?,
    }))
}

pub async fn create_admin_group(
    admin: AdminUser,
    State(state): State<AppState>,
    Json(request): Json<GroupNameRequest>,
) -> Result<(StatusCode, Json<db::access::AppGroup>), AppError> {
    let group = db::admin::create_group(&state.db, &admin.0.subject, &request.name).await?;
    Ok((StatusCode::CREATED, Json(group)))
}

pub async fn rename_admin_group(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(group_id): Path<i64>,
    Json(request): Json<GroupNameRequest>,
) -> Result<StatusCode, AppError> {
    db::admin::rename_group(&state.db, &admin.0.subject, group_id, &request.name).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn delete_admin_group(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(group_id): Path<i64>,
) -> Result<StatusCode, AppError> {
    db::admin::delete_group(&state.db, &admin.0.subject, group_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn set_admin_group_grant(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((group_id, package_name)): Path<(i64, String)>,
    Json(request): Json<AccessLevelRequest>,
) -> Result<StatusCode, AppError> {
    db::admin::set_group_grant(
        &state.db,
        &admin.0.subject,
        group_id,
        &package_name,
        request.access_level,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn remove_admin_group_grant(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((group_id, package_name)): Path<(i64, String)>,
) -> Result<StatusCode, AppError> {
    db::admin::remove_group_grant(&state.db, &admin.0.subject, group_id, &package_name).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn add_admin_group_member(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((group_id, subject)): Path<(i64, String)>,
) -> Result<StatusCode, AppError> {
    db::admin::set_membership(&state.db, &admin.0.subject, group_id, &subject, true).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn remove_admin_group_member(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((group_id, subject)): Path<(i64, String)>,
) -> Result<StatusCode, AppError> {
    db::admin::set_membership(&state.db, &admin.0.subject, group_id, &subject, false).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn set_admin_release_channel(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((package_name, version_code)): Path<(String, i64)>,
    Json(request): Json<ReleaseChannelRequest>,
) -> Result<StatusCode, AppError> {
    db::admin::set_release_channel(
        &state.db,
        &admin.0.subject,
        &package_name,
        version_code,
        request.is_beta,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}
