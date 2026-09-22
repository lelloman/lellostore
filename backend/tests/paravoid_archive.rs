//! Synthetic components exercise container/inventory checks, not Android validity.
use base64::{engine::general_purpose::STANDARD, Engine};
use lellostore_backend::paravoid::{
    archive::{inspect, InspectionError},
    canonical_json, TrustPolicy, MAX_ARCHIVE_BYTES,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    io::{Cursor, Write},
    process::Command,
};
use zip::{write::SimpleFileOptions, CompressionMethod};

fn hash(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}
struct Fixture {
    directory: tempfile::TempDir,
    trust: TrustPolicy,
}
impl Fixture {
    fn new() -> Self {
        let directory = tempfile::tempdir().unwrap();
        let key = directory.path().join("release.pem");
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
            .expect("OpenSSL required for signed archive tests")
            .status
            .success());
        let public = directory.path().join("public.der");
        assert!(Command::new("openssl")
            .args(["pkey", "-in"])
            .arg(&key)
            .args(["-pubout", "-outform", "DER", "-out"])
            .arg(&public)
            .output()
            .unwrap()
            .status
            .success());
        let policy = json!({"version":1, "applicationId":"example.app", "releaseKeys":{"publisher": STANDARD.encode(std::fs::read(public).unwrap())}, "headKeys":{}, "grantKeys":{}, "minimumPayloadVersion":0, "minimumHeadRevision":0});
        std::fs::write(
            directory.path().join("trust.json"),
            serde_json::to_vec(&policy).unwrap(),
        )
        .unwrap();
        let trust = TrustPolicy::parse(&serde_json::to_vec(&policy).unwrap()).unwrap();
        Self { directory, trust }
    }
    fn files(&self) -> BTreeMap<String, Vec<u8>> {
        BTreeMap::from([
            ("code/classes.dex".into(), b"synthetic dex".to_vec()),
            (
                "java-resources.jar".into(),
                zip(&BTreeMap::new(), CompressionMethod::Stored),
            ),
            (
                "resources.apk".into(),
                zip(
                    &BTreeMap::from([("resources.arsc".into(), b"synthetic table".to_vec())]),
                    CompressionMethod::Deflated,
                ),
            ),
            ("resource-ledger.json".into(), b"{}".to_vec()),
        ])
    }
    fn signed(
        &self,
        mut files: BTreeMap<String, Vec<u8>>,
        change: impl FnOnce(&mut Value),
    ) -> BTreeMap<String, Vec<u8>> {
        let inventory: Vec<_> = files
            .iter()
            .map(|(name, bytes)| json!({"path":name,"size":bytes.len(),"sha256":hash(bytes)}))
            .collect();
        let abis: std::collections::BTreeSet<_> = files
            .keys()
            .filter_map(|p| p.strip_prefix("native/").and_then(|p| p.split('/').next()))
            .collect();
        let mut body = json!({"version":1,"applicationId":"example.app","shellContractId":"a".repeat(64),"releaseId":"release-1","payloadVersion":1,"runtimeAbi":1,"formatVersion":1,"minSdk":30,"maxSdk":0,"abis":abis,"ledgerSha256":hash(&files["resource-ledger.json"]),"inventory":inventory});
        change(&mut body);
        let body = canonical_json(&body).unwrap();
        let mut input = b"paravoid/v1/release\n".to_vec();
        input.extend_from_slice(&body);
        let input_path = self.directory.path().join("input");
        let signature_path = self.directory.path().join("signature");
        std::fs::write(&input_path, input).unwrap();
        assert!(Command::new("openssl")
            .args(["dgst", "-sha256", "-sign"])
            .arg(self.directory.path().join("release.pem"))
            .arg("-out")
            .arg(&signature_path)
            .arg(&input_path)
            .output()
            .unwrap()
            .status
            .success());
        files.insert("release.json".into(), canonical_json(&json!({"keyId":"publisher","body":STANDARD.encode(body),"signature":STANDARD.encode(std::fs::read(signature_path).unwrap())})).unwrap());
        files
    }
    fn inspect(
        &self,
        bytes: Vec<u8>,
    ) -> Result<lellostore_backend::paravoid::archive::ArchiveInspection, InspectionError> {
        inspect(&mut Cursor::new(bytes), &self.trust, &"a".repeat(64))
    }
}
fn zip(files: &BTreeMap<String, Vec<u8>>, compression: CompressionMethod) -> Vec<u8> {
    let mut writer = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default()
        .compression_method(compression)
        .unix_permissions(0o644);
    for (path, bytes) in files {
        writer.start_file(path, options).unwrap();
        writer.write_all(bytes).unwrap();
    }
    writer.finish().unwrap().into_inner()
}
fn central(bytes: &[u8]) -> usize {
    let end = bytes.len() - 22;
    u32::from_le_bytes(bytes[end + 16..end + 20].try_into().unwrap()) as usize
}

#[test]
fn authenticates_inventory_without_claiming_executable_validity() {
    let f = Fixture::new();
    let mut files = f.files();
    files.insert("code/classes2.dex".into(), b"second dex".to_vec());
    files.insert(
        "native/arm64-v8a/libc++_shared.so".into(),
        b"synthetic library".to_vec(),
    );
    let signed = f.signed(files, |_| {});
    let bytes = zip(&signed, CompressionMethod::Stored);
    let archive_path = f.directory.path().join("payload.vpk");
    std::fs::write(&archive_path, &bytes).unwrap();
    let command = Command::new(env!("CARGO_BIN_EXE_vpk_inspect"))
        .arg(&archive_path)
        .arg(f.directory.path().join("trust.json"))
        .arg("a".repeat(64))
        .output()
        .unwrap();
    assert!(command.status.success());
    let report: Value = serde_json::from_slice(&command.stdout).unwrap();
    assert_eq!(report["complete_verification"], false);
    assert_eq!(report["publication_allowed"], false);
    let inspection = f.inspect(bytes.clone()).unwrap();
    assert_eq!(inspection.archive_sha256, hash(&bytes));
    assert_eq!(inspection.archive_size, bytes.len() as u64);
    assert_eq!(inspection.manifest_sha256, hash(&signed["release.json"]));
    assert_eq!(inspection.release.abis, ["arm64-v8a"]);
    assert_eq!(inspection.release.inventory.len(), 6);
    assert_eq!(inspection.signing_key_id, "publisher");
    assert!(matches!(
        inspect(&mut Cursor::new(bytes), &f.trust, &"b".repeat(64)),
        Err(InspectionError::Incompatible)
    ));
}

#[test]
fn rejects_noncanonical_containers_before_admission() {
    let f = Fixture::new();
    let files = f.signed(f.files(), |_| {});
    let valid = zip(&files, CompressionMethod::Stored);
    assert!(f.inspect(zip(&files, CompressionMethod::Deflated)).is_err());
    for index in [6, 8, 14, 26, 28] {
        // local flags, compression, CRC, name/extra lengths
        let mut bad = valid.clone();
        bad[index] ^= 1;
        assert!(f.inspect(bad).is_err(), "local header byte {index}");
    }
    let mut bad = valid.clone();
    bad.push(0);
    assert!(f.inspect(bad).is_err());
    let mut bad = valid.clone();
    bad.insert(0, 0);
    assert!(f.inspect(bad).is_err());
    let start = central(&valid);
    for index in [8, 10, 30, 32, 34, 42] {
        // central flags/method/extra/comment/disk/offset
        let mut bad = valid.clone();
        bad[start + index] ^= 1;
        assert!(f.inspect(bad).is_err(), "central header byte {index}");
    }
    let mut bad = valid.clone();
    bad[start + 38..start + 42].copy_from_slice(&(0o120777_u32 << 16).to_le_bytes());
    assert!(f.inspect(bad).is_err());
    let mut bad = valid.clone();
    bad[30 + "code/classes.dex".len()] ^= 1;
    assert!(matches!(f.inspect(bad), Err(InspectionError::Integrity)));
    let mut bad = valid;
    let oversized = (128_u32 * 1024 * 1024 + 1).to_le_bytes();
    bad[start + 20..start + 24].copy_from_slice(&oversized);
    bad[start + 24..start + 28].copy_from_slice(&oversized);
    assert!(matches!(f.inspect(bad), Err(InspectionError::Limit)));
}

#[test]
fn rejects_signed_scope_inventory_ledger_and_abi_mismatches() {
    let f = Fixture::new();
    for (field, value) in [
        ("applicationId", json!("other.app")),
        ("runtimeAbi", json!(2)),
        ("payloadVersion", json!(0)),
        ("minSdk", json!(29)),
        ("minSdk", json!(2_147_483_648_u64)),
        ("maxSdk", json!(2_147_483_648_u64)),
        ("maxSdk", json!(29)),
        ("ledgerSha256", json!("0".repeat(64))),
        ("abis", json!(["arm64-v8a"])),
        ("unknown", json!(true)),
    ] {
        let files = f.signed(f.files(), |body| body[field] = value);
        assert!(
            f.inspect(zip(&files, CompressionMethod::Stored)).is_err(),
            "{field}"
        );
    }
    let mut files = f.signed(f.files(), |_| {});
    files.insert(
        "code/classes.dex".into(),
        b"replaced content with valid zip CRC".to_vec(),
    );
    assert!(matches!(
        f.inspect(zip(&files, CompressionMethod::Stored)),
        Err(InspectionError::Inventory)
    ));
    for extra in [
        "code/classes1.dex",
        "code/classes3.dex",
        "../escaped",
        "native/arm64-v8a/sub/library.so",
    ] {
        let mut files = f.files();
        files.insert(extra.into(), b"x".to_vec());
        let signed = f.signed(files, |_| {});
        assert!(
            f.inspect(zip(&signed, CompressionMethod::Stored)).is_err(),
            "{extra}"
        );
    }
    let files = f.signed(f.files(), |body| {
        body["inventory"].as_array_mut().unwrap().reverse()
    });
    assert!(matches!(
        f.inspect(zip(&files, CompressionMethod::Stored)),
        Err(InspectionError::Inventory)
    ));
}

#[test]
fn rejects_oversized_sparse_file_without_reading_payload() {
    let f = Fixture::new();
    let mut file = tempfile::tempfile().unwrap();
    file.set_len(MAX_ARCHIVE_BYTES + 1).unwrap();
    assert!(matches!(
        inspect(&mut file, &f.trust, &"a".repeat(64)),
        Err(InspectionError::Limit)
    ));
}

#[test]
fn rejects_nested_unsafe_names_code_and_inconsistent_headers() {
    let f = Fixture::new();
    for name in [
        "../outside",
        "dir/../outside",
        "\\outside",
        "/outside",
        "file.class",
        "classes.dex",
        "C:/outside",
        "a//b",
    ] {
        let mut files = f.files();
        files.insert(
            "java-resources.jar".into(),
            zip(
                &BTreeMap::from([(name.into(), b"x".to_vec())]),
                CompressionMethod::Stored,
            ),
        );
        let signed = f.signed(files, |_| {});
        assert!(
            f.inspect(zip(&signed, CompressionMethod::Stored)).is_err(),
            "{name}"
        );
    }
    for mutate in [0, 1, 2, 3, 4] {
        let mut component = zip(
            &BTreeMap::from([("valid.txt".into(), b"content".to_vec())]),
            CompressionMethod::Stored,
        );
        let start = central(&component);
        match mutate {
            0 => component[30] ^= 1, // differing raw local/central names
            1 => component[14] ^= 1, // differing local/central CRC
            2 => component[start + 38..start + 42]
                .copy_from_slice(&(0o120777_u32 << 16).to_le_bytes()),
            3 => component[30 + "valid.txt".len()] ^= 1, // valid envelope, bad nested CRC
            4 => {
                // reject a ZIP bomb's size before any decompression
                let size = (2_u32 * 1024 * 1024 * 1024 + 1).to_le_bytes();
                component[22..26].copy_from_slice(&size);
                component[start + 24..start + 28].copy_from_slice(&size);
            }
            _ => unreachable!(),
        }
        let mut files = f.files();
        files.insert("java-resources.jar".into(), component);
        let signed = f.signed(files, |_| {});
        assert!(
            f.inspect(zip(&signed, CompressionMethod::Stored)).is_err(),
            "mutation {mutate}"
        );
    }
}

#[test]
fn validates_nested_streaming_data_descriptors() {
    let f = Fixture::new();
    let original = zip(
        &BTreeMap::from([("data.txt".into(), b"data".to_vec())]),
        CompressionMethod::Stored,
    );
    let central_start = central(&original);
    let mut descriptor = b"PK\x07\x08".to_vec();
    descriptor.extend_from_slice(&original[central_start + 16..central_start + 28]);
    let mut streamed = original.clone();
    streamed.splice(central_start..central_start, descriptor);
    let shifted = central_start + 16;
    streamed[6] |= 8;
    streamed[14..26].fill(0);
    streamed[shifted + 8] |= 8;
    let end = streamed.len() - 22;
    streamed[end + 16..end + 20].copy_from_slice(&(shifted as u32).to_le_bytes());
    let mut files = f.files();
    files.insert("java-resources.jar".into(), streamed.clone());
    let signed = f.signed(files, |_| {});
    assert!(f.inspect(zip(&signed, CompressionMethod::Stored)).is_ok());
    streamed[central_start + 4] ^= 1;
    let mut files = f.files();
    files.insert("java-resources.jar".into(), streamed);
    let signed = f.signed(files, |_| {});
    assert!(f.inspect(zip(&signed, CompressionMethod::Stored)).is_err());
}

fn rename_raw(bytes: &mut [u8], from: &[u8], to: &[u8]) {
    assert_eq!(from.len(), to.len());
    let positions: Vec<_> = bytes
        .windows(from.len())
        .enumerate()
        .filter_map(|(i, value)| (value == from).then_some(i))
        .collect();
    assert_eq!(positions.len(), 2); // local and central record names
    for at in positions {
        bytes[at..at + to.len()].copy_from_slice(to);
    }
}

#[test]
fn rejects_duplicate_raw_names_in_outer_and_nested_archives() {
    let f = Fixture::new();
    let mut files = f.files();
    files.insert("native/x86/liba.so".into(), b"a".to_vec());
    files.insert("native/x86/libb.so".into(), b"b".to_vec());
    let mut bytes = zip(&f.signed(files, |_| {}), CompressionMethod::Stored);
    rename_raw(&mut bytes, b"native/x86/libb.so", b"native/x86/liba.so");
    assert!(f.inspect(bytes).is_err());
    let mut component = zip(
        &BTreeMap::from([
            ("a.txt".into(), b"a".to_vec()),
            ("b.txt".into(), b"b".to_vec()),
        ]),
        CompressionMethod::Stored,
    );
    rename_raw(&mut component, b"b.txt", b"a.txt");
    let mut files = f.files();
    files.insert("java-resources.jar".into(), component);
    assert!(f
        .inspect(zip(&f.signed(files, |_| {}), CompressionMethod::Stored))
        .is_err());
}

#[test]
fn rejects_hidden_nested_bytes_and_non_utf8_names() {
    let f = Fixture::new();
    let nested = zip(
        &BTreeMap::from([("asset.txt".into(), b"content".to_vec())]),
        CompressionMethod::Stored,
    );
    let directory = central(&nested);
    for insertion in [0, directory] {
        let mut bad = nested.clone();
        bad.splice(insertion..insertion, b"hidden".iter().copied());
        let end = bad.len() - 22;
        let shifted = directory + 6;
        bad[end + 16..end + 20].copy_from_slice(&(shifted as u32).to_le_bytes());
        if insertion == 0 {
            bad[shifted + 42..shifted + 46].copy_from_slice(&6_u32.to_le_bytes());
        }
        let mut files = f.files();
        files.insert("java-resources.jar".into(), bad);
        assert!(f
            .inspect(zip(&f.signed(files, |_| {}), CompressionMethod::Stored))
            .is_err());
    }
    let mut bad = nested;
    bad[30] = 0xff;
    bad[directory + 46] = 0xff;
    let mut files = f.files();
    files.insert("java-resources.jar".into(), bad);
    assert!(f
        .inspect(zip(&f.signed(files, |_| {}), CompressionMethod::Stored))
        .is_err());
}

fn format_valid_files(f: &Fixture) -> BTreeMap<String, Vec<u8>> {
    // Structurally valid headers/checksums, deliberately not executable Android content.
    let mut files = f.files();
    let mut dex = vec![0; 112];
    dex[..8].copy_from_slice(b"dex\n035\0");
    dex[32..36].copy_from_slice(&112_u32.to_le_bytes());
    dex[36..40].copy_from_slice(&112_u32.to_le_bytes());
    dex[40..44].copy_from_slice(&0x12345678_u32.to_le_bytes());
    let signature = ring::digest::digest(&ring::digest::SHA1_FOR_LEGACY_USE_ONLY, &dex[32..]);
    dex[12..32].copy_from_slice(signature.as_ref());
    let mut adler = adler2::Adler32::new();
    adler.write_slice(&dex[12..]);
    dex[8..12].copy_from_slice(&adler.checksum().to_le_bytes());
    files.insert("code/classes.dex".into(), dex);
    let mut table = vec![0; 12];
    table[..4].copy_from_slice(&0x000c0002_u32.to_le_bytes());
    table[4..8].copy_from_slice(&12_u32.to_le_bytes());
    files.insert(
        "resources.apk".into(),
        zip(
            &BTreeMap::from([("resources.arsc".into(), table)]),
            CompressionMethod::Deflated,
        ),
    );
    files.insert("resource-ledger.json".into(),serde_json::to_vec(&json!({"version":1,"applicationId":"example.app","entries":[{"name":"string/title","id":"0x7f010001","removed":false}]})).unwrap());
    files
}
#[test]
fn checks_component_formats_and_pinned_resource_reservations() {
    let f = Fixture::new();
    let files = format_valid_files(&f);
    let reservations = BTreeMap::from([("string/title".into(), "0x7f010001".into())]);
    let verify = |files| {
        let bytes = zip(&f.signed(files, |_| {}), CompressionMethod::Stored);
        if let Ok(classes) = std::env::var("PARAVOID_VPK_JAVA_CLASSES") {
            let archive = f.directory.path().join("candidate.vpk");
            let pinned = f.directory.path().join("reservations.json");
            std::fs::write(&archive, &bytes).unwrap();
            std::fs::write(&pinned, serde_json::to_vec(&reservations).unwrap()).unwrap();
            let java = Command::new("java")
                .args(["-cp", &classes, "StoreVpkCheck"])
                .arg(&archive)
                .arg(f.directory.path().join("trust.json"))
                .arg("a".repeat(64))
                .arg(&pinned)
                .output()
                .unwrap();
            let rust = lellostore_backend::paravoid::compatibility::inspect(
                &mut Cursor::new(bytes.clone()),
                &f.trust,
                &"a".repeat(64),
                &reservations,
            );
            assert_eq!(
                rust.is_ok(),
                java.status.success(),
                "Java/Rust VPK verdict disagrees"
            );
        }
        lellostore_backend::paravoid::compatibility::inspect(
            &mut Cursor::new(bytes),
            &f.trust,
            &"a".repeat(64),
            &reservations,
        )
    };
    let report = verify(files.clone()).unwrap();
    assert_eq!(report.dex_files, 1);
    assert_eq!(report.resource_reservations, 1);
    // Mirror upstream 9118a93: only nested local headers may have a short
    // zero tail. Exercise these bytes through both Rust and Java verifiers.
    let nested = zip(
        &BTreeMap::from([("probe.txt".into(), b"content".to_vec())]),
        CompressionMethod::Stored,
    );
    for padding in 1..=3 {
        let directory = central(&nested);
        let insertion = 30 + "probe.txt".len();
        let mut aligned = nested.clone();
        aligned.splice(insertion..insertion, vec![0; padding]);
        aligned[28..30].copy_from_slice(&(padding as u16).to_le_bytes());
        let end = aligned.len() - 22;
        aligned[end + 16..end + 20].copy_from_slice(&((directory + padding) as u32).to_le_bytes());
        let mut candidate = files.clone();
        candidate.insert("java-resources.jar".into(), aligned.clone());
        assert!(verify(candidate).is_ok());
        aligned[insertion] = 1;
        let mut bad = files.clone();
        bad.insert("java-resources.jar".into(), aligned);
        assert!(verify(bad).is_err());
        // The same truncated field in a central header is not alignment.
        let mut central_padding = nested.clone();
        let insertion = central_padding.len() - 22;
        central_padding.splice(insertion..insertion, vec![0; padding]);
        central_padding[directory + 30..directory + 32]
            .copy_from_slice(&(padding as u16).to_le_bytes());
        let end = central_padding.len() - 22;
        central_padding[end + 12..end + 16]
            .copy_from_slice(&((end - directory) as u32).to_le_bytes());
        let mut bad = files.clone();
        bad.insert("java-resources.jar".into(), central_padding);
        assert!(verify(bad).is_err());
    }
    // Re-signing malicious content cannot make a corrupt DEX acceptable.
    for index in [0, 8, 12, 32, 36, 40, 111] {
        let mut bad = files.clone();
        bad.get_mut("code/classes.dex").unwrap()[index] ^= 1;
        assert!(verify(bad).is_err(), "DEX byte {index}");
    }
    let mut bad = files.clone();
    bad.insert(
        "resource-ledger.json".into(),
        br#"{"version":1,"applicationId":"example.app","entries":[]}"#.to_vec(),
    );
    assert!(verify(bad).is_err());
    let mut bad = files.clone();
    bad.insert("resource-ledger.json".into(),br#"{"version":1,"applicationId":"example.app","entries":[{"name":"string/title","id":"0x7f010001","removed":true},{"name":"string/other","id":"0x7f010001","removed":false}]}"#.to_vec());
    assert!(verify(bad).is_err());
    let mut bad = files.clone();
    bad.insert("native/arm64-v8a/libbad.so".into(), vec![0; 20]);
    assert!(verify(bad).is_err());
    let mut bad = files;
    bad.insert(
        "resources.apk".into(),
        zip(
            &BTreeMap::from([("resources.arsc".into(), vec![0; 12])]),
            CompressionMethod::Stored,
        ),
    );
    assert!(verify(bad).is_err());
}

#[test]
fn policy_preflight_checks_the_complete_contract_without_claiming_apk_verification() {
    let f = Fixture::new();
    let trust: Value =
        serde_json::from_slice(&std::fs::read(f.directory.path().join("trust.json")).unwrap())
            .unwrap();
    let descriptor = json!({"profile":"complete-apk-v1","runtimeAbi":1,"trustPolicy":trust,
        "installed":{"applicationId":"example.app","minSdk":30,"manifestSha256":"b".repeat(64),
        "declarations":{},"pinnedResources":{},"runtimeClasses":{},"nativeAbis":{},
        "ledgerReservations":{"string/title":"0x7f010001"},"apkSigners":["c".repeat(64)],"toolchain":{}},
        "distribution":{"bootstrap":"embedded","enabled":false,"baseUrl":"","channel":"stable","authentication":"public","debugHttpAllowed":false}});
    let contract = hash(&canonical_json(&descriptor).unwrap());
    let policy = f.directory.path().join("shell-policy.json");
    std::fs::write(
        &policy,
        canonical_json(&json!({"version":1,"contractId":contract,"descriptor":descriptor}))
            .unwrap(),
    )
    .unwrap();
    let archive = f.directory.path().join("policy.vpk");
    for matching in [true, false] {
        let bytes = zip(
            &f.signed(format_valid_files(&f), |body| {
                if matching {
                    body["shellContractId"] = json!(contract);
                }
            }),
            CompressionMethod::Stored,
        );
        std::fs::write(&archive, bytes).unwrap();
        let result = Command::new(env!("CARGO_BIN_EXE_vpk_verify"))
            .arg(&archive)
            .arg("--shell-policy")
            .arg(&policy)
            .output()
            .unwrap();
        assert_eq!(
            result.status.success(),
            matching,
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        if matching {
            let report: Value = serde_json::from_slice(&result.stdout).unwrap();
            assert_eq!(report["apk_policy_verified"], false);
            assert_eq!(report["publication_allowed"], false);
            assert_eq!(report["inspection"]["resource_reservations"], 1);
        }
    }
}

#[test]
#[ignore = "requires PARAVOID_ANDROID_COMPONENTS and PARAVOID_VPK_JAVA_CLASSES from interoperability script"]
fn checks_real_android_components_against_upstream_java() {
    let root = std::path::PathBuf::from(std::env::var("PARAVOID_ANDROID_COMPONENTS").unwrap());
    let classes = std::env::var("PARAVOID_VPK_JAVA_CLASSES").unwrap();
    let f = Fixture::new();
    let files = BTreeMap::from([
        (
            "code/classes.dex".into(),
            std::fs::read(root.join("dex/classes.dex")).unwrap(),
        ),
        (
            "resources.apk".into(),
            std::fs::read(root.join("resources.apk")).unwrap(),
        ),
        (
            "java-resources.jar".into(),
            std::fs::read(root.join("java-resources.jar")).unwrap(),
        ),
        (
            "resource-ledger.json".into(),
            std::fs::read(root.join("resource-ledger.json")).unwrap(),
        ),
    ]);
    let ledger: Value = serde_json::from_slice(&files["resource-ledger.json"]).unwrap();
    let reservations: BTreeMap<String, String> = ledger["entries"]
        .as_array()
        .unwrap()
        .iter()
        .map(|entry| {
            (
                entry["name"].as_str().unwrap().into(),
                entry["id"].as_str().unwrap().into(),
            )
        })
        .collect();
    assert!(!reservations.is_empty());
    let archive = f.directory.path().join("real.vpk");
    let pinned = f.directory.path().join("reservations.json");
    let bytes = zip(&f.signed(files, |_| {}), CompressionMethod::Stored);
    std::fs::write(&archive, &bytes).unwrap();
    std::fs::write(&pinned, serde_json::to_vec(&reservations).unwrap()).unwrap();
    let rust = lellostore_backend::paravoid::compatibility::inspect(
        &mut Cursor::new(bytes),
        &f.trust,
        &"a".repeat(64),
        &reservations,
    )
    .unwrap();
    assert_eq!(rust.dex_files, 1);
    let java = Command::new("java")
        .args(["-cp", &classes, "StoreVpkCheck"])
        .arg(&archive)
        .arg(f.directory.path().join("trust.json"))
        .arg("a".repeat(64))
        .arg(&pinned)
        .output()
        .unwrap();
    assert!(
        java.status.success(),
        "Java rejected actual D8/AAPT2 components: {}",
        String::from_utf8_lossy(&java.stderr)
    );
    let cli = Command::new(env!("CARGO_BIN_EXE_vpk_verify"))
        .arg(&archive)
        .arg(f.directory.path().join("trust.json"))
        .arg("a".repeat(64))
        .arg(&pinned)
        .output()
        .unwrap();
    assert!(cli.status.success());
    assert_eq!(
        serde_json::from_slice::<Value>(&cli.stdout).unwrap()["publication_allowed"],
        false
    );
}

#[allow(dead_code)]
mod common;
#[tokio::test]
async fn durable_vpk_jobs_check_components_without_claiming_installed_policy_verification() {
    use lellostore_backend::{
        db::paravoid,
        services::{upload_jobs, ApkParser, StorageService, UploadService},
    };
    let ctx = common::create_test_context().await;
    let f = Fixture::new();
    sqlx::query("INSERT INTO apps(package_name,name) VALUES ('example.app','Fixture')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk,distribution_mode) VALUES ('example.app',1,'1','shell.apk',1,'hash',30,'paravoid')").execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state) VALUES ('example.app',?,1,'stable','public','embedded','https://example.test/',?,'{}','pending')").bind("a".repeat(64)).bind(std::fs::read_to_string(f.directory.path().join("trust.json")).unwrap()).execute(&ctx.pool).await.unwrap();
    let service = UploadService::new(
        StorageService::new(ctx.storage_path.clone()),
        ApkParser::new("unused-aapt2".into()),
        None,
        ctx.pool.clone(),
        1024 * 1024,
    );
    for valid in [false, true] {
        let files = if valid {
            format_valid_files(&f)
        } else {
            f.files()
        };
        let bytes = zip(&f.signed(files, |_| {}), CompressionMethod::Stored);
        let input = f.directory.path().join("job.vpk");
        std::fs::write(&input, bytes).unwrap();
        let job = upload_jobs::enqueue_vpk(
            &ctx.pool,
            &ctx.storage_path,
            "admin",
            "job.vpk",
            &input,
            "example.app",
            &"a".repeat(64),
        )
        .await
        .unwrap();
        assert!(upload_jobs::process_next(&ctx.pool, &service)
            .await
            .unwrap());
        let completed = upload_jobs::get(&ctx.pool, &job.id).await.unwrap();
        assert_eq!(completed.status, if valid { "ready" } else { "failed" });
    }
    let releases = paravoid::releases(&ctx.pool, "example.app").await.unwrap();
    assert_eq!(releases.len(), 1);
    assert_eq!(releases[0].validation_state, "inspected");
    let report: Value = serde_json::from_str(&releases[0].validation_report).unwrap();
    assert_eq!(report["dex_files"], 1);
    assert_eq!(report["resource_reservations"], "pending");
    assert!(!report["publication_ready"].as_bool().unwrap());
}

#[tokio::test]
async fn registered_policy_promotes_only_payloads_preserving_installed_reservations() {
    use lellostore_backend::services::{upload_jobs, ApkParser, StorageService, UploadService};
    let ctx = common::create_test_context().await;
    let f = Fixture::new();
    let mut trust: Value =
        serde_json::from_slice(&std::fs::read(f.directory.path().join("trust.json")).unwrap())
            .unwrap();
    let online: Value =
        serde_json::from_str(include_str!("fixtures/paravoid-metadata/trust.json")).unwrap();
    trust["headKeys"] = online["headKeys"].clone();
    let descriptor = json!({"profile":"complete-apk-v1","runtimeAbi":1,"trustPolicy":trust,
        "installed":{"applicationId":"example.app","minSdk":30,"manifestSha256":"b".repeat(64),"declarations":{},"pinnedResources":{},"runtimeClasses":{},"nativeAbis":{},"ledgerReservations":{"string/title":"0x7f010001"},"apkSigners":["c".repeat(64)],"toolchain":{}},
        "distribution":{"bootstrap":"empty","enabled":true,"baseUrl":"https://example.test/","channel":"stable","authentication":"public","debugHttpAllowed":false}});
    let contract = hash(&canonical_json(&descriptor).unwrap());
    sqlx::query("INSERT INTO apps(package_name,name) VALUES ('example.app','Fixture')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk) VALUES ('example.app',1,'1','shell.apk',1,'hash',30)").execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state) VALUES ('example.app',?,1,'stable','public','empty','https://example.test/',?,?,'verified')")
        .bind(&contract).bind(trust.to_string()).bind(descriptor.to_string()).execute(&ctx.pool).await.unwrap();
    let service = UploadService::new(
        StorageService::new(ctx.storage_path.clone()),
        ApkParser::new("unused".into()),
        None,
        ctx.pool.clone(),
        1024 * 1024,
    );
    for valid in [false, true] {
        let mut files = format_valid_files(&f);
        if !valid {
            files.insert(
                "resource-ledger.json".into(),
                br#"{"version":1,"applicationId":"example.app","entries":[]}"#.to_vec(),
            );
        }
        let bytes = zip(
            &f.signed(files, |body| body["shellContractId"] = json!(contract)),
            CompressionMethod::Stored,
        );
        let path = f.directory.path().join("queued.vpk");
        std::fs::write(&path, bytes).unwrap();
        let job = upload_jobs::enqueue_vpk(
            &ctx.pool,
            &ctx.storage_path,
            "admin",
            "payload.vpk",
            &path,
            "example.app",
            &contract,
        )
        .await
        .unwrap();
        upload_jobs::process_next(&ctx.pool, &service)
            .await
            .unwrap();
        let result = upload_jobs::get(&ctx.pool, &job.id).await.unwrap();
        assert_eq!(
            result.status,
            if valid { "ready" } else { "failed" },
            "{:?}",
            result.error
        );
    }
    let releases = lellostore_backend::db::paravoid::releases(&ctx.pool, "example.app")
        .await
        .unwrap();
    assert_eq!(releases.len(), 1);
    assert_eq!(releases[0].validation_state, "verified");
    assert_eq!(releases[0].publication_state, "draft");
}
