# Owned HTTP test fixtures — 2026-09-28

The backend uses sibling `simple-server` revision
`d61c049aa49d89de6936de9e93a68bd18685f373`. The normal dependency enables
`web`; the test dependency adds optional `test-harness-ws`. The backend no longer
enables `web-compat` or depends on `axum-test`. CI and README select the reviewed
shared source revision.

The four affected suites use `testing::TestServer` with owned routers:
`e2e_auth`, `integration`, `paravoid_distribution`, and `publications`.
`e2e_auth` also compiles its Paravoid HTTP and opt-in device fixtures. In-process
handlers, real loopback HTTP/WebSocket upgrades, multipart APK/VPK uploads, raw
bytes and status/header/JSON assertions now use the shared harness. A small
`owned_harness_ws` test helper encodes LelloStore-specific JSON and close-frame
assertions with `web::ws::Message`; it does not expose a backend socket.

Baseline on clean `master` at `9fbf140`: the four affected suites passed **53**
tests with **three existing ignores**. The migrated suites pass the same **53**
tests with the same ignores, including authenticated catalog events, Paravoid
subscription hints, subprotocols, Ping/Pong, shutdown closes, binary
APK/VPK multipart uploads and publication contracts. The complete locked backend
`--tests` run passed **200** tests with **six existing ignores**. The first
full run timed out in three real-process lifecycle tests; those tests passed on
unchanged `master`, passed separately on the migration branch, and passed in the
final complete backend rerun. Formatting, strict default-feature all-target
Clippy, and a locked production backend build passed.
The opt-in device acceptance test still requires a disposable emulator and its
listed Android tools; it was compiled but not run. Frontend, Android and Docker
suites were not rerun for this test-only migration.

The test harness bounds each HTTP request/response and WebSocket operation; its
per-request defaults are five seconds and 8 MiB. Active fixture payloads are
small. The ignored device acceptance fixture uses real local APKs, so it explicitly
allows 100 MiB responses and 120-second operations; it remains opt-in. Production request routing, auth, uploads, socket policy and serving
were not changed. A source audit after the migration finds no direct Axum or
axum-test use in `backend` Rust code. The shared implementation remains backed
by its private framework as documented in the library contract.

Implementation is committed in the isolated `migration/owned-test-harness`
worktree branch, then local `master` is rebased onto that branch. Tested-tree
identity and ancestry are verified before removing the temporary worktree and
branch. Pre-existing unrelated worktrees remain untouched. No push or deployment
is part of this migration.
