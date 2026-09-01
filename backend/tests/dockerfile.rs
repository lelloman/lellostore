#[test]
fn container_listens_on_all_interfaces_by_default() {
    let dockerfile = include_str!("../../Dockerfile");

    assert!(
        dockerfile.contains("ENV LISTEN_ADDR=0.0.0.0:8080"),
        "the container must not inherit the loopback-only application default",
    );
}
