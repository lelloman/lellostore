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
/// Retries with exponential backoff to handle startup race conditions
/// where the auth server may not be immediately available.
pub async fn init_auth(
    issuer_url: &str,
    audience: &str,
    role_claim_path: &str,
    admin_role: &str,
) -> Result<AuthState, AuthError> {
    init_auth_with_retries(issuer_url, audience, role_claim_path, admin_role, 5, 1000).await
}

/// Initialize authentication with configurable retry parameters
async fn init_auth_with_retries(
    issuer_url: &str,
    audience: &str,
    role_claim_path: &str,
    admin_role: &str,
    max_retries: u32,
    initial_delay_ms: u64,
) -> Result<AuthState, AuthError> {
    let client = reqwest::Client::new();
    let mut last_error = None;

    for attempt in 0..=max_retries {
        if attempt > 0 {
            let delay_ms = initial_delay_ms * 2u64.pow(attempt - 1);
            tracing::info!(
                "Retrying OIDC discovery in {}ms (attempt {}/{})",
                delay_ms,
                attempt + 1,
                max_retries + 1
            );
            tokio::time::sleep(std::time::Duration::from_millis(delay_ms)).await;
        }

        // Fetch OIDC discovery document
        match fetch_discovery(&client, issuer_url).await {
            Ok(discovery) => {
                // Initialize JWKS cache
                match JwksCache::new(discovery.jwks_uri.clone(), client.clone()).await {
                    Ok(jwks) => {
                        let validator = Arc::new(TokenValidator::new(
                            Arc::new(jwks),
                            discovery.issuer,
                            audience.to_string(),
                        ));
                        return Ok(AuthState::new(
                            validator,
                            role_claim_path.to_string(),
                            admin_role.to_string(),
                        ));
                    }
                    Err(e) => {
                        tracing::warn!("JWKS fetch failed: {}", e);
                        last_error = Some(e);
                    }
                }
            }
            Err(e) => {
                tracing::warn!("OIDC discovery failed: {}", e);
                last_error = Some(e);
            }
        }
    }

    Err(last_error.unwrap_or_else(|| AuthError::DiscoveryFailed("No attempts made".to_string())))
}

// Note: Integration tests for init_auth require a running OIDC server.
// Unit tests are in the individual submodules (discovery, jwks, validator, etc.)
