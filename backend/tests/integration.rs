use axum::http::StatusCode;
use axum_test::TestServer;

mod common;

use common::{create_test_app, create_test_metrics_app};

#[tokio::test]
async fn test_health_endpoint() {
    let (_temp_dir, app) = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/health").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let body: serde_json::Value = response.json();
    assert_eq!(body["status"], "healthy");
}

#[tokio::test]
async fn test_apps_list_empty() {
    let (_temp_dir, app) = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/api/apps").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let body: serde_json::Value = response.json();
    assert_eq!(body["apps"], serde_json::json!([]));
}

#[tokio::test]
async fn test_app_not_found() {
    let (_temp_dir, app) = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/api/apps/com.nonexistent").await;
    assert_eq!(response.status_code(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn test_metrics_endpoint() {
    let app = create_test_metrics_app();
    let server = TestServer::new(app).unwrap();

    let response = server.get("/metrics").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    // Check that the content type is correct for Prometheus
    let content_type = response.headers().get("content-type").unwrap();
    assert!(content_type.to_str().unwrap().starts_with("text/plain"));

    // The metrics endpoint should return some content (metrics format)
    let body = response.text();
    // The registry will have metrics registered, check for the presence of our custom metrics
    // After register_metrics() is called, these will be in the output
    assert!(
        body.contains("lellostore") || body.contains("homelab"),
        "Expected metrics with lellostore or homelab prefix, got: {}",
        body
    );
}
