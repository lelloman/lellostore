use super::AppState;
use crate::{
    auth::AdminUser,
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

pub async fn get(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<UploadJob>, AppError> {
    Ok(Json(upload_jobs::get(&state.db, &id).await?))
}

pub async fn retry(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<UploadJob>, AppError> {
    let changed = sqlx::query("UPDATE upload_jobs SET status = 'queued', error = NULL, updated_at = datetime('now') WHERE id = ? AND status = 'failed'")
        .bind(&id).execute(&state.db).await?.rows_affected();
    if changed == 0 {
        return Err(AppError::Conflict(
            "Only failed uploads can be retried".into(),
        ));
    }
    Ok(Json(upload_jobs::get(&state.db, &id).await?))
}
