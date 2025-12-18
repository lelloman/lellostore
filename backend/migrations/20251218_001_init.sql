-- Apps table
CREATE TABLE IF NOT EXISTS apps (
    package_name TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    icon_path TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- App versions table
CREATE TABLE IF NOT EXISTS app_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL REFERENCES apps(package_name) ON DELETE CASCADE,
    version_code INTEGER NOT NULL,
    version_name TEXT NOT NULL,
    apk_path TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    min_sdk INTEGER NOT NULL,
    uploaded_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(package_name, version_code)
);

-- Index for faster queries
CREATE INDEX IF NOT EXISTS idx_app_versions_package ON app_versions(package_name);
