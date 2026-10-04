# SQLite schema applicability and backend dependency cleanup

Assessed 2026-10-04 on active `master`, starting at `061a7cc`.

Step 07e is N/A for the current schema description, creation and validation
capability. LelloStore uses SQLite through SQLx, but all production table and
index creation is owned by the authored, versioned files in `backend/migrations`.
`backend/src/db/mod.rs::run_migrations` already inspects the SQLx ledger through
simple-server's migration preflight before invoking the SQLx migrator. There is
no separate unversioned creation path or structural comparison adapter to
replace. SQLx owns its internal ledger; replacing its migrator would change
migration ownership, checksums and execution semantics. No new schema-validation
policy is introduced simply to enable a feature.

No backend source uses Tower HTTP. Its unused fs dependency is removed, including
Tower HTTP 0.5 and http-range-header from the lockfile. Direct Tower 0.4 is needed
only by `backend/tests/http_tracing.rs`, so it moves to dev-dependencies.
Embedded frontend serving and application-specific APK range/stream responses
already use simple-server's owned HTTP types; their behavior is unchanged.

The existing public simple-server pin remains 0.1.0, registry checksum
`1f3187c81c94701cd041df7ab17ce968b73c967db77c551170e3c24960b6c77a`, published
source `c955061` (the shared library is reviewed at `64795a6`). No new shared API
is required. Internal Axum/Tower/Tower HTTP dependencies of simple-server and
Reqwest remain transitive implementation details; SQLx remains the database
execution driver.

Verification uses an isolated build directory and a copy of the original
checkout's existing frontend/dist assets for the embed-frontend feature.

Baseline all-feature backend tests: 227 passed, zero failed, nine existing
ignores. Final full rerun: the same 227 passed and nine ignored. An initial final
run failed the existing fake-aapt2 50 ms timeout assertion; its isolated rerun
and subsequent full rerun passed. All-feature locked check, strict all-target/
all-feature Clippy, formatting and diff checks pass. No tests were added for
this dependency-only cleanup. Frontend was not rebuilt; Android interoperability
and the environment-dependent ignored tests were not run.
