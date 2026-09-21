# Step 03c: HTTP tracing

Reviewed shared library: `adc1640bde4ac8f934ed454c8d6c5e264a6a2790`.
Migration starts from master `22881b3` in an isolated sibling worktree.

The backend production router replaces default tower-http TraceLayer with shared
`http_tracing::trace`, retaining its placement outside request metrics and inside
CORS. Health, API auth/fail-closed responses, admin endpoints and static fallback
are observed; outer CORS short-circuits and the separate metrics listener retain
their previous scope. No correlation IDs or subscriber changes are introduced.

Telemetry deliberately moves from tower DEBUG messages/raw URI span to the shared
INFO request span with safe method/route template, response-header event and
one terminal body event. Header time and total body time are distinct. Query
strings and concrete private paths are not logged automatically. Standard shared
cancellation, streaming error and upgrade semantics apply; body bytes, headers,
status, metrics, auth policy and application/domain logs remain unchanged.

Baseline backend `cargo test --locked` passed 133 tests and ignored two existing
doctests. Final passed 134, with the same ignores. The new actual production
router test covers health200, method405 and fail-closed503, safe route logging,
no private query/path data, no new correlation header and no duplicate tower
request events. Existing suite covers authenticated API, file/range responses,
real-process startup/signal/drain and logging configuration. Changed Rust files
pass rustfmt; all-target Clippy result is recorded in the central tracker.
No Android/frontend build, external OIDC, Docker deployment or APK tooling
qualification was performed. Unrelated active Android/backend edits are preserved
in the original checkout and are not part of the tested migration tree.

README and CI checkout pins are updated. Integrate by rebasing master onto the
migration commit in a clean linked worktree while preserving original edits,
verify ancestry/tree and restored WIP, then remove the temporary branch/worktree.
Final integration details live in the central migration tracker.
