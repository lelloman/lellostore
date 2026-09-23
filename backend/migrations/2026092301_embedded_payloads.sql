ALTER TABLE paravoid_installers ADD COLUMN embedded_vpk_id TEXT REFERENCES vpk_releases(id);
