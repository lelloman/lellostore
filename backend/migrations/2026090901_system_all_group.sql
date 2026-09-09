ALTER TABLE app_groups
ADD COLUMN system_kind TEXT
    CHECK (system_kind IS NULL OR system_kind = 'all');

INSERT INTO app_groups (name, system_kind)
VALUES ('all', 'all')
ON CONFLICT(name) DO UPDATE SET
    name = excluded.name,
    system_kind = excluded.system_kind,
    updated_at = datetime('now');

DELETE FROM app_group_grants
WHERE group_id IN (SELECT id FROM app_groups WHERE system_kind = 'all');
