CREATE TABLE distribution_reviews (
    id TEXT PRIMARY KEY,
    package_name TEXT NOT NULL,
    from_mode TEXT NOT NULL,
    to_mode TEXT NOT NULL,
    from_version INTEGER NOT NULL,
    target_version INTEGER NOT NULL,
    target_sha256 TEXT NOT NULL,
    signer_sha256 TEXT NOT NULL,
    review_revision INTEGER NOT NULL,
    migration_evidence TEXT NOT NULL,
    actor_subject TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_distribution_reviews_app ON distribution_reviews(package_name,review_revision);
