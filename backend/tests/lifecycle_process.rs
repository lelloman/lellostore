//! Exercise the real binary using temporary data, loopback listeners and mock OIDC.
#![cfg(unix)]

use std::{io, net::SocketAddr, path::Path, process::Stdio, time::Duration};
use tempfile::TempDir;
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader, Lines},
    net::TcpStream,
    process::{Child, ChildStdout, Command},
};

#[allow(dead_code)]
mod mock_oidc;

fn command(directory: &Path, grace: &str) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_lellostore-backend"));
    command
        .env_clear()
        .current_dir(directory)
        .env("RUST_LOG", "info")
        .env("LISTEN_ADDR", "127.0.0.1:0")
        .env("METRICS_ADDR", "127.0.0.1:0")
        .env("SHUTDOWN_GRACE_SECS", grace)
        .env("OIDC_ISSUER_URL", "https://example.com")
        .env(
            "DATABASE_URL",
            format!("sqlite:{}?mode=rwc", directory.join("catalog.db").display()),
        )
        .env("STORAGE_PATH", directory.join("storage"))
        .kill_on_drop(true);
    command
}

struct Server {
    child: Child,
    lines: Lines<BufReader<ChildStdout>>,
    api: SocketAddr,
    metrics: SocketAddr,
    output: String,
}

impl Server {
    async fn start(mut command: Command) -> io::Result<Self> {
        let mut child = command.stdout(Stdio::piped()).spawn()?;
        let mut lines = BufReader::new(child.stdout.take().unwrap()).lines();
        let mut api = None;
        let mut metrics = None;
        let mut output = String::new();
        while api.is_none() || metrics.is_none() {
            let Some(line) = lines.next_line().await? else {
                panic!("server exited before binding: {output}");
            };
            if let Some(start) = line.find("127.0.0.1:") {
                let address = line[start..]
                    .chars()
                    .take_while(|c| c.is_ascii_digit() || *c == '.' || *c == ':')
                    .collect::<String>()
                    .parse()
                    .unwrap();
                if line.contains("Metrics server listening on") {
                    metrics = Some(address);
                } else if line.contains("Server listening on") {
                    api = Some(address);
                }
            }
            output.push_str(&line);
        }
        Ok(Self {
            child,
            lines,
            api: api.unwrap(),
            metrics: metrics.unwrap(),
            output,
        })
    }

    async fn signal(&self, signal: &str) -> io::Result<()> {
        assert!(Command::new("kill")
            .args([signal, &self.child.id().unwrap().to_string()])
            .status()
            .await?
            .success());
        Ok(())
    }

    async fn finish(mut self, success: bool) -> io::Result<String> {
        while let Some(line) = self.lines.next_line().await? {
            self.output.push_str(&line);
        }
        let status = self.child.wait().await?;
        assert_eq!(status.success(), success, "{status}: {}", self.output);
        if success {
            assert!(
                self.output.contains("Metrics updater stopped"),
                "{}",
                self.output
            );
            assert!(
                self.output.contains("Catalog event connections drained"),
                "{}",
                self.output
            );
            let closed = self
                .output
                .find("Database pool closed")
                .expect("database cleanup logged");
            let stopped = self
                .output
                .find("LelloStore shutdown complete")
                .expect("shutdown logged");
            assert!(closed < stopped);
        }
        assert!(TcpStream::connect(self.api).await.is_err());
        assert!(TcpStream::connect(self.metrics).await.is_err());
        Ok(self.output)
    }
}

#[tokio::test]
async fn signals_drain_both_listeners_and_close_database() -> io::Result<()> {
    for signal in ["-INT", "-TERM"] {
        tokio::time::timeout(Duration::from_secs(15), async {
            let temp = TempDir::new()?;
            let server = Server::start(command(temp.path(), "3")).await?;
            let client = reqwest::Client::new();
            assert!(client
                .get(format!("http://{}/health", server.api))
                .send()
                .await
                .unwrap()
                .status()
                .is_success());
            assert_eq!(
                client
                    .get(format!("http://{}/api/apps", server.api))
                    .send()
                    .await
                    .unwrap()
                    .status(),
                reqwest::StatusCode::SERVICE_UNAVAILABLE
            );
            let metrics = client
                .get(format!("http://{}/metrics", server.metrics))
                .send()
                .await
                .unwrap()
                .text()
                .await
                .unwrap();
            assert!(metrics.contains("lellostore_apps_total"));
            server.signal(signal).await?;
            server.finish(true).await?;
            Ok::<_, io::Error>(())
        })
        .await
        .expect("signal shutdown timed out")?;
    }
    Ok(())
}

#[tokio::test]
async fn either_bind_failure_prevents_serving_and_bad_budget_is_rejected() -> io::Result<()> {
    tokio::time::timeout(Duration::from_secs(15), async {
        for variable in ["LISTEN_ADDR", "METRICS_ADDR"] {
            let temp = TempDir::new()?;
            let occupied = std::net::TcpListener::bind("127.0.0.1:0")?;
            let output = command(temp.path(), "3")
                .env(variable, occupied.local_addr()?.to_string())
                .output()
                .await?;
            let stdout = String::from_utf8_lossy(&output.stdout);
            assert!(!output.status.success(), "{stdout}");
            assert!(stdout.contains("Address already in use"), "{stdout}");
            assert!(
                !stdout.contains("Server listening on"),
                "neither listener should serve: {stdout}"
            );
        }
        let temp = TempDir::new()?;
        let output = command(temp.path(), "bad").output().await?;
        assert!(!output.status.success());
        assert!(String::from_utf8_lossy(&output.stdout).contains("Invalid SHUTDOWN_GRACE_SECS"));
        Ok(())
    })
    .await
    .expect("startup failure test timed out")
}

#[tokio::test]
async fn active_multipart_request_drains_or_reports_http_deadline() -> io::Result<()> {
    for finish_request in [true, false] {
        tokio::time::timeout(Duration::from_secs(15), async {
            let oidc = mock_oidc::MockOidc::start().await;
            let temp = TempDir::new()?;
            let mut cmd = command(temp.path(), if finish_request { "3" } else { "1" });
            cmd.env("OIDC_ISSUER_URL", oidc.issuer_url());
            let server = Server::start(cmd).await?;
            let mut stream = TcpStream::connect(server.api).await?;
            // Valid multipart metadata but no APK: the expected final response is
            // 400. Holding the body lets us test a real in-flight upload handler.
            let body = b"--drain\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\ntest\r\n--drain--\r\n";
            let headers = format!("POST /api/admin/apps HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {}\r\nContent-Type: multipart/form-data; boundary=drain\r\nContent-Length: {}\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n", oidc.get_admin_token(), body.len());
            stream.write_all(headers.as_bytes()).await?;
            let mut interim = Vec::new();
            while !interim.ends_with(b"\r\n\r\n") { interim.push(stream.read_u8().await?); }
            assert!(String::from_utf8_lossy(&interim).starts_with("HTTP/1.1 100"));
            server.signal("-TERM").await?;
            // Wait for listener closure before releasing the in-flight body.
            while TcpStream::connect(server.api).await.is_ok() {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
            if finish_request {
                stream.write_all(body).await?;
                let mut response = String::new();
                stream.read_to_string(&mut response).await?;
                assert!(response.starts_with("HTTP/1.1 400"), "{response}");
                server.finish(true).await?;
            } else {
                let output = server.finish(false).await?;
                assert!(output.contains("deadline expired: Services([\"http\"])"), "{output}");
                assert!(!output.contains("Database pool closed"), "cleanup must not run with unfinished HTTP");
            }
            Ok::<_, io::Error>(())
        }).await.expect("multipart shutdown test timed out")?;
    }
    Ok(())
}
