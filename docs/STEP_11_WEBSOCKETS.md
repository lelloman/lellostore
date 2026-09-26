# Step 11: owned WebSocket transport

The backend uses `simple_server::web::ws::{WebSocketUpgrade, WebSocket, Message}`
at reviewed shared revision `46c724315a3ed35e35cb086b2328bb4040cd0531`.
README and CI select that source; the sibling path dependency remains unchanged.
The isolated migration branch starts from clean `master`
`4ecc2d3291810aa4af842b7d9c3f25d49de8623d`.

Both production endpoints adopt owned upgrade/socket/frame contracts:

- `/api/events` retains OIDC authentication, catalog-change JSON, subscription
  before upgrade, lag resynchronization, Ping/Pong, shutdown close and pending
  upgrade/connection guards.
- `/api/paravoid/v1/events` retains `paravoid.updates.v1` negotiation, 4096-byte
  frame/message limits, a 15-second initial subscription deadline, strict
  subscription parsing, installed-shell grant authorization, repeated scope and
  revocation checks, initial/reconnect hints, UUID event identifiers, periodic
  checks, Ping/Pong and bounded writes/close.

Only the transport type imports change. Application protocol, socket loops,
authentication, task admission/draining and shutdown ownership stay in LelloStore.
The reviewed shared wrapper forwards upgrade configuration and socket operations
to the same transport without exposing its types to these production handlers.

## Verification

- Baseline and final `cargo test --all-features --locked --offline`: 210 passed,
  eight existing ignores (six external Android/toolchain/upstream cases and two
  doctests). Real socket tests require execution outside the filesystem sandbox;
  an initial sandbox-only baseline hit a listener permission error and was rerun.
- Before changing production imports, enhanced the actual endpoint transport
  tests to assert catalog Ping/Pong and Paravoid subprotocol negotiation and
  Ping/Pong. Both passed on the compatibility transport and on the owned transport
  in the final suite. Existing tests exercise unauthorized catalog upgrades,
  publication JSON, socket shutdown/draining and rejected shutdown upgrades,
  reconnect event identities and incorrect subscription scope closure.
- `cargo fmt --check`, `git diff --check`, strict
  `cargo clippy --all-targets --all-features --locked --offline -- -D warnings`
  and `cargo build --all-features --locked --offline` pass.
- Builds use two jobs and reduced debug information. Embedded assets are an
  unchanged copy of the original ignored `frontend/dist`; frontend sources and
  Android/Docker code were unchanged and those separate suites were not run.

## Remaining boundaries

Production retains only the HTTP tracing compatibility adapter as its explicit
Axum boundary. Tests retain `axum-test`, its multipart/WebSocket helpers and the
explicit router adapter. Production WebSocket upgrades, sockets and messages
are no longer remaining Axum exposure.

Integration rebases the development branch onto the migration commit, verifies
unchanged tested tree and ancestry, and removes the owned temporary worktree and
branch after successful integration. Nothing is pushed or deployed.
