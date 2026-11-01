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
pub use extractors::{AdminOrPublisher, AdminUser, AuthenticatedUser};
pub use jwks::JwksCache;
pub use middleware::{auth_middleware, require_auth_ready};
pub use user::{PublishScope, User};
pub use validator::{TokenClaims, TokenValidator};

use sqlx::SqlitePool;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

const OIDC_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const OIDC_REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

/// Shared state for authentication middleware
#[derive(Clone)]
pub struct AuthState {
    /// Token validator with JWKS cache
    pub validator: Arc<TokenValidator>,
    /// Dot-separated path to roles claim in JWT (e.g., "realm_access.roles")
    pub role_claim_path: String,
    /// Role name that grants admin access
    pub admin_role: String,
    /// The store database. It holds the local user directory, populated only
    /// after successful OIDC token validation, and resolves publish tokens.
    /// Without it, publish tokens are rejected.
    pub user_registry: Option<SqlitePool>,
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
            user_registry: None,
        }
    }

    pub fn with_user_registry(mut self, pool: SqlitePool) -> Self {
        self.user_registry = Some(pool);
        self
    }
}

/// Authentication state that becomes available once OIDC discovery succeeds.
///
/// The store starts serving before the issuer is reachable. Until the slot is
/// filled, every authenticated route fails closed with 503; afterwards it is
/// fixed for the lifetime of the process.
#[derive(Clone, Default)]
pub struct AuthSlot(Arc<OnceLock<AuthState>>);

impl AuthSlot {
    /// A slot that is never filled: authentication stays unavailable.
    pub fn empty() -> Self {
        Self::default()
    }

    /// A slot that is already filled.
    pub fn ready(auth: AuthState) -> Self {
        let slot = Self::default();
        slot.fill(auth);
        slot
    }

    pub fn get(&self) -> Option<&AuthState> {
        self.0.get()
    }

    pub fn is_ready(&self) -> bool {
        self.0.get().is_some()
    }

    /// Fill the slot. Returns false if it was already filled.
    pub fn fill(&self, auth: AuthState) -> bool {
        self.0.set(auth).is_ok()
    }
}

const BACKGROUND_INITIAL_DELAY: Duration = Duration::from_secs(1);
const BACKGROUND_MAX_DELAY: Duration = Duration::from_secs(60);

/// Keep retrying OIDC discovery until it succeeds, then fill `slot`.
///
/// Waits start at one second and double up to a one-minute cap, so an issuer
/// that is down when the store boots is picked up shortly after it returns,
/// without a restart.
pub async fn init_auth_until_ready(
    slot: AuthSlot,
    issuer_url: String,
    audience: String,
    role_claim_path: String,
    admin_role: String,
    user_registry: SqlitePool,
) {
    let mut delay = BACKGROUND_INITIAL_DELAY;
    let mut attempt: u64 = 1;
    loop {
        match init_auth_with_retries(&issuer_url, &audience, &role_claim_path, &admin_role, 0, 0)
            .await
        {
            Ok(auth) => {
                slot.fill(auth.with_user_registry(user_registry));
                tracing::info!(
                    "Authentication initialized with issuer: {} (attempt {})",
                    issuer_url,
                    attempt
                );
                return;
            }
            Err(error) => {
                tracing::warn!(
                    "Authentication unavailable (attempt {}): {}. Retrying in {}s.",
                    attempt,
                    error,
                    delay.as_secs()
                );
            }
        }
        tokio::time::sleep(delay).await;
        delay = (delay * 2).min(BACKGROUND_MAX_DELAY);
        attempt += 1;
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
    let client = reqwest::Client::builder()
        .connect_timeout(OIDC_CONNECT_TIMEOUT)
        .timeout(OIDC_REQUEST_TIMEOUT)
        .build()
        .map_err(|error| {
            AuthError::DiscoveryFailed(format!("HTTP client setup failed: {error}"))
        })?;
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
