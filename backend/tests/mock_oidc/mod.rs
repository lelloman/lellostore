//! Mock OIDC server for E2E tests
//!
//! This module provides an in-process mock OIDC server that can be used
//! in integration tests without requiring an external server.

use axum::{extract::State, response::Json, routing::get, Router};
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use serde::Serialize;
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::oneshot;

// Fixed RSA key pair for testing
const RSA_PRIVATE_KEY_PEM: &str = r#"-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEAwD0oMRsg1c8QsNYFJg5KLEvU0CvYsHMNkVPP7u8FGbk4i5Bf
GVyy6PyjJjS0GNlNv9OLUDW82yw+n+3kKoCU0GgfKueRclmKemOaN1DPrwyicUSV
Vw2LMudjVuepvrZdzdgnw9u0+4u4CJCziOesmEMmxei+rR4GJggYWtk8ztyw0w9J
x68ny77oNPPAiHx9/fTvI90wOQY37fWZBBzpZmqKFTqV8cHHT2+Rg+SlHnTyAAD0
1VDG33zAQbNh4ouw64uZNjyxBNtqbs1+/ngFz9PuoHAdsE1qL8YaG1NPPsQG0b4t
v2v1CeXS+RRd4ugAYjffi1aM7itotmd98wLeqwIDAQABAoIBAB3S/yLLMidppg3B
TnAuhFxl8WDQhKAvYVn8FkTb6T8p6LdiURa2tn0GAOvC/nPidrj9gV4S0DdyoE6g
kibz5uKEXN2DqqGCecTuIfVWALqIK8WF3eNxEvj1RAiuDTwsv9XZJKYytlvWO9l8
VZY2VyUSOfg3WSmzgEGzPNAPZusythYJHTjPDCPUMK3OOqRAbat7tnSRoqNoGzYP
ncYegO0WI8m5lJj7a96fIH4gcu0SkT3ynfQvdstmfxsJr31IJsBSMwtCuipAjLVa
ItsPrEwVpIvej5/pGr88Hxr7f9XuZU2p+wMmH0zZJV4Q9bwb4gDrLH8hpm9OJPP+
OdgRQcUCgYEA4apQlz5GTxbsNolrFyGfaJUHFJA6sQsFNTac+DUipdHvv/Z95gzi
DGqb1GAsLBzyTPed5JHqcsRYvdBF+MyhowHxw9EiT3nfOqCSoECRg8VeBlnpyVfQ
zEb6f/g9nlmebjLaSPTpM7i6X+UciHOM9N5b9jStLVzTwf19kw9v4qUCgYEA2hSP
OtqT1vWmE/QZk5kROtO2jSJEcCwidPvI+kx4vvBKX/jPL1uAOFS08WQAjoUq6mk9
HBofPPuywe981Nyq/prU+xAIDZlee9I8umEhYNlz3VXRXGDT3j5uDmhcLp/gKUSu
kwV5kPz2Xkm6YOu7ugfWyjKqZDBncbGE+CnBiw8CgYBSGkkf/cRO2iQu42hXDiCV
SEM/NApmh3/pkGkj1RE6C1uTF/dOT7mektsHNcZcdluhrSWBF6WZA97VkIUktC7K
w9ZWTCyThd+10N4H9/X5X0GKRgRNAOoyqFBTZtnkVu7RYScSDCkcbEVCxnTPIFtU
a+JBfYDUVEWm+rdJXgBzEQKBgQC03ixUcYf/1khhcCfuRBsIISLcNrlwFwqU32Y2
QUo7gesNYbvj2Q2kqoxPT9MuYL/RHmsybW/PEimVstxjZojjFOLjPs6PCM5V/22i
XoBiZLc1sMEszpmpTznT9TXO7YXqdC4dfYLvfv2OAbP0Qk614V6A4Dh1U7fXkZVo
hKkifQKBgHqthV1mq/IvAgqetJ5isiRLenADeiH9U+d+ZVE7aUXGZ6uv5okZLJMt
iKScEnKv6scuhb9ewZIy73S/F4PFFk24gbUhUJ+soDSQW+kgePyXl35am24+LXrK
KwSYdjnyOKQXO3heKK573wnOA+Zqy+NnXZEuQhwwbJDeSs7liNef
-----END RSA PRIVATE KEY-----"#;

// Base64url-encoded modulus (n) from the public key
const RSA_MODULUS: &str = "wD0oMRsg1c8QsNYFJg5KLEvU0CvYsHMNkVPP7u8FGbk4i5BfGVyy6PyjJjS0GNlNv9OLUDW82yw-n-3kKoCU0GgfKueRclmKemOaN1DPrwyicUSVVw2LMudjVuepvrZdzdgnw9u0-4u4CJCziOesmEMmxei-rR4GJggYWtk8ztyw0w9Jx68ny77oNPPAiHx9_fTvI90wOQY37fWZBBzpZmqKFTqV8cHHT2-Rg-SlHnTyAAD01VDG33zAQbNh4ouw64uZNjyxBNtqbs1-_ngFz9PuoHAdsE1qL8YaG1NPPsQG0b4tv2v1CeXS-RRd4ugAYjffi1aM7itotmd98wLeqw";

const KEY_ID: &str = "test-key-1";

/// Mock OIDC server that runs in-process
pub struct MockOidc {
    addr: SocketAddr,
    shutdown_tx: Option<oneshot::Sender<()>>,
}

impl MockOidc {
    /// Start the mock OIDC server on a random port
    pub async fn start() -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let base_url = format!("http://{}", addr);
        let app = create_mock_oidc_app(base_url);

        let (shutdown_tx, shutdown_rx) = oneshot::channel();

        tokio::spawn(async move {
            axum::serve(listener, app)
                .with_graceful_shutdown(async {
                    let _ = shutdown_rx.await;
                })
                .await
                .ok();
        });

        // Give the server a moment to start
        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;

        MockOidc {
            addr,
            shutdown_tx: Some(shutdown_tx),
        }
    }

    /// Get the issuer URL for this mock server
    pub fn issuer_url(&self) -> String {
        format!("http://{}", self.addr)
    }

    /// Generate an admin token
    pub fn get_admin_token(&self) -> String {
        self.generate_token(true, "test-admin", "lellostore", 3600)
    }

    /// Generate a regular user token
    pub fn get_user_token(&self) -> String {
        self.generate_token(false, "test-user", "lellostore", 3600)
    }

    /// Generate an expired token
    pub fn get_expired_token(&self) -> String {
        self.generate_token(false, "test-user", "lellostore", -3600)
    }

    /// Generate a token with a specific audience
    pub fn get_token_with_audience(&self, audience: &str) -> String {
        self.generate_token(false, "test-user", audience, 3600)
    }

    fn generate_token(&self, is_admin: bool, subject: &str, audience: &str, expires_in_secs: i64) -> String {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs() as i64;

        let mut roles = vec!["user".to_string()];
        if is_admin {
            roles.push("admin".to_string());
        }

        let claims = serde_json::json!({
            "iss": self.issuer_url(),
            "sub": subject,
            "aud": audience,
            "exp": now + expires_in_secs,
            "iat": now,
            "email": format!("{}@test.local", subject),
            "realm_access": {
                "roles": roles
            }
        });

        let key = EncodingKey::from_rsa_pem(RSA_PRIVATE_KEY_PEM.as_bytes()).unwrap();
        let mut header = Header::new(Algorithm::RS256);
        header.kid = Some(KEY_ID.to_string());

        encode(&header, &claims, &key).unwrap()
    }
}

impl Drop for MockOidc {
    fn drop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }
    }
}

#[derive(Clone)]
struct MockOidcState {
    base_url: String,
}

fn create_mock_oidc_app(base_url: String) -> Router {
    let state = MockOidcState { base_url };
    Router::new()
        .route("/.well-known/openid-configuration", get(openid_config))
        .route("/jwks", get(jwks))
        .with_state(state)
}

#[derive(Serialize)]
struct OpenIdConfig {
    issuer: String,
    jwks_uri: String,
}

async fn openid_config(State(state): State<MockOidcState>) -> Json<OpenIdConfig> {
    Json(OpenIdConfig {
        issuer: state.base_url.clone(),
        jwks_uri: format!("{}/jwks", state.base_url),
    })
}

#[derive(Serialize)]
struct Jwks {
    keys: Vec<Jwk>,
}

#[derive(Serialize)]
struct Jwk {
    kty: String,
    alg: String,
    r#use: String,
    kid: String,
    n: String,
    e: String,
}

async fn jwks() -> Json<Jwks> {
    Json(Jwks {
        keys: vec![Jwk {
            kty: "RSA".to_string(),
            alg: "RS256".to_string(),
            r#use: "sig".to_string(),
            kid: KEY_ID.to_string(),
            n: RSA_MODULUS.to_string(),
            e: "AQAB".to_string(), // Standard exponent 65537
        }],
    })
}
