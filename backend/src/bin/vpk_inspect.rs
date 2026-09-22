//! Offline diagnostic, not an artifact publication or compatibility authority.
use lellostore_backend::paravoid::{archive, TrustPolicy, MAX_RELEASE_BYTES};
use std::{fs::File, io::Read};

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() != 3 {
        return Err(
            "Usage: vpk_inspect <payload.vpk> <apk-pinned-trust.json> <shell-contract-sha256>"
                .into(),
        );
    }
    let mut bytes = Vec::new();
    File::open(&args[1])?
        .take(MAX_RELEASE_BYTES as u64 + 1)
        .read_to_end(&mut bytes)?;
    let trust = TrustPolicy::parse(&bytes)?;
    let contract = args[2].to_str().ok_or("Invalid contract encoding")?;
    let inspection = archive::inspect(&mut File::open(&args[0])?, &trust, contract)?;
    serde_json::to_writer_pretty(
        std::io::stdout(),
        &serde_json::json!({
            "complete_verification": false,
            "publication_allowed": false,
            "inspection": inspection,
            "remaining_checks": ["verified shell contract and signer continuity", "Android executable/resource semantics", "resource ledger and pinned-content compatibility", "published identity and version history", "upstream complete-VPK conformance"]
        }),
    )?;
    println!();
    Ok(())
}
fn main() {
    if let Err(error) = run() {
        eprintln!("VPK inspection failed: {error}");
        std::process::exit(1);
    }
}
