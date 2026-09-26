-- Release metadata and immutable identities outlive superseded artifact bytes.
ALTER TABLE app_versions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1));
ALTER TABLE app_versions ADD COLUMN artifact_removed INTEGER NOT NULL DEFAULT 0 CHECK(artifact_removed IN (0,1));
ALTER TABLE app_versions ADD COLUMN artifact_cleaned INTEGER NOT NULL DEFAULT 0 CHECK(artifact_cleaned IN (0,1));
ALTER TABLE vpk_releases ADD COLUMN archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1));
ALTER TABLE vpk_releases ADD COLUMN artifact_removed INTEGER NOT NULL DEFAULT 0 CHECK(artifact_removed IN (0,1));
ALTER TABLE vpk_releases ADD COLUMN artifact_cleaned INTEGER NOT NULL DEFAULT 0 CHECK(artifact_cleaned IN (0,1));
