use simple_server::auth::{
    AsyncAccess, CredentialError, HeaderCredential, RepeatedHeaders, SchemeCase,
};
use simple_server::web::{
    body::Body,
    extract::State,
    http::{header::AUTHORIZATION, request::Parts, HeaderMap, Request},
    middleware::Next,
    response::Response,
};
use tracing::{debug, warn};

use super::error::AuthError;
use super::user::User;
use super::AuthState;

/// Extract Bearer token from Authorization header
#[cfg(test)]
fn extract_bearer_token(request: &Request<Body>) -> Result<&str, AuthError> {
    extract_bearer_header(request.headers())
}

fn extract_bearer_header(headers: &HeaderMap) -> Result<&str, AuthError> {
    let bearer = HeaderCredential::new(AUTHORIZATION)
        .with_scheme("Bearer", SchemeCase::Exact)
        .repeated(RepeatedHeaders::First);
    let lowercase = HeaderCredential::new(AUTHORIZATION)
        .with_scheme("bearer", SchemeCase::Exact)
        .repeated(RepeatedHeaders::First);
    let credential = bearer.extract(headers).or_else(|error| {
        if error == CredentialError::InvalidScheme {
            lowercase.extract(headers)
        } else {
            Err(error)
        }
    });
    credential
        .map(|value| value.expose())
        .map_err(|error| match error {
            CredentialError::Missing => AuthError::MissingToken,
            _ => AuthError::InvalidAuthHeader,
        })
}

fn authenticated_access(auth: AuthState) -> AsyncAccess<Parts, User, AuthError> {
    AsyncAccess::new(move |parts: &Parts| {
        let auth = auth.clone();
        Box::pin(async move {
            let path = parts.uri.path();
            let token = extract_bearer_header(&parts.headers).map_err(|error| {
                warn!(path = %path, error = %error, "Authentication failed: missing or invalid token");
                error
            })?;
            let claims = auth.validator.validate(token).await.map_err(|error| {
                warn!(path = %path, error = %error, "Authentication failed: token validation error");
                error
            })?;
            let user = User::from_claims(&claims, &auth.role_claim_path, &auth.admin_role);
            if let Some(pool) = &auth.user_registry {
                crate::db::admin::observe_user(pool, &user)
                    .await
                    .map_err(|error| AuthError::UserRegistryUnavailable(error.to_string()))?;
            }
            debug!(user = %user.subject, is_admin = user.is_admin, path = %path, "User authenticated");
            Ok(user)
        })
    })
}

/// Authentication middleware that validates tokens and attaches User to request
pub async fn auth_middleware(
    State(auth): State<AuthState>,
    request: Request<Body>,
    next: Next,
) -> Result<Response, AuthError> {
    let (mut parts, body) = request.into_parts();
    let user = authenticated_access(auth).evaluate(&parts).await?;
    parts.extensions.insert(user);
    Ok(next.run(Request::from_parts(parts, body)).await)
}

#[cfg(test)]
mod tests {
    use super::*;
    use simple_server::web::http::Request;

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

    #[test]
    fn bearer_compatibility_contract() {
        for value in ["Bearer token", "bearer token"] {
            assert_eq!(
                extract_bearer_token(&make_request_with_auth(value)).unwrap(),
                "token"
            );
        }
        for value in [
            "BEARER token",
            "BeArEr token",
            "Basic token",
            "Bearer ",
            "Bearer",
        ] {
            assert!(matches!(
                extract_bearer_token(&make_request_with_auth(value)),
                Err(AuthError::InvalidAuthHeader)
            ));
        }
        assert_eq!(
            extract_bearer_token(&make_request_with_auth("Bearer  token")).unwrap(),
            " token"
        );

        let mut request = make_request_with_auth("Bearer first");
        request
            .headers_mut()
            .append(AUTHORIZATION, "Bearer second".parse().unwrap());
        assert_eq!(extract_bearer_token(&request).unwrap(), "first");
        let mut request = make_request_with_auth("Basic first");
        request
            .headers_mut()
            .append(AUTHORIZATION, "Bearer second".parse().unwrap());
        assert!(matches!(
            extract_bearer_token(&request),
            Err(AuthError::InvalidAuthHeader)
        ));
        let mut request = make_request_without_auth();
        request.headers_mut().insert(
            AUTHORIZATION,
            simple_server::web::http::HeaderValue::from_bytes(b"Bearer \xff").unwrap(),
        );
        assert!(matches!(
            extract_bearer_token(&request),
            Err(AuthError::InvalidAuthHeader)
        ));
    }
}
