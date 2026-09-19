# Step 02: lifecycle and entry-point setup

LelloStore uses the lifecycle helpers from `simple-server` revision
`c5359079ff4fad0b4b0359e8c88880dbbbc4eeb5`. Application setup, logging,
authentication, routes, uploads, migrations, and the Tokio runtime remain owned
by LelloStore. This migration is based on LelloStore revision `eed1cff`.

## Participating services

The coordinator runs four named futures:

| Service | Shutdown behavior |
| --- | --- |
| `http` | Stop accepting new API/frontend connections and drain active requests, uploads, and downloads |
| `metrics-http` | Stop accepting new metrics connections and drain active scrapes |
| `metrics-updater` | Stop scheduling cycles and finish the active storage/database sampling cycle |
| `catalog-events` | Close WebSocket admission and wait for accepted upgrades and socket handlers to finish |

Both TCP listeners bind before any of these futures run. Failure to bind either
port now fails startup; metrics binding failures are no longer just logged by a
detached task. The logs report actual bound addresses, including port zero.

The metrics updater is no longer detached. Its directory traversal runs on the
blocking pool and is awaited, so traversal does not block the async runtime's
shutdown timer. Existing query failures still log warnings and allow subsequent
cycles; a blocking task failure propagates to the lifecycle coordinator.

Catalog WebSocket handlers observe the shared shutdown notification and attempt
to send a close frame. Tracking begins before scheduling the upgrade, including
pending handshakes. An admission lock serializes token creation with tracker
closure, preventing late upgrades from escaping an already-completed drain.
Requests for a new authenticated upgrade after shutdown receive HTTP 503.

Once all four services finish, application cleanup closes the SQLite pool. The
same deadline covers service draining and pool closure. Cleanup is skipped if
services remain unfinished at the deadline.

## Configuration and exit policy

`SHUTDOWN_GRACE_SECS` defaults to 30. It accepts a nonnegative integer; invalid
or unrepresentable durations fail configuration loading. Zero permits no drain
wait. Configure a deployment stop timeout longer than this budget (for example,
35 seconds with the default budget).

SIGINT and SIGTERM use explicit shared signal registration. Errors and timeouts
retain named service details, are logged, and exit with status 1. An explicit
error exit prevents a timed-out blocking task from keeping Tokio runtime teardown
open indefinitely. Successful draining and database cleanup exit normally.

The budget begins when shutdown is observed by the coordinator; it does not
cover application startup. A deadline bounds cooperative waiting, not completion
of arbitrary blocking work or termination of external Java/aapt2 processes.
Uploads and downloads still in progress can be interrupted at expiry. Subprocess
termination/recovery remains an application-specific concern; this step does not
claim process-tree supervision.

## Source and build setup

The dependency currently uses `path = "../../simple-server"`, with `lifecycle`
added alongside `multipart`, `macros`, and `ws`. Keep the reviewed library commit
checked out beside LelloStore. Cargo.lock records the dependency graph, but does
not pin a path dependency's source revision.

The backend CI job checks out LelloStore and the reviewed library as siblings.
The library commit must be available on the public mirror before remote CI can
fetch it; committing this migration locally does not publish either repository.

From the LelloStore root:

```sh
docker build --build-context simple-server=../simple-server -t lellostore .
```

The Dockerfile takes the library manifest and source from this named build
context. Both cached and final Rust release builds use `--locked` and preserve
frontend embedding. Once the library revision is published, the consumer can
return to a reviewed Git dependency and remove the sibling-context requirement.

## Validation

- Unchanged baseline: 122 backend tests passed, with two existing ignored
  documentation examples.
- After migration: all 128 backend tests pass (`cargo test --all-features
  --locked`), with the same two ignored documentation examples.
- New coverage includes pending WebSocket upgrade tracking, authenticated
  WebSocket closure and admission rejection, and shutdown-budget validation.
- Real-binary tests use temporary data and loopback sockets. They cover SIGINT,
  SIGTERM, both listener bind failures, invalid configuration, health/metrics,
  fail-closed API behavior without OIDC, and ordered database cleanup.
- A real authenticated multipart request held at `100 Continue` completes after
  shutdown when its body arrives within the budget. Keeping the body withheld
  instead returns a named `http` timeout and skips pool cleanup.
- Formatting and strict all-target/all-feature Clippy pass.
- All 16 repository/script tests pass. These use mock publisher operations; no
  artifact was uploaded. Android and frontend application source are unchanged.
- Docker build passed using the explicit library context, locked Rust 1.92
  release build, and embedded frontend. Nothing was deployed or published.
