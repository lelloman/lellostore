-- Optional derived DVPK patches between archived full VPKs of one shell contract.
-- A row is both the deduplicated generation job and, once ready, the verified
-- relationship. Only 'ready' rows may be advertised; 'retired' rows remain
-- downloadable during a grace period until their file is removed.
CREATE TABLE vpk_deltas (
    id TEXT PRIMARY KEY,
    package_name TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    base_vpk_id TEXT NOT NULL REFERENCES vpk_releases(id),
    target_vpk_id TEXT NOT NULL REFERENCES vpk_releases(id),
    base_payload_version INTEGER NOT NULL,
    base_archive_sha256 TEXT NOT NULL,
    base_archive_size INTEGER NOT NULL CHECK(base_archive_size > 0),
    target_archive_sha256 TEXT NOT NULL,
    target_archive_size INTEGER NOT NULL CHECK(target_archive_size > 0),
    algorithm TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','running','ready','skipped','failed','retired')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at INTEGER NOT NULL DEFAULT 0,
    claimed_at INTEGER,
    failure TEXT,
    patch_sha256 TEXT,
    patch_size INTEGER,
    patch_path TEXT,
    encoder_version TEXT,
    duration_ms INTEGER,
    verified_at INTEGER,
    retired_at INTEGER,
    file_removed INTEGER NOT NULL DEFAULT 0 CHECK(file_removed IN (0,1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(package_name, contract_id, base_archive_sha256, target_archive_sha256, algorithm),
    CHECK(state NOT IN ('ready','retired') OR (patch_sha256 IS NOT NULL AND patch_size > 0 AND patch_path IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE INDEX idx_vpk_deltas_queue ON vpk_deltas(state, next_attempt_at, created_at);
CREATE INDEX idx_vpk_deltas_target ON vpk_deltas(target_vpk_id, state);

-- Whether the current head epoch was computed with delta advertising enabled.
-- A mismatch advances the epoch, so toggling never re-signs an observed revision.
ALTER TABLE paravoid_streams ADD COLUMN dvpk_advertising INTEGER NOT NULL DEFAULT 0 CHECK(dvpk_advertising IN (0,1));
