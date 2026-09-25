use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use simple_server::web::{
    extract::{multipart::Field, Multipart, Path, Query, State},
    http::{header::RANGE, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use tokio::io::AsyncWriteExt;

use crate::auth::{AdminUser, AuthenticatedUser};
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
    pub publication_state: String,
    pub distribution_mode: String,
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
    pub access_level: AppAccessLevel,
    pub distribution_mode: String,
    pub publication_revision: i64,
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
    pub publication_state: String,
    pub distribution_mode: String,
    pub release_notes: String,
    pub published_at: Option<String>,
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
    pub access_level: AppAccessLevel,
    pub distribution_mode: String,
    pub publication_revision: i64,
}

/// Apps list response
#[derive(Debug, Serialize)]
pub struct AppsListResponse {
    pub apps: Vec<AppListItem>,
}

// ============================================================================
// Helper Functions
// ============================================================================

async fn make_icon_url(state: &AppState, package_name: &str, icon_path: Option<&str>) -> String {
    let url = format!("/api/apps/{}/icon", package_name);
    let Some(icon_path) = icon_path else {
        return url;
    };
    // Clients cache icons by URL. Fingerprint the actual bytes so existing icons
    // and replacements (including multiple uploads in one second) are versioned.
    match tokio::fs::read(state.config.storage_path.join(icon_path)).await {
        Ok(data) => format!("{url}?v={}", hex::encode(Sha256::digest(&data))),
        Err(error) => {
            tracing::warn!(package_name, %error, "Could not fingerprint app icon");
            url
        }
    }
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
        publication_state: v.publication_state.clone(),
        distribution_mode: v.distribution_mode.clone(),
        release_notes: v.release_notes.clone(),
        published_at: v.published_at.clone(),
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

pub fn health_check(
    _: Result<(), simple_server::health::CheckFailure<std::convert::Infallible>>,
) -> Response {
    Json(json!({ "status": "healthy" })).into_response()
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

#[derive(Debug, Serialize)]
pub struct CurrentUserResponse {
    pub subject: String,
    pub email: Option<String>,
    pub is_admin: bool,
}

pub async fn get_current_user(
    AuthenticatedUser(user): AuthenticatedUser,
) -> Json<CurrentUserResponse> {
    Json(CurrentUserResponse {
        subject: user.subject,
        email: user.email,
        is_admin: user.is_admin,
    })
}

async fn list_apps_with_access(
    state: &AppState,
    access: Vec<db::access::EffectiveAppAccess>,
    include_drafts: bool,
    sdk: Option<u32>,
) -> Result<Json<AppsListResponse>, AppError> {
    let apps = db::get_all_apps(&state.db).await?;

    let mut items = Vec::new();
    for app in apps {
        let Some(grant) = access
            .iter()
            .find(|grant| grant.package_name == app.package_name)
        else {
            continue;
        };
        // Get latest version for this app
        let versions = db::get_app_versions(&state.db, &app.package_name)
            .await?
            .into_iter()
            .filter(|version| include_drafts || version.publication_state == "published")
            .filter(|version| sdk.is_none_or(|sdk| version.min_sdk <= i64::from(sdk)))
            .filter(|version| grant.access_level == AppAccessLevel::Beta || !version.is_beta)
            .collect::<Vec<_>>();
        if !include_drafts && versions.is_empty() {
            continue;
        }
        let total_size = versions.iter().map(|version| version.size).sum();
        let latest = versions.into_iter().max_by_key(|v| v.version_code);

        items.push(AppListItem {
            package_name: app.package_name.clone(),
            name: app.name,
            description: app.description,
            icon_url: make_icon_url(state, &app.package_name, app.icon_path.as_deref()).await,
            total_size,
            latest_version: latest.map(|v| LatestVersionInfo {
                publication_state: v.publication_state,
                distribution_mode: v.distribution_mode,
                version_code: v.version_code,
                version_name: v.version_name,
                size: v.size,
                min_sdk: v.min_sdk,
                uploaded_at: v.uploaded_at,
                is_beta: v.is_beta,
            }),
            access_level: grant.access_level,
            distribution_mode: app.distribution_mode,
            publication_revision: app.publication_revision,
        });
    }

    Ok(Json(AppsListResponse { apps: items }))
}

pub async fn list_apps(
    State(state): State<AppState>,
    Query(device): Query<DeviceQuery>,
) -> Result<Json<AppsListResponse>, AppError> {
    let access = db::get_all_apps(&state.db)
        .await?
        .into_iter()
        .map(|app| db::access::EffectiveAppAccess {
            package_name: app.package_name,
            access_level: AppAccessLevel::Beta,
        })
        .collect();
    list_apps_with_access(&state, access, false, device.sdk).await
}

#[derive(Debug, Default, Deserialize)]
pub struct DeviceQuery {
    pub sdk: Option<u32>,
}

pub async fn list_authorized_apps(
    user: AuthenticatedUser,
    State(state): State<AppState>,
    Query(device): Query<DeviceQuery>,
) -> Result<Json<AppsListResponse>, AppError> {
    let access = db::access::get_effective_app_access(&state.db, &user.0.subject).await?;
    list_apps_with_access(&state, access, false, device.sdk).await
}

pub async fn list_admin_apps(
    _admin: AdminUser,
    State(state): State<AppState>,
) -> Result<Json<AppsListResponse>, AppError> {
    let access = db::get_all_apps(&state.db)
        .await?
        .into_iter()
        .map(|app| db::access::EffectiveAppAccess {
            package_name: app.package_name,
            access_level: AppAccessLevel::Beta,
        })
        .collect();
    list_apps_with_access(&state, access, true, None).await
}

pub async fn get_admin_app(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(package_name): Path<String>,
) -> Result<Json<AppDetailResponse>, AppError> {
    let Json(mut response) = get_app(
        State(state.clone()),
        Path(package_name.clone()),
        Query(DeviceQuery::default()),
    )
    .await?;
    response.versions = db::get_app_versions(&state.db, &package_name)
        .await?
        .iter()
        .map(to_version_info)
        .collect();
    Ok(Json(response))
}

pub async fn get_app(
    State(state): State<AppState>,
    Path(package_name): Path<String>,
    Query(device): Query<DeviceQuery>,
) -> Result<Json<AppDetailResponse>, AppError> {
    let app = db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{}' not found", package_name)))?;

    let versions = db::get_published_versions(&state.db, &package_name).await?;
    let version_infos: Vec<AppVersionInfo> = versions
        .iter()
        .filter(|v| device.sdk.is_none_or(|sdk| v.min_sdk <= i64::from(sdk)))
        .map(to_version_info)
        .collect();

    Ok(Json(AppDetailResponse {
        package_name: app.package_name.clone(),
        name: app.name,
        description: app.description,
        icon_url: make_icon_url(&state, &app.package_name, app.icon_path.as_deref()).await,
        versions: version_infos,
        access_level: AppAccessLevel::Beta,
        distribution_mode: app.distribution_mode,
        publication_revision: app.publication_revision,
    }))
}

pub async fn get_authorized_app(
    user: AuthenticatedUser,
    State(state): State<AppState>,
    Path(package_name): Path<String>,
    Query(device): Query<DeviceQuery>,
) -> Result<Json<AppDetailResponse>, AppError> {
    let access =
        db::access::get_effective_access_for_app(&state.db, &user.0.subject, &package_name)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("App '{package_name}' not found")))?;
    let app = db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{package_name}' not found")))?;
    let versions = db::get_published_versions(&state.db, &package_name)
        .await?
        .into_iter()
        .filter(|version| {
            device
                .sdk
                .is_none_or(|sdk| version.min_sdk <= i64::from(sdk))
        })
        .filter(|version| access == AppAccessLevel::Beta || !version.is_beta)
        .map(|version| to_version_info(&version))
        .collect();
    Ok(Json(AppDetailResponse {
        package_name: app.package_name.clone(),
        name: app.name,
        description: app.description,
        icon_url: make_icon_url(&state, &app.package_name, app.icon_path.as_deref()).await,
        versions,
        access_level: access,
        distribution_mode: app.distribution_mode,
        publication_revision: app.publication_revision,
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

pub async fn get_authorized_icon(
    user: AuthenticatedUser,
    State(state): State<AppState>,
    Path(package_name): Path<String>,
) -> Result<Response, AppError> {
    db::access::get_effective_access_for_app(&state.db, &user.0.subject, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{package_name}' not found")))?;
    get_icon(State(state), Path(package_name)).await
}

async fn ensure_canonical_download(
    state: &AppState,
    version: &db::models::AppVersion,
) -> Result<(), AppError> {
    if version.distribution_mode == "paravoid" {
        let public: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM paravoid_contracts c JOIN paravoid_installers i USING(package_name,contract_id) WHERE i.package_name = ? AND i.installer_version = ? AND c.authentication = 'public' AND c.verification_state = 'verified')")
            .bind(&version.package_name).bind(version.version_code).fetch_one(&state.db).await?;
        if !public {
            return Err(AppError::Conflict("acquisition_required: use an APK acquisition to receive this shell with update access".into()));
        }
    }
    Ok(())
}

/// Serve APK file with Range header support
pub async fn download_apk(
    State(state): State<AppState>,
    Path((package_name, version_code)): Path<(String, i64)>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    // Get version from database
    let versions = db::get_published_versions(&state.db, &package_name).await?;
    let version = versions
        .into_iter()
        .find(|v| v.version_code == version_code)
        .ok_or_else(|| {
            AppError::NotFound(format!(
                "Version {} not found for '{}'",
                version_code, package_name
            ))
        })?;

    ensure_canonical_download(&state, &version).await?;
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

pub async fn download_authorized_apk(
    user: AuthenticatedUser,
    State(state): State<AppState>,
    Path((package_name, version_code)): Path<(String, i64)>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    let access =
        db::access::get_effective_access_for_app(&state.db, &user.0.subject, &package_name)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("Version {version_code} not found")))?;
    let version = db::get_published_versions(&state.db, &package_name)
        .await?
        .into_iter()
        .find(|version| version.version_code == version_code)
        .filter(|version| access == AppAccessLevel::Beta || !version.is_beta)
        .ok_or_else(|| AppError::NotFound(format!("Version {version_code} not found")))?;
    ensure_canonical_download(&state, &version).await?;
    let full_path = state.config.storage_path.join(&version.apk_path);
    let filename = format!("{}-{}.apk", package_name, version.version_name);
    let range_header = headers.get(RANGE).and_then(|header| header.to_str().ok());
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
#[derive(Debug, Default, Deserialize)]
pub struct UploadOptions {
    #[serde(default)]
    pub asynchronous: bool,
}

pub async fn upload_app(
    admin: AdminUser,
    simple_server::web::extract::Query(options): simple_server::web::extract::Query<UploadOptions>,
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
    let mut replace_latest = false;
    let mut publication = None;
    let mut distribution_mode = None;

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
            Some("publication") => {
                publication = Some(read_metadata_text(field).await?);
            }
            Some("distribution_mode") => {
                distribution_mode = Some(read_metadata_text(field).await?);
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
            Some("replace_latest") => {
                replace_latest = match read_metadata_text(field).await?.as_str() {
                    "true" => true,
                    "false" => false,
                    _ => {
                        return Err(AppError::BadRequest(
                            "replace_latest must be 'true' or 'false'".to_string(),
                        ))
                    }
                };
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

    if publication.as_deref() != Some("draft") {
        return Err(AppError::BadRequest("client_upgrade_required: uploads now create drafts; send publication=draft, then publish through /publications".into()));
    }
    let mode = distribution_mode
        .as_deref()
        .filter(|m| ["normal", "paravoid"].contains(m))
        .ok_or_else(|| {
            AppError::BadRequest("distribution_mode must be normal or paravoid".into())
        })?;
    if replace_latest {
        return Err(AppError::BadRequest(
            "replace_latest belongs to publication, not upload".into(),
        ));
    }
    if options.asynchronous {
        let job = crate::services::upload_jobs::enqueue_with_mode(
            &state.db,
            &state.config.storage_path,
            &admin.0.subject,
            &filename,
            &upload_path,
            override_name,
            override_description,
            is_beta,
            mode,
        )
        .await?;
        return Ok((StatusCode::ACCEPTED, Json(job)).into_response());
    }
    // Process the upload using UploadService
    let result = state
        .upload_service
        .process_distribution_draft_upload(
            &filename,
            &upload_path,
            override_name,
            override_description,
            is_beta,
            mode,
        )
        .await
        .map_err(|e| match e {
            crate::services::UploadError::FileTooLarge { .. } => AppError::PayloadTooLarge,
            crate::services::UploadError::AabError(crate::services::AabError::OutputTooLarge {
                ..
            }) => AppError::PayloadTooLarge,
            crate::services::UploadError::InvalidFileType => AppError::InvalidFileType,
            crate::services::UploadError::InvalidPayload(message) => AppError::BadRequest(message),
            crate::services::UploadError::UnsupportedShell => AppError::BadRequest(e.to_string()),
            crate::services::UploadError::VersionExists {
                package_name,
                version_code,
            } => AppError::Conflict(format!(
                "Version {} already exists for {}",
                version_code, package_name
            )),
            crate::services::UploadError::ReplacementNotNewer => AppError::Conflict(
                "Replacement version code must be higher than the latest release in this channel"
                    .to_string(),
            ),
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
        icon_url: make_icon_url(
            &state,
            &result.package_name,
            app.as_ref().and_then(|a| a.icon_path.as_deref()),
        )
        .await,
        description: app.and_then(|a| a.description),
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

    state.catalog_events.notify_catalog_changed();
    Ok(Json(AppDetailResponse {
        package_name: app.package_name.clone(),
        name: app.name,
        description: app.description,
        icon_url: make_icon_url(&state, &app.package_name, app.icon_path.as_deref()).await,
        versions: version_infos,
        access_level: AppAccessLevel::Beta,
        distribution_mode: app.distribution_mode,
        publication_revision: app.publication_revision,
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

    let retained: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM paravoid_contracts WHERE package_name = ?)",
    )
    .bind(&package_name)
    .fetch_one(&state.db)
    .await?;
    if retained {
        return Err(AppError::Conflict("This app has retained Paravoid contracts. Withdraw its installers and retire its streams instead of deleting delivery history.".into()));
    }

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

    state.catalog_events.notify_catalog_changed();
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

    state.catalog_events.notify_catalog_changed();
    Ok(Json(json!({
        "message": "Icon uploaded successfully",
        "icon_url": make_icon_url(&state, &package_name, Some(&icon_path)).await
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
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE apps SET publication_revision = publication_revision + 1 WHERE package_name = ?",
    )
    .bind(&package_name)
    .execute(&mut *tx)
    .await?;
    let retained: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM paravoid_contracts c JOIN paravoid_installers i USING(package_name,contract_id) WHERE i.package_name = ? AND i.installer_version = ?)")
        .bind(&package_name).bind(version_code).fetch_one(&mut *tx).await?;
    if retained {
        return Err(AppError::Conflict(
            "This installer anchors a retained shell contract and cannot be deleted".into(),
        ));
    }
    let removed = sqlx::query("DELETE FROM app_versions WHERE package_name = ? AND version_code = ? AND publication_state = 'draft'")
        .bind(&package_name).bind(version_code).execute(&mut *tx).await?;
    if removed.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Only drafts can be deleted. Published releases must be withdrawn.".into(),
        ));
    }
    let remaining: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM app_versions WHERE package_name = ?")
            .bind(&package_name)
            .fetch_one(&mut *tx)
            .await?;
    let is_last_version = remaining == 0;
    if is_last_version {
        sqlx::query("DELETE FROM apps WHERE package_name = ?")
            .bind(&package_name)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;

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

    state.catalog_events.notify_catalog_changed();
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
    state.catalog_events.notify_catalog_changed();
    Ok(StatusCode::NO_CONTENT)
}

pub async fn remove_admin_direct_grant(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((subject, package_name)): Path<(String, String)>,
) -> Result<StatusCode, AppError> {
    db::admin::remove_direct_grant(&state.db, &admin.0.subject, &subject, &package_name).await?;
    state.catalog_events.notify_catalog_changed();
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
    state.catalog_events.notify_catalog_changed();
    Ok((StatusCode::CREATED, Json(group)))
}

pub async fn rename_admin_group(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(group_id): Path<i64>,
    Json(request): Json<GroupNameRequest>,
) -> Result<StatusCode, AppError> {
    db::admin::rename_group(&state.db, &admin.0.subject, group_id, &request.name).await?;
    state.catalog_events.notify_catalog_changed();
    Ok(StatusCode::NO_CONTENT)
}

pub async fn delete_admin_group(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(group_id): Path<i64>,
) -> Result<StatusCode, AppError> {
    db::admin::delete_group(&state.db, &admin.0.subject, group_id).await?;
    state.catalog_events.notify_catalog_changed();
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
    state.catalog_events.notify_catalog_changed();
    Ok(StatusCode::NO_CONTENT)
}

pub async fn remove_admin_group_grant(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((group_id, package_name)): Path<(i64, String)>,
) -> Result<StatusCode, AppError> {
    db::admin::remove_group_grant(&state.db, &admin.0.subject, group_id, &package_name).await?;
    state.catalog_events.notify_catalog_changed();
    Ok(StatusCode::NO_CONTENT)
}

pub async fn add_admin_group_member(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((group_id, subject)): Path<(i64, String)>,
) -> Result<StatusCode, AppError> {
    db::admin::set_membership(&state.db, &admin.0.subject, group_id, &subject, true).await?;
    state.catalog_events.notify_catalog_changed();
    Ok(StatusCode::NO_CONTENT)
}

pub async fn remove_admin_group_member(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((group_id, subject)): Path<(i64, String)>,
) -> Result<StatusCode, AppError> {
    db::admin::set_membership(&state.db, &admin.0.subject, group_id, &subject, false).await?;
    state.catalog_events.notify_catalog_changed();
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
    state.catalog_events.notify_catalog_changed();
    Ok(StatusCode::NO_CONTENT)
}
