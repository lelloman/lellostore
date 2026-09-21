# Step 05: health

LelloStore serves unauthenticated `GET /health` even when OIDC initialization
fails. It is process liveness only and always returns HTTP 200 with
`{"status":"healthy"}`. It performs no database or identity-provider readiness
check. The route remains inside the existing CORS, tracing, and metrics layers,
and its GET/HEAD/405 behavior and authentication exemption are unchanged.

The route now mounts `simple_server::health::Probe::liveness()` through
`get_service`, while the application retains its exact JSON renderer. The
reviewed shared implementation is revision
`ed245d2d46e9d29aeee7be5202f3a8b8113c9caf`.

## Verification

The focused production-router health test passes before and after the change
with offline locked dependencies, two build jobs, incremental compilation
disabled, and debug information disabled. Existing integration and tracing
tests cover the JSON response, unauthenticated access, failed-OIDC availability,
GET and POST 405 behavior, CORS, tracing, and metrics. Formatting and whitespace
checks pass. Containers, Android clients, and deployment were not rerun.
