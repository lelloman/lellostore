CREATE TABLE acquisitions (
    id TEXT PRIMARY KEY,
    user_subject TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    package_name TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    purpose TEXT NOT NULL CHECK (purpose IN ('install', 'update', 'repair')),
    apk_path TEXT NOT NULL,
    size INTEGER NOT NULL CHECK (size > 0),
    sha256 TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    UNIQUE(user_subject, idempotency_key)
);
CREATE INDEX idx_acquisition_expiry ON acquisitions(expires_at);
CREATE INDEX idx_acquisition_artifact ON acquisitions(package_name, version_code);
