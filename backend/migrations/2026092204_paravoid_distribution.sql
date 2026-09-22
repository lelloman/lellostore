CREATE TABLE paravoid_contracts (
    package_name TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    installer_version INTEGER NOT NULL,
    channel TEXT NOT NULL CHECK(channel IN ('stable','beta')),
    authentication TEXT NOT NULL CHECK(authentication IN ('public','apkKey')),
    bootstrap TEXT NOT NULL CHECK(bootstrap IN ('embedded','empty')),
    base_url TEXT NOT NULL,
    trust_json TEXT NOT NULL,
    descriptor_json TEXT NOT NULL,
    verification_state TEXT NOT NULL CHECK(verification_state IN ('pending','verified')),
    validation_report TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY(package_name, contract_id),
    FOREIGN KEY(package_name,installer_version) REFERENCES app_versions(package_name,version_code)
);
CREATE TABLE vpk_releases (
    id TEXT PRIMARY KEY,
    package_name TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    release_id TEXT NOT NULL,
    payload_version INTEGER NOT NULL CHECK(payload_version > 0),
    archive_path TEXT NOT NULL,
    archive_size INTEGER NOT NULL CHECK(archive_size > 0),
    archive_sha256 TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    manifest_json TEXT NOT NULL,
    min_sdk INTEGER NOT NULL,
    max_sdk INTEGER NOT NULL,
    abis_json TEXT NOT NULL,
    signing_key_id TEXT NOT NULL,
    validation_state TEXT NOT NULL CHECK(validation_state IN ('inspected','verified')),
    validation_report TEXT NOT NULL,
    publication_state TEXT NOT NULL DEFAULT 'draft' CHECK(publication_state IN ('draft','published','withdrawn')),
    release_notes TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    published_at TEXT,
    UNIQUE(package_name,release_id),
    UNIQUE(package_name,payload_version),
    FOREIGN KEY(package_name,contract_id) REFERENCES paravoid_contracts(package_name,contract_id)
);
CREATE TABLE published_vpk_identities (
    package_name TEXT NOT NULL,
    payload_version INTEGER NOT NULL,
    release_id TEXT NOT NULL,
    archive_sha256 TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    PRIMARY KEY(package_name,payload_version),
    UNIQUE(package_name,release_id)
);
CREATE TABLE paravoid_streams (
    package_name TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','retired')),
    issued_at INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(package_name,contract_id),
    FOREIGN KEY(package_name,contract_id) REFERENCES paravoid_contracts(package_name,contract_id)
);
CREATE TABLE paravoid_heads (
    package_name TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    scope TEXT NOT NULL,
    envelope BLOB NOT NULL,
    sha256 TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY(package_name,contract_id,revision,scope)
);
CREATE TABLE paravoid_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    release_id TEXT,
    grant_id TEXT,
    action TEXT NOT NULL,
    actor_subject TEXT NOT NULL,
    revision INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE paravoid_grants (
    id TEXT PRIMARY KEY,
    key_id TEXT NOT NULL UNIQUE,
    credential_sha256 TEXT NOT NULL UNIQUE,
    package_name TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    installer_version INTEGER NOT NULL,
    user_subject TEXT NOT NULL,
    acquisition_id TEXT NOT NULL UNIQUE,
    issued_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL DEFAULT 0,
    revoked_at INTEGER,
    revoked_by TEXT,
    last_used_at INTEGER,
    request_count INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(package_name,contract_id) REFERENCES paravoid_contracts(package_name,contract_id)
);
CREATE INDEX idx_paravoid_grant_app ON paravoid_grants(package_name,issued_at);
CREATE INDEX idx_vpk_stream ON vpk_releases(package_name,contract_id,publication_state,payload_version);
ALTER TABLE upload_jobs ADD COLUMN input_cleaned_at INTEGER;
ALTER TABLE upload_jobs ADD COLUMN kind TEXT NOT NULL DEFAULT 'apk' CHECK(kind IN ('apk','vpk'));
ALTER TABLE upload_jobs ADD COLUMN package_name TEXT;
ALTER TABLE upload_jobs ADD COLUMN contract_id TEXT;
CREATE UNIQUE INDEX idx_contract_installer ON paravoid_contracts(package_name,installer_version);
CREATE TABLE personalization_jobs (
    id TEXT PRIMARY KEY,
    user_subject TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    package_name TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    purpose TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    grant_id TEXT NOT NULL UNIQUE,
    key_id TEXT NOT NULL UNIQUE,
    credential_sha256 TEXT NOT NULL,
    files_cleaned_at INTEGER,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    UNIQUE(user_subject,idempotency_key),
    FOREIGN KEY(package_name,contract_id) REFERENCES paravoid_contracts(package_name,contract_id)
);
