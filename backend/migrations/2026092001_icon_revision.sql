-- Re-extract existing icons once with adaptive launcher artwork preferred.
ALTER TABLE apps ADD COLUMN icon_revision INTEGER NOT NULL DEFAULT 0;
