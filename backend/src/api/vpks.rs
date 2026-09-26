use super::AppState;
use crate::{auth::AdminUser, db::paravoid, error::AppError};
use serde::Deserialize;
use simple_server::web::{
    extract::{Multipart, Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use tokio::io::AsyncWriteExt;

pub async fn overview(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(package): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let app = crate::db::get_app(&state.db, &package)
        .await?
        .ok_or_else(|| AppError::NotFound("App not found".into()))?;
    let contracts = paravoid::contracts(&state.db, &package).await?;
    let installers: Vec<(i64,String,Option<String>)> = sqlx::query_as("SELECT installer_version,contract_id,embedded_vpk_id FROM paravoid_installers WHERE package_name = ? ORDER BY installer_version DESC").bind(&package).fetch_all(&state.db).await?;
    let installers: Vec<_> = installers.into_iter().map(|(version,contract,embedded)| serde_json::json!({"installer_version":version,"contract_id":contract,"embedded_vpk_id":embedded})).collect();
    let releases = paravoid::releases(&state.db, &package).await?;
    let streams: Vec<paravoid::Stream> = sqlx::query_as(
        "SELECT * FROM paravoid_streams WHERE package_name = ? ORDER BY contract_id",
    )
    .bind(&package)
    .fetch_all(&state.db)
    .await?;
    let grants = paravoid::grants(&state.db, &package).await?;
    let events: Vec<paravoid::Event> = sqlx::query_as(
        "SELECT * FROM paravoid_events WHERE package_name = ? ORDER BY id DESC LIMIT 200",
    )
    .bind(&package)
    .fetch_all(&state.db)
    .await?;
    Ok(Json(
        serde_json::json!({"distribution_mode":app.distribution_mode,"publication_revision":app.publication_revision,"contracts":contracts,"installers":installers,"releases":releases,"streams":streams,"grants":grants,"events":events}),
    ))
}
pub async fn upload(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((package, contract)): Path<(String, String)>,
    mut multipart: Multipart,
) -> Result<Response, AppError> {
    paravoid::contract(&state.db, &package, &contract).await?;
    let temporary = state
        .storage
        .create_temp_dir()
        .map_err(|_| AppError::Internal("Cannot prepare upload".into()))?;
    let path = temporary.path().join("payload.vpk");
    let mut filename = None;
    while let Some(mut field) = multipart
        .next_field()
        .await
        .map_err(|_| AppError::BadRequest("Invalid upload form".into()))?
    {
        if field.name() != Some("file") || filename.is_some() {
            return Err(AppError::BadRequest("Provide exactly one VPK file".into()));
        }
        filename = Some(field.file_name().unwrap_or("payload.vpk").to_owned());
        let mut output = tokio::fs::File::create(&path).await?;
        let mut size = 0_u64;
        while let Some(bytes) = field
            .chunk()
            .await
            .map_err(|_| AppError::BadRequest("Interrupted upload".into()))?
        {
            size = size
                .checked_add(bytes.len() as u64)
                .ok_or(AppError::PayloadTooLarge)?;
            if size
                > state
                    .config
                    .max_upload_size
                    .min(crate::paravoid::MAX_ARCHIVE_BYTES)
            {
                return Err(AppError::PayloadTooLarge);
            }
            output.write_all(&bytes).await?;
        }
        output.flush().await?;
    }
    let filename = filename.ok_or_else(|| AppError::BadRequest("VPK file missing".into()))?;
    let job = crate::services::upload_jobs::enqueue_vpk(
        &state.db,
        &state.config.storage_path,
        &admin.0.subject,
        &filename,
        &path,
        &package,
        &contract,
    )
    .await?;
    Ok((StatusCode::ACCEPTED, Json(job)).into_response())
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Revision {
    pub expected_revision: i64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Notes {
    pub expected_revision: i64,
    pub release_notes: String,
}
pub async fn notes(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path((package, id)): Path<(String, String)>,
    Json(request): Json<Notes>,
) -> Result<StatusCode, AppError> {
    if request.release_notes.len() > 65536 {
        return Err(AppError::BadRequest("Release notes exceed 64 KiB".into()));
    }
    let mut tx = state.db.begin().await?;
    paravoid::lock_app(&mut tx, &package, request.expected_revision).await?;
    if sqlx::query("UPDATE vpk_releases SET release_notes = ? WHERE id = ? AND package_name = ? AND publication_state = 'draft'").bind(request.release_notes).bind(id).bind(package).execute(&mut *tx).await?.rows_affected()!=1 { return Err(AppError::Conflict("Only draft notes can be edited".into())); }
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
pub async fn publish(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((package, id)): Path<(String, String)>,
    Json(request): Json<Revision>,
) -> Result<StatusCode, AppError> {
    let release = paravoid::release(&state.db, &package, &id).await?;
    let path = state.config.storage_path.join(&release.archive_path);
    if crate::services::upload::calculate_sha256_file(&path).await? != release.archive_sha256
        || tokio::fs::metadata(&path).await?.len() != release.archive_size as u64
    {
        return Err(AppError::Conflict(
            "Stored VPK integrity check failed".into(),
        ));
    }
    paravoid::publish(
        &state.db,
        &package,
        &id,
        &admin.0.subject,
        request.expected_revision,
    )
    .await?;
    if let Err(error) =
        crate::services::retention::cleanup_replaced(&state.db, &state.config.storage_path).await
    {
        tracing::warn!(%error, "Artifact cleanup will retry in the background");
    }
    state.catalog_events.notify_catalog_changed();
    Ok(StatusCode::NO_CONTENT)
}
pub async fn withdraw(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((package, id)): Path<(String, String)>,
    Json(request): Json<Revision>,
) -> Result<StatusCode, AppError> {
    paravoid::withdraw(
        &state.db,
        &package,
        &id,
        &admin.0.subject,
        request.expected_revision,
    )
    .await?;
    if let Err(error) =
        crate::services::retention::cleanup_replaced(&state.db, &state.config.storage_path).await
    {
        tracing::warn!(%error, "Artifact cleanup will retry in the background");
    }
    state.catalog_events.notify_catalog_changed();
    Ok(StatusCode::NO_CONTENT)
}
pub async fn download(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path((package, id)): Path<(String, String)>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    let release = paravoid::release(&state.db, &package, &id).await?;
    if release.artifact_removed {
        return Err(AppError::NotFound("Artifact was replaced".into()));
    }
    super::file_response::serve_immutable_file(
        state.config.storage_path.join(&release.archive_path),
        "application/vnd.paravoid.vpk",
        format!("{}.vpk", release.release_id),
        &release.archive_sha256,
        release.archive_size as u64,
        &headers,
    )
    .await
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StreamUpdate {
    pub expected_revision: i64,
    pub retired: bool,
}
pub async fn stream(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((package, id)): Path<(String, String)>,
    Json(request): Json<StreamUpdate>,
) -> Result<StatusCode, AppError> {
    paravoid::set_stream(
        &state.db,
        &package,
        &id,
        request.retired,
        &admin.0.subject,
        request.expected_revision,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}
pub async fn revoke(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((package, id)): Path<(String, String)>,
    Json(request): Json<Revision>,
) -> Result<StatusCode, AppError> {
    paravoid::revoke(
        &state.db,
        &package,
        &id,
        &admin.0.subject,
        request.expected_revision,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}
