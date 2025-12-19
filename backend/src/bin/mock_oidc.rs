//! Mock OIDC server for local testing
//!
//! Run with: cargo run --bin mock_oidc
//! Then set OIDC_ISSUER_URL=http://localhost:9999 in your .env
//!
//! Get a test token: curl http://localhost:9999/token?admin=true

use axum::{extract::Query, response::Json, routing::get, Router};
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

// Use a fixed RSA key pair for testing (2048-bit)
// In production you'd never do this - but this is for local testing only
const RSA_PRIVATE_KEY_PEM: &str = r#"-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEA2a2rwplBQLF5bZrFaHMz4Dv+sxX0qyMje3TCgwe8NfEOTCpH
5gXBCGvJhKWxkhMsySH+S2PJfBgXmyFxOCVp3PjLVnxXdOxwetpFdPRxxgfKGgqj
FKxKUzShN5PwagMgVOqnfBkgquEf3bkDLBxgwGCvPT0KTGE+oYJiPmVqS/Yyihjt
QrJLmXBz0vkEuPM7rLWFzxN7EGOrGqwj6txkY7P7ZwcFtKDKljBgDyDc0cFHaPnP
n1vsyMiyg+E4azu6E3xJjrrGvaZ3EOWK6uFgbKmFjkWwNhPMpEqPl/pXkGpPvfBB
t5Mkn/lODnKcVbJPqwfUSDXpYxLvjSMBaddxBwIDAQABAoIBAD58Tak9yLCxYVfq
Rh4ek/pFfgLqoU3Xs0gLzKKmFaZmRhHqU0EhozYvny69r2/M/iXU0TcC1hR4PLVX
UuAVH+03AXcB7VzyN0nOnWNqwxL5yjbxBvmfWkQfgGChzqJr2VFqHg2RPwnLsE9P
nKDxNrVZqMmvyMsZNmR/R1pZVgfC8C7TdT1G2MsFogMBAY2BhVq9pNqBqXqg1vHG
8RN5P1SRvFUB9xCE/vbDL1z0i+KHnpcf9hBpYQ0G9FUBwwQ0DYjM1e7wPgkVLO8L
nlrO7jx3cTSEiLm59DFHbfCfXDieOLqQoFx2NeXqHv41LpRe4pFv7sFNjs2HEVBP
/DPNPoECgYEA8NnWT2enWRCRbPzYqVlHNvhwePaT1PbbhXjQpHiMaKs1/M957hn1
3gvVo8VlkIzgEKb9KUMUOH5hm6XD85ozWrRfQgc3SBe7Qx1kLfpMBbBZ5PkYFYVL
VLQCaE4E3shMsHPoBn0+L4ux2LPgyWWr/xX7Rj0eySH4VPDBl+cJmecCgYEA5vES
kHNOnVbEudOegPf8UsHbE3CvolhGrqIB21wfKdH3r+71lKuD5x5kM6RLVfb4M4YP
fPJVv7fOqn6wED7F8sQe8FQL8r0Gn2GN4SBHRhXxYKQ4VqCepO36V/iK1Q3q80cf
vY0r9rm5dMDBb0t2jp04U+o6vWT+swaXxWlZJoECgYA5hxZ6S6Yt8tGa3E+XVLLE
U3kWF6cv05qBbtfEimC5kZZjenPdNm6RpTf4E5giP0WbJl7R7uoWLqNhvOjsLCFN
LlSyAmHnHR7j/Y2365grG2FuaD4MO/V7r0Oi5qfFZhAF8gn1lPpQMPOL2m9bakUe
r0x/2p3kcUyrzOSPHNphTwKBgFVtyWQgwi/BpxWrP58yNSBob+8WsXmtTwMQqdMd
e7y0rrqzMRwM67JqvNr9kp0Hx1IEcu8YDzCCwPPqSoN7MxjblKeFKCJDB+FqHPv0
ylPAN7s/LraNXi7f+Xbxj6LCTA0YMVHMWQDOK6lOfnJnmreZVcFUxnT7qaQq3zuM
xZQBAoGBAOyhjhjHxm3s/CzGp01vTpOKq8pcXvjZZcJqvX0RZwp6MfGgMx5rXmND
7F2qp3hMr5wXZwHnMzEELmnF9SQQJQZ8gC/Lcaru2B+r7xDLoO7vkhPEjRLyadZy
acLGBwmhaongeRfeNR5xPZfLS0uMffThLLpLcSTpqE/smkajLJGB
-----END RSA PRIVATE KEY-----"#;

const KEY_ID: &str = "test-key-1";

#[derive(Serialize)]
struct OpenIdConfig {
    issuer: String,
    jwks_uri: String,
    authorization_endpoint: String,
    token_endpoint: String,
    response_types_supported: Vec<String>,
    subject_types_supported: Vec<String>,
    id_token_signing_alg_values_supported: Vec<String>,
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

#[derive(Serialize)]
struct TokenResponse {
    access_token: String,
    token_type: String,
    expires_in: u64,
}

#[derive(Deserialize)]
struct TokenQuery {
    #[serde(default)]
    admin: bool,
    #[serde(default = "default_subject")]
    sub: String,
}

fn default_subject() -> String {
    "test-user".to_string()
}

fn get_public_key_components() -> (String, String) {

    // These are the n and e values extracted from the public key
    // n (modulus) - extracted from the public key
    let n = "2a2rwplBQLF5bZrFaHMz4Dv-sxX0qyMje3TCgwe8NfEOTCpH5gXBCGvJhKWxkhMsySH-S2PJfBgXmyFxOCVp3PjLVnxXdOxwetpFdPRxxgfKGgqjFKxKUzShN5PwagMgVOqnfBkgquEf3bkDLBxgwGCvPT0KTGE-oYJiPmVqS_YyihjtQrJLmXBz0vkEuPM7rLWFzxN7EGOrGqwj6txkY7P7ZwcFtKDKljBgDyDc0cFHaPnPn1vsyMiyg-E4azu6E3xJjrrGvaZ3EOWK6uFgbKmFjkWwNhPMpEqPl_pXkGpPvfBBt5Mkn_lODnKcVbJPqwfUSDXpYxLvjSMBaddxBw";
    // e (exponent) - standard value 65537 = 0x010001
    let e = "AQAB";

    (n.to_string(), e.to_string())
}

async fn openid_config() -> Json<OpenIdConfig> {
    let base_url = "http://localhost:9999";
    Json(OpenIdConfig {
        issuer: base_url.to_string(),
        jwks_uri: format!("{}/jwks", base_url),
        authorization_endpoint: format!("{}/authorize", base_url),
        token_endpoint: format!("{}/token", base_url),
        response_types_supported: vec!["code".to_string(), "token".to_string()],
        subject_types_supported: vec!["public".to_string()],
        id_token_signing_alg_values_supported: vec!["RS256".to_string()],
    })
}

async fn jwks() -> Json<Jwks> {
    let (n, e) = get_public_key_components();
    Json(Jwks {
        keys: vec![Jwk {
            kty: "RSA".to_string(),
            alg: "RS256".to_string(),
            r#use: "sig".to_string(),
            kid: KEY_ID.to_string(),
            n,
            e,
        }],
    })
}

async fn get_token(Query(params): Query<TokenQuery>) -> Json<TokenResponse> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs();

    let expires_in = 3600u64;

    // Build claims
    let mut roles = vec!["user".to_string()];
    if params.admin {
        roles.push("admin".to_string());
    }

    let claims = serde_json::json!({
        "iss": "http://localhost:9999",
        "sub": params.sub,
        "aud": "lellostore",
        "exp": now + expires_in,
        "iat": now,
        "email": format!("{}@test.local", params.sub),
        "realm_access": {
            "roles": roles
        }
    });

    // Sign with RSA using jsonwebtoken
    use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
    let key = EncodingKey::from_rsa_pem(RSA_PRIVATE_KEY_PEM.as_bytes()).unwrap();
    let mut jwt_header = Header::new(Algorithm::RS256);
    jwt_header.kid = Some(KEY_ID.to_string());

    let token = encode(&jwt_header, &claims, &key).unwrap();

    Json(TokenResponse {
        access_token: token,
        token_type: "Bearer".to_string(),
        expires_in,
    })
}

async fn index() -> &'static str {
    r#"Mock OIDC Server for lellostore testing

Endpoints:
  GET /.well-known/openid-configuration - OIDC discovery
  GET /jwks                              - JSON Web Key Set
  GET /token?admin=true&sub=username     - Get a test JWT token

Usage:
  1. Set OIDC_ISSUER_URL=http://localhost:9999 in backend/.env
  2. Start main server: cargo run
  3. Get admin token: curl "http://localhost:9999/token?admin=true"
  4. Use token in requests:
     curl -H "Authorization: Bearer <token>" http://localhost:8080/api/admin/apps

Frontend:
  Set VITE_OIDC_AUTHORITY=http://localhost:9999 in frontend/.env
"#
}

#[tokio::main]
async fn main() {
    println!("Starting Mock OIDC Server on http://localhost:9999");
    println!();
    println!("Get admin token:  curl 'http://localhost:9999/token?admin=true'");
    println!("Get user token:   curl 'http://localhost:9999/token'");
    println!();

    let app = Router::new()
        .route("/", get(index))
        .route("/.well-known/openid-configuration", get(openid_config))
        .route("/jwks", get(jwks))
        .route("/token", get(get_token));

    let listener = tokio::net::TcpListener::bind("127.0.0.1:9999").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
