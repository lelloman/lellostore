use jsonwebtoken::{decode, decode_header, Validation};
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::Arc;
use tracing::debug;

use super::error::AuthError;
use super::jwks::JwksCache;

/// Claims extracted from a validated JWT
#[derive(Debug, Clone, Deserialize)]
pub struct TokenClaims {
    /// Subject (unique user identifier)
    pub sub: String,
    /// Issuer
    pub iss: String,
    /// Audience (can be string or array)
    #[serde(deserialize_with = "deserialize_audience")]
    pub aud: Vec<String>,
    /// Expiration time (Unix timestamp)
    pub exp: u64,
    /// Issued at time (Unix timestamp)
    pub iat: u64,
    /// User email (optional)
    #[serde(default)]
    pub email: Option<String>,
    /// All other claims for role extraction
    #[serde(flatten)]
    pub extra: HashMap<String, serde_json::Value>,
}

/// Custom deserializer for audience which can be a string or array
fn deserialize_audience<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum OneOrMany {
        One(String),
        Many(Vec<String>),
    }

    match OneOrMany::deserialize(deserializer)? {
        OneOrMany::One(s) => Ok(vec![s]),
        OneOrMany::Many(v) => Ok(v),
    }
}

/// JWT Token validator using JWKS
pub struct TokenValidator {
    jwks: Arc<JwksCache>,
    issuer: String,
    audience: String,
}

impl TokenValidator {
    pub fn new(jwks: Arc<JwksCache>, issuer: String, audience: String) -> Self {
        Self {
            jwks,
            issuer,
            audience,
        }
    }

    /// Validate a JWT token and return its claims
    pub async fn validate(&self, token: &str) -> Result<TokenClaims, AuthError> {
        // 1. Decode header to get key ID
        let header = decode_header(token)
            .map_err(|e| AuthError::TokenInvalid(format!("Invalid token header: {}", e)))?;

        let kid = header
            .kid
            .ok_or_else(|| AuthError::TokenInvalid("Token missing 'kid' header".to_string()))?;

        debug!("Validating token with kid: {}", kid);

        // 2. Get decoding key from cache
        let (decoding_key, algorithm) = self.jwks.get_key(&kid).await?;

        // 3. Set up validation
        let mut validation = Validation::new(algorithm);
        validation.set_issuer(&[&self.issuer]);
        validation.set_audience(&[&self.audience]);
        // Allow 60 seconds of clock skew
        validation.leeway = 60;

        // 4. Decode and validate token
        let token_data = decode::<TokenClaims>(token, &decoding_key, &validation).map_err(|e| {
            use jsonwebtoken::errors::ErrorKind;
            match e.kind() {
                ErrorKind::ExpiredSignature => AuthError::TokenExpired,
                ErrorKind::InvalidIssuer => AuthError::TokenInvalid("Invalid issuer".to_string()),
                ErrorKind::InvalidAudience => {
                    AuthError::TokenInvalid("Invalid audience".to_string())
                }
                ErrorKind::InvalidSignature => {
                    AuthError::TokenInvalid("Invalid signature".to_string())
                }
                _ => AuthError::TokenInvalid(format!("Token validation failed: {}", e)),
            }
        })?;

        debug!("Token validated for subject: {}", token_data.claims.sub);
        Ok(token_data.claims)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_claims_deserialize_single_audience() {
        let json = r#"{
            "sub": "user123",
            "iss": "https://auth.example.com",
            "aud": "my-app",
            "exp": 1700000000,
            "iat": 1699999000,
            "email": "user@example.com"
        }"#;

        let claims: TokenClaims = serde_json::from_str(json).unwrap();
        assert_eq!(claims.sub, "user123");
        assert_eq!(claims.aud, vec!["my-app"]);
        assert_eq!(claims.email, Some("user@example.com".to_string()));
    }

    #[test]
    fn test_claims_deserialize_array_audience() {
        let json = r#"{
            "sub": "user123",
            "iss": "https://auth.example.com",
            "aud": ["app1", "app2"],
            "exp": 1700000000,
            "iat": 1699999000
        }"#;

        let claims: TokenClaims = serde_json::from_str(json).unwrap();
        assert_eq!(claims.aud, vec!["app1", "app2"]);
        assert_eq!(claims.email, None);
    }

    #[test]
    fn test_claims_deserialize_extra_claims() {
        let json = r#"{
            "sub": "user123",
            "iss": "https://auth.example.com",
            "aud": "my-app",
            "exp": 1700000000,
            "iat": 1699999000,
            "realm_access": {
                "roles": ["user", "admin"]
            }
        }"#;

        let claims: TokenClaims = serde_json::from_str(json).unwrap();
        assert!(claims.extra.contains_key("realm_access"));
    }
}
