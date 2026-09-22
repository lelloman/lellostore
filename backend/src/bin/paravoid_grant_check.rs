use lellostore_backend::paravoid::{
    apk_grant, apk_policy, parse_json, Authentication, InstalledPolicy, TrustPolicy,
    MAX_GRANT_BYTES, MAX_RELEASE_BYTES,
};
use std::{fs::File, io::Read};
fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() != 6 && !(args.len() == 8 && args[6] == "--source-apk") {
        return Err("Expected trust, contract, audience, channel, mode and input path".into());
    }
    let mut bytes = Vec::new();
    File::open(&args[0])?
        .take(MAX_RELEASE_BYTES as u64 + 1)
        .read_to_end(&mut bytes)?;
    let trust = TrustPolicy::parse(&bytes)?;
    if args.len() == 8 {
        let pinned = apk_policy::read(&mut File::open(&args[7])?)?;
        let distribution = &pinned.descriptor.distribution;
        if pinned.contract_id != args[1]
            || distribution.base_url != args[2]
            || distribution.channel != args[3]
            || distribution.authentication != "apkKey"
            || pinned.descriptor.trust_policy != parse_json(&bytes, MAX_RELEASE_BYTES)?
        {
            return Err("Registered policy differs from signed APK".into());
        }
        if args[4] == "apk"
            && apk_policy::read(&mut File::open(&args[5])?)?.descriptor_bytes
                != pinned.descriptor_bytes
        {
            return Err("Personalized APK policy changed".into());
        }
    }
    let policy = InstalledPolicy::new(
        trust,
        args[1].clone(),
        args[2].clone(),
        args[3].clone(),
        Authentication::ApkKey,
    )?;
    let bytes = match args[4].as_str() {
        "apk" => apk_grant::read(&mut File::open(&args[5])?)?,
        "envelope" => {
            let mut bytes = Vec::new();
            File::open(&args[5])?
                .take(MAX_GRANT_BYTES as u64 + 1)
                .read_to_end(&mut bytes)?;
            bytes
        }
        _ => return Err("Invalid mode".into()),
    };
    policy.verify_grant(&bytes)?;
    Ok(())
}
fn main() {
    if run().is_err() {
        eprintln!("Grant signature, scope or carrier verification failed");
        std::process::exit(1);
    }
}
