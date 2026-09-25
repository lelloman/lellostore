use simple_server::web::{
    body::{to_bytes, Body},
    http::Request,
};
use std::io::{self, Write};
use std::sync::{Arc, Mutex};
use tower::Service;
use tracing::instrument::WithSubscriber;

#[derive(Clone, Default)]
struct Capture(Arc<Mutex<Vec<u8>>>);
impl Write for Capture {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for Capture {
    type Writer = Self;
    fn make_writer(&'a self) -> Self {
        self.clone()
    }
}
#[allow(dead_code)]
mod common;
#[tokio::test]
async fn production_router_traces_health_and_fail_closed_responses_safely() {
    let (_temp, app) = common::create_fail_closed_test_app().await;
    let output = Capture::default();
    let subscriber = tracing_subscriber::fmt()
        .json()
        .with_ansi(false)
        .with_max_level(tracing::Level::TRACE)
        .with_writer(output.clone())
        .finish();
    async {
        for (method, uri, expected) in [
            ("GET", "/health?secret=do-not-log-this", 200),
            ("POST", "/health?secret=do-not-log-this", 405),
            (
                "GET",
                "/api/apps/private-package?secret=do-not-log-this",
                503,
            ),
        ] {
            let response = app
                .clone()
                .call(
                    Request::builder()
                        .method(method)
                        .uri(uri)
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status().as_u16(), expected);
            assert!(response.headers().get("x-request-id").is_none());
            to_bytes(response.into_body(), usize::MAX).await.unwrap();
        }
    }
    .with_subscriber(subscriber)
    .await;
    let logs = String::from_utf8(output.0.lock().unwrap().clone()).unwrap();
    assert_eq!(logs.matches("http.response_headers").count(), 3, "{logs}");
    assert_eq!(logs.matches("http.finished").count(), 3, "{logs}");
    assert!(logs.contains("simple_server::http_tracing"), "{logs}");
    assert!(logs.contains("\"route\":\"/health\""), "{logs}");
    assert!(!logs.contains("do-not-log-this"), "{logs}");
    assert!(!logs.contains("private-package"), "{logs}");
    assert!(!logs.contains("tower_http::trace"), "{logs}");
}
