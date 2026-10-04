use lellostore_backend::notifications::{hash, model::*, now, Broker, Connection};
use simple_server::web::http::{HeaderMap, HeaderValue, StatusCode};
use sqlx::sqlite::SqlitePoolOptions;
use std::sync::Arc;
#[path = "support/push.rs"]
mod push;
const ORIGIN: &str = "https://push.example";
#[tokio::test]
async fn populated_legacy_push_database_can_be_reset() {
    let db = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::raw_sql(include_str!("../migrations/2026092901_notifications.sql"))
        .execute(&db)
        .await
        .unwrap();
    sqlx::raw_sql(r#"
      INSERT INTO notification_credentials VALUES('credential','lellostore',NULL,0);
      INSERT INTO notification_devices VALUES('device','hash','issuer','subject','store',0,1);
      INSERT INTO notification_subscriptions VALUES('sub','lellostore','device','app','cert','component','install','generation','subject',999,1,0);
      INSERT INTO notification_events VALUES('lellostore','event','hash',1,NULL);
      INSERT INTO notification_deliveries VALUES('delivery','lellostore','event','sub','type',NULL,1,1,'{}',2,999,0,NULL,'pending',NULL,1);
      INSERT INTO notification_watermarks VALUES('sub','type','key',1,1,999);
      INSERT INTO notification_audit(at,actor,action,entity) VALUES(1,'admin','approve','lellostore');
    "#).execute(&db).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/2026100401_unifiedpush.sql"))
        .execute(&db)
        .await
        .unwrap();
    let old: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sqlite_master WHERE name='notification_credentials'",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(old, 0);
    let new: i64 = sqlx::query_scalar("SELECT count(*) FROM push_subscriptions")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(new, 0);
    let audit: i64 = sqlx::query_scalar("SELECT count(*) FROM notification_audit")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(audit, 1);
}
async fn fixture() -> (Arc<Broker>, push::Sender, Subscription, String) {
    let db = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    lellostore_backend::db::run_migrations(&db).await.unwrap();
    let sender = push::Sender::new();
    sqlx::query("INSERT INTO push_senders(id,name) VALUES('test','Test')")
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO push_keys(key,sender_id) VALUES(?,'test')")
        .bind(&sender.key)
        .execute(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO push_devices VALUES('phone',?,'issuer','alice','com.lelloman.store',0,?)",
    )
    .bind(hash("device-secret"))
    .bind(now())
    .execute(&db)
    .await
    .unwrap();
    let broker = Broker::new(db);
    let sub = Subscription {
        token: "token-one".into(),
        package: "app.test".into(),
        vapid: sender.key.clone(),
        endpoint_secret: "a".repeat(64),
    };
    let id = broker.subscribe("phone", &sub).await.unwrap();
    (broker, sender, sub, id)
}
fn message(ttl: i64, topic: Option<&str>) -> Publication {
    Publication {
        payload: vec![0, 255, 10, 0, 20],
        ttl,
        topic: topic.map(str::to_owned),
        urgency: "normal".into(),
    }
}
#[tokio::test]
async fn registration_is_idempotent_scoped_and_revocable() {
    let (b, sender, mut sub, id) = fixture().await;
    assert_eq!(b.subscribe("phone", &sub).await.unwrap(), id);
    sub.package = "other.app".into();
    assert_eq!(
        b.subscribe("phone", &sub).await.unwrap_err().0,
        StatusCode::FORBIDDEN
    );
    sub.package = "app.test".into();
    sub.token = "token-two".into();
    sub.endpoint_secret = "b".repeat(64);
    assert_ne!(b.subscribe("phone", &sub).await.unwrap(), id);
    sub.vapid = push::Sender::new().key;
    assert!(b.subscribe("phone", &sub).await.is_err());
    b.revoke("phone", Some(&id)).await.unwrap();
    assert_eq!(
        b.publish(
            &"a".repeat(64),
            Some(&sender.authorization(ORIGIN, now() + 100)),
            ORIGIN,
            &message(60, None)
        )
        .await
        .unwrap_err()
        .0,
        StatusCode::GONE
    );
}
#[tokio::test]
async fn encrypted_bytes_survive_restart_and_only_matching_ack_finishes_delivery() {
    let (b, sender, sub, _) = fixture().await;
    let id = b
        .publish(
            &sub.endpoint_secret,
            Some(&sender.authorization(ORIGIN, now() + 100)),
            ORIGIN,
            &message(60, None),
        )
        .await
        .unwrap();
    let restarted = Broker::new(b.db.clone());
    let pending = restarted.pending("phone", "epoch").await.unwrap();
    use base64::Engine;
    assert_eq!(
        base64::engine::general_purpose::STANDARD
            .decode(pending[0]["payload"].as_str().unwrap())
            .unwrap(),
        message(60, None).payload
    );
    restarted
        .receipt("other-phone", &id, &sub.token)
        .await
        .unwrap();
    restarted
        .receipt("phone", &id, "wrong-token")
        .await
        .unwrap();
    assert_eq!(restarted.pending("phone", "epoch").await.unwrap().len(), 1);
    restarted.receipt("phone", &id, &sub.token).await.unwrap();
    assert!(restarted
        .pending("phone", "epoch")
        .await
        .unwrap()
        .is_empty());
    restarted.receipt("phone", &id, &sub.token).await.unwrap();
}
#[tokio::test]
async fn vapid_rejects_unapproved_mismatched_expired_and_wrong_audience() {
    let (b, sender, sub, _) = fixture().await;
    for auth in [
        None,
        Some(push::Sender::new().authorization(ORIGIN, now() + 100)),
        Some(sender.authorization("https://wrong.example", now() + 100)),
        Some(sender.authorization(ORIGIN, now() - 1)),
        Some(sender.authorization(ORIGIN, now() + 86401)),
    ] {
        assert!(b
            .publish(
                &sub.endpoint_secret,
                auth.as_deref(),
                ORIGIN,
                &message(60, None)
            )
            .await
            .is_err());
    }
    let mut auth = sender.authorization(ORIGIN, now() + 100);
    auth = auth.replacen("vapid t=", "vapid t=x", 1);
    assert!(b
        .publish(
            &sub.endpoint_secret,
            Some(&auth),
            ORIGIN,
            &message(60, None)
        )
        .await
        .is_err());
    sqlx::query("UPDATE push_senders SET enabled=0")
        .execute(&b.db)
        .await
        .unwrap();
    assert!(b
        .publish(
            &sub.endpoint_secret,
            Some(&sender.authorization(ORIGIN, now() + 100)),
            ORIGIN,
            &message(60, None)
        )
        .await
        .is_err());
}
#[tokio::test]
async fn topic_replacement_is_atomic_with_quota_checks_and_expiry() {
    let (b, sender, sub, _) = fixture().await;
    let auth = sender.authorization(ORIGIN, now() + 100);
    let original = b
        .publish(
            &sub.endpoint_secret,
            Some(&auth),
            ORIGIN,
            &message(60, Some("topic")),
        )
        .await
        .unwrap();
    sqlx::query("UPDATE push_senders SET max_bytes=5")
        .execute(&b.db)
        .await
        .unwrap();
    let mut large = message(60, Some("topic"));
    large.payload = vec![1; 6];
    assert_eq!(
        b.publish(&sub.endpoint_secret, Some(&auth), ORIGIN, &large)
            .await
            .unwrap_err()
            .0,
        StatusCode::TOO_MANY_REQUESTS
    );
    assert_eq!(
        b.pending("phone", "epoch").await.unwrap()[0]["id"],
        original
    );
    let replacement = b
        .publish(
            &sub.endpoint_secret,
            Some(&auth),
            ORIGIN,
            &message(60, Some("topic")),
        )
        .await
        .unwrap();
    b.receipt("phone", &original, &sub.token).await.unwrap();
    assert_eq!(
        b.pending("phone", "epoch").await.unwrap()[0]["id"],
        replacement
    );
    sqlx::query("UPDATE push_messages SET expires_at=0")
        .execute(&b.db)
        .await
        .unwrap();
    b.maintenance().await.unwrap();
    assert!(b.pending("phone", "epoch").await.unwrap().is_empty());
}
#[tokio::test]
async fn zero_ttl_is_never_replayed_on_a_new_connection_or_without_valid_session() {
    let (b, sender, sub, _) = fixture().await;
    let auth = sender.authorization(ORIGIN, now() + 100);
    b.publish(&sub.endpoint_secret, Some(&auth), ORIGIN, &message(0, None))
        .await
        .unwrap();
    b.connections.lock().await.insert(
        "phone".into(),
        Connection {
            epoch: "old".into(),
            wake: Arc::new(tokio::sync::Notify::new()),
            expires: now() + 100,
        },
    );
    assert!(b.pending("phone", "old").await.unwrap().is_empty());
    b.publish(&sub.endpoint_secret, Some(&auth), ORIGIN, &message(0, None))
        .await
        .unwrap();
    assert_eq!(b.pending("phone", "old").await.unwrap().len(), 1);
    assert!(b.pending("phone", "new").await.unwrap().is_empty());
    b.connections.lock().await.get_mut("phone").unwrap().expires = 0;
    b.publish(&sub.endpoint_secret, Some(&auth), ORIGIN, &message(0, None))
        .await
        .unwrap();
    assert_eq!(b.pending("phone", "old").await.unwrap().len(), 1);
}
#[test]
fn webpush_header_validation() {
    let mut h = HeaderMap::new();
    h.insert("content-encoding", HeaderValue::from_static("aes128gcm"));
    assert!(Publication::parse(&h, vec![0; 4096]).is_err());
    h.insert("ttl", HeaderValue::from_static("3000000"));
    assert_eq!(Publication::parse(&h, vec![0; 4096]).unwrap().ttl, MAX_TTL);
    assert!(Publication::parse(&h, vec![0; 4097]).is_err());
    h.insert("topic", HeaderValue::from_static("illegal/topic"));
    assert!(Publication::parse(&h, vec![1]).is_err());
    h.remove("topic");
    h.insert("urgency", HeaderValue::from_static("urgent"));
    assert!(Publication::parse(&h, vec![1]).is_err());
    h.remove("urgency");
    h.append("ttl", HeaderValue::from_static("1"));
    assert!(Publication::parse(&h, vec![1]).is_err());
    assert!(public_key(&"A".repeat(87)).is_err());
    assert!(public_key(&push::Sender::new().key).is_ok());
}

#[test]
fn independent_node_webpush_request_is_accepted_without_wire_conversion() {
    use base64::Engine;
    let vector: serde_json::Value =
        serde_json::from_str(include_str!("fixtures/unifiedpush/webpush.json")).unwrap();
    vapid(
        vector["authorization"].as_str(),
        vector["vapid"].as_str().unwrap(),
        ORIGIN,
        vector["generated_at"].as_i64().unwrap(),
    )
    .unwrap();
    let mut headers = HeaderMap::new();
    for (name, value) in vector["headers"].as_object().unwrap() {
        headers.insert(
            simple_server::web::http::header::HeaderName::from_bytes(name.as_bytes()).unwrap(),
            HeaderValue::from_str(
                &value
                    .as_str()
                    .map(str::to_owned)
                    .unwrap_or_else(|| value.to_string()),
            )
            .unwrap(),
        );
    }
    let payload = base64::engine::general_purpose::STANDARD
        .decode(vector["body"].as_str().unwrap())
        .unwrap();
    let parsed = Publication::parse(&headers, payload.clone()).unwrap();
    assert_eq!(parsed.payload, payload);
    assert_eq!(parsed.ttl, 300);
    assert_eq!(parsed.urgency, "high");
}
