CREATE TABLE notification_senders (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, applications TEXT NOT NULL,
 manifest TEXT NOT NULL DEFAULT '{"types":[],"rules":[]}', overrides TEXT NOT NULL DEFAULT '[]',
 enabled INTEGER NOT NULL DEFAULT 1, policy_version INTEGER NOT NULL DEFAULT 1,
 max_pending INTEGER NOT NULL DEFAULT 100000 CHECK(max_pending > 0),
 max_bytes INTEGER NOT NULL DEFAULT 268435456 CHECK(max_bytes > 0),
 rate REAL NOT NULL DEFAULT 10 CHECK(rate > 0), burst INTEGER NOT NULL DEFAULT 100 CHECK(burst > 0),
 tokens REAL NOT NULL DEFAULT 100, refilled_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE notification_credentials (
 hash TEXT PRIMARY KEY, sender_id TEXT NOT NULL REFERENCES notification_senders(id),
 expires_at INTEGER, revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE notification_invitations (
 hash TEXT PRIMARY KEY, name TEXT NOT NULL, applications TEXT NOT NULL, expires_at INTEGER NOT NULL,
 registration_id TEXT, credential_hash TEXT, sender_id TEXT REFERENCES notification_senders(id)
);
CREATE TABLE notification_rotations (
 sender_id TEXT NOT NULL, request_id TEXT NOT NULL, credential_hash TEXT NOT NULL,
 PRIMARY KEY(sender_id, request_id)
);
CREATE TABLE notification_devices (
 id TEXT PRIMARY KEY, credential_hash TEXT NOT NULL UNIQUE, issuer TEXT NOT NULL, subject TEXT NOT NULL,
 package TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
);
CREATE TABLE notification_enrollments (
 proof_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES notification_devices(id),
 package TEXT NOT NULL, certificate TEXT NOT NULL, component TEXT NOT NULL,
 installation TEXT NOT NULL, generation TEXT NOT NULL, expires_at INTEGER NOT NULL,
 subscription_id TEXT
);
CREATE TABLE notification_subscriptions (
 id TEXT PRIMARY KEY, sender_id TEXT NOT NULL REFERENCES notification_senders(id),
 device_id TEXT NOT NULL REFERENCES notification_devices(id), package TEXT NOT NULL,
 certificate TEXT NOT NULL, component TEXT NOT NULL, installation TEXT NOT NULL, generation TEXT NOT NULL,
 subject TEXT NOT NULL, lease_until INTEGER NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0,
 revoked INTEGER NOT NULL DEFAULT 0,
 UNIQUE(device_id, package, installation, generation, sender_id)
);
CREATE TABLE notification_events (
 sender_id TEXT NOT NULL REFERENCES notification_senders(id), event_id TEXT NOT NULL,
 request_hash TEXT NOT NULL, accepted_at INTEGER NOT NULL, terminal_at INTEGER,
 PRIMARY KEY(sender_id, event_id)
);
CREATE TABLE notification_deliveries (
 id TEXT PRIMARY KEY, sender_id TEXT NOT NULL, event_id TEXT NOT NULL,
 subscription_id TEXT NOT NULL REFERENCES notification_subscriptions(id),
 type TEXT NOT NULL, replacement_key TEXT, occurrence INTEGER NOT NULL, revision INTEGER NOT NULL,
 envelope TEXT NOT NULL, bytes INTEGER NOT NULL, expires_at INTEGER, online_only INTEGER NOT NULL, connection_epoch TEXT,
 state TEXT NOT NULL DEFAULT 'pending', presentation TEXT, accepted_at INTEGER NOT NULL,
 FOREIGN KEY(sender_id,event_id) REFERENCES notification_events(sender_id,event_id)
);
CREATE INDEX notification_pending ON notification_deliveries(subscription_id,state,accepted_at);
CREATE INDEX notification_sender_pending ON notification_deliveries(sender_id,state);
CREATE TABLE notification_watermarks (
 subscription_id TEXT NOT NULL REFERENCES notification_subscriptions(id), type TEXT NOT NULL,
 replacement_key TEXT NOT NULL, occurrence INTEGER NOT NULL, revision INTEGER NOT NULL, expires_at INTEGER,
 PRIMARY KEY(subscription_id,type,replacement_key)
);
CREATE TABLE notification_audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, entity TEXT NOT NULL
);
INSERT INTO notification_senders(id,name,applications,manifest) VALUES(
 'lellostore','LelloStore','[]',
 '{"types":[{"application":"*","name":"catalog.changed","levels":["info"],"default":{"strategy":"latest","ttl_seconds":86400}}],"rules":[]}'
);
