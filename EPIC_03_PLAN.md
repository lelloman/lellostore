# Epic 3: Backend Authentication - Implementation Plan

## Overview

**Goal**: Implement OIDC token validation and role-based access control for the backend API.

**Deliverable**: Authentication middleware that validates OIDC access tokens and enforces role-based access control, with admin-only routes protected.

**Dependencies**: Epic 1 (Backend Foundation) - ✅ Complete

---

## Architecture Decisions

### JWT Validation Approach

**Decision**: Use `jsonwebtoken` crate with async JWKS fetching.

**Rationale**:
- `jsonwebtoken` is the most mature JWT library for Rust
- Support for RS256, RS384, RS512 (common OIDC algorithms)
- We'll fetch JWKS from the OIDC provider's discovery endpoint
- Cache JWKS with automatic refresh on signature failure

**Alternative considered**: `alcoholic_jwt` - simpler but less maintained.

### OIDC Discovery

**Decision**: Fetch discovery document at startup, cache JWKS.

**Flow**:
1. On startup: fetch `{issuer}/.well-known/openid-configuration`
2. Extract `jwks_uri` from discovery document
3. Fetch JWKS from `jwks_uri`
4. Cache keys in memory
5. On signature validation failure: refresh JWKS once and retry

### Token Validation Strategy

**Decision**: Validate tokens in Axum middleware layer.

**Validated claims**:
- `exp` - Token not expired
- `iat` - Token not issued in the future
- `iss` - Issuer matches configured OIDC issuer
- `aud` - Audience includes configured audience

**Extracted claims**:
- `sub` - User subject (unique identifier)
- `email` - User email (if present)
- `roles` or custom claim - For admin detection

### Role Claim Configuration

**Decision**: Support configurable role claim path.

Common patterns:
- Keycloak: `realm_access.roles` or `resource_access.{client}.roles`
- Auth0: `https://example.com/roles` (custom claim)
- Authentik: `groups` or custom claim

We'll support:
- Simple claim: `roles` → token["roles"]
- Nested claim: `realm_access.roles` → token["realm_access"]["roles"]

---

## Tasks

### 1. Dependencies

#### 1.1 Add New Dependencies

```toml
[dependencies]
# JWT validation
jsonwebtoken = "9"

# HTTP client for JWKS fetching
reqwest = { version = "0.12", features = ["json", "rustls-tls"], default-features = false }

# Async synchronization for JWKS cache
tokio = { version = "1", features = ["sync"] }  # Already have this, need RwLock
```

---

### 2. OIDC Discovery & JWKS

#### 2.1 OIDC Discovery Client (`src/auth/discovery.rs`)

```rust
#[derive(Debug, Clone, Deserialize)]
pub struct OidcDiscovery {
    pub issuer: String,
    pub jwks_uri: String,
    pub authorization_endpoint: String,
    pub token_endpoint: String,
}

pub async fn fetch_discovery(issuer_url: &str) -> Result<OidcDiscovery, AuthError>;
```

#### 2.2 JWKS Fetching and Caching (`src/auth/jwks.rs`)

```rust
pub struct JwksCache {
    keys: RwLock<HashMap<String, DecodingKey>>,
    jwks_uri: String,
    client: reqwest::Client,
}

impl JwksCache {
    /// Create new cache and fetch initial JWKS
    pub async fn new(jwks_uri: String) -> Result<Self, AuthError>;

    /// Get decoding key by key ID (kid)
    pub async fn get_key(&self, kid: &str) -> Result<DecodingKey, AuthError>;

    /// Force refresh of JWKS
    pub async fn refresh(&self) -> Result<(), AuthError>;
}
```

**JWKS Response Format**:
```json
{
  "keys": [
    {
      "kty": "RSA",
      "kid": "key-id-1",
      "use": "sig",
      "alg": "RS256",
      "n": "...",
      "e": "AQAB"
    }
  ]
}
```

#### 2.3 Error Types (`src/auth/error.rs`)

```rust
#[derive(Debug, thiserror::Error)]
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
```

---

### 3. JWT Validation

#### 3.1 Token Validator (`src/auth/validator.rs`)

```rust
#[derive(Debug, Clone, Deserialize)]
pub struct TokenClaims {
    pub sub: String,
    pub iss: String,
    pub aud: OneOrMany<String>,  // Can be string or array
    pub exp: u64,
    pub iat: u64,
    #[serde(default)]
    pub email: Option<String>,
    // Capture all other claims for role extraction
    #[serde(flatten)]
    pub extra: HashMap<String, serde_json::Value>,
}

pub struct TokenValidator {
    jwks: Arc<JwksCache>,
    issuer: String,
    audience: String,
}

impl TokenValidator {
    pub fn new(jwks: Arc<JwksCache>, issuer: String, audience: String) -> Self;

    /// Validate token and return claims
    pub async fn validate(&self, token: &str) -> Result<TokenClaims, AuthError>;
}
```

#### 3.2 Validation Steps

1. **Decode header** - Extract `kid` (key ID) and `alg` (algorithm)
2. **Get decoding key** - From JWKS cache by `kid`
3. **Verify signature** - Using jsonwebtoken with the key
4. **Validate claims**:
   - `exp > now` (not expired)
   - `iat <= now` (not issued in future, with small clock skew allowance)
   - `iss == configured_issuer`
   - `aud contains configured_audience`
5. **On signature failure** - Refresh JWKS once and retry

---

### 4. User Context & Role Extraction

#### 4.1 User Context (`src/auth/user.rs`)

```rust
#[derive(Debug, Clone)]
pub struct User {
    pub subject: String,
    pub email: Option<String>,
    pub roles: Vec<String>,
    pub is_admin: bool,
}

impl User {
    /// Extract user from validated token claims
    pub fn from_claims(claims: &TokenClaims, role_claim_path: &str, admin_role: &str) -> Self;
}
```

#### 4.2 Role Extraction Logic

Support nested claim paths like `realm_access.roles`:

```rust
fn extract_roles(claims: &HashMap<String, Value>, path: &str) -> Vec<String> {
    let parts: Vec<&str> = path.split('.').collect();
    let mut current: &Value = // start with claims as Value

    for part in parts {
        current = current.get(part)?;
    }

    // Handle both array of strings and single string
    match current {
        Value::Array(arr) => arr.iter().filter_map(|v| v.as_str()).map(String::from).collect(),
        Value::String(s) => vec![s.clone()],
        _ => vec![],
    }
}
```

---

### 5. Axum Middleware & Extractors

#### 5.1 Auth State (`src/auth/mod.rs`)

```rust
#[derive(Clone)]
pub struct AuthState {
    pub validator: Arc<TokenValidator>,
    pub role_claim_path: String,
    pub admin_role: String,
}

impl AuthState {
    pub async fn new(config: &OidcConfig) -> Result<Self, AuthError>;
}
```

#### 5.2 Auth Middleware (`src/auth/middleware.rs`)

```rust
/// Middleware that validates tokens and attaches User to request extensions
pub async fn auth_middleware(
    State(auth): State<AuthState>,
    mut request: Request,
    next: Next,
) -> Result<Response, AuthError>;
```

**Flow**:
1. Extract `Authorization: Bearer <token>` header
2. Validate token using `TokenValidator`
3. Extract `User` from claims
4. Insert `User` into request extensions
5. Call next handler

#### 5.3 User Extractor (`src/auth/extractors.rs`)

```rust
/// Extractor for authenticated user (any valid token)
pub struct AuthenticatedUser(pub User);

#[async_trait]
impl<S> FromRequestParts<S> for AuthenticatedUser {
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        parts.extensions
            .get::<User>()
            .cloned()
            .map(AuthenticatedUser)
            .ok_or(AuthError::MissingToken)
    }
}

/// Extractor for admin user (valid token + admin role)
pub struct AdminUser(pub User);

#[async_trait]
impl<S> FromRequestParts<S> for AdminUser {
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        let user = parts.extensions
            .get::<User>()
            .cloned()
            .ok_or(AuthError::MissingToken)?;

        if !user.is_admin {
            return Err(AuthError::Forbidden);
        }

        Ok(AdminUser(user))
    }
}
```

#### 5.4 Error Response Conversion

```rust
impl IntoResponse for AuthError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            AuthError::MissingToken |
            AuthError::InvalidAuthHeader |
            AuthError::TokenInvalid(_) |
            AuthError::TokenExpired => (StatusCode::UNAUTHORIZED, "Unauthorized"),

            AuthError::Forbidden => (StatusCode::FORBIDDEN, "Forbidden"),

            // Don't leak internal errors
            _ => (StatusCode::INTERNAL_SERVER_ERROR, "Authentication error"),
        };

        let body = json!({ "error": message });
        (status, Json(body)).into_response()
    }
}
```

---

### 6. Configuration Updates

#### 6.1 Extended OIDC Config

```rust
#[derive(Debug, Clone)]
pub struct OidcConfig {
    pub issuer_url: String,
    pub audience: String,
    pub admin_role: String,
    pub role_claim_path: String,  // NEW: e.g., "realm_access.roles" or "roles"
}
```

#### 6.2 Environment Variables

```env
# Existing
OIDC_ISSUER_URL=https://auth.example.com/realms/myrealm
OIDC_AUDIENCE=lellostore

# New/Updated
OIDC_ADMIN_ROLE=admin
OIDC_ROLE_CLAIM_PATH=realm_access.roles  # Default: "roles"
```

---

### 7. Router Integration

#### 7.1 Update API Router (`src/api/routes.rs`)

```rust
pub fn create_router(state: AppState, auth: AuthState) -> Router {
    let public_routes = Router::new()
        .route("/health", get(health_check));

    let user_routes = Router::new()
        .route("/api/apps", get(list_apps))
        .route("/api/apps/:package_name", get(get_app))
        .route("/api/apps/:package_name/icon", get(get_icon))
        .route("/api/apps/:package_name/versions/:version_code/apk", get(download_apk))
        .layer(middleware::from_fn_with_state(auth.clone(), auth_middleware));

    let admin_routes = Router::new()
        .route("/api/admin/apps", post(upload_app))
        .route("/api/admin/apps/:package_name", put(update_app))
        .route("/api/admin/apps/:package_name", delete(delete_app))
        .route("/api/admin/apps/:package_name/versions/:version_code", delete(delete_version))
        .layer(middleware::from_fn_with_state(auth.clone(), auth_middleware));

    Router::new()
        .merge(public_routes)
        .merge(user_routes)
        .merge(admin_routes)
        .with_state(state)
}
```

**Note**: Admin role checking is done via the `AdminUser` extractor in handlers, not middleware. This gives clearer error messages and more flexibility.

---

### 8. Module Organization

#### 8.1 Auth Module Structure

```
src/auth/
├── mod.rs          # Public exports, AuthState
├── error.rs        # AuthError enum
├── discovery.rs    # OIDC discovery document fetching
├── jwks.rs         # JWKS fetching and caching
├── validator.rs    # JWT validation
├── user.rs         # User struct and role extraction
├── middleware.rs   # Axum auth middleware
└── extractors.rs   # AuthenticatedUser, AdminUser extractors
```

#### 8.2 Update `src/lib.rs`

```rust
pub mod api;
pub mod auth;  // NEW
pub mod config;
pub mod db;
pub mod error;
pub mod metrics;
pub mod services;
```

---

### 9. Testing

#### 9.1 Unit Tests

**JWKS Parsing Tests** (`src/auth/jwks.rs`):
- Parse valid JWKS response
- Handle missing `kid`
- Handle unsupported algorithms

**Token Validation Tests** (`src/auth/validator.rs`):
- Valid token accepted
- Expired token rejected
- Wrong issuer rejected
- Wrong audience rejected
- Invalid signature rejected

**Role Extraction Tests** (`src/auth/user.rs`):
- Extract roles from simple claim
- Extract roles from nested claim path
- Handle missing roles claim
- Correctly identify admin user

#### 9.2 Integration Tests

Create `tests/auth.rs`:

```rust
#[tokio::test]
async fn test_request_without_token() {
    // Request to protected endpoint without Authorization header
    // Expect 401 Unauthorized
}

#[tokio::test]
async fn test_request_with_invalid_token() {
    // Request with malformed/invalid JWT
    // Expect 401 Unauthorized
}

#[tokio::test]
async fn test_request_with_expired_token() {
    // Request with expired JWT
    // Expect 401 Unauthorized
}

#[tokio::test]
async fn test_user_access_to_user_endpoint() {
    // Request with valid user token (no admin role)
    // To user endpoint (GET /api/apps)
    // Expect 200 OK
}

#[tokio::test]
async fn test_user_access_to_admin_endpoint() {
    // Request with valid user token (no admin role)
    // To admin endpoint (POST /api/admin/apps)
    // Expect 403 Forbidden
}

#[tokio::test]
async fn test_admin_access_to_admin_endpoint() {
    // Request with valid admin token
    // To admin endpoint
    // Expect 200 OK (or appropriate success)
}
```

#### 9.3 Mock OIDC Server

For integration tests, create a simple mock:

```rust
// tests/common/mock_oidc.rs

pub struct MockOidcServer {
    pub issuer: String,
    pub jwks_uri: String,
    signing_key: RS256KeyPair,
}

impl MockOidcServer {
    pub fn start() -> Self;

    pub fn generate_token(&self, claims: TokenClaims) -> String;

    pub fn discovery_endpoint(&self) -> impl Filter;
    pub fn jwks_endpoint(&self) -> impl Filter;
}
```

---

## Verification Checklist

### Build Verification
- [ ] `cargo build` completes without errors
- [ ] `cargo clippy` passes with no warnings
- [ ] `cargo fmt --check` passes
- [ ] `cargo test` passes (unit tests)

### Functional Verification

**OIDC Discovery**:
- [ ] Can fetch discovery document from issuer URL
- [ ] Correctly extracts JWKS URI

**JWKS Handling**:
- [ ] Can fetch and parse JWKS
- [ ] Keys cached correctly
- [ ] Can refresh JWKS on demand

**Token Validation**:
- [ ] Valid token accepted
- [ ] Expired token rejected (401)
- [ ] Invalid signature rejected (401)
- [ ] Wrong issuer rejected (401)
- [ ] Wrong audience rejected (401)

**Role-Based Access**:
- [ ] Roles extracted from configured claim path
- [ ] Admin role correctly identified
- [ ] Non-admin users get 403 on admin endpoints
- [ ] Admin users can access admin endpoints

**Error Handling**:
- [ ] Missing token → 401 with JSON body
- [ ] Invalid token → 401 with JSON body
- [ ] Forbidden → 403 with JSON body
- [ ] No sensitive information leaked in errors

---

## Acceptance Criteria

1. **All API endpoints require authentication** (except `/health`)
2. **Token validation is complete**: signature, expiry, issuer, audience
3. **Admin endpoints protected**: non-admin users get 403
4. **JWKS auto-refresh**: handles key rotation gracefully
5. **Clear error responses**: 401 for auth failures, 403 for permission failures
6. **Configurable**: role claim path, admin role name via environment

---

## Notes

### Testing Without Real OIDC Provider

For development/testing, you can:

1. **Use a local Keycloak** in Docker:
   ```bash
   docker run -p 8081:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin quay.io/keycloak/keycloak:latest start-dev
   ```

2. **Generate test JWTs** using the mock server in tests

3. **Temporarily disable auth** with a feature flag (not recommended for production)

### Common OIDC Providers

| Provider | Role Claim Path | Notes |
|----------|-----------------|-------|
| Keycloak | `realm_access.roles` | Realm roles |
| Keycloak | `resource_access.{client}.roles` | Client roles |
| Auth0 | Custom (e.g., `https://myapp/roles`) | Requires rule/action |
| Authentik | `groups` | Groups as roles |
| Azure AD | `roles` | App roles |

### Clock Skew

Allow 60 seconds of clock skew for `iat` and `exp` validation to handle minor time differences between servers.

---

## Progress Tracking

| Task | Status | Notes |
|------|--------|-------|
| 1.1 Add dependencies | Not Started | |
| 2.1 OIDC discovery client | Not Started | |
| 2.2 JWKS fetching and caching | Not Started | |
| 2.3 Error types | Not Started | |
| 3.1 Token validator | Not Started | |
| 3.2 Validation logic | Not Started | |
| 4.1 User context | Not Started | |
| 4.2 Role extraction | Not Started | |
| 5.1 Auth state | Not Started | |
| 5.2 Auth middleware | Not Started | |
| 5.3 User extractors | Not Started | |
| 5.4 Error responses | Not Started | |
| 6.1 Config updates | Not Started | |
| 7.1 Router integration | Not Started | |
| 8.1 Module organization | Not Started | |
| 9.1 Unit tests | Not Started | |
| 9.2 Integration tests | Not Started | |
| 9.3 Mock OIDC server | Not Started | |
| Verification | Not Started | |
