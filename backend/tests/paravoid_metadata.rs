use base64::{engine::general_purpose::STANDARD, Engine};
use lellostore_backend::paravoid::*;
use serde_json::{json, Value};

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/paravoid-metadata")
            .join(name),
    )
    .unwrap()
}
fn policy() -> InstalledPolicy {
    InstalledPolicy::new(
        TrustPolicy::parse(&fixture("trust.json")).unwrap(),
        "a".repeat(64),
        "https://updates.example.test/".into(),
        "stable".into(),
        Authentication::ApkKey,
    )
    .unwrap()
}

#[test]
fn agrees_with_upstream_java_signed_metadata_vectors() {
    let policy = policy();
    let cases: Vec<Value> = serde_json::from_slice(&fixture("cases.json")).unwrap();
    for case in cases {
        let bytes = fixture(case["file"].as_str().unwrap());
        let result = match case["role"].as_str().unwrap() {
            "head" => policy
                .verify_head(&bytes, 30, &["x86_64".into()])
                .map(|_| ()),
            "grant" => policy.verify_grant(&bytes).map(|_| ()),
            _ => panic!("unknown vector role"),
        };
        let expected = match case["result"].as_str().unwrap() {
            "ACCEPT" => Ok(()),
            "INVALID_SIGNATURE" => Err(VerificationError::InvalidSignature),
            "MALFORMED" => Err(VerificationError::Malformed),
            "INCOMPATIBLE" => Err(VerificationError::Incompatible),
            _ => panic!("unknown vector outcome"),
        };
        assert_eq!(result, expected, "{}", case["file"]);
    }
}

#[test]
fn signature_covers_exact_body_bytes_but_not_envelope_whitespace() {
    let policy = policy();
    let mut envelope: Value = serde_json::from_slice(&fixture("head-a.json")).unwrap();
    let pretty = serde_json::to_vec_pretty(&envelope).unwrap();
    let verified = policy.verify_head(&pretty, 30, &["x86_64".into()]).unwrap();
    let original = STANDARD.decode(envelope["body"].as_str().unwrap()).unwrap();
    assert_eq!(verified.authenticated().exact_bytes(), original);
    let mut changed = original;
    changed.push(b' ');
    envelope["body"] = json!(STANDARD.encode(changed));
    assert_eq!(
        policy
            .verify_head(
                &serde_json::to_vec(&envelope).unwrap(),
                30,
                &["x86_64".into()]
            )
            .err(),
        Some(VerificationError::InvalidSignature)
    );
}

#[test]
fn strict_json_rejects_ambiguous_numbers_keys_unicode_and_trailing_data() {
    for bytes in [
        b"{\"a\":1,\"\\u0061\":2}".as_slice(),
        b"-0",
        b"-1",
        b"1.0",
        b"1e0",
        b"01",
        b"9007199254740992",
        b"\"\\ud800\"",
        b"\"\xff\"",
        b"{}{}",
        b"\xef\xbb\xbf{}",
    ] {
        assert!(parse_json(bytes, 4096).is_err(), "{bytes:?}");
    }
    assert!(parse_json(b"9007199254740991", 4096).is_ok());
    assert!(parse_json(b"{}", 1).is_err());
    assert!(parse_json(
        format!("{}0{}", "[".repeat(33), "]".repeat(33)).as_bytes(),
        4096
    )
    .is_err());
}

#[test]
fn canonical_writer_uses_utf16_order_and_safe_integers() {
    let value = json!({"\u{e000}": 2, "\u{10000}": 1, "a": [true, null, "\n\u{0001}"]});
    assert_eq!(
        String::from_utf8(canonical_json(&value).unwrap()).unwrap(),
        "{\"a\":[true,null,\"\\n\\u0001\"],\"𐀀\":1,\"\u{e000}\":2}"
    );
    assert!(canonical_json(&json!(-1)).is_err());
    assert!(canonical_json(&json!(1.0)).is_err());
    assert!(canonical_json(&json!(MAX_INTEGER + 1)).is_err());
}

#[test]
fn untrusted_keys_scope_role_reuse_and_noncanonical_key_encodings_fail() {
    let mut trust: Value = serde_json::from_slice(&fixture("trust.json")).unwrap();
    trust["headKeys"]["head"] = trust["releaseKeys"]["release"].clone();
    assert_eq!(
        TrustPolicy::parse(&serde_json::to_vec(&trust).unwrap()).err(),
        Some(VerificationError::Incompatible)
    );
    let mut trust: Value = serde_json::from_slice(&fixture("trust.json")).unwrap();
    let mut key = STANDARD
        .decode(trust["headKeys"]["head"].as_str().unwrap())
        .unwrap();
    key.push(0);
    trust["headKeys"]["head"] = json!(STANDARD.encode(key));
    assert!(TrustPolicy::parse(&serde_json::to_vec(&trust).unwrap()).is_err());
    let mut envelope: Value = serde_json::from_slice(&fixture("head-a.json")).unwrap();
    envelope["keyId"] = json!("other");
    assert_eq!(
        policy()
            .verify_head(
                &serde_json::to_vec(&envelope).unwrap(),
                30,
                &["x86_64".into()]
            )
            .err(),
        Some(VerificationError::UntrustedKey)
    );
    assert_eq!(
        policy()
            .verify_head(&fixture("head-a.json"), 31, &["x86_64".into()])
            .err(),
        Some(VerificationError::Incompatible)
    );
    assert!(policy()
        .verify_head(
            &fixture("head-a.json"),
            30,
            &["x86_64".into(), "x86_64".into()]
        )
        .is_err());
}

#[test]
fn metadata_verification_does_not_claim_current_time_or_replay_admission() {
    let verified = policy()
        .verify_head(&fixture("head-a.json"), 30, &["x86_64".into()])
        .unwrap();
    assert_eq!(verified.body().issued_at, 1800000000);
    assert_eq!(verified.body().expires_at, 1800003600);
    assert_eq!(verified.body().status, HeadStatus::Available);
    assert!(verified.body().release.as_ref().unwrap().payload_version > 0);
    let grant = policy().verify_grant(&fixture("grant.json")).unwrap();
    assert_eq!(grant.expires_at(), 0);
    assert_eq!(grant.credential().len(), 43);
}

#[cfg(unix)]
#[test]
fn online_signing_round_trips_and_rejects_unsafe_configuration() {
    use lellostore_backend::paravoid::signing::{OnlineSigning, SigningError};
    use std::{os::unix::fs::PermissionsExt, process::Command};
    let temp = tempfile::tempdir().unwrap();
    for role in ["head", "grant"] {
        let pem = temp.path().join(format!("{role}.pem"));
        let der = temp.path().join(format!("{role}.pk8"));
        // Fresh throwaway test authorities. No private keys are committed or
        // reused outside this isolated test directory. OpenSSL is a test tool.
        let output = Command::new("openssl")
            .args([
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:3072",
                "-out",
            ])
            .arg(&pem)
            .output()
            .expect("OpenSSL is required for signing interoperability tests");
        assert!(output.status.success());
        let output = Command::new("openssl")
            .args(["pkcs8", "-topk8", "-nocrypt", "-outform", "DER", "-in"])
            .arg(&pem)
            .arg("-out")
            .arg(&der)
            .output()
            .unwrap();
        assert!(output.status.success());
        std::fs::set_permissions(&der, std::fs::Permissions::from_mode(0o600)).unwrap();
    }
    let path = temp.path().join("config.json");
    let configuration = json!({"version": 1, "baseUrl": "https://updates.example.test/", "headKeys": {"head": "head.pk8"}, "grantKeys": {"grant": "grant.pk8"}, "activeHeadKey": "head", "activeGrantKey": "grant"});
    std::fs::write(&path, serde_json::to_vec(&configuration).unwrap()).unwrap();
    let signer = OnlineSigning::load(&path).unwrap();
    let public = signer.public_configuration();
    let mut trust: Value = serde_json::from_slice(&fixture("trust.json")).unwrap();
    trust["headKeys"] = json!(public.head_keys);
    trust["grantKeys"] = json!(public.grant_keys);
    let policy = InstalledPolicy::new(
        TrustPolicy::parse(&serde_json::to_vec(&trust).unwrap()).unwrap(),
        "a".repeat(64),
        public.base_url,
        "stable".into(),
        Authentication::ApkKey,
    )
    .unwrap();
    for (file, role) in [("head-a.json", "head"), ("grant.json", "grant")] {
        let input: Value = serde_json::from_slice(&fixture(file)).unwrap();
        let body: Value =
            serde_json::from_slice(&STANDARD.decode(input["body"].as_str().unwrap()).unwrap())
                .unwrap();
        let signed = if role == "head" {
            signer.sign_head(&body, &policy, 30, &["x86_64".into()])
        } else {
            signer.sign_grant(&body, &policy)
        }
        .unwrap();
        let envelope: Value = serde_json::from_slice(&signed).unwrap();
        let mut exact = format!("paravoid/v1/{role}\n").into_bytes();
        exact.extend(STANDARD.decode(envelope["body"].as_str().unwrap()).unwrap());
        let data_path = temp.path().join("signed-bytes");
        let sig_path = temp.path().join("signature");
        let pub_path = temp.path().join("public.pem");
        std::fs::write(&data_path, exact).unwrap();
        std::fs::write(
            &sig_path,
            STANDARD
                .decode(envelope["signature"].as_str().unwrap())
                .unwrap(),
        )
        .unwrap();
        assert!(Command::new("openssl")
            .args(["pkey", "-in"])
            .arg(temp.path().join(format!("{role}.pem")))
            .args(["-pubout", "-out"])
            .arg(&pub_path)
            .output()
            .unwrap()
            .status
            .success());
        assert!(Command::new("openssl")
            .args(["dgst", "-sha256", "-verify"])
            .arg(&pub_path)
            .arg("-signature")
            .arg(&sig_path)
            .arg(&data_path)
            .output()
            .unwrap()
            .status
            .success());
        // The original upstream policy does not trust these throwaway keys.
        let original = crate::policy();
        let rejected = if role == "head" {
            signer.sign_head(&body, &original, 30, &["x86_64".into()])
        } else {
            signer.sign_grant(&body, &original)
        };
        assert!(rejected.is_err());
    }
    let exported = serde_json::to_string(&signer.public_configuration()).unwrap();
    assert!(!exported.contains(".pk8"));
    assert!(!exported.contains("PRIVATE"));
    let mut reused = configuration;
    reused["grantKeys"]["grant"] = json!("head.pk8");
    std::fs::write(&path, serde_json::to_vec(&reused).unwrap()).unwrap();
    assert!(matches!(
        OnlineSigning::load(&path),
        Err(SigningError::Configuration)
    ));
    std::fs::set_permissions(
        temp.path().join("head.pk8"),
        std::fs::Permissions::from_mode(0o644),
    )
    .unwrap();
    assert!(matches!(
        OnlineSigning::load(&path),
        Err(SigningError::Permissions)
    ));
}
