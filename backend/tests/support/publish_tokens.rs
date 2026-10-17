//! Per-app publish tokens: least-authority credentials for release pipelines.
use lellostore_backend::services::{upload_jobs, ApkParser, StorageService, UploadService};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use simple_server::testing::{MultipartForm, Part, TestServer};
use simple_server::web::http::StatusCode;

fn bearer(secret: &str) -> String {
    format!("Bearer {secret}")
}

fn apk_form(bytes: Vec<u8>) -> MultipartForm {
    MultipartForm::new()
        .add_text("publication", "draft")
        .add_text("distribution_mode", "normal")
        .add_part("file", Part::bytes(bytes).file_name("app.apk"))
}

async fn create_token(server: &TestServer, admin: &str, name: &str, package: &str) -> Value {
    let response = server
        .post("/api/admin/publish-tokens")
        .header("Authorization", admin)
        .json(&json!({"name": name, "package_name": package}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::CREATED);
    response.json().unwrap()
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

fn upload_inputs(ctx: &crate::common::TestContext) -> usize {
    std::fs::read_dir(ctx.storage_path.join("uploads"))
        .map(|entries| entries.count())
        .unwrap_or(0)
}

async fn job_count(pool: &sqlx::SqlitePool) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM upload_jobs")
        .fetch_one(pool)
        .await
        .unwrap()
}

#[tokio::test]
async fn publish_tokens_upload_poll_and_publish_only_their_package() {
    let (ctx, oidc) = super::create_auth_test_context().await;
    // The fake aapt2 reports every APK as com.test.app version 1.
    let worker = UploadService::new(
        StorageService::new(ctx.storage_path.clone()),
        ApkParser::new(ctx.temp_dir.path().join("fake-aapt2")),
        None,
        ctx.pool.clone(),
        100 * 1024 * 1024,
    );
    let pool = ctx.pool.clone();
    let server = TestServer::new(ctx.router.clone());
    let admin = bearer(&oidc.get_admin_token());
    let user = bearer(&oidc.get_user_token());

    // Only administrators manage tokens.
    let path = "/api/admin/publish-tokens";
    assert_eq!(
        status(&server, "GET", path, &user).await,
        StatusCode::FORBIDDEN
    );
    let created = create_token(&server, &admin, "Pezzottify CI", "com.test.app").await;
    let secret = created["secret"].as_str().unwrap().to_owned();
    let token_id = created["id"].as_str().unwrap().to_owned();
    assert!(secret.starts_with("lellostore_pub_"));
    assert_eq!(created["package_name"], "com.test.app");
    assert_eq!(created["created_by"], "test-admin");
    let other = create_token(&server, &admin, "Other CI", "com.other.app").await;
    let other_secret = other["secret"].as_str().unwrap().to_owned();
    let other_id = other["id"].as_str().unwrap().to_owned();
    let token = bearer(&secret);
    let other_token = bearer(&other_secret);

    // Only the SHA-256 of the secret is stored.
    let (hash, last_used): (String, Option<String>) =
        sqlx::query_as("SELECT token_hash, last_used_at FROM publish_tokens WHERE id = ?")
            .bind(&token_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(hash, hex::encode(Sha256::digest(secret.as_bytes())));
    assert!(last_used.is_none());
    let dump: Vec<(String,)> = sqlx::query_as(
        "SELECT id || name || package_name || token_hash || created_by FROM publish_tokens",
    )
    .fetch_all(&pool)
    .await
    .unwrap();
    assert!(dump.iter().all(|(row,)| !row.contains(&secret[15..])));
    let listed: Value = server
        .get(path)
        .header("Authorization", &admin)
        .send()
        .await
        .expect("test request")
        .json()
        .unwrap();
    assert_eq!(listed.as_array().unwrap().len(), 2);
    assert!(!listed.to_string().contains(&secret));
    assert!(listed
        .as_array()
        .unwrap()
        .iter()
        .all(|t| t.get("secret").is_none()));

    // Synchronous uploads are refused for tokens; asynchronous ones are queued.
    let response = server
        .post("/api/admin/apps")
        .header("Authorization", &token)
        .multipart(apk_form(crate::create_test_apk("com.test.app", 1)))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::BAD_REQUEST);
    let response = server
        .post("/api/admin/apps?asynchronous=true")
        .header("Authorization", &token)
        .multipart(apk_form(crate::create_test_apk("com.test.app", 1)))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::ACCEPTED);
    let job: Value = response.json().unwrap();
    let job_id = job["id"].as_str().unwrap().to_owned();
    assert_eq!(job["publish_token_id"], token_id.as_str());
    assert_eq!(job["actor_subject"], format!("publish-token:{token_id}"));
    assert!(upload_jobs::process_next(&pool, &worker).await.unwrap());

    // The job is visible to its token and to admins, but not to another token.
    let job_path = format!("/api/admin/uploads/{job_id}");
    let polled: Value = server
        .get(&job_path)
        .header("Authorization", &token)
        .send()
        .await
        .expect("test request")
        .json()
        .unwrap();
    assert_eq!(polled["status"], "ready", "{polled}");
    assert_eq!(
        status(&server, "GET", &job_path, &other_token).await,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        status(&server, "GET", &job_path, &admin).await,
        StatusCode::OK
    );
    sqlx::query("INSERT INTO upload_jobs(id,actor_subject,file_name,input_path,is_beta,status,kind,distribution_mode) VALUES ('admin-job','test-admin','a.apk','/nonexistent',0,'failed','apk','normal')")
        .execute(&pool)
        .await
        .unwrap();
    let admin_job = "admin-job";
    assert_eq!(
        status(
            &server,
            "GET",
            &format!("/api/admin/uploads/{admin_job}"),
            &token
        )
        .await,
        StatusCode::FORBIDDEN
    );

    // Reading and publishing its own package works; other packages do not.
    let app: Value = server
        .get("/api/admin/apps/com.test.app")
        .header("Authorization", &token)
        .send()
        .await
        .expect("test request")
        .json()
        .unwrap();
    assert_eq!(app["versions"][0]["publication_state"], "draft");
    assert_eq!(
        status(&server, "GET", "/api/admin/apps/com.test.app", &other_token).await,
        StatusCode::FORBIDDEN
    );
    let publication = json!({"version_code": 1, "expected_revision": app["publication_revision"]});
    let response = server
        .post("/api/admin/apps/com.test.app/publications")
        .header("Authorization", &other_token)
        .json(&publication)
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::FORBIDDEN);
    let response = server
        .post("/api/admin/apps/com.test.app/publications")
        .header("Authorization", &token)
        .json(&publication)
        .send()
        .await
        .expect("test request");
    response.assert_status_ok();
    let published: Value = server
        .get("/api/admin/apps/com.test.app")
        .header("Authorization", &admin)
        .send()
        .await
        .expect("test request")
        .json()
        .unwrap();
    assert_eq!(published["versions"][0]["publication_state"], "published");

    // An APK whose parsed package differs is refused before it is queued.
    let jobs = job_count(&pool).await;
    let inputs = upload_inputs(&ctx);
    let response = server
        .post("/api/admin/apps?asynchronous=true")
        .header("Authorization", &other_token)
        .multipart(apk_form(crate::create_test_apk("com.test.app", 2)))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::FORBIDDEN);
    assert_eq!(job_count(&pool).await, jobs);
    assert_eq!(upload_inputs(&ctx), inputs);

    // The worker enforces the same scope for anything that reaches the queue
    // (for example AABs), and discards the refused artifact.
    let source = ctx.temp_dir.path().join("queued.apk");
    std::fs::write(&source, crate::create_test_apk("com.test.app", 2)).unwrap();
    let queued = upload_jobs::enqueue_with_mode_for_token(
        &pool,
        &ctx.storage_path,
        &format!("publish-token:{other_id}"),
        "queued.apk",
        &source,
        None,
        None,
        false,
        "normal",
        Some(&other_id),
    )
    .await
    .unwrap();
    assert!(std::path::Path::new(&queued.input_path).exists());
    assert!(upload_jobs::process_next(&pool, &worker).await.unwrap());
    let refused = upload_jobs::get(&pool, &queued.id).await.unwrap();
    assert_eq!(refused.status, "failed");
    assert!(
        refused.error.as_deref().unwrap().starts_with("Forbidden:"),
        "{:?}",
        refused.error
    );
    assert!(!std::path::Path::new(&queued.input_path).exists());
    let versions: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM app_versions")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(versions, 1);
    assert_eq!(
        status(
            &server,
            "POST",
            &format!("/api/admin/uploads/{}/retry", queued.id),
            &admin
        )
        .await,
        StatusCode::CONFLICT
    );

    // VPK uploads are limited to the token's package and record the token.
    sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state) VALUES ('com.test.app',?,1,'stable','public','empty','https://store.test/','{}','{}','pending')")
        .bind("c".repeat(64))
        .execute(&pool)
        .await
        .unwrap();
    let vpk_path = format!(
        "/api/admin/apps/com.test.app/contracts/{}/vpks",
        "c".repeat(64)
    );
    let vpk_form = || {
        MultipartForm::new().add_part(
            "file",
            Part::bytes(b"vpk".to_vec()).file_name("payload.vpk"),
        )
    };
    let response = server
        .post(&vpk_path)
        .header("Authorization", &other_token)
        .multipart(vpk_form())
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::FORBIDDEN);
    let response = server
        .post(&vpk_path)
        .header("Authorization", &token)
        .multipart(vpk_form())
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::ACCEPTED);
    let vpk_job: Value = response.json().unwrap();
    assert_eq!(vpk_job["publish_token_id"], token_id.as_str());
    assert_eq!(vpk_job["kind"], "vpk");

    // Everything else is refused, including token management itself.
    for (method, path) in [
        ("GET", "/api/admin/apps"),
        ("PUT", "/api/admin/apps/com.test.app"),
        ("DELETE", "/api/admin/apps/com.test.app"),
        ("DELETE", "/api/admin/apps/com.test.app/versions/1"),
        ("POST", "/api/admin/apps/com.test.app/versions/1/withdraw"),
        ("GET", "/api/admin/apps/com.test.app/publications"),
        ("GET", "/api/admin/apps/com.test.app/distribution"),
        ("GET", "/api/admin/uploads"),
        ("POST", &format!("/api/admin/uploads/{job_id}/retry")),
        ("GET", "/api/admin/users"),
        ("GET", "/api/admin/app-groups"),
        ("GET", "/api/admin/paravoid/configuration"),
        ("GET", "/api/admin/publish-tokens"),
        ("POST", "/api/admin/publish-tokens"),
        ("DELETE", &format!("/api/admin/publish-tokens/{token_id}")),
        ("GET", "/api/me"),
        ("GET", "/api/apps"),
        ("GET", "/api/apps/com.test.app"),
    ] {
        assert_eq!(
            status(&server, method, path, &token).await,
            StatusCode::FORBIDDEN,
            "{method} {path}"
        );
    }
    assert_eq!(
        status(&server, "GET", "/api/admin/notifications", &token).await,
        StatusCode::UNAUTHORIZED
    );

    // Admins keep working everywhere, and see each token's use and uploads.
    for path in ["/api/admin/apps", "/api/admin/users", "/api/admin/uploads"] {
        assert_eq!(
            status(&server, "GET", path, &admin).await,
            StatusCode::OK,
            "{path}"
        );
    }
    let listed: Value = server
        .get("/api/admin/publish-tokens")
        .header("Authorization", &admin)
        .send()
        .await
        .expect("test request")
        .json()
        .unwrap();
    let mine = listed
        .as_array()
        .unwrap()
        .iter()
        .find(|t| t["id"] == token_id.as_str())
        .unwrap();
    assert!(mine["last_used_at"].is_string());
    let uploads = mine["recent_uploads"].as_array().unwrap();
    assert_eq!(uploads.len(), 2);
    assert_eq!(uploads[0]["kind"], "vpk");
    assert_eq!(uploads[1]["package_name"], "com.test.app");
    assert_eq!(uploads[1]["status"], "ready");

    // A revoked token stops authenticating, and its queued work is refused.
    let revoked: Value = server
        .delete(&format!("/api/admin/publish-tokens/{token_id}"))
        .header("Authorization", &admin)
        .send()
        .await
        .expect("test request")
        .json()
        .unwrap();
    assert!(revoked["revoked_at"].is_string());
    assert_eq!(
        status(&server, "GET", "/api/admin/apps/com.test.app", &token).await,
        StatusCode::UNAUTHORIZED
    );
    assert!(upload_jobs::process_next(&pool, &worker).await.unwrap());
    let vpk_result = upload_jobs::get(&pool, vpk_job["id"].as_str().unwrap())
        .await
        .unwrap();
    assert_eq!(vpk_result.status, "failed");
    assert!(vpk_result.error.unwrap().contains("revoked or expired"));
    assert_eq!(
        status(
            &server,
            "DELETE",
            "/api/admin/publish-tokens/missing",
            &admin
        )
        .await,
        StatusCode::NOT_FOUND
    );

    // Expired and unknown tokens do not authenticate.
    let expired = "lellostore_pub_expired-secret-for-test";
    sqlx::query("INSERT INTO publish_tokens(id,name,package_name,token_hash,created_by,expires_at) VALUES ('expired','Old','com.test.app',?,'test-admin',datetime('now','-1 minute'))")
        .bind(hex::encode(Sha256::digest(expired.as_bytes())))
        .execute(&pool)
        .await
        .unwrap();
    for secret in [expired, "lellostore_pub_unknown"] {
        assert_eq!(
            status(
                &server,
                "GET",
                "/api/admin/apps/com.test.app",
                &bearer(secret)
            )
            .await,
            StatusCode::UNAUTHORIZED
        );
    }
    let response = server
        .post("/api/admin/publish-tokens")
        .header("Authorization", &admin)
        .json(&json!({"name": "Past", "package_name": "com.test.app", "expires_at": "2000-01-01T00:00:00Z"}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::BAD_REQUEST);
    let response = server
        .post("/api/admin/publish-tokens")
        .header("Authorization", &admin)
        .json(&json!({"name": "Future", "package_name": "com.test.app", "expires_at": "2999-01-01T00:00:00Z"}))
        .send()
        .await
        .expect("test request");
    response.assert_status(StatusCode::CREATED);
    assert_eq!(
        response.json::<Value>().unwrap()["expires_at"],
        "2999-01-01 00:00:00"
    );
}
