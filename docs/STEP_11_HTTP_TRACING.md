# Step 11: owned HTTP tracing

The production API router uses
`simple_server::web::tracing::trace_with_observer` with the shared default
`web::tracing::TracingObserver` at reviewed shared revision
`ca98a4159e1cb0dd7b9db2faa9a076d198b7973b`. README and CI select that source;
the sibling path dependency remains unchanged. The isolated migration branch
starts from clean `master` `10973456b2ed59d79a4dd7d87abf2265427635ec`.

This replaces the explicit `web::compat` tracing bridge in production. The
owned API uses the same lifecycle engine and default observer, preserving event
names and order, request correlation, route redaction, response-head reporting,
completion after body consumption, streaming error and cancellation handling,
trailers, bodyless and HEAD responses, and upgrade handoff. Correlation remains
outside tracing in the middleware stack, and LelloStore still owns subscriber
installation through its existing logging setup.

## Verification

- Baseline and final `cargo test --all-features --locked`: 196 passed and eight
  existing ignores (six external Android/toolchain/upstream cases and two
  doctests).
- `tests/http_tracing.rs` exercises the production router for 200, 405 and 503
  responses, consumes each response body, verifies one `http.response_headers`
  and one `http.finished` event per request, confirms shared default-observer
  events, and verifies route-template redaction of query and path secrets.
- `cargo fmt --check`, `git diff --check`, strict
  `cargo clippy --all-targets --all-features --locked -- -D warnings`, and
  `cargo build --all-features --locked` pass.
- Builds use two jobs and an isolated target directory. Embedded assets are an
  unchanged copy of the original ignored `frontend/dist`; frontend sources and
  Android/Docker code are unchanged, so their separate suites are not rerun.

## Remaining boundaries

Production has no explicit HTTP tracing compatibility boundary. Other
`web::compat::into_axum_router` uses remain confined to tests and test tooling;
they are outside this migration. Nothing is pushed or deployed.
