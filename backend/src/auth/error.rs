use serde_json::json;
use simple_server::web::{
    http::StatusCode,
    response::{IntoResponse, Response},
};
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

    #[error("Authenticated user directory unavailable: {0}")]
    UserRegistryUnavailable(String),
}

impl simple_server::web::IntoRejectionResponse for AuthError {
    fn into_rejection_response(self) -> simple_server::web::RejectionResponse {
        let (status, message) = match &self {
            AuthError::MissingToken
            | AuthError::InvalidAuthHeader
            | AuthError::TokenInvalid(_)
            | AuthError::TokenExpired
            | AuthError::KeyNotFound(_) => (StatusCode::UNAUTHORIZED, "Unauthorized"),

            AuthError::Forbidden => (StatusCode::FORBIDDEN, "Forbidden"),

            // Don't leak internal errors
            AuthError::DiscoveryFailed(_)
            | AuthError::JwksFailed(_)
            | AuthError::UserRegistryUnavailable(_) => {
                (StatusCode::INTERNAL_SERVER_ERROR, "Authentication error")
            }
        };

        let body = json!({ "error": message });
        let mut response = simple_server::web::RejectionResponse::new(
            serde_json::to_vec(&body).expect("auth error is serializable"),
        );
        *response.status_mut() = status;
        response.headers_mut().insert(
            simple_server::web::http::header::CONTENT_TYPE,
            simple_server::web::HeaderValue::from_static("application/json"),
        );
        response
    }
}

impl IntoResponse for AuthError {
    fn into_response(self) -> Response {
        simple_server::web::IntoRejectionResponse::into_rejection_response(self).into_response()
    }
}
