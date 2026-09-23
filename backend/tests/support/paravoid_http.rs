//! Authenticated HTTP acceptance using ephemeral signing keys and real Android content.
//! This validates Store delivery, not installed Android lifecycle behavior.
use super::signed_shell::{apk, apk_with_payload, document, run};
use axum_test::{
    multipart::{MultipartForm, Part},
    TestServer,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use lellostore_backend::{
    db,
    paravoid::{canonical_json, shell_policy::ShellPolicyDocument, signing::OnlineSigning},
    services::{upload_jobs, ApkParser, StorageService, UploadService},
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use simple_server::axum::http::StatusCode;
use std::{
    collections::BTreeMap,
    io::{Cursor, Write},
    os::unix::fs::PermissionsExt,
    path::Path,
    process::Command,
    sync::Arc,
};

fn keys(directory: &std::path::Path) -> Arc<OnlineSigning> {
    for role in ["head", "grant"] {
        let pem = directory.join(format!("{role}.pem"));
        let der = directory.join(format!("{role}.pk8"));
        assert!(Command::new("openssl")
            .args([
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:3072",
                "-out"
            ])
            .arg(&pem)
            .output()
            .unwrap()
            .status
            .success());
        assert!(Command::new("openssl")
            .args(["pkcs8", "-topk8", "-nocrypt", "-outform", "DER", "-in"])
            .arg(&pem)
            .arg("-out")
            .arg(&der)
            .output()
            .unwrap()
            .status
            .success());
        std::fs::set_permissions(der, std::fs::Permissions::from_mode(0o600)).unwrap();
    }
    let config = directory.join("config.json");
    std::fs::write(&config,json!({"version":1,"baseUrl":"https://store.test/api/paravoid/","headKeys":{"head":"head.pk8"},"grantKeys":{"grant":"grant.pk8"},"activeHeadKey":"head","activeGrantKey":"grant"}).to_string()).unwrap();
    Arc::new(OnlineSigning::load(&config).unwrap())
}
fn payload(root: &Path, contract: &str) -> Vec<u8> {
    let mut files = BTreeMap::new();
    for (entry, source) in [
        ("code/classes.dex", "dex/classes.dex"),
        ("resources.apk", "resources.apk"),
        ("java-resources.jar", "java-resources.jar"),
        ("resource-ledger.json", "resource-ledger.json"),
    ] {
        files.insert(
            entry,
            std::fs::read(root.join("components").join(source)).unwrap(),
        );
    }
    let inventory: Vec<_> = files.iter().map(|(path, bytes)| json!({"path":path,"size":bytes.len(),"sha256":hex::encode(Sha256::digest(bytes))})).collect();
    let body = canonical_json(&json!({"version":1,"applicationId":"example.app","shellContractId":contract,"releaseId":"release-1","payloadVersion":1,"runtimeAbi":1,"formatVersion":1,"minSdk":30,"maxSdk":0,"abis":[],"ledgerSha256":hex::encode(Sha256::digest(&files["resource-ledger.json"])),"inventory":inventory})).unwrap();
    let mut input = b"paravoid/v1/release\n".to_vec();
    input.extend_from_slice(&body);
    std::fs::write(root.join("release-input"), input).unwrap();
    run(Command::new("openssl")
        .args(["dgst", "-sha256", "-sign"])
        .arg(root.join("release.pem"))
        .arg("-out")
        .arg(root.join("signature"))
        .arg(root.join("release-input")));
    files.insert("release.json", canonical_json(&json!({"keyId":"release","body":STANDARD.encode(body),"signature":STANDARD.encode(std::fs::read(root.join("signature")).unwrap())})).unwrap());
    let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
    for (name, bytes) in files {
        zip.start_file(
            name,
            zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Stored)
                .unix_permissions(0o644),
        )
        .unwrap();
        zip.write_all(&bytes).unwrap();
    }
    zip.finish().unwrap().into_inner()
}

#[tokio::test]
#[ignore = "requires Android SDK 36, APKSIGNER_PATH, Java, OpenSSL and local OIDC listener"]
async fn signed_shell_to_authenticated_http_delivery() {
    let sdk = std::path::PathBuf::from(std::env::var("ANDROID_HOME").unwrap());
    let root = tempfile::tempdir().unwrap();
    let signing = keys(root.path());
    run(Command::new("openssl")
        .args([
            "genpkey",
            "-algorithm",
            "RSA",
            "-pkeyopt",
            "rsa_keygen_bits:3072",
            "-out",
        ])
        .arg(root.path().join("release.pem")));
    run(Command::new("openssl")
        .args(["pkey", "-in"])
        .arg(root.path().join("release.pem"))
        .args(["-pubout", "-outform", "DER", "-out"])
        .arg(root.path().join("release.der")));
    run(Command::new("keytool")
        .args(["-genkeypair", "-keystore"])
        .arg(root.path().join("test.p12"))
        .args([
            "-storepass",
            "testpassword",
            "-keypass",
            "testpassword",
            "-alias",
            "test",
            "-dname",
            "CN=Ephemeral HTTP Test",
            "-keyalg",
            "RSA",
            "-keysize",
            "3072",
            "-validity",
            "2",
            "-noprompt",
        ]));
    run(Command::new("keytool")
        .args(["-exportcert", "-keystore"])
        .arg(root.path().join("test.p12"))
        .args(["-storepass", "testpassword", "-alias", "test", "-file"])
        .arg(root.path().join("cert.der")));
    let certificate = hex::encode(Sha256::digest(
        std::fs::read(root.path().join("cert.der")).unwrap(),
    ));
    run(Command::new("bash")
        .arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("../scripts/tests/build-vpk-components.sh"))
        .arg(root.path().join("components")));

    for (authentication, bootstrap) in [
        ("public", "empty"),
        ("apkKey", "empty"),
        ("public", "embedded"),
        ("apkKey", "embedded"),
    ] {
        let mut policy: Value = serde_json::from_slice(&document(&certificate)).unwrap();
        let ledger: Value = serde_json::from_slice(
            &std::fs::read(root.path().join("components/resource-ledger.json")).unwrap(),
        )
        .unwrap();
        let reservations: BTreeMap<_, _> = ledger["entries"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| (e["name"].as_str().unwrap(), e["id"].as_str().unwrap()))
            .collect();
        policy["descriptor"]["installed"]["ledgerReservations"] = json!(reservations);
        let public = signing.public_configuration();
        policy["descriptor"]["trustPolicy"]["headKeys"] = json!(public.head_keys);
        policy["descriptor"]["trustPolicy"]["grantKeys"] = json!(public.grant_keys);
        policy["descriptor"]["trustPolicy"]["releaseKeys"] = json!({"release":STANDARD.encode(std::fs::read(root.path().join("release.der")).unwrap())});
        policy["descriptor"]["distribution"]["baseUrl"] = json!(public.base_url);
        policy["descriptor"]["distribution"]["authentication"] = json!(authentication);
        policy["descriptor"]["distribution"]["bootstrap"] = json!(bootstrap);
        let contract = hex::encode(Sha256::digest(
            canonical_json(&policy["descriptor"]).unwrap(),
        ));
        policy["contractId"] = json!(contract);
        let policy = ShellPolicyDocument::parse(&canonical_json(&policy).unwrap()).unwrap();
        let policy_bytes = canonical_json(&json!({"version":1,"contractId":contract,"descriptor":serde_json::from_slice::<Value>(&policy.descriptor_bytes).unwrap()})).unwrap();
        let vpk = payload(root.path(), &contract);
        let embedded = (bootstrap == "embedded").then_some(vpk.as_slice());
        let source = apk_with_payload(root.path(), &sdk, 1, &policy_bytes, embedded);
        let source_bytes = std::fs::read(&source).unwrap();
        let (ctx, oidc) = super::create_auth_test_context_options(
            Default::default(),
            Some(signing.clone()),
            Some(&sdk),
        )
        .await;
        let worker = UploadService::new(
            StorageService::new(ctx.storage_path.clone()),
            ApkParser::new(sdk.join("build-tools/36.0.0/aapt2")),
            None,
            ctx.pool.clone(),
            100 * 1024 * 1024,
        );
        // A signed APK must not register if its embedded bytes are absent,
        // damaged, or signed for another contract. No partial draft may survive.
        if bootstrap == "embedded" {
            let wrong = payload(root.path(), &"a".repeat(64));
            for candidate in [None, Some(&b"corrupt"[..]), Some(wrong.as_slice())] {
                let invalid = apk_with_payload(root.path(), &sdk, 9, &policy_bytes, candidate);
                assert!(worker
                    .process_distribution_draft_upload(
                        "invalid.apk",
                        &invalid,
                        None,
                        None,
                        false,
                        "paravoid"
                    )
                    .await
                    .is_err());
                assert!(db::get_app(&ctx.pool, "example.app")
                    .await
                    .unwrap()
                    .is_none());
            }
        } else {
            let invalid = apk_with_payload(root.path(), &sdk, 9, &policy_bytes, Some(&vpk));
            assert!(worker
                .process_distribution_draft_upload(
                    "invalid.apk",
                    &invalid,
                    None,
                    None,
                    false,
                    "paravoid"
                )
                .await
                .is_err());
        }
        let server = TestServer::new(ctx.router).unwrap();
        let admin = format!("Bearer {}", oidc.get_admin_token());
        let user = format!("Bearer {}", oidc.get_user_token());
        let uploaded = server
            .post("/api/admin/apps?asynchronous=true")
            .add_header("Authorization", &admin)
            .multipart(
                MultipartForm::new()
                    .add_text("publication", "draft")
                    .add_text("distribution_mode", "paravoid")
                    .add_part(
                        "file",
                        Part::bytes(source_bytes.clone()).file_name("shell.apk"),
                    ),
            )
            .await;
        uploaded.assert_status(StatusCode::ACCEPTED);
        upload_jobs::process_next(&ctx.pool, &worker).await.unwrap();
        let job = uploaded.json::<Value>();
        let ready = server
            .get(&format!(
                "/api/admin/uploads/{}",
                job["id"].as_str().unwrap()
            ))
            .add_header("Authorization", &admin)
            .await;
        ready.assert_status_ok();
        assert_eq!(ready.json::<Value>()["status"], "ready", "{}", ready.text());
        server
            .get("/api/apps/example.app")
            .add_header("Authorization", &user)
            .await
            .assert_status_not_found();
        if bootstrap == "empty" {
            let uploaded = server
                .post(&format!(
                    "/api/admin/apps/example.app/contracts/{contract}/vpks"
                ))
                .add_header("Authorization", &admin)
                .multipart(
                    MultipartForm::new()
                        .add_part("file", Part::bytes(vpk.clone()).file_name("payload.vpk")),
                )
                .await;
            uploaded.assert_status(StatusCode::ACCEPTED);
            upload_jobs::process_next(&ctx.pool, &worker).await.unwrap();
            let completed =
                upload_jobs::get(&ctx.pool, uploaded.json::<Value>()["id"].as_str().unwrap())
                    .await
                    .unwrap();
            assert_eq!(completed.status, "ready", "{:?}", completed.error);
        }
        let overview = server
            .get("/api/admin/apps/example.app/distribution")
            .add_header("Authorization", &admin)
            .await
            .json::<Value>();
        assert_eq!(
            overview["releases"][0]["validation_state"], "verified",
            "{overview}"
        );
        assert_eq!(overview["releases"][0]["publication_state"], "draft");
        let id = overview["releases"][0]["id"].as_str().unwrap();
        if bootstrap == "embedded" {
            assert_eq!(overview["installers"][0]["embedded_vpk_id"], id);
            server.post("/api/admin/apps/example.app/publications").add_header("Authorization", &admin)
                .json(&json!({"version_code":1,"expected_revision":overview["publication_revision"],"bootstrap_vpk":"different-payload"})).await.assert_status_conflict();
        }
        let publish = json!({"version_code":1,"expected_revision":overview["publication_revision"],"bootstrap_vpk": if bootstrap == "embedded" { Value::Null } else { json!(id) }});
        server
            .post("/api/admin/apps/example.app/publications")
            .add_header("Authorization", &admin)
            .json(&publish)
            .await
            .assert_status_ok();
        server
            .post("/api/admin/apps/example.app/publications")
            .add_header("Authorization", &admin)
            .json(&publish)
            .await
            .assert_status_conflict();
        db::access::set_direct_grant(
            &ctx.pool,
            "test-user",
            "example.app",
            db::access::AppAccessLevel::Stable,
        )
        .await
        .unwrap();
        let catalog = server
            .get("/api/apps/example.app")
            .add_header("Authorization", &user)
            .await;
        catalog.assert_status_ok();
        assert_eq!(catalog.json::<Value>()["distribution_mode"], "paravoid");
        let request =
            json!({"version_code":1,"purpose":"install","idempotency_key":"http-install"});
        let acquisition = server
            .post("/api/apps/example.app/acquisitions")
            .add_header("Authorization", &user)
            .json(&request)
            .await;
        acquisition.assert_status_ok();
        let acquired = acquisition.json::<Value>();
        let retry = server
            .post("/api/apps/example.app/acquisitions")
            .add_header("Authorization", &user)
            .json(&request)
            .await
            .json::<Value>();
        assert_eq!(acquired, retry);
        let delivered = server
            .get(acquired["apk_url"].as_str().unwrap())
            .add_header("Authorization", &user)
            .await;
        delivered.assert_status_ok();
        assert_eq!(acquired["size"], delivered.as_bytes().len());
        assert_eq!(
            acquired["sha256"],
            hex::encode(Sha256::digest(delivered.as_bytes()))
        );
        let downloaded = root.path().join("delivered.apk");
        std::fs::write(&downloaded, delivered.as_bytes()).unwrap();
        assert_eq!(
            lellostore_backend::services::apk_signatures::verified_signer(&downloaded)
                .await
                .unwrap(),
            certificate
        );
        let stored = db::paravoid::contract(&ctx.pool, "example.app", &contract)
            .await
            .unwrap();
        let pinned = stored.policy().unwrap();
        let head = format!("/api/paravoid/v1/apps/example.app/head?contract={contract}&channel=stable&sdk=30&abis=x86_64&runtime=1&format=1&protocol=1");
        let payload_url = "/api/paravoid/v1/apps/example.app/releases/release-1/payload.vpk";
        let mut head_request = server.get(&head);
        let mut payload_request = server.get(payload_url);
        let grant = if authentication == "apkKey" {
            assert_ne!(delivered.as_bytes().as_ref(), source_bytes);
            server
                .get("/api/apps/example.app/versions/1/apk")
                .add_header("Authorization", &user)
                .await
                .assert_status_conflict();
            server.get(&head).await.assert_status_unauthorized();
            let bytes = lellostore_backend::paravoid::apk_grant::read(
                &mut std::fs::File::open(&downloaded).unwrap(),
            )
            .unwrap();
            let grant = pinned.verify_grant(&bytes).unwrap();
            let bearer = format!("Bearer {}", grant.credential());
            head_request = head_request.add_header("Authorization", &bearer);
            payload_request = payload_request.add_header("Authorization", &bearer);
            Some(grant)
        } else {
            assert_eq!(delivered.as_bytes().as_ref(), source_bytes);
            None
        };
        let response = head_request.await;
        response.assert_status_ok();
        let verified = pinned
            .verify_head(response.as_bytes(), 30, &["x86_64".into()])
            .unwrap();
        assert_eq!(
            verified.body().status,
            lellostore_backend::paravoid::HeadStatus::Available
        );
        let payload_response = payload_request.await;
        payload_response.assert_status_ok();
        assert_eq!(payload_response.as_bytes().as_ref(), vpk);
        if let Some(grant) = grant {
            let overview = server
                .get("/api/admin/apps/example.app/distribution")
                .add_header("Authorization", &admin)
                .await
                .json::<Value>();
            server
                .post(&format!(
                    "/api/admin/apps/example.app/grants/{}/revoke",
                    grant.grant_id()
                ))
                .add_header("Authorization", &admin)
                .json(&json!({"expected_revision":overview["publication_revision"]}))
                .await
                .assert_status(StatusCode::NO_CONTENT);
            let bearer = format!("Bearer {}", grant.credential());
            server
                .get(&head)
                .add_header("Authorization", &bearer)
                .add_header("If-None-Match", response.header("etag").clone())
                .await
                .assert_status_forbidden();
            server
                .get(payload_url)
                .add_header("Authorization", &bearer)
                .add_header("Range", "bytes=0-15")
                .await
                .assert_status_forbidden();
        }

        // Publishing automatically verifies signing continuity in both directions.
        for (code, mode) in [(2, "normal"), (3, "paravoid")] {
            let bytes = if mode == "normal" {
                &[][..]
            } else {
                policy_bytes.as_slice()
            };
            let path = if mode == "normal" {
                apk(root.path(), &sdk, code, bytes)
            } else {
                apk_with_payload(root.path(), &sdk, code, bytes, embedded)
            };
            server
                .post("/api/admin/apps")
                .add_header("Authorization", &admin)
                .multipart(
                    MultipartForm::new()
                        .add_text("publication", "draft")
                        .add_text("distribution_mode", mode)
                        .add_part(
                            "file",
                            Part::bytes(std::fs::read(path).unwrap()).file_name("installer.apk"),
                        ),
                )
                .await
                .assert_status(StatusCode::CREATED);
            let overview = server
                .get("/api/admin/apps/example.app/distribution")
                .add_header("Authorization", &admin)
                .await
                .json::<Value>();
            let bootstrap = if mode == "paravoid" {
                json!(id)
            } else {
                Value::Null
            };
            server.post("/api/admin/apps/example.app/publications").add_header("Authorization", &admin)
                .json(&json!({"version_code":code,"expected_revision":overview["publication_revision"],"bootstrap_vpk":bootstrap,"replace_latest":true}))
                .await.assert_status_ok();
            let reviews = server
                .get("/api/admin/apps/example.app/distribution-reviews")
                .add_header("Authorization", &admin)
                .await
                .json::<Value>();
            assert_eq!(reviews[0]["signer_sha256"], certificate);
            let evidence: Value =
                serde_json::from_str(reviews[0]["migration_evidence"].as_str().unwrap()).unwrap();
            assert_eq!(evidence["verification"], "automatic");
            assert!(evidence.get("tested_upgrade").is_none());
            let app = server
                .get("/api/apps/example.app")
                .add_header("Authorization", &user)
                .await
                .json::<Value>();
            assert_eq!(app["distribution_mode"], mode);
            let retained = db::paravoid::release(&ctx.pool, "example.app", id)
                .await
                .unwrap();
            assert_eq!(retained.publication_state, "published");
        }
    }
}
