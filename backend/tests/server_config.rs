#[allow(dead_code)]
mod common;

use lellostore_backend::config::ClientConfig;
use simple_server::{testing::TestServer, web::http::StatusCode};

#[tokio::test]
async fn discovery_is_public_during_auth_outage_but_catalog_stays_closed() {
    let ctx = common::create_discovery_test_context(ClientConfig {
        store_name: "Independent Store".into(),
        android_client_id: "independent-android".into(),
        web_client_id: "independent-web".into(),
        publisher_client_id: "independent-publisher".into(),
        ..Default::default()
    })
    .await;
    let server = TestServer::new(ctx.router);
    let response = server.get("/api/server-config").send().await.unwrap();
    assert_eq!(response.status_code(), StatusCode::OK);
    assert_eq!(response.headers().get("cache-control").unwrap(), "no-store");
    let body: serde_json::Value = response.json().unwrap();
    assert_eq!(
        body,
        serde_json::json!({
            "schema_version": 1,
            "name": "Independent Store",
            "auth": {
                "method": "oidc", "issuer_url": "https://identity.example.test/realm",
                "clients": {"android": "independent-android", "web": "independent-web", "publisher": "independent-publisher"},
                "scopes": ["openid", "profile", "email"]
            },
            "capabilities": {"push": false, "paravoid": false}
        })
    );
    let catalog = server.get("/api/apps").send().await.unwrap();
    assert_eq!(catalog.status_code(), StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn unconfigured_discovery_is_an_explicit_uncached_error() {
    let (_dir, app) = common::create_fail_closed_test_app().await;
    let server = TestServer::new(app);
    let response = server.get("/api/server-config").send().await.unwrap();
    assert_eq!(response.status_code(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(response.headers().get("cache-control").unwrap(), "no-store");
    let body: serde_json::Value = response.json().unwrap();
    assert_eq!(body["code"], "server_not_configured");
    assert!(body.get("auth").is_none());
}

#[test]
fn discovery_rejects_unsafe_or_incomplete_metadata() {
    let configured = ClientConfig {
        android_client_id: "android".into(),
        web_client_id: "web".into(),
        publisher_client_id: "publisher".into(),
        ..Default::default()
    };
    assert!(configured.is_configured("https://id.example/realm"));
    for issuer in [
        "https://example.com",
        "http://id.example",
        "https://user:secret@id.example",
        "https://id.example?x=1",
        "https://id.example/#fragment",
        "invalid",
    ] {
        assert!(!configured.is_configured(issuer), "{issuer}");
    }
    assert!(!ClientConfig::default().is_configured("https://id.example"));
    let mut invalid = configured.clone();
    invalid.scopes = vec!["profile".into()];
    assert!(invalid.validate().is_err());
    invalid = configured.clone();
    invalid.web_client_id = "bad\nclient".into();
    assert!(invalid.validate().is_err());
    invalid = configured;
    invalid.store_name = String::new();
    assert!(invalid.validate().is_err());
}

#[tokio::test]
async fn catalog_identity_cannot_silently_move_to_another_issuer() {
    let ctx = common::create_test_context().await;
    let bind = lellostore_backend::db::deployment::bind_issuer;
    bind(&ctx.pool, "https://example.com").await.unwrap();
    bind(&ctx.pool, "https://id.one.example").await.unwrap();
    bind(&ctx.pool, "https://id.one.example").await.unwrap();
    assert!(bind(&ctx.pool, "https://id.two.example").await.is_err());
    let issuer: String = sqlx::query_scalar("SELECT issuer FROM deployment_identity")
        .fetch_one(&ctx.pool)
        .await
        .unwrap();
    assert_eq!(issuer, "https://id.one.example");
}
