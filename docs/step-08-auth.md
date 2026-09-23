# Step 08/09 auth adoption

LelloStore `master` adopts `simple_server::auth` from reviewed revision
`0a629da7b5eb5aeeb0ed64aac2c5f96cd4d9717b`. Production protected API
routes still mount LelloStore's Axum middleware. The middleware evaluates a
shared `AsyncAccess` flow over request parts. Its verifier extracts the Bearer
credential with `HeaderCredential`, validates the JWT with LelloStore's OIDC/JWKS
validator, builds the user from configured role claims, and records the user in
the local registry before attaching the principal to the request. Admin
extractors evaluate a shared `Access` flow with an explicit `is_admin` check.

Compatibility settings accept only exact `Bearer ` and `bearer ` prefixes and
select the first Authorization header if repeated. Missing headers and malformed,
empty or invalid tokens retain the existing 401 JSON response. Valid non-admin
tokens retain 403 on admin routes; registry failures retain 500. `/health`, static
assets and signed Paravoid delivery stay outside the OIDC gate. When OIDC startup
fails, protected routes remain unavailable. The application still owns issuer,
audience, signature, expiry, role extraction, registry writes and error rendering.
Resource-specific acquisition, publication and delivery authorization remains in
application database transactions. The shared Tower `AuthLayer` is not mounted
because the existing middleware preserves LelloStore's `User` extractor contract.

Baseline on `master` `1a6611e`: 26 auth unit tests pass and the OIDC
expiry/audience test passes through axum-test's in-process transport with a real
mock OIDC socket. The first sandbox attempt could not bind the mock JWKS socket;
the unrestricted rerun passed. The new compatibility and registry-outage contract
also passed against baseline code through the default in-process transport.
The same new contract passed on baseline with `.http_transport()`, which sends
requests over an actual local HTTP listener.

Final checks: all backend targets passed, 186 tests passed and 4 ignored. This
includes the new real HTTP compatibility/registry-outage contract, authenticated
WebSocket coverage and real-process lifecycle tests. `cargo clippy --all-targets
--locked -- -D warnings`, targeted `rustfmt --check`, and `git diff --check` pass.
No external live OIDC provider, deployment or Android build was tested for this
auth migration. CI and the active README source pin now reference the reviewed
simple-server revision. The local `master` integration and temporary-worktree
cleanup are recorded in the migration commit history and shared tracker.
