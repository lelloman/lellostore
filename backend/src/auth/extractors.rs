use axum::{async_trait, extract::FromRequestParts, http::request::Parts};
use tracing::warn;

use super::error::AuthError;
use super::user::User;

/// Extractor for authenticated users
///
/// Use this in route handlers to require authentication:
/// ```ignore
/// async fn my_handler(user: AuthenticatedUser) -> impl IntoResponse {
///     // user.0 contains the User struct
/// }
/// ```
#[derive(Debug, Clone)]
pub struct AuthenticatedUser(pub User);

#[async_trait]
impl<S> FromRequestParts<S> for AuthenticatedUser
where
    S: Send + Sync,
{
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        parts
            .extensions
            .get::<User>()
            .cloned()
            .map(AuthenticatedUser)
            .ok_or(AuthError::MissingToken)
    }
}

/// Extractor for admin users only
///
/// Use this in route handlers to require admin privileges:
/// ```ignore
/// async fn admin_handler(user: AdminUser) -> impl IntoResponse {
///     // user.0 contains the User struct (guaranteed to be admin)
/// }
/// ```
#[derive(Debug, Clone)]
pub struct AdminUser(pub User);

#[async_trait]
impl<S> FromRequestParts<S> for AdminUser
where
    S: Send + Sync,
{
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        let user = parts
            .extensions
            .get::<User>()
            .cloned()
            .ok_or(AuthError::MissingToken)?;

        if user.is_admin {
            Ok(AdminUser(user))
        } else {
            warn!(
                user = %user.subject,
                path = %parts.uri.path(),
                "Authorization denied: user is not admin"
            );
            Err(AuthError::Forbidden)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::Request;

    fn make_user(is_admin: bool) -> User {
        User {
            subject: "user-123".to_string(),
            email: Some("user@example.com".to_string()),
            roles: if is_admin {
                vec!["user".to_string(), "admin".to_string()]
            } else {
                vec!["user".to_string()]
            },
            is_admin,
        }
    }

    #[tokio::test]
    async fn test_authenticated_user_extractor_success() {
        let user = make_user(false);
        let mut request = Request::builder().body(()).unwrap();
        request.extensions_mut().insert(user.clone());

        let (mut parts, _) = request.into_parts();
        let result = AuthenticatedUser::from_request_parts(&mut parts, &()).await;

        assert!(result.is_ok());
        let extracted = result.unwrap();
        assert_eq!(extracted.0.subject, "user-123");
    }

    #[tokio::test]
    async fn test_authenticated_user_extractor_missing() {
        let request = Request::builder().body(()).unwrap();
        let (mut parts, _) = request.into_parts();

        let result = AuthenticatedUser::from_request_parts(&mut parts, &()).await;

        assert!(matches!(result, Err(AuthError::MissingToken)));
    }

    #[tokio::test]
    async fn test_admin_user_extractor_success() {
        let user = make_user(true);
        let mut request = Request::builder().body(()).unwrap();
        request.extensions_mut().insert(user);

        let (mut parts, _) = request.into_parts();
        let result = AdminUser::from_request_parts(&mut parts, &()).await;

        assert!(result.is_ok());
        let extracted = result.unwrap();
        assert!(extracted.0.is_admin);
    }

    #[tokio::test]
    async fn test_admin_user_extractor_not_admin() {
        let user = make_user(false);
        let mut request = Request::builder().body(()).unwrap();
        request.extensions_mut().insert(user);

        let (mut parts, _) = request.into_parts();
        let result = AdminUser::from_request_parts(&mut parts, &()).await;

        assert!(matches!(result, Err(AuthError::Forbidden)));
    }

    #[tokio::test]
    async fn test_admin_user_extractor_missing() {
        let request = Request::builder().body(()).unwrap();
        let (mut parts, _) = request.into_parts();

        let result = AdminUser::from_request_parts(&mut parts, &()).await;

        assert!(matches!(result, Err(AuthError::MissingToken)));
    }
}
