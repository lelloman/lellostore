use lellostore_backend::paravoid::{canonical_json, shell_policy::ShellPolicyDocument};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

fn descriptor() -> Value {
    let trust: Value =
        serde_json::from_str(include_str!("fixtures/paravoid-metadata/trust.json")).unwrap();
    json!({"profile":"complete-apk-v1","runtimeAbi":1,"trustPolicy":trust,
        "installed":{"applicationId":trust["applicationId"],"minSdk":30,"manifestSha256":"b".repeat(64),
            "declarations":{"application":"example.App"},"pinnedResources":{"string/title":{"id":"0x7f010001","sha256":"c".repeat(64)}},
            "runtimeClasses":{"runtime.class":"d".repeat(64)},"nativeAbis":{},
            "ledgerReservations":{"string/title":"0x7f010001"},"apkSigners":["e".repeat(64)],"toolchain":{"agp":"8.13.2"}},
        "distribution":{"bootstrap":"embedded","enabled":true,"baseUrl":"https://updates.example.test/","channel":"stable","authentication":"apkKey","debugHttpAllowed":false}})
}
fn envelope(descriptor: Value) -> Vec<u8> {
    let hash = hex::encode(Sha256::digest(canonical_json(&descriptor).unwrap()));
    canonical_json(&json!({"version":1,"contractId":hash,"descriptor":descriptor})).unwrap()
}
fn agrees(bytes: &[u8], accepted: bool) {
    assert_eq!(ShellPolicyDocument::parse(bytes).is_ok(), accepted);
    if let Ok(classes) = std::env::var("PARAVOID_POLICY_JAVA_CLASSES") {
        let input = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(input.path(), bytes).unwrap();
        let output = std::process::Command::new("java")
            .args(["-cp", &classes, "StorePolicyCheck"])
            .arg(input.path())
            .output()
            .unwrap();
        assert_eq!(
            output.status.success(),
            accepted,
            "Java policy verdict differs: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
}
#[test]
fn binds_distribution_trust_and_installed_resource_boundary() {
    let bytes = envelope(descriptor());
    agrees(&bytes, true);
    let parsed = ShellPolicyDocument::parse(&bytes).unwrap();
    assert_eq!(
        parsed.descriptor.installed.ledger_reservations["string/title"],
        "0x7f010001"
    );
    assert_eq!(
        parsed.trust.application_id(),
        parsed.descriptor.installed.application_id
    );
    for (field, value) in [
        ("bootstrap", json!("empty")),
        ("channel", json!("beta")),
        ("baseUrl", json!("https://other.example/")),
    ] {
        let mut changed = descriptor();
        changed["distribution"][field] = value;
        let bytes = envelope(changed);
        agrees(&bytes, true);
        assert_ne!(
            ShellPolicyDocument::parse(&bytes).unwrap().contract_id,
            parsed.contract_id
        );
    }
    let mut offline = descriptor();
    offline["distribution"]["enabled"] = json!(false);
    offline["distribution"]["baseUrl"] = json!("");
    offline["distribution"]["authentication"] = json!("public");
    offline["trustPolicy"]["headKeys"] = json!({});
    agrees(&envelope(offline.clone()), true);
    offline["distribution"]["bootstrap"] = json!("empty");
    agrees(&envelope(offline), false);
}
#[test]
fn rejects_untrusted_shapes_tampering_and_conflicting_reservations() {
    let bytes = envelope(descriptor());
    let text = String::from_utf8(bytes).unwrap();
    agrees(
        text.replace("example.App", "example.Other").as_bytes(),
        false,
    );
    agrees(
        text.replacen("\"version\":1", "\"version\":1,\"version\":1", 1)
            .as_bytes(),
        false,
    );
    for (path, value) in [
        ("/profile", json!("embedded-apk-v1")),
        ("/runtimeAbi", json!(2)),
        ("/installed/minSdk", json!(29)),
        ("/installed/apkSigners", json!([])),
        ("/installed/applicationId", json!("another.app")),
        ("/installed/nativeAbis", json!({"mips":"a".repeat(64)})),
        (
            "/installed/ledgerReservations",
            json!({"string/title":"0x7f000001"}),
        ),
        (
            "/installed/ledgerReservations",
            json!({"string/title":"0x7f010001","color/other":"0x7f010002"}),
        ),
        (
            "/installed/ledgerReservations",
            json!({"string/title":"0x7f010001","string/other":"0x7f010001"}),
        ),
        (
            "/installed/ledgerReservations",
            json!({"string/title":"0x7f010002"}),
        ),
        ("/distribution/authentication", json!("token")),
        ("/distribution/channel", json!("bad/channel")),
        (
            "/distribution/baseUrl",
            json!("https://user@updates.example/"),
        ),
        (
            "/distribution/baseUrl",
            json!("https://updates.example:443/"),
        ),
        (
            "/distribution/baseUrl",
            json!("https://updates.example/a/../"),
        ),
        ("/distribution/debugHttpAllowed", json!(true)),
        ("/trustPolicy/headKeys", json!({})),
        ("/trustPolicy/grantKeys", json!({})),
    ] {
        let mut changed = descriptor();
        *changed.pointer_mut(path).unwrap() = value;
        agrees(&envelope(changed), false);
    }
    let mut unknown = descriptor();
    unknown["distribution"]["credential"] = json!("unexpected");
    agrees(&envelope(unknown), false);
}
