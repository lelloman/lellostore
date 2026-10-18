//! Device tokens: read-only, zero-login catalog credentials for devices.
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use simple_server::testing::TestServer;
use simple_server::web::http::StatusCode;

fn bearer(secret: &str) -> String {
    format!("Bearer {secret}")
}

async fn status(server: &TestServer, method: &str, path: &str, auth: &str) -> StatusCode {
    let request = match method {
        "GET" => server.get(path),
        "POST" => server.post(path).json(&json!({})),
        "PUT" => server.put(path).json(&json!({})),
        "DELETE" => server.delete(path),
        _ => unreachable!(),
    };
    request
        .header("Authorization", auth)
        .send()
        .await
        .expect("test request")
        .status_code()
}

async fn get_json(server: &TestServer, path: &str, auth: &str) -> Value {
    let response = server
        .get(path)
        .header("Authorization", auth)
        .send()
        .await
        .expect("test request");
    response.assert_status_ok();
    response.json().unwrap()
}

#[tokio::test]
async fn device_tokens_read_the_granted_catalog_and_nothing_else() {
    let (ctx, oidc) = super::create_auth_test_context().await;
    let pool = ctx.pool.clone();
    sqlx::query("INSERT INTO apps (package_name, name) VALUES ('com.example.tv', 'TV app')")
        .execute(&pool)
        .await
        .unwrap();
    std::fs::write(ctx.storage_path.join("tv.apk"), b"apk-1").unwrap();
    sqlx::query("INSERT INTO app_versions (package_name, version_code, version_name, apk_path, size, sha256, min_sdk) VALUES ('com.example.tv', 1, '1', 'tv.apk', 5, ?, 24)")
        .bind(hex::encode(Sha256::digest(b"apk-1")))
        .execute(&pool)
        .await
        .unwrap();
    let server = TestServer::new(ctx.router.clone());
    let admin = bearer(&oidc.get_admin_token());
    let user = bearer(&oidc.get_user_token());
    let path = "/api/admin/device-tokens";

    // Only administrators manage device tokens.
    for method in ["GET", "POST"] {
        assert_eq!(
            status(&server, method, path, &user).await,
            StatusCode::FORBIDDEN
        );
    }
    let response = server
        .post(path)
        .header("Authorization", &admin)
        .json(&json!({"name": "Living room TV"}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::CREATED);
    let created: Value = response.json().unwrap();
    let secret = created["secret"].as_str().unwrap().to_owned();
    let token_id = created["id"].as_str().unwrap().to_owned();
    let subject = format!("device-token:{token_id}");
    assert!(secret.starts_with("lellostore_dev_"));
    assert_eq!(secret.len(), "lellostore_dev_".len() + 64);
    assert_eq!(created["name"], "Living room TV");
    assert_eq!(created["created_by"], "test-admin");
    assert_eq!(created["subject"], subject.as_str());
    assert_eq!(created["groups"], json!([]));
    assert!(created["revoked_at"].is_null());
    let hash: String = sqlx::query_scalar("SELECT token_hash FROM device_tokens WHERE id = ?")
        .bind(&token_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(hash, hex::encode(Sha256::digest(secret.as_bytes())));
    let known: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM known_users WHERE subject = ?)")
            .bind(&subject)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!(known);
    let listed = get_json(&server, path, &admin).await;
    assert_eq!(listed.as_array().unwrap().len(), 1);
    assert!(listed[0].get("secret").is_none());
    assert!(!listed.to_string().contains(&secret));

    // The Users listing labels the principal with the token's name.
    let users = get_json(&server, "/api/admin/users", &admin).await;
    let entry = users["users"]
        .as_array()
        .unwrap()
        .iter()
        .find(|u| u["subject"] == subject.as_str())
        .unwrap();
    assert_eq!(entry["device_token_name"], "Living room TV");
    assert!(users["users"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|u| u["subject"] != subject.as_str())
        .all(|u| u["device_token_name"].is_null()));

    // Without grants the token sees an empty catalog.
    let device = bearer(&secret);
    let apps = get_json(&server, "/api/apps", &device).await;
    assert!(apps["apps"].as_array().unwrap().is_empty());
    let me = get_json(&server, "/api/me", &device).await;
    assert_eq!(me["subject"], subject.as_str());
    assert_eq!(me["is_admin"], false);

    // Grant the app through a group using the existing admin routes.
    let response = server
        .post("/api/admin/app-groups")
        .header("Authorization", &admin)
        .json(&json!({"name": "TV boxes"}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::CREATED);
    let group_id = response.json::<Value>().unwrap()["id"].as_i64().unwrap();
    let response = server
        .put(&format!(
            "/api/admin/app-groups/{group_id}/apps/com.example.tv"
        ))
        .header("Authorization", &admin)
        .json(&json!({"access_level": "stable"}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::NO_CONTENT);
    assert_eq!(
        status(
            &server,
            "PUT",
            &format!("/api/admin/app-groups/{group_id}/users/{subject}"),
            &admin
        )
        .await,
        StatusCode::NO_CONTENT
    );
    let apps = get_json(&server, "/api/apps", &device).await;
    assert_eq!(apps["apps"][0]["package_name"], "com.example.tv");
    let detail = get_json(&server, "/api/apps/com.example.tv", &device).await;
    assert_eq!(detail["versions"][0]["version_code"], 1);
    let apk = server
        .get("/api/apps/com.example.tv/versions/1/apk")
        .header("Authorization", &device)
        .send()
        .await
        .expect("test request");
    apk.assert_status_ok();
    assert_eq!(apk.as_bytes().as_ref(), b"apk-1");
    let response = server
        .post("/api/apps/com.example.tv/acquisitions")
        .header("Authorization", &device)
        .json(&json!({"version_code": 1, "purpose": "update", "idempotency_key": "tv-1"}))
        .send()
        .await
        .expect("test request");
    response.assert_status_ok();
    let acquisition: Value = response.json().unwrap();
    let acquired = server
        .get(acquisition["apk_url"].as_str().unwrap())
        .header("Authorization", &device)
        .send()
        .await
        .expect("test request");
    acquired.assert_status_ok();
    assert_eq!(acquired.as_bytes().as_ref(), b"apk-1");
    let listed = get_json(&server, path, &admin).await;
    assert_eq!(
        listed[0]["groups"],
        json!([{"id": group_id, "name": "TV boxes"}])
    );
    assert!(listed[0]["last_used_at"].is_string());

    // Nothing administrative or publishing is open to a device token.
    for (method, path) in [
        ("GET", "/api/admin/apps"),
        ("POST", "/api/admin/apps"),
        ("GET", "/api/admin/apps/com.example.tv"),
        ("POST", "/api/admin/apps/com.example.tv/publications"),
        ("GET", "/api/admin/uploads"),
        ("GET", "/api/admin/users"),
        ("GET", "/api/admin/app-groups"),
        ("GET", "/api/admin/publish-tokens"),
        ("POST", "/api/admin/publish-tokens"),
        ("GET", "/api/admin/device-tokens"),
        ("POST", "/api/admin/device-tokens"),
        ("DELETE", &format!("/api/admin/device-tokens/{token_id}")),
    ] {
        assert_eq!(
            status(&server, method, path, &device).await,
            StatusCode::FORBIDDEN,
            "{method} {path}"
        );
    }

    // Creating with groups joins them atomically; an unknown group creates nothing.
    let response = server
        .post(path)
        .header("Authorization", &admin)
        .json(&json!({"name": "Bedroom TV", "group_ids": [group_id]}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::CREATED);
    let grouped: Value = response.json().unwrap();
    assert_eq!(
        grouped["groups"],
        json!([{"id": group_id, "name": "TV boxes"}])
    );
    let apps = get_json(
        &server,
        "/api/apps",
        &bearer(grouped["secret"].as_str().unwrap()),
    )
    .await;
    assert_eq!(apps["apps"].as_array().unwrap().len(), 1);
    let tokens_before: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    let response = server
        .post(path)
        .header("Authorization", &admin)
        .json(&json!({"name": "Ghost", "group_ids": [group_id, 999_999]}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::NOT_FOUND);
    let tokens_after: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(tokens_before, tokens_after);
    for body in [
        json!({"name": "Past", "expires_at": "2000-01-01T00:00:00Z"}),
        json!({"name": "Bad", "expires_at": "tomorrow"}),
        json!({"name": " "}),
    ] {
        let response = server
            .post(path)
            .header("Authorization", &admin)
            .json(&body)
            .send()
            .await
            .expect("test request");
        response.assert_status(StatusCode::BAD_REQUEST);
    }
    let response = server
        .post(path)
        .header("Authorization", &admin)
        .json(&json!({"name": "Extra", "package_name": "com.example.tv"}))
        .send()
        .await
        .expect("test request");
    assert!(response.status_code().is_client_error());
    let response = server
        .post(path)
        .header("Authorization", &admin)
        .json(&json!({"name": "Future", "expires_at": "2999-01-01T00:00:00Z"}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::CREATED);
    assert_eq!(
        response.json::<Value>().unwrap()["expires_at"],
        "2999-01-01 00:00:00"
    );

    // Revocation is idempotent, stops authentication and keeps the principal.
    for _ in 0..2 {
        let response = server
            .delete(&format!("{path}/{token_id}"))
            .header("Authorization", &admin)
            .send()
            .await
            .expect("test request");
        response.assert_status_ok();
        let revoked: Value = response.json().unwrap();
        assert!(revoked["revoked_at"].is_string());
        assert_eq!(revoked["id"], token_id.as_str());
    }
    for path in ["/api/apps", "/api/me", "/api/apps/com.example.tv"] {
        assert_eq!(
            status(&server, "GET", path, &device).await,
            StatusCode::UNAUTHORIZED,
            "{path}"
        );
    }
    assert_eq!(
        status(&server, "DELETE", &format!("{path}/missing"), &admin).await,
        StatusCode::NOT_FOUND
    );
    let users = get_json(&server, "/api/admin/users", &admin).await;
    assert!(users["users"]
        .as_array()
        .unwrap()
        .iter()
        .any(|u| u["subject"] == subject.as_str()));

    // Expired and unknown tokens do not authenticate.
    let expired = "lellostore_dev_expired-secret-for-test";
    sqlx::query("INSERT INTO device_tokens(id,name,token_hash,created_by,expires_at) VALUES ('expired','Old',?,'test-admin',datetime('now','-1 minute'))")
        .bind(hex::encode(Sha256::digest(expired.as_bytes())))
        .execute(&pool)
        .await
        .unwrap();
    for secret in [expired, "lellostore_dev_unknown"] {
        assert_eq!(
            status(&server, "GET", "/api/apps", &bearer(secret)).await,
            StatusCode::UNAUTHORIZED
        );
    }
}
