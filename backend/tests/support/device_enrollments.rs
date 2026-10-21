//! Store-native device enrollment over HTTP, and deleting revoked device tokens.
use serde_json::{json, Value};
use simple_server::testing::TestServer;
use simple_server::web::http::StatusCode;

fn bearer(secret: &str) -> String {
    format!("Bearer {secret}")
}

async fn start(server: &TestServer, ip: &str, body: Value) -> (StatusCode, Value) {
    let response = server
        .post("/api/device-enrollments")
        .header("Host", "store.example.com")
        .header("X-Forwarded-For", ip)
        .json(&body)
        .send()
        .await
        .expect("test request");
    (response.status_code(), response.json().unwrap())
}

async fn poll(server: &TestServer, device_code: &str) -> (StatusCode, Value) {
    let response = server
        .post("/api/device-enrollments/poll")
        .header("Host", "store.example.com")
        .json(&json!({"device_code": device_code}))
        .send()
        .await
        .expect("test request");
    assert_eq!(response.headers().get("cache-control").unwrap(), "no-store");
    (response.status_code(), response.json().unwrap())
}

async fn admin_call(
    server: &TestServer,
    method: &str,
    path: &str,
    auth: &str,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let request = match method {
        "GET" => server.get(path),
        "POST" => server.post(path),
        "DELETE" => server.delete(path),
        _ => unreachable!(),
    };
    let request = match body {
        Some(body) => request.json(&body),
        None => request,
    };
    let response = request
        .header("Authorization", auth)
        .send()
        .await
        .expect("test request");
    let status = response.status_code();
    (status, response.json().unwrap_or(Value::Null))
}

#[tokio::test]
async fn a_device_enrolls_through_admin_approval_and_receives_its_token_once() {
    let (ctx, oidc) = super::create_auth_test_context().await;
    let server = TestServer::new(ctx.router.clone());
    let admin = bearer(&oidc.get_admin_token());
    let user = bearer(&oidc.get_user_token());

    let (status, started) = start(
        &server,
        "198.51.100.7",
        json!({"name": "Mi Box (living room)", "model": "MIBOX4", "android_sdk": 28, "client_version": "1.0.0"}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let device_code = started["device_code"].as_str().unwrap().to_owned();
    let user_code = started["user_code"].as_str().unwrap().to_owned();
    assert!(device_code.starts_with("lellostore_enr_"));
    assert_eq!(started["expires_in"], 600);
    assert_eq!(started["interval"], 5);
    assert_eq!(
        started["verification_uri"],
        "https://store.example.com/admin/device-tokens/approve"
    );
    let payload: Value =
        serde_json::from_str(started["verification_payload"].as_str().unwrap()).unwrap();
    assert_eq!(
        payload,
        json!({"type": "device-enrollment", "version": 1, "server_url": "https://store.example.com", "user_code": user_code})
    );

    let (status, body) = poll(&server, &device_code).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "authorization_pending");
    let (status, body) = poll(&server, &device_code).await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::BAD_REQUEST, Some("slow_down"))
    );

    // Only administrators look up, approve or deny; never users or devices.
    let device = {
        let (status, created) = admin_call(
            &server,
            "POST",
            "/api/admin/device-tokens",
            &admin,
            Some(json!({"name": "Other box"})),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        bearer(created["secret"].as_str().unwrap())
    };
    let lookup = format!("/api/admin/device-enrollments/{user_code}");
    for auth in [&user, &device] {
        for (method, path) in [
            ("GET", lookup.clone()),
            ("POST", format!("{lookup}/approve")),
            ("POST", format!("{lookup}/deny")),
        ] {
            let (status, _) = admin_call(&server, method, &path, auth, Some(json!({}))).await;
            assert_eq!(status, StatusCode::FORBIDDEN, "{method} {path}");
        }
    }
    let response = server.get(&lookup).send().await.expect("test request");
    assert_eq!(response.status_code(), StatusCode::UNAUTHORIZED);

    let (status, details) = admin_call(&server, "GET", &lookup, &admin, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(details["requested_name"], "Mi Box (living room)");
    assert_eq!(details["model"], "MIBOX4");
    assert_eq!(details["android_sdk"], 28);
    assert_eq!(details["source_ip"], "198.51.100.7");
    assert!(details.get("device_code").is_none());
    let (status, _) = admin_call(
        &server,
        "POST",
        &format!("{lookup}/approve"),
        &admin,
        Some(json!({"unknown": true})),
    )
    .await;
    assert!(status.is_client_error());
    let (status, approved) = admin_call(
        &server,
        "POST",
        &format!("{lookup}/approve"),
        &admin,
        Some(json!({})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(approved["rekeyed"], false);
    assert_eq!(approved["device_token"]["name"], "Mi Box (living room)");
    assert_eq!(approved["device_token"]["groups"][0]["name"], "all");
    assert!(approved.to_string().find("lellostore_dev_").is_none());

    let (status, file) = poll(&server, &device_code).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        file,
        json!({
            "version": 1,
            "server_url": "https://store.example.com",
            "name": "Mi Box (living room)",
            "token": file["token"].as_str().unwrap(),
        })
    );
    let token = bearer(file["token"].as_str().unwrap());
    let me = server
        .get("/api/me")
        .header("Authorization", &token)
        .send()
        .await
        .expect("test request");
    me.assert_status_ok();
    assert_eq!(
        me.json::<Value>().unwrap()["subject"],
        approved["device_token"]["subject"]
    );
    sqlx::query("UPDATE device_enrollments SET last_polled_at = datetime('now', '-1 minute')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    let (status, body) = poll(&server, &device_code).await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::BAD_REQUEST, Some("invalid_grant"))
    );

    // A reset box gets the same principal back by rekeying its old token.
    let (_, again) = start(&server, "198.51.100.7", json!({"name": "Mi Box"})).await;
    let again_code = again["user_code"].as_str().unwrap();
    let (status, rekeyed) = admin_call(
        &server,
        "POST",
        &format!("/api/admin/device-enrollments/{again_code}/approve"),
        &admin,
        Some(json!({"replace_device_token_id": approved["device_token"]["id"]})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(rekeyed["rekeyed"], true);
    assert_eq!(
        rekeyed["device_token"]["id"],
        approved["device_token"]["id"]
    );
    let (status, _) = admin_call(&server, "GET", "/api/me", &token, None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, file) = poll(&server, again["device_code"].as_str().unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(file["name"], "Mi Box (living room)");

    // Denial, optionally blocking the address for 24 hours.
    let (_, denied) = start(&server, "203.0.113.5", json!({"name": "Intruder"})).await;
    let (status, _) = admin_call(
        &server,
        "POST",
        &format!(
            "/api/admin/device-enrollments/{}/deny",
            denied["user_code"].as_str().unwrap()
        ),
        &admin,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, body) = poll(&server, denied["device_code"].as_str().unwrap()).await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::BAD_REQUEST, Some("access_denied"))
    );
    let (status, body) = start(&server, "203.0.113.5", json!({"name": "Intruder"})).await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::FORBIDDEN, Some("access_denied"))
    );
    let (_, unblocked) = start(&server, "203.0.113.6", json!({"name": "Guest"})).await;
    let (status, _) = admin_call(
        &server,
        "POST",
        &format!(
            "/api/admin/device-enrollments/{}/deny?block_source=false",
            unblocked["user_code"].as_str().unwrap()
        ),
        &admin,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = start(&server, "203.0.113.6", json!({"name": "Guest"})).await;
    assert_eq!(status, StatusCode::CREATED);

    // Unknown codes are 404, and too many of them are 429.
    let mut statuses = Vec::new();
    for _ in 0..11 {
        let (status, _) = admin_call(
            &server,
            "GET",
            "/api/admin/device-enrollments/BBBB-BBBB",
            &admin,
            None,
        )
        .await;
        statuses.push(status);
    }
    assert_eq!(statuses[0], StatusCode::NOT_FOUND);
    assert_eq!(statuses[10], StatusCode::TOO_MANY_REQUESTS);

    let audits: Vec<String> = sqlx::query_scalar(
        "SELECT DISTINCT action FROM access_audit_log WHERE action LIKE 'device_enrollment.%' ORDER BY action",
    )
    .fetch_all(&ctx.pool)
    .await
    .unwrap();
    assert_eq!(
        audits,
        [
            "device_enrollment.approve",
            "device_enrollment.create",
            "device_enrollment.deny",
            "device_enrollment.lookup_failed",
            "device_enrollment.redeem"
        ]
    );
}

#[tokio::test]
async fn enrollment_requests_are_validated_and_rate_limited() {
    let (ctx, _oidc) = super::create_auth_test_context().await;
    let server = TestServer::new(ctx.router.clone());
    for body in [json!({}), json!({"name": " "}), json!("text")] {
        let (status, error) = start(&server, "198.51.100.7", body).await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert_eq!(error["error"], "invalid_request");
    }
    let response = server
        .post("/api/device-enrollments")
        .json(&json!({"name": "x".repeat(5000)}))
        .send()
        .await
        .expect("test request");
    assert_eq!(response.status_code(), StatusCode::PAYLOAD_TOO_LARGE);
    let response = server
        .post("/api/device-enrollments/poll")
        .json(&json!({}))
        .send()
        .await
        .expect("test request");
    assert_eq!(response.status_code(), StatusCode::BAD_REQUEST);
    assert_eq!(
        response.json::<Value>().unwrap()["error"],
        "invalid_request"
    );
    let (status, body) = poll(&server, "lellostore_enr_unknown").await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::BAD_REQUEST, Some("invalid_grant"))
    );
    for _ in 0..10 {
        let (status, _) = start(&server, "198.51.100.7", json!({"name": "TV"})).await;
        assert_eq!(status, StatusCode::CREATED);
    }
    let (status, body) = start(&server, "198.51.100.7", json!({"name": "TV"})).await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::TOO_MANY_REQUESTS, Some("slow_down"))
    );
    let (status, _) = start(&server, "198.51.100.8", json!({"name": "TV"})).await;
    assert_eq!(status, StatusCode::CREATED);
}

#[tokio::test]
async fn revoked_device_tokens_can_be_deleted_and_rekeyed() {
    let (ctx, oidc) = super::create_auth_test_context().await;
    let server = TestServer::new(ctx.router.clone());
    let admin = bearer(&oidc.get_admin_token());
    let user = bearer(&oidc.get_user_token());
    let base = "/api/admin/device-tokens";
    let mut ids = Vec::new();
    for name in ["Living room", "Xiaomi TV Box", "Xiaomi TV Box"] {
        let (status, created) =
            admin_call(&server, "POST", base, &admin, Some(json!({"name": name}))).await;
        assert_eq!(status, StatusCode::CREATED);
        ids.push(created["id"].as_str().unwrap().to_owned());
    }
    for (method, path) in [
        ("POST", format!("{base}/{}/purge", ids[1])),
        ("POST", format!("{base}/purge-revoked")),
        ("POST", format!("{base}/{}/rekey", ids[1])),
    ] {
        let (status, _) = admin_call(&server, method, &path, &user, None).await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{method} {path}");
    }
    // An active token must be revoked before it can be deleted.
    let (status, _) = admin_call(
        &server,
        "POST",
        &format!("{base}/{}/purge", ids[1]),
        &admin,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT);
    for id in &ids[1..] {
        let (status, revoked) =
            admin_call(&server, "DELETE", &format!("{base}/{id}"), &admin, None).await;
        assert_eq!(status, StatusCode::OK);
        assert!(revoked["revoked_at"].is_string());
    }
    let (status, _) = admin_call(
        &server,
        "POST",
        &format!("{base}/{}/purge", ids[1]),
        &admin,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = admin_call(
        &server,
        "POST",
        &format!("{base}/{}/purge", ids[1]),
        &admin,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, purged) = admin_call(
        &server,
        "POST",
        &format!("{base}/purge-revoked"),
        &admin,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(purged, json!({"deleted": 1}));
    let (_, listed) = admin_call(&server, "GET", base, &admin, None).await;
    assert_eq!(listed.as_array().unwrap().len(), 1);
    assert_eq!(listed[0]["id"], ids[0].as_str());

    // Rekey returns a new secret once and keeps the token's identity.
    let (status, rekeyed) = admin_call(
        &server,
        "POST",
        &format!("{base}/{}/rekey", ids[0]),
        &admin,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(rekeyed["id"], ids[0].as_str());
    let secret = rekeyed["secret"].as_str().unwrap();
    assert!(secret.starts_with("lellostore_dev_"));
    let (status, _) = admin_call(&server, "GET", "/api/me", &bearer(secret), None).await;
    assert_eq!(status, StatusCode::OK);
}
