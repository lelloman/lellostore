CREATE TABLE upload_jobs (
    id TEXT PRIMARY KEY,
    actor_subject TEXT NOT NULL,
    file_name TEXT NOT NULL,
    input_path TEXT NOT NULL,
    override_name TEXT,
    override_description TEXT,
    is_beta INTEGER NOT NULL CHECK (is_beta IN (0, 1)),
    status TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'validating', 'ready', 'failed')),
    result_json TEXT,
    error TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_upload_jobs_status ON upload_jobs(status, created_at);
