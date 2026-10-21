//! Administration of device tokens. Every handler here requires an
//! administrator; device tokens can never manage tokens.
use super::AppState;
use crate::{
    auth::AdminUser,
    db::device_tokens::{self, CreatedDeviceToken, DeviceToken},
    error::AppError,
};
use serde::{Deserialize, Serialize};
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

/// Give a token a new secret, returned once. The id, principal, grants and
/// groups stay; a revoked token becomes active again.
pub async fn rekey(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<CreatedDeviceToken>, AppError> {
    let rekeyed = device_tokens::rekey(&state.db, &admin.0.subject, &id).await?;
    tracing::info!(token_id = %rekeyed.token.id, actor = %admin.0.subject, "Device token rekeyed");
    Ok(Json(rekeyed))
}

/// Permanently delete a revoked token; an active one is `409`.
pub async fn purge(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<StatusCode, AppError> {
    device_tokens::purge(&state.db, &admin.0.subject, &id).await?;
    state.catalog_events.notify_catalog_changed();
    tracing::info!(token_id = %id, actor = %admin.0.subject, "Device token deleted");
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Serialize)]
pub struct PurgedTokens {
    pub deleted: u64,
}

/// Permanently delete every revoked token.
pub async fn purge_revoked(
    admin: AdminUser,
    State(state): State<AppState>,
) -> Result<Json<PurgedTokens>, AppError> {
    let deleted = device_tokens::purge_revoked(&state.db, &admin.0.subject).await?;
    if deleted > 0 {
        state.catalog_events.notify_catalog_changed();
    }
    tracing::info!(deleted, actor = %admin.0.subject, "Revoked device tokens deleted");
    Ok(Json(PurgedTokens { deleted }))
}
