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
