-- Device tokens let provisioned devices (for example a TV box) read the
-- catalog without an interactive login. They authenticate as the non-admin
-- principal 'device-token:<id>', whose access comes from ordinary grants and
-- group memberships. Only the SHA-256 of the secret is stored.
CREATE TABLE device_tokens (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
    token_hash TEXT NOT NULL UNIQUE CHECK(length(token_hash) = 64),
    created_by TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at TEXT,
    last_used_at TEXT,
    revoked_at TEXT
);
