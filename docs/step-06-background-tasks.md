# Step 06: background task ownership

Based on master `dbebdfa`; reviewed simple-server source
`4a6353f55b23dff173ec1968915c6e10312d5795`.

## Applicability and adoption

06a is adopted in `backend/src/api/events.rs`: the catalog event hub reserves
shared `WorkGuard`s before WebSocket upgrades and uses `WorkTracker` to close
admission permanently and drain accepted connections. This replaces the local
TaskTracker/admission mutex pair. Shutdown still rejects upgrades with 503,
sends close frames, and uses the existing Lifecycle shutdown budget.
Cancelling a drain does not discard reservations or reopen admission.

06b is N/A: the sole periodic maintenance job, the 60-second metrics refresh in
`backend/src/metrics.rs`, is a directly awaited Lifecycle service with skipped
missed ticks. There is no independent job queue, cron, overlap or resource-pool
scheduler to migrate. 06c is N/A: there are no background-job retry, runtime/queue
budget, circuit-breaker or pause policies. Authentication startup retries and
HTTP client timeouts remain client/startup concerns.

## Verification

- Baseline `cargo test --locked`: 136 passed, two existing ignored doctests.
- Final `cargo test --locked`: 137 passed, two existing ignored doctests.
- Includes ten real HTTP/authentication tests and three real process lifecycle
  tests (SIGINT/SIGTERM, listener failure, active upload graceful/deadline paths).
- Strengthened the real WebSocket shutdown case to cover multiple connections
  and an abruptly disconnected client; all remaining clients close and late
  upgrades return 503.
- Added interrupted-drain coverage retaining two pending upgrade reservations
  across cancellation and observing final completion through a cloned hub.
- Strict all-target Clippy (`-D warnings`), formatting and diff checks pass.
- Default backend features tested; frontend embedding is unaffected and its
  asset-dependent all-feature build is not claimed as verified here.

Commands used an isolated worktree, temporary test databases and loopback ports,
with `CARGO_TARGET_DIR=/tmp/step06-rollout-target` and debug info disabled.
