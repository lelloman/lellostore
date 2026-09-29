use lellostore_backend::notifications::{hash, model::*, now, Broker, Connection};
use serde_json::json;
use sqlx::sqlite::SqlitePoolOptions;
use std::sync::Arc;

async fn fixture(strategy: &str, ttl: Option<i64>) -> Arc<Broker> {
    let db = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    lellostore_backend::db::run_migrations(&db).await.unwrap();
    let manifest = json!({"types":[{"application":"app.test","name":"incident","levels":["warning","info"],"default":{"strategy":strategy,"ttl_seconds":ttl}}],"rules":[]});
    sqlx::query(
        "INSERT INTO notification_senders(id,name,applications,manifest) VALUES('test','Test',?,?)",
    )
    .bind(json!([{"package":"app.test","certificates":["a".repeat(64)]}]).to_string())
    .bind(manifest.to_string())
    .execute(&db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO notification_credentials(hash,sender_id) VALUES(?,'test')")
        .bind(hash("sender-secret"))
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO notification_devices VALUES('phone',?,'issuer','alice','com.lelloman.store',0,?)").bind(hash("device-secret")).bind(now()).execute(&db).await.unwrap();
    sqlx::query("INSERT INTO notification_subscriptions VALUES('sub','test','phone','app.test',?,'app.test/Receiver','install','generation','alice',?,1,0)").bind("a".repeat(64)).bind(now()+300).execute(&db).await.unwrap();
    Broker::new(db)
}
fn event(id: &str, revision: i64) -> Publication {
    Publication {
        event_id: id.into(),
        application: "app.test".into(),
        message_type: "incident".into(),
        target: Target {
            subject: "alice".into(),
            subscription_id: None,
        },
        level: "warning".into(),
        tags: vec!["server".into()],
        occurred_at: now(),
        expires_at: None,
        replacement_key: Some("host-down".into()),
        occurrence: 1,
        revision,
        payload: json!({"title":"Host unavailable"}),
    }
}

#[tokio::test]
async fn replay_is_durable_and_only_recipient_receipt_finishes_delivery() {
    let b = fixture("queue", None).await;
    let m = event("first", 1);
    let accepted = b.publish("test", &m).await.unwrap();
    assert_eq!(b.publish("test", &m).await.unwrap(), accepted);
    let restarted = Broker::new(b.db.clone());
    let pending = restarted.pending("phone").await.unwrap();
    assert_eq!(pending.len(), 1);
    let id = pending[0]["delivery_id"].as_str().unwrap();
    assert!(restarted.receipt("other-phone", id, None).await.is_err());
    restarted.receipt("phone", id, None).await.unwrap();
    assert!(restarted.pending("phone").await.unwrap().is_empty());
    restarted
        .receipt("phone", id, Some("posted"))
        .await
        .unwrap();
    let receipt = restarted.outcome("test", "first").await.unwrap();
    assert_eq!(receipt["deliveries"][0]["state"], "persisted");
    assert_eq!(receipt["deliveries"][0]["presentation"], "posted");
    let mut changed = m;
    changed.payload = json!({"different":true});
    assert_eq!(
        b.publish("test", &changed).await.unwrap_err().0.as_u16(),
        409
    );
}

#[tokio::test]
async fn device_credentials_do_not_authorize_sending_and_types_are_scoped() {
    let b = fixture("queue", None).await;
    assert!(b.sender("device-secret").await.is_err());
    assert!(b.device("sender-secret").await.is_err());
    assert_eq!(b.sender("sender-secret").await.unwrap(), "test");
    let mut m = event("one", 1);
    m.application = "another.app".into();
    assert!(b.publish("test", &m).await.is_err());
    m.application = "app.test".into();
    m.target.subject = "bob".into();
    assert!(b.publish("test", &m).await.is_err());
    sqlx::query("UPDATE notification_senders SET enabled=0 WHERE id='test'")
        .execute(&b.db)
        .await
        .unwrap();
    assert!(b.sender("sender-secret").await.is_err());
    assert!(b.publish("test", &event("two", 1)).await.is_err());
}

#[tokio::test]
async fn recovery_supersedes_firing_and_watermarks_survive_expiry() {
    let b = fixture("latest", None).await;
    b.publish("test", &event("firing", 1)).await.unwrap();
    let mut recovery = event("recovery", 2);
    recovery.level = "info".into();
    recovery.expires_at = Some(now() - 1);
    b.publish("test", &recovery).await.unwrap();
    assert!(b.pending("phone").await.unwrap().is_empty());
    b.publish("test", &event("late-firing", 1)).await.unwrap();
    assert!(b.pending("phone").await.unwrap().is_empty());
    assert_eq!(
        b.outcome("test", "late-firing").await.unwrap()["deliveries"][0]["state"],
        "superseded"
    );
    let mut new_occurrence = event("new-occurrence", 0);
    new_occurrence.occurrence = 2;
    b.publish("test", &new_occurrence).await.unwrap();
    assert_eq!(b.pending("phone").await.unwrap().len(), 1);
}

#[tokio::test]
async fn policy_precedence_is_ordered_and_existing_expiry_is_frozen() {
    let b = fixture("queue", Some(600)).await;
    let overrides = json!([
        {"application":"app.test","level":"warning","tags":["server"],"policy":{"strategy":"queue","ttl_seconds":60}},
        {"application":"app.test","policy":{"strategy":"queue","ttl_seconds":120}}
    ]);
    sqlx::query("UPDATE notification_senders SET overrides=? WHERE id='test'")
        .bind(overrides.to_string())
        .execute(&b.db)
        .await
        .unwrap();
    b.publish("test", &event("one", 1)).await.unwrap();
    let before = b.pending("phone").await.unwrap();
    assert_eq!(
        before[0]["expires_at"].as_i64().unwrap() - before[0]["accepted_at"].as_i64().unwrap(),
        60
    );
    sqlx::query("UPDATE notification_senders SET overrides='[]' WHERE id='test'")
        .execute(&b.db)
        .await
        .unwrap();
    assert_eq!(before, b.pending("phone").await.unwrap());
}

#[tokio::test]
async fn atomic_fanout_rejects_full_queue_without_losing_old_messages() {
    let b = fixture("queue", None).await;
    b.publish("test", &event("old", 1)).await.unwrap();
    sqlx::query("UPDATE notification_senders SET max_pending=2 WHERE id='test'")
        .execute(&b.db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO notification_subscriptions SELECT 'second',sender_id,device_id,package,certificate,component,'second-install',generation,subject,lease_until,confirmed,revoked FROM notification_subscriptions WHERE id='sub'").execute(&b.db).await.unwrap();
    assert_eq!(
        b.publish("test", &event("new", 2))
            .await
            .unwrap_err()
            .0
            .as_u16(),
        429
    );
    assert_eq!(b.pending("phone").await.unwrap().len(), 1);
    assert!(b.outcome("test", "new").await.is_err());
}

#[tokio::test]
async fn leases_and_confirmation_gate_replay_without_deleting_backlog() {
    let b = fixture("queue", None).await;
    b.publish("test", &event("one", 1)).await.unwrap();
    sqlx::query("UPDATE notification_subscriptions SET lease_until=0")
        .execute(&b.db)
        .await
        .unwrap();
    assert!(b.pending("phone").await.unwrap().is_empty());
    sqlx::query("UPDATE notification_subscriptions SET lease_until=?,confirmed=0")
        .bind(now() + 300)
        .execute(&b.db)
        .await
        .unwrap();
    assert!(b.pending("phone").await.unwrap().is_empty());
    sqlx::query("UPDATE notification_subscriptions SET confirmed=1")
        .execute(&b.db)
        .await
        .unwrap();
    assert_eq!(b.pending("phone").await.unwrap().len(), 1);
    sqlx::query("UPDATE notification_subscriptions SET revoked=1")
        .execute(&b.db)
        .await
        .unwrap();
    assert!(b.pending("phone").await.unwrap().is_empty());
}

#[tokio::test]
async fn online_only_never_queues_for_offline_devices() {
    let b = fixture("online_only", None).await;
    assert_eq!(
        b.publish("test", &event("offline", 1)).await.unwrap()["deliveries"][0]["state"],
        "offline"
    );
    b.connections.lock().await.insert(
        "phone".into(),
        Connection {
            epoch: "1".into(),
            wake: Default::default(),
        },
    );
    b.publish("test", &event("online", 2)).await.unwrap();
    assert_eq!(b.pending("phone").await.unwrap().len(), 1);
}

#[test]
fn manifest_rejects_cross_application_rules_and_invalid_policies() {
    let app = Application {
        package: "app.test".into(),
        certificates: vec!["a".repeat(64)],
    };
    let manifest:Manifest=serde_json::from_value(json!({"types":[{"application":"other","name":"incident","levels":["info"],"default":{"strategy":"queue","ttl_seconds":null}}]})).unwrap();
    assert!(manifest.validate(&[app]).is_err());
    assert!(Policy {
        strategy: Strategy::Queue,
        ttl_seconds: Some(0)
    }
    .validate()
    .is_err());
}

#[tokio::test]
async fn ephemeral_deliveries_cannot_cross_connection_epochs_or_server_restart() {
    let b = fixture("online_only", None).await;
    b.connections.lock().await.insert(
        "phone".into(),
        Connection {
            epoch: "old".into(),
            wake: Arc::new(tokio::sync::Notify::new()),
        },
    );
    b.publish("test", &event("ephemeral", 1)).await.unwrap();
    assert_eq!(b.pending("phone").await.unwrap().len(), 1);
    b.connections.lock().await.insert(
        "phone".into(),
        Connection {
            epoch: "new".into(),
            wake: Arc::new(tokio::sync::Notify::new()),
        },
    );
    assert!(b.pending("phone").await.unwrap().is_empty());
    let restarted = Broker::new(b.db.clone());
    assert!(restarted.pending("phone").await.unwrap().is_empty());
    restarted.maintenance().await.unwrap();
    assert_eq!(
        b.outcome("test", "ephemeral").await.unwrap()["deliveries"][0]["state"],
        "offline"
    );
}

#[tokio::test]
async fn shrinking_application_scope_blocks_stale_manifest_publication() {
    let b = fixture("queue", None).await;
    sqlx::query("UPDATE notification_senders SET applications='[]' WHERE id='test'")
        .execute(&b.db)
        .await
        .unwrap();
    assert!(b.publish("test", &event("removed-app", 1)).await.is_err());
}
