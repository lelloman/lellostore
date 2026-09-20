//! Preserve LelloStore's environment policy while sharing subscriber setup.
use simple_server::logging::{self, AnsiMode, LogOutput, LoggingOptions};
use tracing_subscriber::EnvFilter;

pub fn init() -> Result<(), simple_server::lifecycle::BoxError> {
    let filter = EnvFilter::from_default_env().to_string();
    let mut options = LoggingOptions::new(if filter.is_empty() { "off" } else { &filter });
    options.output = LogOutput::Stdout;
    options.ansi = if std::env::var("NO_COLOR").is_ok_and(|value| !value.is_empty()) {
        AnsiMode::Never
    } else {
        AnsiMode::Always
    };
    logging::try_init(options)?;
    tracing_log::LogTracer::builder()
        .with_max_level(tracing_log::AsLog::as_log(
            &tracing::level_filters::LevelFilter::current(),
        ))
        .init()?;
    Ok(())
}
