use std::env;
use std::path::Path;
use std::process::Command;

fn main() {
    // Only build frontend in release mode or if FORCE_FRONTEND_BUILD is set
    let profile = env::var("PROFILE").unwrap_or_default();
    let force_build = env::var("FORCE_FRONTEND_BUILD").is_ok();

    if profile != "release" && !force_build {
        // In dev mode, assume frontend is built separately
        return;
    }

    let frontend_dir = Path::new("../frontend");
    let dist_dir = frontend_dir.join("dist");

    // Check if dist exists and has content
    if dist_dir.exists() && dist_dir.read_dir().map(|mut d| d.next().is_some()).unwrap_or(false) {
        println!("cargo:rerun-if-changed=../frontend/dist");
        return;
    }

    println!("cargo:warning=Building frontend...");

    // Run npm install
    let status = Command::new("npm")
        .arg("install")
        .current_dir(frontend_dir)
        .status()
        .expect("Failed to run npm install");

    if !status.success() {
        panic!("npm install failed");
    }

    // Run npm build
    let status = Command::new("npm")
        .arg("run")
        .arg("build")
        .current_dir(frontend_dir)
        .status()
        .expect("Failed to run npm run build");

    if !status.success() {
        panic!("npm run build failed");
    }

    println!("cargo:rerun-if-changed=../frontend/src");
    println!("cargo:rerun-if-changed=../frontend/public");
    println!("cargo:rerun-if-changed=../frontend/index.html");
}
