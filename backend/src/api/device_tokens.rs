//! Administration of device tokens. Every handler here requires an
//! administrator; device tokens can never manage tokens.
use super::AppState;
use crate::{
    auth::AdminUser,
    db::device_tokens::{self, CreatedDeviceToken, DeviceToken},
    error::AppError,
};
use serde::Deserialize;
use simple_server::web::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CreateRequest {
    pub name: String,
    #[serde(default)]
    pub expires_at: Option<String>,
    /// App groups the token's principal joins at creation.
    #[serde(default)]
    pub group_ids: Vec<i64>,
}

pub async fn create(
    admin: AdminUser,
    State(state): State<AppState>,
    Json(request): Json<CreateRequest>,
) -> Result<(StatusCode, Json<CreatedDeviceToken>), AppError> {
    let created = device_tokens::create(
        &state.db,
        &admin.0.subject,
        &request.name,
        request.expires_at.as_deref(),
        &request.group_ids,
    )
    .await?;
    if !created.token.groups.is_empty() {
        state.catalog_events.notify_catalog_changed();
    }
    tracing::info!(token_id = %created.token.id, actor = %admin.0.subject, "Device token created");
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn list(
    _admin: AdminUser,
    State(state): State<AppState>,
) -> Result<Json<Vec<DeviceToken>>, AppError> {
    Ok(Json(device_tokens::list(&state.db).await?))
}

pub async fn revoke(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<DeviceToken>, AppError> {
    let token = device_tokens::revoke(&state.db, &admin.0.subject, &id).await?;
    tracing::info!(token_id = %token.id, actor = %admin.0.subject, "Device token revoked");
    Ok(Json(token))
}
