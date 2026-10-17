use super::AppState;
use crate::{
    auth::{AdminOrPublisher, AdminUser},
    error::AppError,
    services::upload_jobs::{self, UploadJob},
};
use simple_server::web::{
    extract::{Path, State},
    Json,
};

pub async fn list(
    _admin: AdminUser,
    State(state): State<AppState>,
) -> Result<Json<Vec<UploadJob>>, AppError> {
    Ok(Json(
        sqlx::query_as("SELECT * FROM upload_jobs ORDER BY created_at DESC, id DESC LIMIT 100")
            .fetch_all(&state.db)
            .await?,
    ))
}

/// Poll one upload. A publish token may read only the jobs it created.
pub async fn get(
    principal: AdminOrPublisher,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<UploadJob>, AppError> {
    let job = upload_jobs::get(&state.db, &id).await?;
    principal.for_upload_job(job.publish_token_id.as_deref())?;
    Ok(Json(job))
}

pub async fn retry(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<UploadJob>, AppError> {
    let changed = sqlx::query("UPDATE upload_jobs SET status = 'queued', error = NULL, updated_at = datetime('now') WHERE id = ? AND status = 'failed' AND input_cleaned_at IS NULL")
        .bind(&id).execute(&state.db).await?.rows_affected();
    if changed == 0 {
        return Err(AppError::Conflict(
            "Only failed uploads whose artifact is still stored can be retried".into(),
        ));
    }
    Ok(Json(upload_jobs::get(&state.db, &id).await?))
}
