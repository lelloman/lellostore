use axum::{
    body::Body,
    extract::State,
    http::{header::AUTHORIZATION, Request},
    middleware::Next,
    response::Response,
};
use tracing::debug;

use super::error::AuthError;
use super::user::User;
use super::AuthState;

/// Extract Bearer token from Authorization header
fn extract_bearer_token(request: &Request<Body>) -> Result<&str, AuthError> {
    let auth_header = request
        .headers()
        .get(AUTHORIZATION)
        .ok_or(AuthError::MissingToken)?
        .to_str()
        .map_err(|_| AuthError::InvalidAuthHeader)?;

    let token = auth_header
        .strip_prefix("Bearer ")
        .or_else(|| auth_header.strip_prefix("bearer "))
        .ok_or(AuthError::InvalidAuthHeader)?;

    if token.is_empty() {
        return Err(AuthError::InvalidAuthHeader);
    }

    Ok(token)
}

/// Authentication middleware that validates tokens and attaches User to request
pub async fn auth_middleware(
    State(auth): State<AuthState>,
    mut request: Request<Body>,
    next: Next,
) -> Result<Response, AuthError> {
    // Extract Bearer token
    let token = extract_bearer_token(&request)?;

    // Validate token
    let claims = auth.validator.validate(token).await?;

    // Create user from claims
    let user = User::from_claims(&claims, &auth.role_claim_path, &auth.admin_role);

    debug!("Authenticated user: {} (admin: {})", user.subject, user.is_admin);

    // Attach user to request extensions
    request.extensions_mut().insert(user);

    // Continue to handler
    Ok(next.run(request).await)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::Request;

    fn make_request_with_auth(auth_value: &str) -> Request<Body> {
        Request::builder()
            .header(AUTHORIZATION, auth_value)
            .body(Body::empty())
            .unwrap()
    }

    fn make_request_without_auth() -> Request<Body> {
        Request::builder().body(Body::empty()).unwrap()
    }

    #[test]
    fn test_extract_bearer_token_valid() {
        let request = make_request_with_auth("Bearer eyJhbGciOiJSUzI1NiJ9.test.sig");
        let token = extract_bearer_token(&request).unwrap();
        assert_eq!(token, "eyJhbGciOiJSUzI1NiJ9.test.sig");
    }

    #[test]
    fn test_extract_bearer_token_lowercase() {
        let request = make_request_with_auth("bearer mytoken");
        let token = extract_bearer_token(&request).unwrap();
        assert_eq!(token, "mytoken");
    }

    #[test]
    fn test_extract_bearer_token_missing() {
        let request = make_request_without_auth();
        let result = extract_bearer_token(&request);
        assert!(matches!(result, Err(AuthError::MissingToken)));
    }

    #[test]
    fn test_extract_bearer_token_no_bearer_prefix() {
        let request = make_request_with_auth("Basic dXNlcjpwYXNz");
        let result = extract_bearer_token(&request);
        assert!(matches!(result, Err(AuthError::InvalidAuthHeader)));
    }

    #[test]
    fn test_extract_bearer_token_empty() {
        let request = make_request_with_auth("Bearer ");
        let result = extract_bearer_token(&request);
        assert!(matches!(result, Err(AuthError::InvalidAuthHeader)));
    }
}
