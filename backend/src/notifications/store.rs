use super::{hash, model::*, now, Broker, Error, Result};
use serde_json::json;
use sqlx::Row;
use uuid::Uuid;

impl Broker {
    pub async fn sender(&self, credential: &str) -> Result<String> {
        sqlx::query_scalar("SELECT s.id FROM notification_senders s JOIN notification_credentials c ON c.sender_id=s.id WHERE c.hash=? AND c.revoked=0 AND (c.expires_at IS NULL OR c.expires_at>?) AND s.enabled=1")
            .bind(hash(credential)).bind(now()).fetch_optional(&self.db).await?.ok_or_else(Error::unauthorized)
    }
    pub async fn device(&self, credential: &str) -> Result<(String, String, String)> {
        sqlx::query_as("SELECT id,issuer,subject FROM notification_devices WHERE credential_hash=? AND revoked=0")
            .bind(hash(credential)).fetch_optional(&self.db).await?.ok_or_else(Error::unauthorized)
    }
    pub async fn audit(&self, actor: &str, action: &str, entity: &str) -> Result<()> {
        sqlx::query("INSERT INTO notification_audit(at,actor,action,entity) VALUES(?,?,?,?)")
            .bind(now())
            .bind(actor)
            .bind(action)
            .bind(entity)
            .execute(&self.db)
            .await?;
        Ok(())
    }
    pub async fn publish(&self, sender: &str, message: &Publication) -> Result<serde_json::Value> {
        let time = now();
        message.validate(time)?;
        let request_hash = hash(&serde_json::to_string(message)?);
        let mut tx = self.db.begin_with("BEGIN IMMEDIATE").await?;
        let row = sqlx::query("SELECT * FROM notification_senders WHERE id=? AND enabled=1")
            .bind(sender)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(Error::denied)?;
        if let Some(existing) = sqlx::query_scalar::<_, String>(
            "SELECT request_hash FROM notification_events WHERE sender_id=? AND event_id=?",
        )
        .bind(sender)
        .bind(&message.event_id)
        .fetch_optional(&mut *tx)
        .await?
        {
            if existing != request_hash {
                return Err(Error::conflict(
                    "event_id already used with different content",
                ));
            }
            tx.commit().await?;
            return self.outcome(sender, &message.event_id).await;
        }
        if sender != "lellostore" {
            let applications: Vec<Application> = serde_json::from_str(row.get("applications"))?;
            if !applications
                .iter()
                .any(|a| a.package == message.application)
            {
                return Err(Error::denied());
            }
        }
        let manifest: Manifest = serde_json::from_str(row.get("manifest"))?;
        let overrides: Vec<Rule> = serde_json::from_str(row.get("overrides"))?;
        let policy = if sender == "lellostore" && message.message_type == "catalog.changed" {
            overrides
                .iter()
                .find(|r| r.matches(message))
                .map(|r| r.policy.clone())
                .unwrap_or(Policy {
                    strategy: Strategy::Latest,
                    ttl_seconds: Some(86400),
                })
        } else {
            manifest.policy(message, &overrides)?
        };
        if policy.strategy == Strategy::Latest && message.replacement_key.is_none() {
            return Err(Error::bad(
                "latest-state publications require replacement_key",
            ));
        }
        let tokens = (row.get::<f64, _>("tokens")
            + (time - row.get::<i64, _>("refilled_at")).max(0) as f64 * row.get::<f64, _>("rate"))
        .min(row.get::<i64, _>("burst") as f64);
        if tokens < 1.0 {
            return Err(Error::full());
        }
        sqlx::query("UPDATE notification_senders SET tokens=?,refilled_at=? WHERE id=?")
            .bind(tokens - 1.0)
            .bind(time)
            .bind(sender)
            .execute(&mut *tx)
            .await?;
        sqlx::query("UPDATE notification_deliveries SET state='expired',envelope='',bytes=0 WHERE state='pending' AND expires_at<=?")
            .bind(time).execute(&mut *tx).await?;
        let subscriptions = sqlx::query("SELECT s.* FROM notification_subscriptions s JOIN notification_devices d ON d.id=s.device_id WHERE s.sender_id=? AND s.package=? AND s.subject=? AND s.revoked=0 AND d.revoked=0 AND (? IS NULL OR s.id=?)")
            .bind(sender).bind(&message.application).bind(&message.target.subject)
            .bind(&message.target.subscription_id).bind(&message.target.subscription_id).fetch_all(&mut *tx).await?;
        if subscriptions.is_empty() {
            return Err(Error::conflict("no enrolled recipient"));
        }
        let expires = match (policy.ttl_seconds.map(|t| time + t), message.expires_at) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        };
        sqlx::query("INSERT INTO notification_events(sender_id,event_id,request_hash,accepted_at) VALUES(?,?,?,?)")
            .bind(sender).bind(&message.event_id).bind(request_hash).bind(time).execute(&mut *tx).await?;
        for sub in subscriptions {
            let sub_id: String = sub.get("id");
            let device: String = sub.get("device_id");
            let epoch = self
                .connections
                .lock()
                .await
                .get(&device)
                .map(|c| c.epoch.clone());
            let mut state = if expires.is_some_and(|e| e <= time) {
                "expired"
            } else {
                "pending"
            };
            if policy.strategy == Strategy::OnlineOnly
                && (epoch.is_none()
                    || sub.get::<i64, _>("lease_until") <= time
                    || sub.get::<i64, _>("confirmed") == 0)
            {
                state = "offline";
            }
            if policy.strategy == Strategy::Latest {
                let keys:i64=sqlx::query_scalar("SELECT count(*) FROM notification_watermarks WHERE subscription_id=? AND NOT (type=? AND replacement_key=?)").bind(&sub_id).bind(&message.message_type).bind(&message.replacement_key).fetch_one(&mut *tx).await?;
                if keys >= 256 {
                    return Err(Error::full());
                }
                if let Some((occurrence,revision)) = sqlx::query_as::<_,(i64,i64)>("SELECT occurrence,revision FROM notification_watermarks WHERE subscription_id=? AND type=? AND replacement_key=?")
                    .bind(&sub_id).bind(&message.message_type).bind(&message.replacement_key).fetch_optional(&mut *tx).await? {
                    if (message.occurrence,message.revision) <= (occurrence,revision) { state="superseded"; }
                }
                if state != "superseded" {
                    sqlx::query("INSERT INTO notification_watermarks VALUES(?,?,?,?,?,?) ON CONFLICT(subscription_id,type,replacement_key) DO UPDATE SET occurrence=excluded.occurrence,revision=excluded.revision,expires_at=excluded.expires_at")
                        .bind(&sub_id).bind(&message.message_type).bind(&message.replacement_key).bind(message.occurrence).bind(message.revision).bind(expires).execute(&mut *tx).await?;
                    sqlx::query("UPDATE notification_deliveries SET state='superseded',envelope='',bytes=0 WHERE subscription_id=? AND type=? AND replacement_key=? AND state='pending'")
                        .bind(&sub_id).bind(&message.message_type).bind(&message.replacement_key).execute(&mut *tx).await?;
                }
            }
            let id = Uuid::new_v4().to_string();
            let envelope = Envelope {
                version: 1,
                delivery_id: id.clone(),
                sender_id: sender.into(),
                subscription_id: sub_id.clone(),
                generation: sub.get("generation"),
                installation: sub.get("installation"),
                component: sub.get("component"),
                certificate: sub.get("certificate"),
                policy_version: row.get("policy_version"),
                accepted_at: time,
                expires_at: expires,
                message: message.clone(),
            };
            let body = if state == "pending" {
                serde_json::to_string(&envelope)?
            } else {
                String::new()
            };
            sqlx::query("INSERT INTO notification_deliveries(id,sender_id,event_id,subscription_id,type,replacement_key,occurrence,revision,envelope,bytes,expires_at,online_only,connection_epoch,state,accepted_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .bind(id).bind(sender).bind(&message.event_id).bind(&sub_id).bind(&message.message_type).bind(&message.replacement_key)
                .bind(message.occurrence).bind(message.revision).bind(&body).bind(body.len() as i64).bind(expires)
                .bind(policy.strategy==Strategy::OnlineOnly).bind(&epoch).bind(state).bind(time).execute(&mut *tx).await?;
        }
        let (count,bytes):(i64,i64)=sqlx::query_as("SELECT count(*),coalesce(sum(bytes),0) FROM notification_deliveries WHERE sender_id=? AND state='pending'")
            .bind(sender).fetch_one(&mut *tx).await?;
        let all: i64 = sqlx::query_scalar(
            "SELECT coalesce(sum(bytes),0) FROM notification_deliveries WHERE state='pending'",
        )
        .fetch_one(&mut *tx)
        .await?;
        if count > row.get::<i64, _>("max_pending")
            || bytes > row.get::<i64, _>("max_bytes")
            || all > 1024 * 1024 * 1024
        {
            return Err(Error::full());
        }
        tx.commit().await?;
        self.wake().await;
        self.outcome(sender, &message.event_id).await
    }
    pub async fn outcome(&self, sender: &str, event: &str) -> Result<serde_json::Value> {
        let exists: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM notification_events WHERE sender_id=? AND event_id=?)",
        )
        .bind(sender)
        .bind(event)
        .fetch_one(&self.db)
        .await?;
        if !exists {
            return Err(Error(
                simple_server::web::http::StatusCode::NOT_FOUND,
                "Event not found".into(),
            ));
        }
        let rows=sqlx::query("SELECT id,subscription_id,state,presentation FROM notification_deliveries WHERE sender_id=? AND event_id=? ORDER BY id").bind(sender).bind(event).fetch_all(&self.db).await?;
        Ok(
            json!({"event_id":event,"deliveries":rows.iter().map(|r|json!({"delivery_id":r.get::<String,_>("id"),"subscription_id":r.get::<String,_>("subscription_id"),"state":r.get::<String,_>("state"),"presentation":r.get::<Option<String>,_>("presentation")})).collect::<Vec<_>>()}),
        )
    }
    pub async fn pending(&self, device: &str) -> Result<Vec<serde_json::Value>> {
        let epoch = self
            .connections
            .lock()
            .await
            .get(device)
            .map(|c| c.epoch.clone());
        let rows:Vec<String>=sqlx::query_scalar("SELECT envelope FROM (SELECT n.envelope,n.accepted_at,row_number() OVER (PARTITION BY s.package ORDER BY n.accepted_at,n.id) AS slot FROM notification_deliveries n JOIN notification_subscriptions s ON s.id=n.subscription_id JOIN notification_senders p ON p.id=s.sender_id WHERE s.device_id=? AND s.revoked=0 AND s.confirmed=1 AND s.lease_until>? AND p.enabled=1 AND n.state='pending' AND (n.online_only=0 OR n.connection_epoch=?) AND (n.expires_at IS NULL OR n.expires_at>?)) ORDER BY slot,accepted_at LIMIT 32")
            .bind(device).bind(now()).bind(epoch).bind(now()).fetch_all(&self.db).await?;
        rows.into_iter()
            .map(|s| Ok(serde_json::from_str(&s)?))
            .collect()
    }
    pub async fn receipt(&self, device: &str, id: &str, presentation: Option<&str>) -> Result<()> {
        if presentation.is_some_and(|s| {
            ![
                "posted",
                "suppressed",
                "permission_blocked",
                "expired",
                "superseded",
            ]
            .contains(&s)
        }) {
            return Err(Error::bad("invalid presentation state"));
        }
        let changed=sqlx::query("UPDATE notification_deliveries SET state='persisted',presentation=coalesce(?,presentation),envelope='',bytes=0 WHERE id=? AND state IN ('pending','persisted') AND subscription_id IN (SELECT id FROM notification_subscriptions WHERE device_id=? AND revoked=0)")
            .bind(presentation).bind(id).bind(device).execute(&self.db).await?.rows_affected();
        if changed == 0 {
            return Err(Error::conflict("delivery is no longer pending"));
        }
        Ok(())
    }
    pub async fn catalog(&self) -> Result<()> {
        let rows=sqlx::query("SELECT DISTINCT package,subject FROM notification_subscriptions WHERE sender_id='lellostore' AND revoked=0").fetch_all(&self.db).await?;
        let revision = chrono::Utc::now().timestamp_millis();
        for row in rows {
            let p = Publication {
                event_id: Uuid::new_v4().to_string(),
                application: row.get("package"),
                message_type: "catalog.changed".into(),
                target: Target {
                    subject: row.get("subject"),
                    subscription_id: None,
                },
                level: "info".into(),
                tags: vec![],
                occurred_at: now(),
                expires_at: None,
                replacement_key: Some("catalog".into()),
                occurrence: 0,
                revision,
                payload: json!({}),
            };
            self.publish("lellostore", &p).await?;
        }
        Ok(())
    }
    pub async fn maintenance(&self) -> Result<()> {
        let mut tx = self.db.begin_with("BEGIN IMMEDIATE").await?;
        let epochs: Vec<String> = self
            .connections
            .lock()
            .await
            .values()
            .map(|c| c.epoch.clone())
            .collect();
        sqlx::query("UPDATE notification_deliveries SET state='expired',envelope='',bytes=0 WHERE state='pending' AND expires_at<=?").bind(now()).execute(&mut *tx).await?;
        sqlx::query("UPDATE notification_deliveries SET state='offline',envelope='',bytes=0 WHERE state='pending' AND online_only=1 AND connection_epoch NOT IN (SELECT value FROM json_each(?))").bind(serde_json::to_string(&epochs)?).execute(&mut *tx).await?;
        sqlx::query("UPDATE notification_events SET terminal_at=? WHERE terminal_at IS NULL AND NOT EXISTS(SELECT 1 FROM notification_deliveries n WHERE n.sender_id=notification_events.sender_id AND n.event_id=notification_events.event_id AND n.state='pending')").bind(now()).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM notification_deliveries WHERE EXISTS(SELECT 1 FROM notification_events e WHERE e.sender_id=notification_deliveries.sender_id AND e.event_id=notification_deliveries.event_id AND e.terminal_at<?)").bind(now()-30*86400).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM notification_events WHERE terminal_at<?")
            .bind(now() - 30 * 86400)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM notification_enrollments WHERE expires_at<?")
            .bind(now() - 86400)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM notification_invitations WHERE expires_at<?")
            .bind(now() - 86400)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM notification_audit WHERE id<(SELECT coalesce(max(id),0)-10000 FROM notification_audit)").execute(&mut *tx).await?;
        tx.commit().await?;
        crate::metrics::NOTIFICATION_CONNECTIONS.set(self.connections.lock().await.len() as i64);
        crate::metrics::NOTIFICATION_QUEUE.reset();
        for row in sqlx::query(
            "SELECT state,count(*) AS count FROM notification_deliveries GROUP BY state",
        )
        .fetch_all(&self.db)
        .await?
        {
            crate::metrics::NOTIFICATION_QUEUE
                .with_label_values(&[row.get::<&str, _>("state")])
                .set(row.get("count"));
        }
        let (bytes,oldest):(i64,Option<i64>)=sqlx::query_as("SELECT coalesce(sum(bytes),0),min(accepted_at) FROM notification_deliveries WHERE state='pending'").fetch_one(&self.db).await?;
        crate::metrics::NOTIFICATION_BYTES.set(bytes);
        crate::metrics::NOTIFICATION_OLDEST.set(oldest.map(|t| (now() - t).max(0)).unwrap_or(0));
        Ok(())
    }
}
