//! Administration of per-app publish tokens. Every handler here requires an
//! administrator; publish tokens can never manage tokens.
use super::AppState;
use crate::{
    auth::AdminUser,
    db::publish_tokens::{self, CreatedPublishToken, PublishToken, PublishTokenSummary},
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
    pub package_name: String,
    #[serde(default)]
    pub expires_at: Option<String>,
}

pub async fn create(
    admin: AdminUser,
    State(state): State<AppState>,
    Json(request): Json<CreateRequest>,
) -> Result<(StatusCode, Json<CreatedPublishToken>), AppError> {
    let created = publish_tokens::create(
        &state.db,
        &admin.0.subject,
        &request.name,
        &request.package_name,
        request.expires_at.as_deref(),
    )
    .await?;
    tracing::info!(token_id = %created.token.id, package = %created.token.package_name, actor = %admin.0.subject, "Publish token created");
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn list(
    _admin: AdminUser,
    State(state): State<AppState>,
) -> Result<Json<Vec<PublishTokenSummary>>, AppError> {
    Ok(Json(publish_tokens::list(&state.db).await?))
}

pub async fn revoke(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<PublishToken>, AppError> {
    let token = publish_tokens::revoke(&state.db, &admin.0.subject, &id).await?;
    tracing::info!(token_id = %token.id, actor = %admin.0.subject, "Publish token revoked");
    Ok(Json(token))
}
