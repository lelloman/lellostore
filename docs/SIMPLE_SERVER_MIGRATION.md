# Step 01: Axum centralization

LelloStore's backend pins the public `simple-server` Git dependency at revision
`46a36391ccca522f3ec9aa24206f2b231162dd96`, with `multipart`, `macros`, and `ws`.
Production code, the mock OIDC binary, and tests use `simple_server::axum`.
The backend lockfile resolves Axum 0.8.9 instead of 0.7.9.

The migration updates named routes to `{parameter}` syntax, implements the
native async authentication extractor traits, and converts outgoing WebSocket
text to Axum 0.8's message type. The compatible `axum-test` 18 companion also
requires adapting test header construction. The service retains ownership of
startup, OIDC authentication, access policies, uploads, and database setup.
No sibling checkout is needed for standalone or Docker builds.

## Verification (2026-09-19)

- Frontend: `npm ci` and `npm run build` passed, producing real embedded assets.
- Backend: `cargo test --all-features --locked` passed **122 tests**, with two
  existing ignored documentation examples. This includes eight authentication
  E2E scenarios, 21 integration tests, and seven upload-service tests.
- Added a real-socket E2E test that rejects an unauthenticated WebSocket upgrade,
  connects an authenticated user, uploads an APK as admin, and verifies delivery
  of the expected catalog-change JSON event.
- `cargo fmt --check` and strict
  `cargo clippy --all-targets --all-features --locked -- -D warnings` passed.
- `cargo tree --locked -i axum` shows one Axum 0.8.9, consumed through
  `simple-server` and the compatible test library.
- The existing Dockerfile built the release binary and embedded frontend.
  An ephemeral container with networking disabled and an unreachable OIDC
  endpoint passed health and frontend checks (HTTP 200). Catalog and admin
  endpoints remained unavailable (HTTP 503), preserving fail-closed behavior.
  The container was stopped and automatically removed.

Android and publisher code were unchanged; their separate test suites were not
run. Nothing was published or deployed as part of this migration.
