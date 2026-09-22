use serde::Deserialize;
use simple_server::axum::{
    extract::{Path, State},
    Json,
};

use super::AppState;
use crate::{
    auth::AdminUser,
    db::{self, publications::*},
    error::AppError,
};

pub async fn publish(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(package): Path<String>,
    Json(request): Json<PublishRequest>,
) -> Result<Json<PublicationResult>, AppError> {
    let version = db::get_app_versions(&state.db, &package)
        .await?
        .into_iter()
        .find(|v| v.version_code == request.version_code)
        .ok_or_else(|| AppError::NotFound("Draft not found".into()))?;
    // Recheck stored bytes before committing publication; never offer missing or changed files.
    let path = state.config.storage_path.join(&version.apk_path);
    let actual = crate::services::upload::calculate_sha256_file(&path).await?;
    if actual != version.sha256 || tokio::fs::metadata(&path).await?.len() != version.size as u64 {
        return Err(AppError::Conflict(
            "Stored artifact failed integrity verification; upload a valid replacement draft"
                .into(),
        ));
    }
    let result = db::publications::publish(&state.db, &package, &admin.0.subject, &request).await?;
    state.catalog_events.notify_catalog_changed();
    Ok(Json(result))
}

pub async fn withdraw(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((package, code)): Path<(String, i64)>,
    Json(request): Json<RevisionRequest>,
) -> Result<Json<PublicationResult>, AppError> {
    let result = db::publications::withdraw(
        &state.db,
        &package,
        code,
        &admin.0.subject,
        request.expected_revision,
    )
    .await?;
    state.catalog_events.notify_catalog_changed();
    Ok(Json(result))
}

pub async fn history(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(package): Path<String>,
) -> Result<Json<Vec<PublicationEvent>>, AppError> {
    Ok(Json(db::publications::history(&state.db, &package).await?))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DraftMetadata {
    pub release_notes: String,
    pub is_beta: bool,
}

pub async fn edit_draft(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path((package, code)): Path<(String, i64)>,
    Json(request): Json<DraftMetadata>,
) -> Result<Json<serde_json::Value>, AppError> {
    if request.release_notes.len() > 64 * 1024 {
        return Err(AppError::BadRequest("Release notes exceed 64 KiB".into()));
    }
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE apps SET publication_revision = publication_revision + 1 WHERE package_name = ?",
    )
    .bind(&package)
    .execute(&mut *tx)
    .await?;
    let changed = sqlx::query("UPDATE app_versions SET release_notes = ?, is_beta = ? WHERE package_name = ? AND version_code = ? AND publication_state = 'draft'")
        .bind(request.release_notes).bind(request.is_beta).bind(&package).bind(code).execute(&mut *tx).await?;
    if changed.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Only draft metadata can be edited".into(),
        ));
    }
    tx.commit().await?;
    Ok(Json(serde_json::json!({"status": "saved"})))
}
