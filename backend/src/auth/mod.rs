//! Authentication module for OIDC/JWT token validation
//!
//! This module provides:
//! - OIDC discovery document fetching
//! - JWKS (JSON Web Key Set) caching and refresh
//! - JWT token validation
//! - User context extraction with role-based access control
//! - Axum middleware and extractors for authentication

mod discovery;
mod error;
mod extractors;
mod jwks;
mod middleware;
mod user;
mod validator;

pub use discovery::{fetch_discovery, OidcDiscovery};
pub use error::AuthError;
pub use extractors::{AdminUser, AuthenticatedUser};
pub use jwks::JwksCache;
pub use middleware::auth_middleware;
pub use user::User;
pub use validator::{TokenClaims, TokenValidator};

use std::sync::Arc;

/// Shared state for authentication middleware
#[derive(Clone)]
pub struct AuthState {
    /// Token validator with JWKS cache
    pub validator: Arc<TokenValidator>,
    /// Dot-separated path to roles claim in JWT (e.g., "realm_access.roles")
    pub role_claim_path: String,
    /// Role name that grants admin access
    pub admin_role: String,
}

impl AuthState {
    /// Create a new AuthState
    pub fn new(
        validator: Arc<TokenValidator>,
        role_claim_path: String,
        admin_role: String,
    ) -> Self {
        Self {
            validator,
            role_claim_path,
            admin_role,
        }
    }
}

/// Initialize authentication from OIDC issuer URL
///
/// This performs OIDC discovery and fetches the initial JWKS.
pub async fn init_auth(
    issuer_url: &str,
    audience: &str,
    role_claim_path: &str,
    admin_role: &str,
) -> Result<AuthState, AuthError> {
    let client = reqwest::Client::new();

    // Fetch OIDC discovery document
    let discovery = fetch_discovery(&client, issuer_url).await?;

    // Initialize JWKS cache
    let jwks = Arc::new(JwksCache::new(discovery.jwks_uri, client).await?);

    // Create token validator
    let validator = Arc::new(TokenValidator::new(
        jwks,
        discovery.issuer,
        audience.to_string(),
    ));

    Ok(AuthState::new(
        validator,
        role_claim_path.to_string(),
        admin_role.to_string(),
    ))
}

// Note: Integration tests for init_auth require a running OIDC server.
// Unit tests are in the individual submodules (discovery, jwks, validator, etc.)
