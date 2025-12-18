use jsonwebtoken::{Algorithm, DecodingKey};
use serde::Deserialize;
use std::collections::HashMap;
use tokio::sync::RwLock;
use tracing::{debug, warn};

use super::error::AuthError;

/// A single JWK from the JWKS response
#[derive(Debug, Clone, Deserialize)]
pub struct Jwk {
    pub kty: String,
    pub kid: Option<String>,
    #[serde(rename = "use")]
    pub use_: Option<String>,
    pub alg: Option<String>,
    // RSA key components
    pub n: Option<String>,
    pub e: Option<String>,
}

/// JWKS response from the OIDC provider
#[derive(Debug, Clone, Deserialize)]
pub struct JwksResponse {
    pub keys: Vec<Jwk>,
}

/// Cached JWKS with automatic refresh capability
pub struct JwksCache {
    keys: RwLock<HashMap<String, CachedKey>>,
    jwks_uri: String,
    client: reqwest::Client,
}

#[derive(Clone)]
struct CachedKey {
    decoding_key: DecodingKey,
    algorithm: Algorithm,
}

impl JwksCache {
    /// Create a new JWKS cache and fetch initial keys
    pub async fn new(jwks_uri: String, client: reqwest::Client) -> Result<Self, AuthError> {
        let cache = Self {
            keys: RwLock::new(HashMap::new()),
            jwks_uri,
            client,
        };

        cache.refresh().await?;
        Ok(cache)
    }

    /// Get a decoding key and algorithm by key ID
    pub async fn get_key(&self, kid: &str) -> Result<(DecodingKey, Algorithm), AuthError> {
        // First try to get from cache
        {
            let keys = self.keys.read().await;
            if let Some(cached) = keys.get(kid) {
                return Ok((cached.decoding_key.clone(), cached.algorithm));
            }
        }

        // Key not found, try refreshing once
        debug!("Key '{}' not found in cache, refreshing JWKS", kid);
        self.refresh().await?;

        // Try again after refresh
        let keys = self.keys.read().await;
        keys.get(kid)
            .map(|cached| (cached.decoding_key.clone(), cached.algorithm))
            .ok_or_else(|| AuthError::KeyNotFound(kid.to_string()))
    }

    /// Force refresh of JWKS from the provider
    pub async fn refresh(&self) -> Result<(), AuthError> {
        debug!("Fetching JWKS from {}", self.jwks_uri);

        let response = self
            .client
            .get(&self.jwks_uri)
            .send()
            .await
            .map_err(|e| AuthError::JwksFailed(format!("Request failed: {}", e)))?;

        if !response.status().is_success() {
            return Err(AuthError::JwksFailed(format!(
                "HTTP {}: {}",
                response.status(),
                self.jwks_uri
            )));
        }

        let jwks: JwksResponse = response
            .json()
            .await
            .map_err(|e| AuthError::JwksFailed(format!("Invalid JSON: {}", e)))?;

        let mut new_keys = HashMap::new();

        for jwk in jwks.keys {
            // Only process RSA keys with a key ID
            if jwk.kty != "RSA" {
                debug!("Skipping non-RSA key: {}", jwk.kty);
                continue;
            }

            let kid = match &jwk.kid {
                Some(kid) => kid.clone(),
                None => {
                    warn!("Skipping JWK without kid");
                    continue;
                }
            };

            // Only process signature keys
            if jwk.use_.as_deref() == Some("enc") {
                debug!("Skipping encryption key: {}", kid);
                continue;
            }

            // Determine algorithm
            let algorithm = match jwk.alg.as_deref() {
                Some("RS256") | None => Algorithm::RS256, // Default to RS256
                Some("RS384") => Algorithm::RS384,
                Some("RS512") => Algorithm::RS512,
                Some(alg) => {
                    warn!("Unsupported algorithm '{}' for key '{}'", alg, kid);
                    continue;
                }
            };

            // Extract RSA components
            let (n, e) = match (&jwk.n, &jwk.e) {
                (Some(n), Some(e)) => (n.as_str(), e.as_str()),
                _ => {
                    warn!("JWK '{}' missing n or e component", kid);
                    continue;
                }
            };

            // Create decoding key
            let decoding_key = match DecodingKey::from_rsa_components(n, e) {
                Ok(key) => key,
                Err(e) => {
                    warn!("Failed to create decoding key for '{}': {}", kid, e);
                    continue;
                }
            };

            debug!("Loaded key '{}' with algorithm {:?}", kid, algorithm);
            new_keys.insert(
                kid,
                CachedKey {
                    decoding_key,
                    algorithm,
                },
            );
        }

        if new_keys.is_empty() {
            return Err(AuthError::JwksFailed(
                "No valid RSA signing keys found in JWKS".to_string(),
            ));
        }

        debug!("Cached {} keys from JWKS", new_keys.len());

        // Update cache
        let mut keys = self.keys.write().await;
        *keys = new_keys;

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_jwks_response_deserialize() {
        let json = r#"{
            "keys": [
                {
                    "kty": "RSA",
                    "kid": "key-1",
                    "use": "sig",
                    "alg": "RS256",
                    "n": "test-n",
                    "e": "AQAB"
                }
            ]
        }"#;

        let jwks: JwksResponse = serde_json::from_str(json).unwrap();
        assert_eq!(jwks.keys.len(), 1);
        assert_eq!(jwks.keys[0].kid, Some("key-1".to_string()));
        assert_eq!(jwks.keys[0].alg, Some("RS256".to_string()));
    }

    #[test]
    fn test_jwks_response_multiple_keys() {
        let json = r#"{
            "keys": [
                {
                    "kty": "RSA",
                    "kid": "key-1",
                    "n": "n1",
                    "e": "e1"
                },
                {
                    "kty": "RSA",
                    "kid": "key-2",
                    "n": "n2",
                    "e": "e2"
                }
            ]
        }"#;

        let jwks: JwksResponse = serde_json::from_str(json).unwrap();
        assert_eq!(jwks.keys.len(), 2);
    }
}
