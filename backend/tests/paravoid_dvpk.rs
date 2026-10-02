//! DVPK generation, discovery negotiation, revision epochs and delta transport.
//! Hermetic tests use a zlib-only stand-in encoder with the reference CLI and
//! report shape; ignored tests use the vendored reference encoder and upstream
//! Java decoder (see scripts/check-paravoid-dvpk.sh).
#[allow(dead_code)]
mod common;
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use lellostore_backend::{
    config::DvpkConfig,
    db::{self, dvpk, paravoid},
    paravoid::signing::OnlineSigning,
    services::{dvpk::Worker, retention},
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use simple_server::{
    testing::{TestResponse, TestServer},
    web::http::StatusCode,
};
use std::{
    collections::HashMap,
    os::unix::fs::PermissionsExt,
    path::{Path, PathBuf},
    process::Command,
    sync::Arc,
};

const ALGORITHM: &str = "bsdiff-deflate-v1";

/// Same CLI and JSON report as the reference encoder. One control record adds
/// bytewise differences, which compress well for same-length targets.
const STAND_IN_ENCODER: &str = r#"
import hashlib, json, struct, sys, time, zlib
from pathlib import Path
mode_file = Path(__file__).with_name('mode')
mode = mode_file.read_text().strip() if mode_file.exists() else 'diff'
base, target, output = (Path(a) for a in sys.argv[1:4])
b, t = base.read_bytes(), target.read_bytes()
if mode == 'fail':
    sys.exit('simulated encoder crash')
if mode == 'sleep':
    time.sleep(30)
def deflate(data):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()
diff = bytes((t[i] - (b[i] if i < len(b) else 0)) & 255 for i in range(len(t)))
if mode == 'corrupt':
    diff = bytes([diff[0] ^ 1]) + diff[1:]
blocks = [deflate(struct.pack('<qqq', len(t), 0, 0)), deflate(diff), deflate(b'')]
patch = b'DVPKD001' + struct.pack('<qqq', len(blocks[0]), len(blocks[1]), len(t)) + b''.join(blocks)
output.write_bytes(patch)
digest = '0' * 64 if mode == 'lie' else hashlib.sha256(patch).hexdigest()
print(json.dumps({'delta': {'algorithm': 'bsdiff-deflate-v1', 'baseArchiveSha256': hashlib.sha256(b).hexdigest(),
    'baseArchiveSize': len(b), 'patchSha256': digest, 'patchSize': len(patch)},
    'target': {'archiveSha256': hashlib.sha256(t).hexdigest(), 'archiveSize': len(t)}}))
"#;

fn contract() -> String {
    "a".repeat(64)
}
fn sha(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn keys(directory: &Path) -> Arc<OnlineSigning> {
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

/// Deterministic pseudo-random archive bytes.
fn archive(seed: u32, len: usize) -> Vec<u8> {
    let mut state = seed.wrapping_mul(2_654_435_761).wrapping_add(1);
    (0..len)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            state as u8
        })
        .collect()
}
fn edited(base: &[u8]) -> Vec<u8> {
    let mut target = base.to_vec();
    for i in (0..target.len()).step_by(997) {
        target[i] = target[i].wrapping_add(3);
    }
    target
}

struct Fixture {
    dir: tempfile::TempDir,
    ctx: common::TestContext,
    config: DvpkConfig,
    signing: Arc<OnlineSigning>,
}
impl Fixture {
    async fn new(advertising: bool, auth: &str) -> Self {
        Self::with(advertising, auth, |_| {}).await
    }
    async fn with(advertising: bool, auth: &str, change: impl FnOnce(&mut DvpkConfig)) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let signing = keys(dir.path());
        let encoder = dir.path().join("encoder.py");
        std::fs::write(&encoder, STAND_IN_ENCODER).unwrap();
        let mut config = DvpkConfig {
            generation: true,
            advertising,
            encoder: Some(encoder),
            timeout_secs: 20,
            ..Default::default()
        };
        change(&mut config);
        // Every test in this binary runs with generation enabled.
        dvpk::set_generation_enabled(true);
        let ctx = common::create_dvpk_test_context(signing.clone(), config.clone()).await;
        let public = signing.public_configuration();
        let mut trust: Value =
            serde_json::from_str(include_str!("fixtures/paravoid-metadata/trust.json")).unwrap();
        trust["applicationId"] = json!("test.app");
        trust["headKeys"] = json!(public.head_keys);
        trust["grantKeys"] = json!(public.grant_keys);
        sqlx::query("INSERT INTO apps(package_name,name,distribution_mode) VALUES ('test.app','Test','paravoid')").execute(&ctx.pool).await.unwrap();
        sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk,distribution_mode) VALUES ('test.app',1,'1','apk',3,'hash',30,'paravoid')").execute(&ctx.pool).await.unwrap();
        sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state) VALUES ('test.app',?,1,'stable',?,'embedded','https://store.test/api/paravoid/',?,'{}','verified')")
            .bind(contract()).bind(auth).bind(trust.to_string()).execute(&ctx.pool).await.unwrap();
        Self {
            dir,
            ctx,
            config,
            signing,
        }
    }
    fn mode(&self, mode: &str) {
        std::fs::write(self.dir.path().join("mode"), mode).unwrap();
    }
    async fn release(&self, version: i64, bytes: &[u8]) -> String {
        let hash = sha(bytes);
        let path = format!("vpks/{hash}.vpk");
        std::fs::create_dir_all(self.ctx.storage_path.join("vpks")).unwrap();
        std::fs::write(self.ctx.storage_path.join(&path), bytes).unwrap();
        let id = format!("vpk-{version}");
        sqlx::query("INSERT INTO vpk_releases(id,package_name,contract_id,release_id,payload_version,archive_path,archive_size,archive_sha256,manifest_sha256,manifest_json,min_sdk,max_sdk,abis_json,signing_key_id,validation_state,validation_report) VALUES (?,'test.app',?,?,?,?,?,?,?,'{}',30,0,'[]','release','verified','{}')")
            .bind(&id).bind(contract()).bind(format!("release-{version}")).bind(version).bind(path).bind(bytes.len() as i64).bind(hash).bind("b".repeat(64))
            .execute(&self.ctx.pool).await.unwrap();
        id
    }
    async fn publish(&self, id: &str) {
        let revision = db::get_app(&self.ctx.pool, "test.app")
            .await
            .unwrap()
            .unwrap()
            .publication_revision;
        paravoid::publish(&self.ctx.pool, "test.app", id, "admin", revision)
            .await
            .unwrap();
    }
    async fn enqueue(&self, id: &str) -> u64 {
        let mut conn = self.ctx.pool.acquire().await.unwrap();
        dvpk::enqueue_for_target(&mut conn, "test.app", id)
            .await
            .unwrap()
    }
    /// Claim and process one job, returning its recorded state.
    async fn generate(&self) -> Option<dvpk::Delta> {
        let job = dvpk::claim(&self.ctx.pool).await.unwrap()?;
        Worker::new(
            self.ctx.pool.clone(),
            self.ctx.storage_path.clone(),
            self.config.clone(),
        )
        .unwrap()
        .process(&job)
        .await
        .unwrap();
        Some(self.delta(&job.id).await)
    }
    async fn delta(&self, id: &str) -> dvpk::Delta {
        sqlx::query_as("SELECT * FROM vpk_deltas WHERE id = ?")
            .bind(id)
            .fetch_one(&self.ctx.pool)
            .await
            .unwrap()
    }
    fn server(&self) -> TestServer {
        TestServer::new(self.ctx.router.clone())
    }
    async fn verify(&self, envelope: &[u8]) {
        paravoid::contract(&self.ctx.pool, "test.app", &contract())
            .await
            .unwrap()
            .policy()
            .unwrap()
            .verify_head(envelope, 30, &["x86_64".into()])
            .unwrap();
    }
}

fn head_url() -> String {
    format!("/api/paravoid/v1/apps/test.app/head?contract={}&channel=stable&sdk=30&abis=x86_64&runtime=1&format=1&protocol=1",contract())
}
async fn head(server: &TestServer, capable: bool, base: Option<&str>) -> TestResponse {
    let mut request = server.get(&head_url());
    if capable {
        request = request.header("X-Paravoid-Dvpk", ALGORITHM);
    }
    if let Some(base) = base {
        request = request.header("X-Paravoid-Base-Sha256", base);
    }
    request.send().await.expect("test request")
}
fn body(response: &TestResponse) -> Value {
    let envelope: Value = response.json().unwrap();
    serde_json::from_slice(&STANDARD.decode(envelope["body"].as_str().unwrap()).unwrap()).unwrap()
}
fn delta_url(release: &str, base: &str) -> String {
    format!("/api/paravoid/v1/apps/test.app/releases/{release}/deltas/{base}/payload.dvpk")
}

#[tokio::test]
async fn patches_wait_for_publication_and_reach_capable_shells_through_a_new_revision() {
    let f = Fixture::new(true, "public").await;
    let base = archive(1, 200_000);
    let target = edited(&base);
    let v1 = f.release(1, &base).await;
    f.publish(&v1).await;
    let v2 = f.release(2, &target).await;
    // Draft upload enqueues once; repeated enqueue attempts deduplicate.
    assert_eq!(f.enqueue(&v2).await, 1);
    assert_eq!(f.enqueue(&v2).await, 0);
    let ready = f.generate().await.unwrap();
    assert_eq!(ready.state, "ready", "{:?}", ready.failure);
    let patch_size = ready.patch_size.unwrap() as u64;
    let patch_sha = ready.patch_sha256.clone().unwrap();
    assert!(patch_size * 5 <= target.len() as u64 * 4);
    assert_eq!(
        ready.patch_path.as_deref(),
        Some(format!("dvpks/{patch_sha}.dvpk").as_str())
    );
    assert!(ready
        .encoder_version
        .unwrap()
        .starts_with("reference-dvpk.py:"));
    assert!(dvpk::claim(&f.ctx.pool).await.unwrap().is_none());

    // An unpublished target is never offered.
    let server = f.server();
    let held = head(&server, true, Some(&sha(&base))).await;
    held.assert_status_ok();
    assert_eq!(body(&held)["release"]["releaseId"], "release-1");
    assert!(body(&held)["release"].get("deltas").is_none());

    f.publish(&v2).await;
    // The replaced payload stays stored as a base for the next target.
    retention::cleanup_replaced(&f.ctx.pool, &f.ctx.storage_path)
        .await
        .unwrap();
    assert!(f
        .ctx
        .storage_path
        .join(format!("vpks/{}.vpk", sha(&base)))
        .exists());

    let legacy = head(&server, false, None).await;
    legacy.assert_status_ok();
    let legacy_body = body(&legacy);
    assert_eq!(legacy_body["release"]["releaseId"], "release-2");
    assert!(legacy_body["release"].get("deltas").is_none());
    f.verify(legacy.as_bytes()).await;

    let capable = head(&server, true, Some(&sha(&base))).await;
    capable.assert_status_ok();
    let capable_body = body(&capable);
    f.verify(capable.as_bytes()).await;
    assert_eq!(
        capable_body["headRevision"].as_u64().unwrap(),
        legacy_body["headRevision"].as_u64().unwrap() + 1
    );
    assert_eq!(
        capable_body["release"]["deltas"],
        json!([{"algorithm":ALGORITHM,"baseArchiveSha256":sha(&base),"baseArchiveSize":base.len(),"patchSha256":patch_sha,"patchSize":patch_size}])
    );
    // Identity fields are unchanged by the delivery alternative.
    for field in [
        "releaseId",
        "payloadVersion",
        "manifestSha256",
        "archiveSha256",
        "archiveSize",
    ] {
        assert_eq!(
            capable_body["release"][field],
            legacy_body["release"][field]
        );
    }
    let vary = capable.headers()["vary"].to_str().unwrap();
    assert!(vary.contains("X-Paravoid-Dvpk") && vary.contains("X-Paravoid-Base-Sha256"));
    let etag = capable.headers()["etag"].to_str().unwrap().to_owned();
    assert_eq!(etag, format!("\"{}\"", sha(capable.as_bytes())));
    let cached = server
        .get(&head_url())
        .header("X-Paravoid-Dvpk", ALGORITHM)
        .header("If-None-Match", &etag)
        .send()
        .await
        .unwrap();
    cached.assert_status(StatusCode::NOT_MODIFIED);
    // The legacy validator never matches the capable representation.
    let legacy_etag = legacy.headers()["etag"].to_str().unwrap();
    assert_ne!(legacy_etag, etag);

    // Exact artifact transport.
    let url = delta_url("release-2", &sha(&base));
    let full = server.get(&url).send().await.unwrap();
    full.assert_status_ok();
    let headers = full.headers();
    assert_eq!(headers["content-type"], "application/vnd.paravoid.dvpk");
    assert_eq!(headers["content-encoding"], "identity");
    assert_eq!(
        headers["etag"].to_str().unwrap(),
        format!("\"{patch_sha}\"")
    );
    assert_eq!(
        headers["content-length"].to_str().unwrap(),
        patch_size.to_string()
    );
    let bytes = full.as_bytes().to_vec();
    assert_eq!(sha(&bytes), patch_sha);
    let dir = tempfile::tempdir().unwrap();
    let paths = ["base", "patch", "target"].map(|n| dir.path().join(n));
    for (path, data) in paths.iter().zip([&base, &bytes, &target]) {
        std::fs::write(path, data).unwrap();
    }
    lellostore_backend::paravoid::dvpk::verify(&paths[0], &paths[1], &paths[2]).unwrap();

    let partial = server
        .get(&url)
        .header("Range", "bytes=0-9")
        .send()
        .await
        .unwrap();
    partial.assert_status(StatusCode::PARTIAL_CONTENT);
    assert_eq!(partial.headers()["content-length"], "10");
    assert_eq!(
        partial.headers()["content-range"].to_str().unwrap(),
        format!("bytes 0-9/{patch_size}")
    );
    assert_eq!(partial.as_bytes().as_ref(), &bytes[..10]);
    let stale = server
        .get(&url)
        .header("Range", "bytes=0-9")
        .header("If-Range", "\"stale\"")
        .send()
        .await
        .unwrap();
    stale.assert_status_ok();
    assert_eq!(stale.as_bytes().len() as u64, patch_size);
    server
        .get(&url)
        .header("Range", &format!("bytes={patch_size}-"))
        .send()
        .await
        .unwrap()
        .assert_status(StatusCode::RANGE_NOT_SATISFIABLE);
    for missing in [
        delta_url("release-2", &sha(b"other")),
        delta_url("release-2", "not-a-hash"),
        delta_url("release-2", &sha(&base).to_uppercase()),
        delta_url("release-1", &sha(&base)),
        delta_url("release-9", &sha(&base)),
    ] {
        server
            .get(&missing)
            .send()
            .await
            .unwrap()
            .assert_status(StatusCode::NOT_FOUND);
    }
    // The full archive remains available, unchanged.
    let full_vpk = server
        .get("/api/paravoid/v1/apps/test.app/releases/release-2/payload.vpk")
        .send()
        .await
        .unwrap();
    full_vpk.assert_status_ok();
    assert_eq!(
        full_vpk.headers()["content-type"],
        "application/vnd.paravoid.vpk"
    );
    assert_eq!(full_vpk.as_bytes().as_ref(), target.as_slice());
}

/// Minimal model of the shell's admission rule: a revision below the observed
/// high-water mark is a replay; at an equal revision, the same request scope
/// must have identical signed bytes.
#[derive(Default)]
struct Device {
    revision: u64,
    bodies: HashMap<u64, String>,
}
impl Device {
    fn observe(&mut self, response: &TestResponse) -> u64 {
        response.assert_status_ok();
        let revision = body(response)["headRevision"].as_u64().unwrap();
        let digest = sha(response.as_bytes());
        assert!(revision >= self.revision, "replayed revision {revision}");
        if revision > self.revision {
            self.revision = revision;
            self.bodies.clear();
        }
        if let Some(known) = self.bodies.insert(revision, digest.clone()) {
            assert_eq!(known, digest, "conflicting body at revision {revision}");
        }
        revision
    }
}

#[tokio::test]
async fn negotiation_and_revision_epochs_never_conflict_for_upgrading_shells() {
    let f = Fixture::new(true, "public").await;
    let base = archive(2, 100_000);
    let v1 = f.release(1, &base).await;
    f.publish(&v1).await;
    let server = f.server();
    for (name, value) in [
        ("X-Paravoid-Base-Sha256", "xyz"),
        ("X-Paravoid-Base-Sha256", &sha(&base).to_uppercase()),
    ] {
        server
            .get(&head_url())
            .header("X-Paravoid-Dvpk", ALGORITHM)
            .header(name, value)
            .send()
            .await
            .unwrap()
            .assert_status(StatusCode::BAD_REQUEST);
    }
    for name in ["X-Paravoid-Dvpk", "X-Paravoid-Base-Sha256"] {
        let value = if name == "X-Paravoid-Dvpk" {
            ALGORITHM.to_string()
        } else {
            sha(&base)
        };
        server
            .get(&head_url())
            .header(name, &value)
            .header(name, &value)
            .send()
            .await
            .unwrap()
            .assert_status(StatusCode::BAD_REQUEST);
    }

    let mut legacy = Device::default();
    let mut upgraded = Device::default();
    let first = legacy.observe(&head(&server, false, None).await);
    // An unknown capability receives the identical full-only representation.
    let unknown = server
        .get(&head_url())
        .header("X-Paravoid-Dvpk", "bsdiff-deflate-v9")
        .send()
        .await
        .unwrap();
    assert_eq!(legacy.observe(&unknown), first);
    // The same device updated to a capable shell keeps its replay history.
    upgraded.observe(&head(&server, false, None).await);
    let capable = upgraded.observe(&head(&server, true, None).await);
    assert_eq!(capable, first + 1);
    // Repeated capable requests reuse exact bytes, also with another base hint.
    upgraded.observe(&head(&server, true, Some(&sha(b"other"))).await);

    // Expiry refresh.
    sqlx::query("UPDATE paravoid_streams SET expires_at = 0")
        .execute(&f.ctx.pool)
        .await
        .unwrap();
    let refreshed = upgraded.observe(&head(&server, true, None).await);
    assert!(refreshed > capable);
    assert!(legacy.observe(&head(&server, false, None).await) > capable);

    // A new published target, then delayed patch readiness.
    let target = edited(&base);
    let v2 = f.release(2, &target).await;
    f.publish(&v2).await;
    let without = upgraded.observe(&head(&server, true, Some(&sha(&base))).await);
    assert!(without > refreshed);
    assert!(body(&head(&server, true, None).await)["release"]
        .get("deltas")
        .is_none());
    legacy.observe(&head(&server, false, None).await);
    assert_eq!(f.generate().await.unwrap().state, "ready");
    let offered_response = head(&server, true, Some(&sha(&base))).await;
    let offered = upgraded.observe(&offered_response);
    assert!(offered > without);
    assert_eq!(
        body(&offered_response)["release"]["deltas"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert!(body(&head(&server, false, None).await)["release"]
        .get("deltas")
        .is_none());
    legacy.observe(&head(&server, false, None).await);

    // A stored advertising state from a different configuration starts a new
    // epoch instead of re-signing an observed revision.
    sqlx::query("UPDATE paravoid_streams SET dvpk_advertising = 0")
        .execute(&f.ctx.pool)
        .await
        .unwrap();
    let toggled = upgraded.observe(&head(&server, true, None).await);
    assert!(toggled > offered);
    legacy.observe(&head(&server, false, None).await);

    // Rollback: a lost patch is withdrawn through a fresh revision, and the
    // shell falls back to the full archive, which remains served.
    let patch = f
        .delta(&dvpk::list(&f.ctx.pool, "test.app").await.unwrap()[0].id)
        .await;
    std::fs::remove_file(f.ctx.storage_path.join(patch.patch_path.unwrap())).unwrap();
    server
        .get(&delta_url("release-2", &sha(&base)))
        .send()
        .await
        .unwrap()
        .assert_status(StatusCode::NOT_FOUND);
    assert_eq!(f.delta(&patch.id).await.state, "failed");
    let rolled_back_response = head(&server, true, None).await;
    assert!(upgraded.observe(&rolled_back_response) > toggled);
    assert!(body(&rolled_back_response)["release"]
        .get("deltas")
        .is_none());
    legacy.observe(&head(&server, false, None).await);
    server
        .get("/api/paravoid/v1/apps/test.app/releases/release-2/payload.vpk")
        .send()
        .await
        .unwrap()
        .assert_status_ok();
}

#[tokio::test]
async fn advertising_off_gives_capable_shells_the_full_only_head() {
    let f = Fixture::new(false, "public").await;
    let base = archive(3, 100_000);
    let v1 = f.release(1, &base).await;
    f.publish(&v1).await;
    let v2 = f.release(2, &edited(&base)).await;
    f.enqueue(&v2).await;
    assert_eq!(f.generate().await.unwrap().state, "ready");
    f.publish(&v2).await;
    let server = f.server();
    let legacy = head(&server, false, None).await;
    let capable = head(&server, true, Some(&sha(&base))).await;
    assert_eq!(legacy.as_bytes(), capable.as_bytes());
    assert!(body(&capable)["release"].get("deltas").is_none());
    // Generation-only rollout: the verified patch can be fetched for inspection.
    server
        .get(&delta_url("release-2", &sha(&base)))
        .send()
        .await
        .unwrap()
        .assert_status_ok();
}

#[tokio::test]
async fn keyed_delta_downloads_require_the_target_grant() {
    let f = Fixture::new(true, "apkKey").await;
    let base = archive(4, 100_000);
    let v1 = f.release(1, &base).await;
    f.publish(&v1).await;
    let v2 = f.release(2, &edited(&base)).await;
    f.enqueue(&v2).await;
    assert_eq!(f.generate().await.unwrap().state, "ready");
    f.publish(&v2).await;
    let key = URL_SAFE_NO_PAD.encode([7_u8; 32]);
    sqlx::query("INSERT INTO paravoid_grants(id,key_id,credential_sha256,package_name,contract_id,installer_version,user_subject,acquisition_id,issued_at) VALUES ('grant','key',?,'test.app',?,1,'alice','copy',1)")
        .bind(sha(key.as_bytes())).bind(contract()).execute(&f.ctx.pool).await.unwrap();
    let server = f.server();
    let url = delta_url("release-2", &sha(&base));
    let token = format!("Bearer {key}");
    server
        .get(&url)
        .send()
        .await
        .unwrap()
        .assert_status(StatusCode::UNAUTHORIZED);
    // A valid grant without app entitlement, then with it.
    server
        .get(&url)
        .header("Authorization", &token)
        .send()
        .await
        .unwrap()
        .assert_status(StatusCode::FORBIDDEN);
    db::access::set_direct_grant(
        &f.ctx.pool,
        "alice",
        "test.app",
        db::access::AppAccessLevel::Stable,
    )
    .await
    .unwrap();
    let capable = server
        .get(&head_url())
        .header("Authorization", &token)
        .header("X-Paravoid-Dvpk", ALGORITHM)
        .send()
        .await
        .unwrap();
    capable.assert_status_ok();
    assert_eq!(
        body(&capable)["release"]["deltas"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    server
        .get(&url)
        .header("Authorization", &token)
        .send()
        .await
        .unwrap()
        .assert_status_ok();
    let wrong = format!("Bearer {}", URL_SAFE_NO_PAD.encode([8_u8; 32]));
    server
        .get(&url)
        .header("Authorization", &wrong)
        .send()
        .await
        .unwrap()
        .assert_status(StatusCode::FORBIDDEN);
    paravoid::revoke(&f.ctx.pool, "test.app", "grant", "admin", 2)
        .await
        .unwrap();
    server
        .get(&url)
        .header("Authorization", &token)
        .send()
        .await
        .unwrap()
        .assert_status(StatusCode::FORBIDDEN);
    sqlx::query("UPDATE paravoid_grants SET revoked_at = NULL, expires_at = 2")
        .execute(&f.ctx.pool)
        .await
        .unwrap();
    server
        .get(&url)
        .header("Authorization", &token)
        .send()
        .await
        .unwrap()
        .assert_status(StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn generation_failures_skip_or_retry_without_affecting_full_delivery() {
    let f = Fixture::with(true, "public", |c| c.timeout_secs = 3).await;
    let base = archive(5, 100_000);
    let v1 = f.release(1, &base).await;
    f.publish(&v1).await;
    let v2 = f.release(2, &edited(&base)).await;
    let reset = || async {
        sqlx::query("DELETE FROM vpk_deltas")
            .execute(&f.ctx.pool)
            .await
            .unwrap();
        assert_eq!(f.enqueue(&v2).await, 1);
    };
    let dvpks = f.ctx.storage_path.join("dvpks");
    let published = || std::fs::read_dir(&dvpks).map(|d| d.count()).unwrap_or(0);

    // Concurrent claims and worker death.
    reset().await;
    let claimed = dvpk::claim(&f.ctx.pool).await.unwrap().unwrap();
    assert!(dvpk::claim(&f.ctx.pool).await.unwrap().is_none());
    // A running job pins its superseded base against cleanup.
    sqlx::query("UPDATE vpk_releases SET artifact_removed = 1 WHERE id = 'vpk-1'")
        .execute(&f.ctx.pool)
        .await
        .unwrap();
    retention::cleanup_replaced(&f.ctx.pool, &f.ctx.storage_path)
        .await
        .unwrap();
    assert!(f
        .ctx
        .storage_path
        .join(format!("vpks/{}.vpk", sha(&base)))
        .exists());
    assert_eq!(dvpk::recover(&f.ctx.pool).await.unwrap(), 1);
    assert_eq!(f.delta(&claimed.id).await.state, "queued");
    sqlx::query(
        "UPDATE vpk_releases SET artifact_removed = 0, artifact_cleaned = 0 WHERE id = 'vpk-1'",
    )
    .execute(&f.ctx.pool)
    .await
    .unwrap();

    for (mode, state, reason) in [
        ("corrupt", "failed", "Reconstructed archive differs"),
        ("lie", "failed", "Encoder report differs"),
        ("sleep", "skipped", "time limit"),
    ] {
        f.mode(mode);
        reset().await;
        let outcome = f.generate().await.unwrap();
        assert_eq!(outcome.state, state, "{mode}: {:?}", outcome.failure);
        assert!(outcome.failure.unwrap().contains(reason), "{mode}");
        assert_eq!(published(), 0, "{mode}");
    }

    // Transient failures retry with backoff, then fail permanently.
    f.mode("fail");
    reset().await;
    for attempt in 1..=3 {
        let outcome = f.generate().await.unwrap();
        assert_eq!(outcome.attempts, attempt);
        if attempt < 3 {
            assert_eq!(outcome.state, "queued");
            assert!(outcome.next_attempt_at > chrono::Utc::now().timestamp());
            assert!(dvpk::claim(&f.ctx.pool).await.unwrap().is_none());
            sqlx::query("UPDATE vpk_deltas SET next_attempt_at = 0")
                .execute(&f.ctx.pool)
                .await
                .unwrap();
        } else {
            assert_eq!(outcome.state, "failed");
        }
    }

    // A completely different target saves nothing.
    f.mode("diff");
    let v3 = f.release(3, &archive(6, 100_000)).await;
    sqlx::query("DELETE FROM vpk_deltas")
        .execute(&f.ctx.pool)
        .await
        .unwrap();
    f.enqueue(&v3).await;
    let outcome = f.generate().await.unwrap();
    assert_eq!(outcome.state, "skipped");
    assert!(outcome.failure.unwrap().contains("Insufficient savings"));
    assert_eq!(published(), 0);

    // The full target is unaffected by every optional failure.
    f.publish(&v2).await;
    let server = f.server();
    let response = head(&server, true, None).await;
    assert_eq!(body(&response)["release"]["releaseId"], "release-2");
    assert!(body(&response)["release"].get("deltas").is_none());
    server
        .get("/api/paravoid/v1/apps/test.app/releases/release-2/payload.vpk")
        .send()
        .await
        .unwrap()
        .assert_status_ok();
}

#[tokio::test]
async fn worker_limits_and_retention_bound_derived_patches() {
    let f = Fixture::with(true, "public", |c| c.max_input_bytes = 50_000).await;
    let base = archive(7, 100_000);
    let v1 = f.release(1, &base).await;
    f.publish(&v1).await;
    let v2 = f.release(2, &edited(&base)).await;
    f.enqueue(&v2).await;
    let outcome = f.generate().await.unwrap();
    assert_eq!(outcome.state, "skipped");
    assert!(outcome.failure.unwrap().contains("input limit"));

    // Superseded targets retire their patches; files outlive a grace period.
    let f = Fixture::new(true, "public").await;
    let v1 = f.release(1, &base).await;
    f.publish(&v1).await;
    let v2 = f.release(2, &edited(&base)).await;
    f.enqueue(&v2).await;
    let ready = f.generate().await.unwrap();
    f.publish(&v2).await;
    let v3 = f.release(3, &edited(&edited(&base))).await;
    f.publish(&v3).await;
    let now = chrono::Utc::now().timestamp();
    retention::cleanup(&f.ctx.pool, &f.ctx.storage_path, now)
        .await
        .unwrap();
    let retired = f.delta(&ready.id).await;
    assert_eq!(retired.state, "retired");
    let file = f.ctx.storage_path.join(retired.patch_path.clone().unwrap());
    assert!(file.exists());
    // Still servable to outstanding heads during the grace period.
    let server = f.server();
    server
        .get(&delta_url("release-2", &sha(&base)))
        .send()
        .await
        .unwrap()
        .assert_status_ok();
    retention::cleanup(
        &f.ctx.pool,
        &f.ctx.storage_path,
        now + dvpk::RETIRED_GRACE_SECS + 1,
    )
    .await
    .unwrap();
    assert!(!file.exists());
    assert!(f.delta(&ready.id).await.file_removed);
    server
        .get(&delta_url("release-2", &sha(&base)))
        .send()
        .await
        .unwrap()
        .assert_status(StatusCode::NOT_FOUND);
    // Unreferenced patch files from an interrupted publication are reconciled
    // after the transfer grace period; referenced ones never are.
    let orphan = f
        .ctx
        .storage_path
        .join(format!("dvpks/{}.dvpk", "0".repeat(64)));
    std::fs::write(&orphan, b"orphan").unwrap();
    let v4 = f.release(4, &edited(&edited(&edited(&base)))).await;
    f.enqueue(&v4).await;
    let kept = f.generate().await.unwrap();
    assert_eq!(kept.state, "ready", "{:?}", kept.failure);
    let kept = f.ctx.storage_path.join(kept.patch_path.unwrap());
    retention::cleanup(&f.ctx.pool, &f.ctx.storage_path, now)
        .await
        .unwrap();
    assert!(orphan.exists());
    retention::cleanup(&f.ctx.pool, &f.ctx.storage_path, now + 8 * 86400)
        .await
        .unwrap();
    assert!(!orphan.exists());
    assert!(kept.exists());
    // Abandoned worker directories are removed.
    let abandoned = f
        .ctx
        .storage_path
        .join("dvpk-work")
        .join(uuid::Uuid::new_v4().to_string());
    std::fs::create_dir_all(&abandoned).unwrap();
    retention::cleanup(&f.ctx.pool, &f.ctx.storage_path, now)
        .await
        .unwrap();
    assert!(!abandoned.exists());
    drop(f.signing.clone());
}

#[test]
fn strict_parser_validates_delta_offers() {
    // Exercised through signing: the Store refuses to sign heads its own strict
    // verifier rejects. Malformed offers are checked via the Java gate as well.
    let dir = tempfile::tempdir().unwrap();
    let signing = keys(dir.path());
    let public = signing.public_configuration();
    let mut trust: Value =
        serde_json::from_str(include_str!("fixtures/paravoid-metadata/trust.json")).unwrap();
    trust["applicationId"] = json!("test.app");
    trust["headKeys"] = json!(public.head_keys);
    let policy = lellostore_backend::paravoid::InstalledPolicy::new(
        lellostore_backend::paravoid::TrustPolicy::parse(trust.to_string().as_bytes()).unwrap(),
        contract(),
        "https://store.test/api/paravoid/".into(),
        "stable".into(),
        lellostore_backend::paravoid::Authentication::Public,
    )
    .unwrap();
    let offer = |base: &str| json!({"algorithm":ALGORITHM,"baseArchiveSha256":base,"baseArchiveSize":10,"patchSha256":"c".repeat(64),"patchSize":40});
    let sign = |deltas: Option<Value>| {
        let mut release = json!({"releaseId":"release-2","payloadVersion":2,"manifestSha256":"b".repeat(64),"archiveSha256":"d".repeat(64),"archiveSize":100});
        if let Some(deltas) = deltas {
            release["deltas"] = deltas;
        }
        let body = json!({"version":1,"applicationId":"test.app","shellContractId":contract(),"channel":"stable","sdk":30,"abis":["x86_64"],"runtimeAbi":1,"formatVersion":1,"headRevision":2,"issuedAt":1,"expiresAt":2,"status":"available","release":release});
        signing.sign_head(&body, &policy, 30, &["x86_64".into()])
    };
    assert!(sign(None).is_ok());
    assert!(sign(Some(json!([]))).is_ok());
    assert!(sign(Some(json!([offer(&"e".repeat(64))]))).is_ok());
    let unknown = json!([{"algorithm":"future-codec","baseArchiveSha256":"e".repeat(64),"baseArchiveSize":10,"patchSha256":"c".repeat(64),"patchSize":40}]);
    assert!(sign(Some(unknown)).is_ok());
    let many: Vec<_> = (0..17).map(|i| offer(&format!("{i:064x}"))).collect();
    let mut extra = offer(&"e".repeat(64));
    extra["url"] = json!("https://elsewhere.test/");
    let mut oversized = offer(&"e".repeat(64));
    oversized["patchSize"] = json!(256 * 1024 * 1024 + 1);
    let mut empty = offer(&"e".repeat(64));
    empty["baseArchiveSize"] = json!(0);
    for invalid in [
        Value::Null,
        json!({}),
        json!(many),
        json!([offer(&"e".repeat(64)), offer(&"e".repeat(64))]),
        json!([offer(&"E".repeat(64))]),
        json!([extra]),
        json!([oversized]),
        json!([empty]),
    ] {
        assert!(sign(Some(invalid.clone())).is_err(), "{invalid}");
    }
}

/// Reference encoder plus upstream Java decoder over developer-signed VPKs.
/// Run through scripts/check-paravoid-dvpk.sh.
#[tokio::test]
#[ignore = "requires PARAVOID_DVPK_PYTHON (bsdiff4==1.2.6) and PARAVOID_DVPK_JAVA_CLASSES"]
async fn reference_patches_reconstruct_signed_vpks_in_upstream_java() {
    let python = PathBuf::from(std::env::var("PARAVOID_DVPK_PYTHON").unwrap());
    let classes = std::env::var("PARAVOID_DVPK_JAVA_CLASSES").unwrap();
    let encoder = Path::new(env!("CARGO_MANIFEST_DIR")).join("../scripts/vendor/paravoid/dvpk.py");
    let signer = signed::Signer::new();
    let payload = archive(8, 400_000);
    let v1 = signer.vpk(&contract(), "release-1", 1, &payload);
    let v2 = signer.vpk(&contract(), "release-2", 2, &edited(&payload));
    // Both are complete, verified VPKs under the pinned trust.
    for bytes in [&v1, &v2] {
        signer.inspect(bytes).unwrap();
    }
    let f = Fixture::with(true, "public", |c| {
        c.encoder = Some(encoder.clone());
        c.python = python.clone();
    })
    .await;
    let r1 = f.release(1, &v1).await;
    f.publish(&r1).await;
    let r2 = f.release(2, &v2).await;
    f.enqueue(&r2).await;
    let ready = f.generate().await.unwrap();
    assert_eq!(ready.state, "ready", "{:?}", ready.failure);
    assert!(ready
        .encoder_version
        .unwrap()
        .starts_with("reference-dvpk.py:"));
    let patch = f.ctx.storage_path.join(ready.patch_path.unwrap());
    let dir = tempfile::tempdir().unwrap();
    let base = dir.path().join("base.vpk");
    std::fs::write(&base, &v1).unwrap();
    let apply = |patch: &Path, target: &[u8], output: &Path| {
        let patch_bytes = std::fs::read(patch).unwrap();
        Command::new("java")
            .args(["-cp", &classes, "StoreDvpkCheck"])
            .arg(&base)
            .arg(patch)
            .arg(output)
            .args([
                sha(&v1),
                v1.len().to_string(),
                sha(&patch_bytes),
                patch_bytes.len().to_string(),
                sha(target),
                target.len().to_string(),
            ])
            .output()
            .unwrap()
    };
    let output = dir.path().join("reconstructed.vpk");
    let result = apply(&patch, &v2, &output);
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    assert_eq!(std::fs::read(&output).unwrap(), v2);
    signer.inspect(&std::fs::read(&output).unwrap()).unwrap();

    // Signed heads: the upstream verifier accepts both representations; the
    // pre-DVPK verifier accepts the full-only head and rejects `deltas`, which
    // is why they are never sent without negotiation.
    f.publish(&r2).await;
    let server = f.server();
    let trust = dir.path().join("trust.json");
    let stored: String = sqlx::query_scalar("SELECT trust_json FROM paravoid_contracts")
        .fetch_one(&f.ctx.pool)
        .await
        .unwrap();
    std::fs::write(&trust, stored).unwrap();
    let check = |classes: &str, response: &TestResponse| {
        let head = dir.path().join("head.json");
        std::fs::write(&head, response.as_bytes()).unwrap();
        Command::new("java")
            .args(["-cp", classes, "StoreHeadCheck"])
            .arg(&trust)
            .arg(contract())
            .arg("https://store.test/api/paravoid/")
            .arg(&head)
            .output()
            .unwrap()
            .status
            .success()
    };
    let legacy = head(&server, false, None).await;
    let capable = head(&server, true, Some(&sha(&v1))).await;
    assert!(body(&capable)["release"]["deltas"].is_array());
    assert!(check(&classes, &legacy) && check(&classes, &capable));
    if let Ok(previous) = std::env::var("PARAVOID_DVPK_LEGACY_JAVA_CLASSES") {
        assert!(check(&previous, &legacy));
        assert!(!check(&previous, &capable));
    }

    // An exactly reconstructed but incompatible target still fails ordinary
    // full verification: deltas never replace VPK admission.
    let foreign = signer.vpk(&"f".repeat(64), "release-3", 3, &edited(&payload));
    let target = dir.path().join("foreign.vpk");
    std::fs::write(&target, &foreign).unwrap();
    let foreign_patch = dir.path().join("foreign.dvpk");
    let encoded = Command::new(&python)
        .arg(&encoder)
        .arg(&base)
        .arg(&target)
        .arg(&foreign_patch)
        .output()
        .unwrap();
    assert!(encoded.status.success());
    let reconstructed = dir.path().join("foreign-reconstructed.vpk");
    assert!(apply(&foreign_patch, &foreign, &reconstructed)
        .status
        .success());
    assert_eq!(std::fs::read(&reconstructed).unwrap(), foreign);
    assert!(signer.inspect(&foreign).is_err());
    // A corrupted patch is rejected by the upstream decoder.
    let mut corrupt = std::fs::read(&patch).unwrap();
    let last = corrupt.len() - 1;
    corrupt[last] ^= 0xff;
    let corrupt_path = dir.path().join("corrupt.dvpk");
    std::fs::write(&corrupt_path, corrupt).unwrap();
    assert!(!apply(&corrupt_path, &v2, &dir.path().join("rejected.vpk"))
        .status
        .success());
}

/// Minimal developer-signed VPK builder (see tests/paravoid_archive.rs).
mod signed {
    use super::sha;
    use base64::{engine::general_purpose::STANDARD, Engine};
    use lellostore_backend::paravoid::{canonical_json, TrustPolicy};
    use serde_json::json;
    use std::{
        collections::BTreeMap,
        io::{Cursor, Write},
        process::Command,
    };
    use zip::{write::SimpleFileOptions, CompressionMethod};

    pub struct Signer {
        dir: tempfile::TempDir,
        trust: TrustPolicy,
    }
    fn zip(files: &BTreeMap<String, Vec<u8>>, method: CompressionMethod) -> Vec<u8> {
        let mut writer = zip::ZipWriter::new(Cursor::new(Vec::new()));
        let options = SimpleFileOptions::default()
            .compression_method(method)
            .unix_permissions(0o644);
        for (path, bytes) in files {
            writer.start_file(path, options).unwrap();
            writer.write_all(bytes).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }
    impl Signer {
        pub fn new() -> Self {
            let dir = tempfile::tempdir().unwrap();
            let key = dir.path().join("release.pem");
            assert!(Command::new("openssl")
                .args([
                    "genpkey",
                    "-algorithm",
                    "RSA",
                    "-pkeyopt",
                    "rsa_keygen_bits:3072",
                    "-out"
                ])
                .arg(&key)
                .output()
                .unwrap()
                .status
                .success());
            let public = dir.path().join("public.der");
            assert!(Command::new("openssl")
                .args(["pkey", "-in"])
                .arg(&key)
                .args(["-pubout", "-outform", "DER", "-out"])
                .arg(&public)
                .output()
                .unwrap()
                .status
                .success());
            let policy = json!({"version":1,"applicationId":"test.app","releaseKeys":{"publisher":STANDARD.encode(std::fs::read(public).unwrap())},"headKeys":{},"grantKeys":{},"minimumPayloadVersion":0,"minimumHeadRevision":0});
            let trust = TrustPolicy::parse(&serde_json::to_vec(&policy).unwrap()).unwrap();
            Self { dir, trust }
        }
        pub fn vpk(&self, contract: &str, release: &str, version: u64, payload: &[u8]) -> Vec<u8> {
            let mut files = BTreeMap::from([
                ("code/classes.dex".to_string(), b"synthetic dex".to_vec()),
                (
                    "java-resources.jar".to_string(),
                    zip(
                        &BTreeMap::from([("payload.bin".to_string(), payload.to_vec())]),
                        CompressionMethod::Stored,
                    ),
                ),
                (
                    "resources.apk".to_string(),
                    zip(
                        &BTreeMap::from([(
                            "resources.arsc".to_string(),
                            b"synthetic table".to_vec(),
                        )]),
                        CompressionMethod::Deflated,
                    ),
                ),
                ("resource-ledger.json".to_string(), b"{}".to_vec()),
            ]);
            let inventory: Vec<_> = files
                .iter()
                .map(|(name, bytes)| json!({"path":name,"size":bytes.len(),"sha256":sha(bytes)}))
                .collect();
            let body = json!({"version":1,"applicationId":"test.app","shellContractId":contract,"releaseId":release,"payloadVersion":version,"runtimeAbi":1,"formatVersion":1,"minSdk":30,"maxSdk":0,"abis":[],"ledgerSha256":sha(&files["resource-ledger.json"]),"inventory":inventory});
            let body = canonical_json(&body).unwrap();
            let mut input = b"paravoid/v1/release\n".to_vec();
            input.extend_from_slice(&body);
            let (input_path, signature) = (
                self.dir.path().join("input"),
                self.dir.path().join("signature"),
            );
            std::fs::write(&input_path, input).unwrap();
            assert!(Command::new("openssl")
                .args(["dgst", "-sha256", "-sign"])
                .arg(self.dir.path().join("release.pem"))
                .arg("-out")
                .arg(&signature)
                .arg(&input_path)
                .output()
                .unwrap()
                .status
                .success());
            files.insert("release.json".into(), canonical_json(&json!({"keyId":"publisher","body":STANDARD.encode(body),"signature":STANDARD.encode(std::fs::read(signature).unwrap())})).unwrap());
            zip(&files, CompressionMethod::Stored)
        }
        pub fn inspect(&self, bytes: &[u8]) -> Result<(), String> {
            lellostore_backend::paravoid::archive::inspect(
                &mut Cursor::new(bytes.to_vec()),
                &self.trust,
                &"a".repeat(64),
            )
            .map(|_| ())
            .map_err(|e| e.to_string())
        }
    }
}

#[tokio::test]
async fn recent_published_bases_are_retained_and_each_receives_a_patch() {
    let f = Fixture::new(true, "public").await;
    let mut payload = archive(9, 100_000);
    let mut hashes = Vec::new();
    for version in 1..=5 {
        let id = f.release(version, &payload).await;
        f.publish(&id).await;
        hashes.push(sha(&payload));
        payload = edited(&payload);
    }
    // An unpublished lower draft is not a base and is removed as before.
    let draft = f.release(6, &payload).await;
    let v7 = f.release(7, &edited(&payload)).await;
    f.publish(&v7).await;
    // The three most recent previously published archives are kept.
    let stored: Vec<(i64, bool)> = sqlx::query_as(
        "SELECT payload_version, artifact_removed = 0 FROM vpk_releases ORDER BY payload_version",
    )
    .fetch_all(&f.ctx.pool)
    .await
    .unwrap();
    assert_eq!(
        stored,
        [
            (1, false),
            (2, false),
            (3, true),
            (4, true),
            (5, true),
            (6, false),
            (7, true)
        ]
    );
    assert_eq!(
        paravoid::release(&f.ctx.pool, "test.app", &draft)
            .await
            .unwrap()
            .publication_state,
        "withdrawn"
    );
    // Jobs into since-withdrawn targets are skipped; only payload 7's run.
    let mut ready = 0;
    while let Some(job) = f.generate().await {
        if job.target_vpk_id == v7 {
            assert_eq!(job.state, "ready", "{:?}", job.failure);
            ready += 1;
        } else {
            assert_eq!(job.state, "skipped");
        }
    }
    assert_eq!(ready, 3);
    retention::cleanup(
        &f.ctx.pool,
        &f.ctx.storage_path,
        chrono::Utc::now().timestamp(),
    )
    .await
    .unwrap();
    for (index, hash) in hashes.iter().enumerate() {
        let exists = f.ctx.storage_path.join(format!("vpks/{hash}.vpk")).exists();
        assert_eq!(exists, index >= 2, "payload {}", index + 1);
    }
    // Devices up to three payloads behind get a direct patch to payload 7.
    let response = head(&f.server(), true, None).await;
    let deltas = body(&response)["release"]["deltas"].clone();
    let bases: Vec<_> = deltas
        .as_array()
        .unwrap()
        .iter()
        .map(|d| d["baseArchiveSha256"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(
        bases,
        [hashes[4].clone(), hashes[3].clone(), hashes[2].clone()]
    );
}
