use lellostore_backend::paravoid::canonical_json;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{io::Write, path::Path, process::Command};

pub fn document(signer: &str) -> Vec<u8> {
    let mut trust: Value =
        serde_json::from_str(include_str!("../fixtures/paravoid-metadata/trust.json")).unwrap();
    trust["applicationId"] = json!("example.app");
    let descriptor = json!({"profile":"complete-apk-v1","runtimeAbi":1,"trustPolicy":trust,
        "installed":{"applicationId":"example.app","minSdk":30,"manifestSha256":"b".repeat(64),"declarations":{},"pinnedResources":{},"runtimeClasses":{},"nativeAbis":{},"ledgerReservations":{"string/title":"0x7f010001"},"apkSigners":[signer],"toolchain":{}},
        "distribution":{"bootstrap":"empty","enabled":true,"baseUrl":"https://updates.example.test/","channel":"stable","authentication":"public","debugHttpAllowed":false}});
    let id = hex::encode(Sha256::digest(canonical_json(&descriptor).unwrap()));
    canonical_json(&json!({"version":1,"contractId":id,"descriptor":descriptor})).unwrap()
}
pub fn run(command: &mut Command) {
    let result = command.output().unwrap();
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
}
pub fn apk(root: &Path, sdk: &Path, code: i64, policy: &[u8]) -> std::path::PathBuf {
    let manifest = root.join("AndroidManifest.xml");
    std::fs::write(&manifest,format!(r#"<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app" android:versionCode="{code}" android:versionName="{code}"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/><application android:label="Registration fixture"/></manifest>"#)).unwrap();
    let unsigned = root.join(format!("unsigned-{code}.apk"));
    let signed = root.join(format!("signed-{code}.apk"));
    let tools = sdk.join("build-tools/36.0.0");
    run(Command::new(tools.join("aapt2"))
        .args(["link", "--manifest"])
        .arg(&manifest)
        .arg("-I")
        .arg(sdk.join("platforms/android-36/android.jar"))
        .arg("-o")
        .arg(&unsigned));
    if !policy.is_empty() {
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&unsigned)
            .unwrap();
        let mut zip = zip::ZipWriter::new_append(file).unwrap();
        zip.start_file(
            lellostore_backend::paravoid::shell_policy::APK_PATH,
            zip::write::SimpleFileOptions::default(),
        )
        .unwrap();
        zip.write_all(policy).unwrap();
        zip.finish().unwrap();
    }
    run(Command::new(tools.join("apksigner"))
        .args(["sign", "--ks"])
        .arg(root.join("test.p12"))
        .args([
            "--ks-key-alias",
            "test",
            "--ks-pass",
            "pass:testpassword",
            "--v1-signing-enabled",
            "false",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "true",
            "--v4-signing-enabled",
            "false",
            "--out",
        ])
        .arg(&signed)
        .arg(&unsigned));
    signed
}
