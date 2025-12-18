use serde::Deserialize;

use super::error::AuthError;

/// OIDC Discovery document (subset of fields we need)
#[derive(Debug, Clone, Deserialize)]
pub struct OidcDiscovery {
    pub issuer: String,
    pub jwks_uri: String,
    #[serde(default)]
    pub authorization_endpoint: Option<String>,
    #[serde(default)]
    pub token_endpoint: Option<String>,
}

/// Fetch OIDC discovery document from the well-known endpoint
pub async fn fetch_discovery(
    client: &reqwest::Client,
    issuer_url: &str,
) -> Result<OidcDiscovery, AuthError> {
    let discovery_url = format!(
        "{}/.well-known/openid-configuration",
        issuer_url.trim_end_matches('/')
    );

    let response = client
        .get(&discovery_url)
        .send()
        .await
        .map_err(|e| AuthError::DiscoveryFailed(format!("Request failed: {}", e)))?;

    if !response.status().is_success() {
        return Err(AuthError::DiscoveryFailed(format!(
            "HTTP {}: {}",
            response.status(),
            discovery_url
        )));
    }

    let discovery: OidcDiscovery = response
        .json()
        .await
        .map_err(|e| AuthError::DiscoveryFailed(format!("Invalid JSON: {}", e)))?;

    // Verify issuer matches
    let expected_issuer = issuer_url.trim_end_matches('/');
    let actual_issuer = discovery.issuer.trim_end_matches('/');
    if expected_issuer != actual_issuer {
        return Err(AuthError::DiscoveryFailed(format!(
            "Issuer mismatch: expected '{}', got '{}'",
            expected_issuer, actual_issuer
        )));
    }

    Ok(discovery)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_discovery_deserialize() {
        let json = r#"{
            "issuer": "https://auth.example.com",
            "jwks_uri": "https://auth.example.com/jwks",
            "authorization_endpoint": "https://auth.example.com/auth",
            "token_endpoint": "https://auth.example.com/token"
        }"#;

        let discovery: OidcDiscovery = serde_json::from_str(json).unwrap();
        assert_eq!(discovery.issuer, "https://auth.example.com");
        assert_eq!(discovery.jwks_uri, "https://auth.example.com/jwks");
    }

    #[test]
    fn test_discovery_deserialize_minimal() {
        let json = r#"{
            "issuer": "https://auth.example.com",
            "jwks_uri": "https://auth.example.com/jwks"
        }"#;

        let discovery: OidcDiscovery = serde_json::from_str(json).unwrap();
        assert_eq!(discovery.issuer, "https://auth.example.com");
        assert!(discovery.authorization_endpoint.is_none());
    }
}
