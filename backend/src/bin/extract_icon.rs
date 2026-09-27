//! Extract catalog artwork using the same parser as uploads.
use lellostore_backend::services::apk::ApkParser;
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        return Err("Usage: extract_icon INPUT.apk OUTPUT.png".into());
    }
    let parser = ApkParser::auto_detect()?;
    let metadata = parser.parse(std::path::Path::new(&args[1])).await?;
    let icon = metadata.icon_data.ok_or("No supported icon extracted")?;
    std::fs::write(&args[2], icon)?;
    println!("Extracted icon for {}", metadata.package_name);
    Ok(())
}
