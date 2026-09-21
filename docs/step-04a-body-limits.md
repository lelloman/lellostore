# Step 04a: extractor body limits

Backend configured multipart request ceiling is unchanged, including its existing overhead calculation and route placement. Per-file/APK validation remains local.

Uses optional simple-server body-limit feature at reviewed revision
`0b945750b6b97a9e18c531d1cf1137d4ed4b69c9`. Raw readers and custom domain limits
are not replaced. Shared differential tests qualify JSON/bytes/multipart
rejections, unknown lengths, route overrides and lazy response pass-through.

## Verification

Baseline and final: 134 passed, two ignored doctests. Existing API, upload/file, process, lifecycle and logging tests pass.

Primary command: `cargo test --locked` in the affected package/workspace. Runs use
two jobs, `/tmp/lellostore-03c-target` and dev/test debug=0. Lockfiles add only the
shared library's tower-layer dependency. Strict all-target Clippy passes. Changed-file rustfmt and
whitespace checks pass; repository-wide formatting is not claimed.

Android/frontend/container and external-provider checks were not repeated. Original Android/backend working edits are excluded from the tested migration tree and preserved.

## Integration

Started from active master `d2276292`
in a dedicated `migration/step04a-body-limits` branch and sibling worktree.
Commit there, rebase the original development branch onto the migration, verify
ancestry/tested tree and preserve unrelated work before removing the temporary
worktree/branch. Exact integration revision and any concurrent changes are
recorded in the central simple-server trackers. No push or deployment.
