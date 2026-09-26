# Step 11: shared routing and multipart uploads

LelloStore uses the sibling simple-server checkout at
`4db239946d21d385d8c8835538710aff6c322189`. CI and README select this source.
The API and metrics servers, mock OIDC binary and test fixtures now compose
shared routers, handlers, built-in/custom extractors and responses. Production
startup calls shared serving; no production router is converted back to Axum.

## Multipart ownership

APK/AAB, icon and VPK upload handlers now use
`simple_server::web::extract::{Multipart, multipart::Field}`. Those readers,
fields and errors are simple-server-owned public types. Existing chunked writes
to temporary files and bounded metadata reads stay in this service. File/metadata
validation, storage, application-specific limits, authentication and temporary-file
cleanup retain their previous behavior.

Simple-server also provides owned fields (`web::multipart::OwnedMultipart` with
`multipart-owned`) for consumers needing them. Both interfaces support field
metadata/headers, chunk reads, Stream, bytes/text collection and shared errors.
Borrowed fields enforce exclusivity at compile time; owned fields enforce it at
runtime. The parser does not pre-buffer complete uploads. Legacy compatibility
adapters remain for other services but are not used for LelloStore multipart.

The shared RawQuery extractor preserves raw query encoding for delivery tickets.
Existing WebSocket subprotocol and frame/message size limits are forwarded by the
[owned WebSocket transport](STEP_11_WEBSOCKETS.md). Streamed downloads, range responses, static assets,
authentication error envelopes and middleware placement are preserved.

## Verification

- Baseline default and all-feature suites: 189 passed, eight ignored each.
- Final all-feature suite: 190 passed, the same eight ignores.
- Added real-HTTP multipart rejection checks: metadata over 64 KiB, duplicate
  files, missing files and invalid UTF-8, with empty temporary storage after each
  rejection. Existing suites cover authenticated upload/catalog events, ranges,
  publication/acquisition policy, delivery WebSockets, OIDC and tracing.
- Strict all-target/all-feature Clippy, formatting and diff checks pass.
- All-feature builds use an unchanged copy of the original ignored frontend/dist
  assets. Frontend sources were not changed or rebuilt.
- Shared library: 248 baseline, 254 final tests/doctests; strict Clippy; minimal
  borrowed and owned multipart feature builds pass. Tests compare both parsers'
  field and rejection behavior, verify lazy reads/drop cleanup, preserve raw
  queries, and exercise frame and fragmented-message size limits over real HTTP.
- Six existing Android/toolchain/upstream-dependent integration cases and two
  doctests stay ignored. Docker/Android/frontend suites were not run.

## Remaining boundaries

The tracing compatibility adapter and axum-test's
multipart/WebSocket transport helpers remain explicit backend boundaries. Mock
OIDC server routes and ordinary fixtures use shared APIs. Multipart uploads are
no longer a remaining Axum exposure in this service.
