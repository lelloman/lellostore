use jsonwebtoken::{Algorithm, DecodingKey};
use serde::Deserialize;
use std::collections::HashMap;
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, RwLock};
use tracing::{debug, warn};

use super::error::AuthError;

const UNKNOWN_KEY_REFRESH_COOLDOWN: Duration = Duration::from_secs(30);
const JWKS_REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_KEY_ID_LENGTH: usize = 256;

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
    /// Mutex to prevent concurrent JWKS refreshes (avoids thundering herd)
    refresh_lock: Mutex<()>,
    last_unknown_key_refresh: Mutex<Option<Instant>>,
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
            refresh_lock: Mutex::new(()),
            last_unknown_key_refresh: Mutex::new(None),
            jwks_uri,
            client,
        };

        cache.refresh_internal().await?;
        Ok(cache)
    }

    /// Get a decoding key and algorithm by key ID
    pub async fn get_key(&self, kid: &str) -> Result<(DecodingKey, Algorithm), AuthError> {
        if kid.len() > MAX_KEY_ID_LENGTH {
            return Err(AuthError::KeyNotFound("invalid key id".to_string()));
        }

        // First try to get from cache
        {
            let keys = self.keys.read().await;
            if let Some(cached) = keys.get(kid) {
                return Ok((cached.decoding_key.clone(), cached.algorithm));
            }
        }

        // Key not found, acquire refresh lock to prevent thundering herd
        debug!("Key '{}' not found in cache, acquiring refresh lock", kid);
        let _lock = self.refresh_lock.lock().await;

        // Check again - another request may have refreshed while we waited
        {
            let keys = self.keys.read().await;
            if let Some(cached) = keys.get(kid) {
                debug!(
                    "Key '{}' found after acquiring lock (refreshed by another request)",
                    kid
                );
                return Ok((cached.decoding_key.clone(), cached.algorithm));
            }
        }

        // A different unknown key must not force another network request during
        // the cooldown. Explicit refreshes remain available for operators.
        {
            let mut last_refresh = self.last_unknown_key_refresh.lock().await;
            if last_refresh.is_some_and(|instant| instant.elapsed() < UNKNOWN_KEY_REFRESH_COOLDOWN)
            {
                return Err(AuthError::KeyNotFound(kid.to_string()));
            }
            *last_refresh = Some(Instant::now());
        }

        // Still not found, actually refresh
        debug!("Key '{}' still not found, refreshing JWKS", kid);
        self.refresh_internal().await?;

        // Try again after refresh
        let keys = self.keys.read().await;
        keys.get(kid)
            .map(|cached| (cached.decoding_key.clone(), cached.algorithm))
            .ok_or_else(|| AuthError::KeyNotFound(kid.to_string()))
    }

    /// Force refresh of JWKS from the provider (public, acquires lock)
    pub async fn refresh(&self) -> Result<(), AuthError> {
        let _lock = self.refresh_lock.lock().await;
        self.refresh_internal().await
    }

    /// Internal refresh without lock (caller must hold refresh_lock)
    async fn refresh_internal(&self) -> Result<(), AuthError> {
        debug!("Fetching JWKS from {}", self.jwks_uri);

        let response = self
            .client
            .get(&self.jwks_uri)
            .timeout(JWKS_REQUEST_TIMEOUT)
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
    use axum::{extract::State, routing::get, Json, Router};
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };

    const TEST_RSA_MODULUS: &str = "wD0oMRsg1c8QsNYFJg5KLEvU0CvYsHMNkVPP7u8FGbk4i5BfGVyy6PyjJjS0GNlNv9OLUDW82yw-n-3kKoCU0GgfKueRclmKemOaN1DPrwyicUSVVw2LMudjVuepvrZdzdgnw9u0-4u4CJCziOesmEMmxei-rR4GJggYWtk8ztyw0w9Jx68ny77oNPPAiHx9_fTvI90wOQY37fWZBBzpZmqKFTqV8cHHT2-Rg-SlHnTyAAD01VDG33zAQbNh4ouw64uZNjyxBNtqbs1-_ngFz9PuoHAdsE1qL8YaG1NPPsQG0b4tv2v1CeXS-RRd4ugAYjffi1aM7itotmd98wLeqw";

    async fn counted_jwks(State(requests): State<Arc<AtomicUsize>>) -> Json<serde_json::Value> {
        requests.fetch_add(1, Ordering::SeqCst);
        Json(serde_json::json!({
            "keys": [{
                "kty": "RSA",
                "kid": "known-key",
                "use": "sig",
                "alg": "RS256",
                "n": TEST_RSA_MODULUS,
                "e": "AQAB"
            }]
        }))
    }

    #[tokio::test]
    async fn unknown_key_ids_only_trigger_one_refresh_during_cooldown() {
        let requests = Arc::new(AtomicUsize::new(0));
        let app = Router::new()
            .route("/jwks", get(counted_jwks))
            .with_state(requests.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

        let cache = JwksCache::new(format!("http://{address}/jwks"), reqwest::Client::new())
            .await
            .unwrap();

        assert!(matches!(
            cache.get_key("attacker-key-1").await,
            Err(AuthError::KeyNotFound(_))
        ));
        assert!(matches!(
            cache.get_key("attacker-key-2").await,
            Err(AuthError::KeyNotFound(_))
        ));
        assert_eq!(requests.load(Ordering::SeqCst), 2);
    }

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
