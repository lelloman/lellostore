use super::{hash, model::*, now, Broker, Error, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};
use simple_server::web::http::StatusCode;
use sqlx::Row;
use uuid::Uuid;

impl Broker {
    pub async fn device(&self, credential: &str) -> Result<(String, String, String)> {
        sqlx::query_as(
            "SELECT id,issuer,subject FROM push_devices WHERE credential_hash=? AND revoked=0",
        )
        .bind(hash(credential))
        .fetch_optional(&self.db)
        .await?
        .ok_or_else(Error::unauthorized)
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
    pub async fn subscribe(&self, device: &str, body: &Subscription) -> Result<String> {
        if body.token.is_empty() || body.token.len() > 100 {
            return Err(Error::bad("invalid token"));
        }
        identifier(&body.package)?;
        public_key(&body.vapid)?;
        super::validate_secret(&body.endpoint_secret)?;
        let mut tx = self.db.begin_with("BEGIN IMMEDIATE").await?;
        let approved: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM push_keys k JOIN push_senders s ON s.id=k.sender_id WHERE k.key=? AND k.revoked=0 AND s.enabled=1)")
            .bind(&body.vapid).fetch_one(&mut *tx).await?;
        if !approved {
            return Err(Error::denied());
        }
        let active: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM push_devices WHERE id=? AND revoked=0)",
        )
        .bind(device)
        .fetch_one(&mut *tx)
        .await?;
        if !active {
            return Err(Error::denied());
        }
        if let Some(row) = sqlx::query(
            "SELECT * FROM push_subscriptions WHERE device_id=? AND token=? AND revoked=0",
        )
        .bind(device)
        .bind(&body.token)
        .fetch_optional(&mut *tx)
        .await?
        {
            if row.get::<String, _>("package") != body.package {
                return Err(Error::denied());
            }
            if row.get::<String, _>("vapid") == body.vapid
                && row.get::<String, _>("endpoint_hash") == hash(&body.endpoint_secret)
            {
                return Ok(row.get("id"));
            }
            let id: String = row.get("id");
            sqlx::query("UPDATE push_subscriptions SET revoked=1 WHERE id=?")
                .bind(&id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("UPDATE push_messages SET state='revoked',payload=X'' WHERE subscription_id=? AND state='pending'").bind(id).execute(&mut *tx).await?;
        }
        let count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM push_subscriptions WHERE device_id=? AND package=? AND revoked=0",
        )
        .bind(device)
        .bind(&body.package)
        .fetch_one(&mut *tx)
        .await?;
        let total: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM push_subscriptions WHERE device_id=? AND revoked=0",
        )
        .bind(device)
        .fetch_one(&mut *tx)
        .await?;
        if count >= 1024 || total >= 4096 {
            return Err(Error::full());
        }
        let id = Uuid::new_v4().to_string();
        sqlx::query("INSERT INTO push_subscriptions(id,device_id,token,package,vapid,endpoint_hash) VALUES(?,?,?,?,?,?)")
            .bind(&id).bind(device).bind(&body.token).bind(&body.package).bind(&body.vapid).bind(hash(&body.endpoint_secret)).execute(&mut *tx).await?;
        tx.commit().await?;
        self.wake().await;
        Ok(id)
    }
    pub async fn revoke(&self, device: &str, id: Option<&str>) -> Result<()> {
        let mut tx = self.db.begin_with("BEGIN IMMEDIATE").await?;
        if id.is_none() {
            sqlx::query("UPDATE push_devices SET revoked=1 WHERE id=?")
                .bind(device)
                .execute(&mut *tx)
                .await?;
        }
        sqlx::query(
            "UPDATE push_subscriptions SET revoked=1 WHERE device_id=? AND (? IS NULL OR id=?)",
        )
        .bind(device)
        .bind(id)
        .bind(id)
        .execute(&mut *tx)
        .await?;
        sqlx::query("UPDATE push_messages SET state='revoked',payload=X'' WHERE state='pending' AND subscription_id IN (SELECT id FROM push_subscriptions WHERE revoked=1)").execute(&mut *tx).await?;
        tx.commit().await?;
        self.wake().await;
        Ok(())
    }
    pub async fn publish(
        &self,
        secret: &str,
        authorization: Option<&str>,
        origin: &str,
        message: &Publication,
    ) -> Result<String> {
        let time = now();
        let mut tx = self.db.begin_with("BEGIN IMMEDIATE").await?;
        let row = sqlx::query("SELECT r.id AS subscription,r.device_id,r.vapid,s.* FROM push_subscriptions r JOIN push_devices d ON d.id=r.device_id JOIN push_keys k ON k.key=r.vapid JOIN push_senders s ON s.id=k.sender_id WHERE r.endpoint_hash=? AND r.revoked=0 AND d.revoked=0 AND k.revoked=0")
            .bind(hash(secret)).fetch_optional(&mut *tx).await?.ok_or_else(|| Error(StatusCode::GONE,"push endpoint unavailable".into()))?;
        vapid(authorization, row.get("vapid"), origin, time)?;
        if !row.get::<bool, _>("enabled") {
            return Err(Error::denied());
        }
        let device: String = row.get("device_id");
        let sender: String = row.get("id");
        let subscription: String = row.get("subscription");
        let tokens = (row.get::<f64, _>("tokens")
            + (time - row.get::<i64, _>("refilled_at")).max(0) as f64 * row.get::<f64, _>("rate"))
        .min(row.get::<i64, _>("burst") as f64);
        if tokens < 1.0 {
            return Err(Error::full());
        }
        sqlx::query("UPDATE push_messages SET state='expired',payload=X'' WHERE state='pending' AND expires_at<=? AND connection_epoch IS NULL").bind(time).execute(&mut *tx).await?;
        // Topic replacement and quota checks share a transaction: rejection preserves the old message.
        if let Some(topic) = &message.topic {
            sqlx::query("UPDATE push_messages SET state='replaced',payload=X'' WHERE subscription_id=? AND topic=? AND state='pending'").bind(&subscription).bind(topic).execute(&mut *tx).await?;
        }
        let (count, bytes): (i64,i64) = sqlx::query_as("SELECT count(*),coalesce(sum(length(m.payload)),0) FROM push_messages m JOIN push_subscriptions r ON r.id=m.subscription_id JOIN push_keys k ON k.key=r.vapid WHERE k.sender_id=? AND m.state='pending'").bind(&sender).fetch_one(&mut *tx).await?;
        let (device_count,device_bytes): (i64,i64) = sqlx::query_as("SELECT count(*),coalesce(sum(length(m.payload)),0) FROM push_messages m JOIN push_subscriptions r ON r.id=m.subscription_id WHERE r.device_id=? AND m.state='pending'").bind(&device).fetch_one(&mut *tx).await?;
        let global: i64 = sqlx::query_scalar(
            "SELECT coalesce(sum(length(payload)),0) FROM push_messages WHERE state='pending'",
        )
        .fetch_one(&mut *tx)
        .await?;
        let size = message.payload.len() as i64;
        if count >= row.get::<i64, _>("max_pending")
            || bytes + size > row.get::<i64, _>("max_bytes")
            || device_count >= 1024
            || device_bytes + size > 4194304
            || global + size > 1073741824
        {
            return Err(Error::full());
        }
        let epoch = if message.ttl == 0 {
            self.connections
                .lock()
                .await
                .get(&device)
                .filter(|c| c.expires > time)
                .map(|c| c.epoch.clone())
        } else {
            None
        };
        let state = if message.ttl == 0 && epoch.is_none() {
            "expired"
        } else {
            "pending"
        };
        let id = Uuid::new_v4().to_string();
        sqlx::query("INSERT INTO push_messages(id,subscription_id,payload,topic,urgency,accepted_at,expires_at,connection_epoch,state) VALUES(?,?,?,?,?,?,?,?,?)")
            .bind(&id).bind(subscription).bind(if state=="pending" { message.payload.as_slice() } else { &[] }).bind(&message.topic).bind(&message.urgency).bind(time).bind(time+message.ttl).bind(epoch).bind(state).execute(&mut *tx).await?;
        sqlx::query("UPDATE push_senders SET tokens=?,refilled_at=? WHERE id=?")
            .bind(tokens - 1.0)
            .bind(time)
            .bind(sender)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        self.wake().await;
        Ok(id)
    }
    pub async fn pending(&self, device: &str, epoch: &str) -> Result<Vec<Value>> {
        let rows = sqlx::query("SELECT m.*,r.token FROM push_messages m JOIN push_subscriptions r ON r.id=m.subscription_id JOIN push_keys k ON k.key=r.vapid JOIN push_senders s ON s.id=k.sender_id WHERE r.device_id=? AND r.revoked=0 AND k.revoked=0 AND s.enabled=1 AND m.state='pending' AND ((m.connection_epoch IS NULL AND m.expires_at>?) OR m.connection_epoch=?) ORDER BY CASE m.urgency WHEN 'high' THEN 0 WHEN 'normal' THEN 1 WHEN 'low' THEN 2 ELSE 3 END,m.accepted_at,m.id LIMIT 32")
            .bind(device).bind(now()).bind(epoch).fetch_all(&self.db).await?;
        Ok(rows.iter().map(|r|json!({"id":r.get::<String,_>("id"),"subscription_id":r.get::<String,_>("subscription_id"),"token":r.get::<String,_>("token"),"payload":STANDARD.encode(r.get::<Vec<u8>,_>("payload")),"urgency":r.get::<String,_>("urgency"),"expires_at":r.get::<i64,_>("expires_at"),"immediate":r.get::<Option<String>,_>("connection_epoch").is_some()})).collect())
    }
    pub async fn receipt(&self, device: &str, id: &str, token: &str) -> Result<()> {
        sqlx::query("UPDATE push_messages SET state='acknowledged',payload=X'' WHERE id=? AND state IN ('pending','dispatched') AND subscription_id IN (SELECT id FROM push_subscriptions WHERE device_id=? AND token=?)").bind(id).bind(device).bind(token).execute(&self.db).await?;
        Ok(())
    }
    pub async fn catalog(&self) -> Result<()> {
        self.catalog_revision
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.wake().await;
        Ok(())
    }
    pub async fn maintenance(&self) -> Result<()> {
        sqlx::query("UPDATE push_messages SET state='expired',payload=X'' WHERE state='pending' AND (expires_at<=? AND connection_epoch IS NULL OR connection_epoch IS NOT NULL AND accepted_at<?)").bind(now()).bind(now()-30).execute(&self.db).await?;
        sqlx::query("DELETE FROM push_messages WHERE state<>'pending' AND accepted_at<?")
            .bind(now() - 30 * 86400)
            .execute(&self.db)
            .await?;
        sqlx::query("DELETE FROM notification_audit WHERE id NOT IN (SELECT id FROM notification_audit ORDER BY id DESC LIMIT 10000)").execute(&self.db).await?;
        crate::metrics::NOTIFICATION_CONNECTIONS.set(self.connections.lock().await.len() as i64);
        crate::metrics::NOTIFICATION_QUEUE.reset();
        for row in sqlx::query("SELECT state,count(*) AS n FROM push_messages GROUP BY state")
            .fetch_all(&self.db)
            .await?
        {
            crate::metrics::NOTIFICATION_QUEUE
                .with_label_values(&[row.get::<String, _>("state")])
                .set(row.get("n"));
        }
        let (bytes,oldest):(i64,i64)=sqlx::query_as("SELECT coalesce(sum(length(payload)),0),coalesce(min(accepted_at),?) FROM push_messages WHERE state='pending'").bind(now()).fetch_one(&self.db).await?;
        crate::metrics::NOTIFICATION_BYTES.set(bytes);
        crate::metrics::NOTIFICATION_OLDEST.set((now() - oldest).max(0));
        Ok(())
    }
}
