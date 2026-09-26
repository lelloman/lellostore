use super::AppState;
use crate::{auth::AdminUser, db, error::AppError};
use serde::Deserialize;
use simple_server::web::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ArchiveRequest {
    pub expected_revision: i64,
    pub archived: bool,
}

pub async fn apk(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path((package, code)): Path<(String, i64)>,
    Json(request): Json<ArchiveRequest>,
) -> Result<StatusCode, AppError> {
    let mut tx = state.db.begin().await?;
    db::paravoid::lock_app(&mut tx, &package, request.expected_revision).await?;
    let changed = sqlx::query("UPDATE app_versions SET archived = ? WHERE package_name = ? AND version_code = ? AND artifact_removed = 0")
        .bind(request.archived).bind(package).bind(code).execute(&mut *tx).await?;
    if changed.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Artifact was already replaced or does not exist".into(),
        ));
    }
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn vpk(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path((package, id)): Path<(String, String)>,
    Json(request): Json<ArchiveRequest>,
) -> Result<StatusCode, AppError> {
    let mut tx = state.db.begin().await?;
    db::paravoid::lock_app(&mut tx, &package, request.expected_revision).await?;
    let changed = sqlx::query("UPDATE vpk_releases SET archived = ? WHERE package_name = ? AND id = ? AND artifact_removed = 0")
        .bind(request.archived).bind(package).bind(id).execute(&mut *tx).await?;
    if changed.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Artifact was already replaced or does not exist".into(),
        ));
    }
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
