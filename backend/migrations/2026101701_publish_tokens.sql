-- Per-app publish tokens let unattended release pipelines upload one package.
-- Only the SHA-256 of the secret is stored; the secret is shown once at creation.
CREATE TABLE publish_tokens (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
    package_name TEXT NOT NULL CHECK(length(package_name) > 0),
    token_hash TEXT NOT NULL UNIQUE CHECK(length(token_hash) = 64),
    created_by TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at TEXT,
    last_used_at TEXT,
    revoked_at TEXT
);
CREATE INDEX idx_publish_tokens_package ON publish_tokens(package_name);

-- APK and VPK uploads by a publish token are always durable jobs; the job
-- records which token created it, and the worker enforces the token's package.
ALTER TABLE upload_jobs ADD COLUMN publish_token_id TEXT REFERENCES publish_tokens(id);
CREATE INDEX idx_upload_jobs_publish_token ON upload_jobs(publish_token_id, created_at);
