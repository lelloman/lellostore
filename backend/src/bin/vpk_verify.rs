//! Explicit offline preflight; supplied policy is not proof of APK pinning.
use lellostore_backend::paravoid::{
    compatibility, parse_json, shell_policy::ShellPolicyDocument, TrustPolicy, MAX_RELEASE_BYTES,
};
use std::{collections::BTreeMap, fs::File, io::Read};
fn bounded(path: &str, limit: usize) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let mut bytes = Vec::new();
    File::open(path)?
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() > limit {
        return Err("Input exceeds limit".into());
    }
    Ok(bytes)
}
fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() != 4 && !(args.len() == 3 && args[1] == "--shell-policy") {
        return Err(
            "Usage: vpk_verify archive.vpk trust.json contract-sha256 reservations.json\n       vpk_verify archive.vpk --shell-policy shell-policy.json".into(),
        );
    }
    let (trust, contract, reservations) = if args.len() == 3 {
        let policy = ShellPolicyDocument::parse(&bounded(&args[2], MAX_RELEASE_BYTES)?)?;
        (
            policy.trust,
            policy.contract_id,
            policy.descriptor.installed.ledger_reservations,
        )
    } else {
        let reservations: BTreeMap<String, String> = serde_json::from_value(parse_json(
            &bounded(&args[3], 16 * 1024 * 1024)?,
            16 * 1024 * 1024,
        )?)?;
        (
            TrustPolicy::parse(&bounded(&args[1], MAX_RELEASE_BYTES)?)?,
            args[2].clone(),
            reservations,
        )
    };
    let report =
        compatibility::inspect(&mut File::open(&args[0])?, &trust, &contract, &reservations)?;
    println!(
        "{}",
        serde_json::to_string_pretty(
            &serde_json::json!({"format_checks":"passed","apk_policy_verified":false,"publication_allowed":false,"inspection":report})
        )?
    );
    Ok(())
}
fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(1);
    }
}
