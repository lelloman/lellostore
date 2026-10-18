use simple_server::auth::Access;
use simple_server::web::{extract::FromRequestParts, http::request::Parts};
use tracing::warn;

use super::error::AuthError;
use super::user::User;
use crate::error::AppError;

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

impl<S> FromRequestParts<S> for AuthenticatedUser
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
        if let Some(scope) = &user.publish_scope {
            warn!(token_id = %scope.token_id, path = %parts.uri.path(), "Authorization denied: publish tokens cannot use catalog routes");
            return Err(AuthError::Forbidden);
        }
        Ok(AuthenticatedUser(user))
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

impl<S> FromRequestParts<S> for AdminUser
where
    S: Send + Sync,
{
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        let access = Access::new(|parts: &Parts| {
            parts.extensions.get::<User>().cloned().ok_or(AuthError::MissingToken)
        })
        .with_check(|user, parts| {
            if let Some(scope) = &user.publish_scope {
                warn!(token_id = %scope.token_id, path = %parts.uri.path(), "Authorization denied: publish token used on an administrator-only route");
                Err(AuthError::Forbidden)
            } else if user.is_admin {
                Ok(())
            } else {
                warn!(user = %user.subject, path = %parts.uri.path(), "Authorization denied: user is not admin");
                Err(AuthError::Forbidden)
            }
        });
        access.evaluate(parts).map(AdminUser)
    }
}

/// Extractor for the few publishing handlers that a publish token may reach.
///
/// It admits an administrator or any publish-token principal, but yields no
/// [`User`] by itself: the handler must name what it is about to touch through
/// one of the `for_*` methods, which enforce the token's package scope. Every
/// other administrative handler uses [`AdminUser`], which refuses publish
/// tokens.
#[derive(Debug, Clone)]
pub struct AdminOrPublisher {
    user: User,
    path: String,
}

impl AdminOrPublisher {
    fn refuse(&self, token_id: &str, reason: &str) -> AppError {
        warn!(token_id = %token_id, path = %self.path, reason, "Authorization denied for publish token");
        AppError::Forbidden
    }

    /// Admit an administrator, or a publish token for exactly `package`.
    pub fn for_package(self, package: &str) -> Result<User, AppError> {
        match &self.user.publish_scope {
            None => Ok(self.user),
            Some(scope) if scope.package == package => Ok(self.user),
            Some(scope) => Err(self.refuse(&scope.token_id, "package outside token scope")),
        }
    }

    /// Admit an administrator or any publish token to an upload whose package
    /// is unknown until the artifact is parsed. The caller must carry
    /// `publish_scope` into the upload so the parsed package is enforced.
    pub fn for_upload(self) -> User {
        self.user
    }

    /// Admit an administrator, or the publish token that created the job.
    pub fn for_upload_job(self, job_token: Option<&str>) -> Result<User, AppError> {
        match &self.user.publish_scope {
            None => Ok(self.user),
            Some(scope) if job_token == Some(scope.token_id.as_str()) => Ok(self.user),
            Some(scope) => Err(self.refuse(&scope.token_id, "upload created by another principal")),
        }
    }
}

impl<S> FromRequestParts<S> for AdminOrPublisher
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
        if user.is_admin || user.publish_scope.is_some() {
            Ok(Self {
                user,
                path: parts.uri.path().to_owned(),
            })
        } else {
            warn!(user = %user.subject, path = %parts.uri.path(), "Authorization denied: user is not admin");
            Err(AuthError::Forbidden)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use simple_server::web::http::Request;

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
            publish_scope: None,
            device_token_id: None,
        }
    }

    fn parts_with(user: User, path: &str) -> Parts {
        let mut request = Request::builder().uri(path).body(()).unwrap();
        request.extensions_mut().insert(user);
        request.into_parts().0
    }

    #[tokio::test]
    async fn publish_tokens_are_refused_by_admin_and_catalog_extractors() {
        let token = User::publisher("t1".into(), "com.example.app".into());
        let mut parts = parts_with(token.clone(), "/api/admin/users");
        assert!(matches!(
            AdminUser::from_request_parts(&mut parts, &()).await,
            Err(AuthError::Forbidden)
        ));
        let mut parts = parts_with(token, "/api/apps");
        assert!(matches!(
            AuthenticatedUser::from_request_parts(&mut parts, &()).await,
            Err(AuthError::Forbidden)
        ));
    }

    #[tokio::test]
    async fn device_tokens_are_catalog_users_but_never_admins_or_publishers() {
        let device = User::device("d1".into());
        let mut parts = parts_with(device.clone(), "/api/apps");
        let user = AuthenticatedUser::from_request_parts(&mut parts, &())
            .await
            .unwrap();
        assert_eq!(user.0.subject, "device-token:d1");
        let mut parts = parts_with(device.clone(), "/api/admin/users");
        assert!(matches!(
            AdminUser::from_request_parts(&mut parts, &()).await,
            Err(AuthError::Forbidden)
        ));
        let mut parts = parts_with(device, "/api/admin/apps");
        assert!(matches!(
            AdminOrPublisher::from_request_parts(&mut parts, &()).await,
            Err(AuthError::Forbidden)
        ));
    }

    #[tokio::test]
    async fn admin_or_publisher_enforces_package_and_job_scope() {
        let token = User::publisher("t1".into(), "com.example.app".into());
        let extract = |user: User| async move {
            let mut parts = parts_with(user, "/api/admin/apps/x");
            AdminOrPublisher::from_request_parts(&mut parts, &()).await
        };
        let publisher = extract(token.clone()).await.unwrap();
        assert!(publisher.clone().for_package("com.example.app").is_ok());
        assert!(matches!(
            publisher.clone().for_package("com.example.other"),
            Err(AppError::Forbidden)
        ));
        assert!(publisher.clone().for_upload_job(Some("t1")).is_ok());
        assert!(publisher.clone().for_upload_job(Some("t2")).is_err());
        assert!(publisher.clone().for_upload_job(None).is_err());
        assert!(publisher.for_upload().publish_scope.is_some());

        let admin = extract(make_user(true)).await.unwrap();
        assert!(admin.clone().for_package("anything").is_ok());
        assert!(admin.for_upload_job(Some("t2")).is_ok());
        assert!(matches!(
            extract(make_user(false)).await,
            Err(AuthError::Forbidden)
        ));
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
