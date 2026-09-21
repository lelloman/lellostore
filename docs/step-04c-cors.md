# Step 04c: explicit CORS configuration

The production backend router now builds its existing CORS policy through
`simple_server::cors::CorsConfig` from reviewed revision
`3aa933295860a9ed08b51ae882c8297f17e7eac2`.

Behavior and layer placement are unchanged. The outer CORS layer allows any
origin and request header, allows GET, POST, PUT and DELETE, and does not enable
credentials, exposed response headers or a preflight max-age. It remains outside
HTTP tracing and request metrics, so preflights still bypass those inner layers.
The policy is static and validated when the production router is built.

## Verification

Baseline `cargo test --locked -j 2` passed 135 tests and ignored two doctests.
The same production-router CORS test passed against both the legacy and shared
implementations. It covers an ordinary response, wildcard origin and request
headers, the exact method set, absent credentials, exposed headers and max-age,
OPTIONS short-circuiting on a missing route, and a fail-closed 503 response.
The final all-features suite passed 136 tests with two ignored doctests after
providing the ignored frontend build output required by that feature. Formatting
and strict all-target, all-feature Clippy passed.

The migration started from active `master` at `fed9e9b20f8e8f53b6a335f7575b8e6cab838150`
in a dedicated branch and sibling worktree. No push or deployment is part of the
migration.
