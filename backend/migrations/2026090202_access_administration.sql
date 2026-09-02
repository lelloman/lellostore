CREATE TABLE known_users (
    subject TEXT PRIMARY KEY,
    email TEXT,
    first_seen_at TEXT NOT NULL DEFAULT (datetime('now')),
    last_seen_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE access_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_subject TEXT NOT NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    details_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_access_audit_log_created_at
    ON access_audit_log(created_at DESC);
CREATE INDEX idx_access_audit_log_target
    ON access_audit_log(target_type, target_id);
