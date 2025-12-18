use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AuthError {
    #[error("Failed to fetch OIDC discovery: {0}")]
    DiscoveryFailed(String),

    #[error("Failed to fetch JWKS: {0}")]
    JwksFailed(String),

    #[error("Key not found: {0}")]
    KeyNotFound(String),

    #[error("Token validation failed: {0}")]
    TokenInvalid(String),

    #[error("Token expired")]
    TokenExpired,

    #[error("Missing authorization header")]
    MissingToken,

    #[error("Invalid authorization header format")]
    InvalidAuthHeader,

    #[error("Insufficient permissions")]
    Forbidden,
}

impl IntoResponse for AuthError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AuthError::MissingToken
            | AuthError::InvalidAuthHeader
            | AuthError::TokenInvalid(_)
            | AuthError::TokenExpired
            | AuthError::KeyNotFound(_) => (StatusCode::UNAUTHORIZED, "Unauthorized"),

            AuthError::Forbidden => (StatusCode::FORBIDDEN, "Forbidden"),

            // Don't leak internal errors
            AuthError::DiscoveryFailed(_) | AuthError::JwksFailed(_) => {
                (StatusCode::INTERNAL_SERVER_ERROR, "Authentication error")
            }
        };

        let body = json!({ "error": message });
        (status, Json(body)).into_response()
    }
}
