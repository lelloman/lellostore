# Step 03a: logging setup

The backend already installed a tracing subscriber and now uses the opt-in
`simple-server::logging::try_init` API through its production `logging::init`
adapter. The mock OIDC binary prints explicit console messages and does not
install a logger; no unused initializer was added there.

Reviewed library source and CI checkout:
`71755b5e15ada9b22484559146ebaf4d82c91255`. The sibling path dependency remains
in place. Remote CI requires publishing that library revision first; no push
or deployment is performed by this migration.

Preserved behavior: `.env` loads before logging; `RUST_LOG` uses the original
permissive `EnvFilter::from_default_env()` parser, retaining valid directives
and its default ERROR level. Output stays on stdout with targets and span
context. `NO_COLOR` disables ANSI only for a present, nonempty Unicode value.
The previously implicit log-facade bridge is explicitly installed with the
subscriber's current maximum level. Initialization errors propagate through
the existing fallible entry point.

Verification on 2026-09-20:

- Untouched `master` at `9a3f820` passes 131 tests with two existing ignored
  doctests. An earlier sandbox-restricted run failed to bind a test socket;
  the unrestricted full baseline passes. Baseline all-target Clippy is clean.
- Final backend suite: 133 passed, two existing ignored doctests. Includes
  authentication/API checks and all three real-process lifecycle tests
  (signals, listener failures, and active multipart request draining).
- Fresh-process comparisons call the production adapter and exact old
  initializer. They verify missing/empty/whitespace/invalid filters, `off`,
  target/span filters, ANSI, stdout/stderr, structured fields and log records.
- Strict all-target Clippy, formatting and diff checks pass.
- Android/frontend, Docker builds and browser suites were not rerun for this
  backend initializer change.

Implementation is isolated on `migration/step03a-logging`, based on `master`.
Integration rebases `master` onto the tested commit, then removes the temporary
branch/worktree. Unrelated Android and documentation work is preserved and
verified by file hashes. Only module 03a is adopted; request correlation and
HTTP tracing remain separate optional modules.

Rollback: revert the migration commit, including the dependency and CI pin.
