#[test]
fn container_listens_on_all_interfaces_by_default() {
    let dockerfile = include_str!("../../Dockerfile");

    assert!(
        dockerfile.contains("ENV LISTEN_ADDR=0.0.0.0:8080"),
        "the container must not inherit the loopback-only application default",
    );
}

#[test]
fn container_enables_the_tools_it_installs_for_aab_uploads() {
    let dockerfile = include_str!("../../Dockerfile");

    assert!(
        dockerfile.contains("ENV BUNDLETOOL_PATH=/usr/local/lib/bundletool.jar"),
        "the downloaded bundletool jar must be configured for the backend",
    );
    assert!(
        dockerfile.contains("JAVA_PATH=/usr/bin/java"),
        "the installed Java runtime must be configured for the backend",
    );
    assert!(
        dockerfile.contains("AAPT2_PATH=/usr/bin/aapt"),
        "the installed Android package tool must be configured for APK parsing",
    );
}

#[test]
fn container_enables_frontend_embedding_for_release_builds() {
    let dockerfile = include_str!("../../Dockerfile");

    assert_eq!(
        dockerfile.matches("cargo build --release --features embed-frontend").count(),
        2,
        "both cached and final release builds must use the same frontend feature",
    );
}
