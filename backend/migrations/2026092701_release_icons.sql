ALTER TABLE app_versions ADD COLUMN proposed_icon_path TEXT;

-- Refresh listings published before draft icons were carried into publication.
UPDATE apps SET icon_revision = 0
WHERE EXISTS (SELECT 1 FROM app_versions
              WHERE app_versions.package_name = apps.package_name
                AND publication_state = 'published' AND artifact_removed = 0);
