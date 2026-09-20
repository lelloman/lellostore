#[path = "../src/logging.rs"]
mod logging;
use std::process::Command;
use tracing_subscriber::EnvFilter;

#[test]
fn child() {
    let Ok(mode) = std::env::var("LELLOSTORE_LOGGING_PROBE") else {
        return;
    };
    if mode == "shared" {
        logging::init().unwrap();
    } else {
        tracing_subscriber::fmt()
            .with_env_filter(EnvFilter::from_default_env())
            .init();
    }
    let span = tracing::info_span!(target: "application", "scope", tenant = 7);
    let _scope = span.enter();
    tracing::debug!(target: "application", count = 2, ready = true, "compat-debug");
    tracing::info!(target: "other", "compat-info");
    tracing::warn!(target: "other", "compat-warn");
    tracing::error!(target: "other", "compat-error");
    tracing_log::log::warn!(target: "dependency", "compat-log-bridge");
    tracing::trace!(target: "application", "compat-scoped-trace");
}

fn output(mode: &str, filter: Option<&str>, color: Option<&str>) -> (Vec<String>, Vec<u8>) {
    let mut command = Command::new(std::env::current_exe().unwrap());
    command
        .args(["--exact", "child", "--nocapture"])
        .env("LELLOSTORE_LOGGING_PROBE", mode);
    if let Some(filter) = filter {
        command.env("RUST_LOG", filter);
    } else {
        command.env_remove("RUST_LOG");
    }
    if let Some(color) = color {
        command.env("NO_COLOR", color);
    } else {
        command.env_remove("NO_COLOR");
    }
    let result = command.output().unwrap();
    assert!(result.status.success(), "{result:?}");
    let stdout = String::from_utf8(result.stdout).unwrap();
    assert!(
        stdout.contains("1 passed"),
        "child probe didn't run: {stdout}"
    );
    let events = stdout
        .lines()
        .filter(|line| line.contains("compat-"))
        .map(|line| line.split_once('Z').unwrap().1.to_owned())
        .collect();
    (events, result.stderr)
}

#[test]
fn preserves_lossy_filter_stdout_color_spans_and_log_bridge() {
    for filter in [
        None,
        Some(""),
        Some("   "),
        Some("off"),
        Some("warn"),
        Some("application=debug"),
        Some("application=debug,broken["),
        Some("application[scope{tenant=7}]=trace,off"),
    ] {
        for color in [None, Some(""), Some("1")] {
            assert_eq!(
                output("legacy", filter, color),
                output("shared", filter, color),
                "filter={filter:?} color={color:?}"
            );
        }
    }
}
