ALTER TABLE app_versions
ADD COLUMN is_beta INTEGER NOT NULL DEFAULT 0 CHECK (is_beta IN (0, 1));

CREATE TABLE user_app_grants (
    user_subject TEXT NOT NULL,
    package_name TEXT NOT NULL REFERENCES apps(package_name) ON DELETE CASCADE,
    access_level TEXT NOT NULL CHECK (access_level IN ('stable', 'beta')),
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_subject, package_name)
);

CREATE TABLE app_groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL COLLATE NOCASE UNIQUE
        CHECK (length(trim(name)) BETWEEN 1 AND 100),
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE app_group_grants (
    group_id INTEGER NOT NULL REFERENCES app_groups(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL REFERENCES apps(package_name) ON DELETE CASCADE,
    access_level TEXT NOT NULL CHECK (access_level IN ('stable', 'beta')),
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (group_id, package_name)
);

CREATE TABLE user_app_group_memberships (
    user_subject TEXT NOT NULL,
    group_id INTEGER NOT NULL REFERENCES app_groups(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_subject, group_id)
);

CREATE INDEX idx_user_app_grants_subject
    ON user_app_grants(user_subject);
CREATE INDEX idx_app_group_grants_package
    ON app_group_grants(package_name);
CREATE INDEX idx_user_app_group_memberships_group
    ON user_app_group_memberships(group_id);
