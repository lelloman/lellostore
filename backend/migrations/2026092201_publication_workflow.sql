-- Existing catalog artifacts remain published. New API uploads explicitly create drafts.
ALTER TABLE apps ADD COLUMN distribution_mode TEXT NOT NULL DEFAULT 'normal'
    CHECK (distribution_mode IN ('normal', 'paravoid'));
ALTER TABLE apps ADD COLUMN publication_revision INTEGER NOT NULL DEFAULT 0;

ALTER TABLE app_versions ADD COLUMN publication_state TEXT NOT NULL DEFAULT 'published'
    CHECK (publication_state IN ('draft', 'published', 'withdrawn'));
ALTER TABLE app_versions ADD COLUMN distribution_mode TEXT NOT NULL DEFAULT 'normal'
    CHECK (distribution_mode IN ('normal', 'paravoid'));
ALTER TABLE app_versions ADD COLUMN release_notes TEXT NOT NULL DEFAULT '';
ALTER TABLE app_versions ADD COLUMN proposed_name TEXT;
ALTER TABLE app_versions ADD COLUMN proposed_description TEXT;
ALTER TABLE app_versions ADD COLUMN published_at TEXT;
UPDATE app_versions SET published_at = uploaded_at;

CREATE TABLE publication_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    actor_subject TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('publish', 'withdraw')),
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(package_name, revision)
);
CREATE INDEX idx_versions_publication ON app_versions(package_name, publication_state, version_code);

-- Retain published identities even after catalog deletion; versions must never be reused.
CREATE TABLE published_apk_identities (
    package_name TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    PRIMARY KEY(package_name, version_code)
);
INSERT INTO published_apk_identities SELECT package_name, version_code, sha256 FROM app_versions;

CREATE TRIGGER restore_app_publication_revision AFTER INSERT ON apps
BEGIN
    UPDATE apps SET publication_revision = COALESCE(
        (SELECT MAX(revision) FROM publication_events WHERE package_name = NEW.package_name), 0
    ) WHERE package_name = NEW.package_name;
END;

-- Catalog deletion/recreation must not revive a stale admin review revision.
CREATE TABLE app_publication_revisions (
    package_name TEXT PRIMARY KEY,
    revision INTEGER NOT NULL
);
INSERT INTO app_publication_revisions SELECT package_name, publication_revision FROM apps;
DROP TRIGGER restore_app_publication_revision;
CREATE TRIGGER restore_app_publication_revision AFTER INSERT ON apps
BEGIN
    UPDATE apps SET publication_revision = COALESCE(
        (SELECT revision FROM app_publication_revisions WHERE package_name = NEW.package_name), 0
    ) WHERE package_name = NEW.package_name;
END;
CREATE TRIGGER remember_app_publication_revision AFTER UPDATE OF publication_revision ON apps
BEGIN
    INSERT INTO app_publication_revisions(package_name, revision) VALUES (NEW.package_name, NEW.publication_revision)
    ON CONFLICT(package_name) DO UPDATE SET revision = MAX(revision, excluded.revision);
END;
CREATE TRIGGER invalidate_deleted_app_revision BEFORE DELETE ON apps
BEGIN
    INSERT INTO app_publication_revisions(package_name, revision) VALUES (OLD.package_name, OLD.publication_revision + 1)
    ON CONFLICT(package_name) DO UPDATE SET revision = MAX(revision, excluded.revision);
END;
