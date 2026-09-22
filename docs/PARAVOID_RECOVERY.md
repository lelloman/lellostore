# Store backup and restore verification

Back up the database, complete artifact storage, runtime configuration and signing
secrets together. Restoring an older database can undo grant revocations, lose
publication identities and lower signed-head revisions below installed clients'
replay floors. A SQLite integrity check alone cannot detect that problem.

## Consistent snapshot

1. Remove the Store from public traffic, stop its single backend process and wait
   for shutdown to complete. Keep uploads, personalization and retention workers
   stopped throughout the snapshot. A database-only live backup is insufficient.
2. With the service stopped, use SQLite's backup API (or `.backup`) to write a new
   database snapshot. Do not copy a live main database file while ignoring its WAL.
   Copy the entire configured `STORAGE_PATH` into the same protected backup set,
   including upload inputs and personalized working copies needed for retries.
3. Preserve runtime configuration and every active/retained online signing key in
   the secret manager's backup mechanism. Record the matching secret/configuration
   version with this backup. The verification tool below does not read private keys.
   Keep absolute storage paths unchanged on restore: queued jobs contain absolute
   input paths. Review a path migration separately before restarting workers.
4. Verify the stopped source and backup against the same checkpoint:

```sh
umask 077
python3 scripts/verify-store-backup.py \
  --database /path/to/stopped/store.db --storage /path/to/stopped/storage \
  > /path/to/protected/checkpoint.json
python3 scripts/verify-store-backup.py \
  --database /path/to/backup/store.db --storage /path/to/backup/storage \
  --expected-state /path/to/protected/checkpoint.json
```

Keep the checkpoint independently with the authenticated backup catalog. It
contains a logical database digest, table counts and retained artifact count, not
database rows, credentials or private keys. Protect the backup itself: personalized
APKs and working files contain issued credentials. Restore owner-only permissions.

## Restore drill

Restore into an isolated destination with traffic and workers disabled. Never
replace the live database as part of a drill. Run the verifier on the restored
paths against the independently retained checkpoint before starting a backend.

The tool opens SQLite read-only, verifies integrity and foreign keys, hashes all
logical tables and schema definitions, and checks the exact size/SHA-256 of every
retained APK and VPK, including drafts and withdrawn releases. It rejects artifact
path traversal and symlinks. This catches a missing artifact, altered revocation,
missing identity or lowered stream revision even when SQLite remains valid.
Temporary acquisition copies, upload inputs, icons and signing keys are outside its
artifact check; preserve the whole storage/configuration/secret snapshot and check
those operationally. It does not verify APK developer signatures or VPK signatures
again, and is not a replacement for upload validation.

A matching checkpoint proves restoration of that snapshot, not that the snapshot
is the latest state clients have seen. If traffic continued after it was taken,
reconcile later publications, head revisions and revocations from a newer trusted
snapshot before reusing the endpoint. Do not erase replay floors or recreate grant
records to make a stale restore appear usable.

Before restoring traffic, verify retained signing-key availability, authenticated
catalog access, acquisition authorization, retained range downloads and grant
revocation in the isolated environment. Start only one backend worker. Delete the
drill environment through the normal protected-data disposal procedure.

## Automated evidence

`backend/tests/backup_recovery.rs` creates a fully migrated database with a retained
installer, publication identity, retired stream and revoked grant. It restores a
SQLite snapshot, verifies its checkpoint, then proves that clearing revocation,
lowering the stream revision or corrupting retained APK bytes fails verification.
This is an isolated recovery test; no production restore or live failover is claimed.

```sh
cargo test --manifest-path backend/Cargo.toml --test backup_recovery
```
