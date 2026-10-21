-- Store-native device-code enrollment (docs/CREDENTIAL_PROVIDER_DESIGN.md
-- section 7.2). A device without a human login starts an enrollment and shows
-- its user_code; an administrator approves it and the device's next poll with
-- the secret device_code receives a device token once.
--
-- No device token secret is ever stored here: approval creates (or rekeys)
-- the device_tokens row with an unusable placeholder hash, and the redeeming
-- poll generates the secret and stores only its SHA-256 on that row.
CREATE TABLE device_enrollments (
    id TEXT PRIMARY KEY,
    device_code_hash TEXT NOT NULL UNIQUE CHECK(length(device_code_hash) = 64),
    -- Normalized 8 characters without the display dash, for example WDJBMJHT.
    user_code TEXT NOT NULL UNIQUE CHECK(length(user_code) = 8),
    requested_name TEXT NOT NULL CHECK(length(trim(requested_name)) > 0),
    model TEXT,
    android_sdk INTEGER,
    client_version TEXT,
    source_ip TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    -- Pending: 10 minutes after creation. Approved: 10 minutes after approval.
    expires_at TEXT NOT NULL,
    last_polled_at TEXT,
    state TEXT NOT NULL DEFAULT 'pending'
        CHECK(state IN ('pending', 'approved', 'denied', 'redeemed', 'expired')),
    approved_by TEXT,
    decided_at TEXT,
    redeemed_at TEXT,
    -- Set on approval: the new token, or the existing token that was rekeyed.
    device_token_id TEXT,
    rekeyed INTEGER NOT NULL DEFAULT 0 CHECK(rekeyed IN (0, 1)),
    -- A denial refuses new enrollments from source_ip for 24 hours when set.
    blocks_source INTEGER NOT NULL DEFAULT 0 CHECK(blocks_source IN (0, 1))
);

CREATE INDEX idx_device_enrollments_source ON device_enrollments(source_ip, created_at);
CREATE INDEX idx_device_enrollments_state ON device_enrollments(state, expires_at);
