-- Deliberate protocol reset: old sender credentials cannot authorize Web Push.
DROP TABLE notification_watermarks;
DROP TABLE notification_deliveries;
DROP TABLE notification_events;
DROP TABLE notification_enrollments;
DROP TABLE notification_subscriptions;
DROP TABLE notification_devices;
DROP TABLE notification_rotations;
DROP TABLE notification_invitations;
DROP TABLE notification_credentials;
DROP TABLE notification_senders;
CREATE TABLE push_senders (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
 max_pending INTEGER NOT NULL DEFAULT 100000 CHECK(max_pending>0),
 max_bytes INTEGER NOT NULL DEFAULT 268435456 CHECK(max_bytes>0),
 rate REAL NOT NULL DEFAULT 10 CHECK(rate>0), burst INTEGER NOT NULL DEFAULT 100 CHECK(burst>0),
 tokens REAL NOT NULL DEFAULT 100, refilled_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE push_keys (
 key TEXT PRIMARY KEY, sender_id TEXT NOT NULL REFERENCES push_senders(id), revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE push_devices (
 id TEXT PRIMARY KEY, credential_hash TEXT NOT NULL UNIQUE, issuer TEXT NOT NULL, subject TEXT NOT NULL,
 package TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
);
CREATE TABLE push_subscriptions (
 id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES push_devices(id), token TEXT NOT NULL,
 package TEXT NOT NULL, vapid TEXT NOT NULL REFERENCES push_keys(key), endpoint_hash TEXT NOT NULL UNIQUE,
 revoked INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX push_active_token ON push_subscriptions(device_id,token) WHERE revoked=0;
CREATE TABLE push_messages (
 id TEXT PRIMARY KEY, subscription_id TEXT NOT NULL REFERENCES push_subscriptions(id),
 payload BLOB NOT NULL, topic TEXT, urgency TEXT NOT NULL, accepted_at INTEGER NOT NULL,
 expires_at INTEGER NOT NULL, connection_epoch TEXT, state TEXT NOT NULL DEFAULT 'pending'
);
CREATE INDEX push_pending ON push_messages(subscription_id,state,accepted_at);
CREATE INDEX push_expiry ON push_messages(state,expires_at);
