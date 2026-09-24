# Step 10: rate limiting and admission

Status: **N/A** for LelloStore's backend request handling. Assessment based on
`master` at `e2f4cd5c5e502233e0918b2f5301d4ebc5bb916f` and the reviewed
`simple-server` revision `1d4fd30a20c789dc2797dba08432cd837860749c`.

The production `backend/src/main.rs` serves the application router and a
separate metrics router. `backend/src/api/routes.rs` attaches metrics, HTTP
tracing, CORS, authentication and upload body-size layers, but no rate or
concurrency admission layer. `backend/src/config.rs` has no request quota or
concurrency settings. `backend/Cargo.toml` enables no `rate-limit` feature and
declares no limiter dependency. The handlers and `backend/src/error.rs` define
no quota response or HTTP 429 policy. No production entry point uses Step 10's
budget, policy, or HTTP adapter APIs; adding one would introduce a new admission
policy rather than migrate an existing one.

`SPEC.md` describes JWKS refreshes as rate-limited. The implementation in
`backend/src/auth/jwks.rs` serializes refreshes and applies a 30-second cooldown
to *outbound JWKS fetches triggered by unknown key IDs*. During that cooldown,
token validation returns `KeyNotFound` without fetching keys; it does not
reject otherwise valid inbound HTTP requests based on a request quota. This
authentication cache safeguard remains local and is outside the Step 10 HTTP
admission scope.

Verification: inspected production startup, both routers, configuration,
dependency declarations, handlers, response mapping and the JWKS cache; searched
backend source/tests for limiter, quota, throttle, HTTP 429 and `Retry-After`
usage. Baseline and final `git diff --check` passed. This documentation-only
assessment changes no executable code or dependencies, so no build or runtime
test was needed.
