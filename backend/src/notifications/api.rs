use super::{hash, model::*, now, validate_secret, Broker, Error, Result};
use crate::{api::AppState, auth::TokenClaims};
use serde::Deserialize;
use serde_json::{json, Value};
use simple_server::web::{
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{delete, get, post, put},
    Json, Router,
};
use sqlx::Row;
use std::sync::Arc;
use uuid::Uuid;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/push/v1/devices", post(device).delete(revoke_device))
        .route(
            "/api/push/v1/subscriptions",
            post(subscribe).delete(unsubscribe_token),
        )
        .route("/api/push/v1/subscriptions/{id}", delete(unsubscribe))
        .route("/api/push/v1/stream", get(super::stream::upgrade))
        .route(
            "/api/push/v1/send/{secret}",
            post(publish).layer(simple_server::body_limit::BodyLimit::max(4096)),
        )
        .route("/api/push/v1/message/{id}", delete(cancel_message))
        .route("/api/admin/notifications", get(overview))
        .route("/api/admin/notifications/senders", post(approve))
        .route("/api/admin/notifications/senders/{id}", put(update_sender))
        .route("/api/admin/notifications/senders/{id}/keys", post(add_key))
        .route(
            "/api/admin/notifications/senders/{id}/keys/{key}",
            delete(revoke_key),
        )
        .layer(simple_server::body_limit::BodyLimit::max(65536))
}
pub fn broker(state: &AppState) -> Result<Arc<Broker>> {
    if !state.config.notifications_enabled {
        return Err(Error(
            StatusCode::SERVICE_UNAVAILABLE,
            "Shared notifications are disabled".into(),
        ));
    }
    Ok(state.notifications.clone())
}
pub fn bearer(headers: &HeaderMap) -> Result<&str> {
    headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.strip_prefix("Bearer "))
        .ok_or_else(Error::unauthorized)
}
pub async fn claims(state: &AppState, token: &str) -> Result<TokenClaims> {
    let auth = state.auth.as_ref().ok_or_else(Error::unauthorized)?;
    let c = auth
        .validator
        .validate(token)
        .await
        .map_err(|_| Error::unauthorized())?;
    if c.exp <= now() as u64 {
        return Err(Error::unauthorized());
    }
    Ok(c)
}
async fn admin(state: &AppState, headers: &HeaderMap) -> Result<String> {
    broker(state)?;
    let c = claims(state, bearer(headers)?).await?;
    let auth = state.auth.as_ref().ok_or_else(Error::unauthorized)?;
    if !crate::auth::User::from_claims(&c, &auth.role_claim_path, &auth.admin_role).is_admin {
        return Err(Error::denied());
    }
    Ok(c.sub)
}
async fn owned_device(state: &AppState, h: &HeaderMap) -> Result<(String, TokenClaims)> {
    let b = broker(state)?;
    let c = claims(state, bearer(h)?).await?;
    let token = h
        .get("x-device-credential")
        .and_then(|h| h.to_str().ok())
        .ok_or_else(Error::unauthorized)?;
    let (id, issuer, subject) = b.device(token).await?;
    if c.iss != issuer || c.sub != subject {
        return Err(Error::denied());
    }
    Ok((id, c))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Device {
    installation: String,
    credential: String,
    package: String,
}
async fn device(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Device>,
) -> Result<Json<Value>> {
    broker(&s)?;
    let c = claims(&s, bearer(&h)?).await?;
    identifier(&body.installation)?;
    validate_secret(&body.credential)?;
    if !["com.lelloman.store", "com.lelloman.store.debug"].contains(&body.package.as_str()) {
        return Err(Error::bad("invalid Store package"));
    }
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    if let Some((issuer, subject, credential_hash, revoked)) =
        sqlx::query_as::<_, (String, String, String, bool)>(
            "SELECT issuer,subject,credential_hash,revoked FROM push_devices WHERE id=?",
        )
        .bind(&body.installation)
        .fetch_optional(&mut *tx)
        .await?
    {
        if issuer != c.iss
            || subject != c.sub
            || credential_hash != hash(&body.credential)
            || revoked
        {
            return Err(Error::denied());
        }
    } else {
        sqlx::query("INSERT INTO push_devices(id,credential_hash,issuer,subject,package,created_at) VALUES(?,?,?,?,?,?)").bind(&body.installation).bind(hash(&body.credential)).bind(&c.iss).bind(&c.sub).bind(&body.package).bind(now()).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    Ok(Json(
        json!({"device_id":body.installation,"issuer":c.iss,"subject":c.sub}),
    ))
}

async fn revoke_device(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Value>> {
    let (id, _, _) = broker(&s)?.device(bearer(&h)?).await?;
    s.notifications.revoke(&id, None).await?;
    Ok(Json(json!({"ok":true})))
}
async fn subscribe(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Subscription>,
) -> Result<Json<Value>> {
    let (device, _) = owned_device(&s, &h).await?;
    let id = s.notifications.subscribe(&device, &body).await?;
    Ok(Json(
        json!({"id":id,"endpoint":format!("{}/api/push/v1/send/{}",s.config.push_public_base_url,body.endpoint_secret)}),
    ))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ConnectionToken {
    token: String,
}
async fn unsubscribe_token(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<ConnectionToken>,
) -> Result<Json<Value>> {
    let (device, _) = owned_device(&s, &h).await?;
    let ids: Vec<String> = sqlx::query_scalar(
        "SELECT id FROM push_subscriptions WHERE device_id=? AND token=? AND revoked=0",
    )
    .bind(&device)
    .bind(body.token)
    .fetch_all(&s.db)
    .await?;
    for id in ids {
        s.notifications.revoke(&device, Some(&id)).await?;
    }
    Ok(Json(json!({"ok":true})))
}
async fn unsubscribe(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Value>> {
    let (device, _) = owned_device(&s, &h).await?;
    s.notifications.revoke(&device, Some(&id)).await?;
    Ok(Json(json!({"ok":true})))
}
async fn publish(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(secret): Path<String>,
    body: simple_server::web::Bytes,
) -> Result<Response> {
    let b = broker(&s)?;
    let message = Publication::parse(&h, body.to_vec())?;
    let id = b
        .publish(
            &secret,
            h.get("authorization").and_then(|v| v.to_str().ok()),
            &s.config.push_public_base_url,
            &message,
        )
        .await?;
    Ok((
        StatusCode::CREATED,
        [
            (
                "location",
                format!("{}/api/push/v1/message/{id}", s.config.push_public_base_url),
            ),
            ("ttl", message.ttl.to_string()),
            ("cache-control", "no-store".into()),
        ],
    )
        .into_response())
}
async fn cancel_message(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode> {
    broker(&s)?;
    let key: String=sqlx::query_scalar("SELECT r.vapid FROM push_messages m JOIN push_subscriptions r ON r.id=m.subscription_id WHERE m.id=?").bind(&id).fetch_optional(&s.db).await?.ok_or_else(||Error(StatusCode::NOT_FOUND,"message not found".into()))?;
    vapid(
        h.get("authorization").and_then(|v| v.to_str().ok()),
        &key,
        &s.config.push_public_base_url,
        now(),
    )?;
    sqlx::query(
        "UPDATE push_messages SET state='cancelled',payload=X'' WHERE id=? AND state='pending'",
    )
    .bind(id)
    .execute(&s.db)
    .await?;
    Ok(StatusCode::NO_CONTENT)
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Approval {
    name: String,
    key: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Key {
    key: String,
}
async fn approve(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Approval>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    identifier(&body.name)?;
    public_key(&body.key)?;
    let id = Uuid::new_v4().to_string();
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM push_keys WHERE key=?)")
        .bind(&body.key)
        .fetch_one(&mut *tx)
        .await?;
    if exists {
        return Err(Error::conflict("key already registered"));
    }
    sqlx::query("INSERT INTO push_senders(id,name) VALUES(?,?)")
        .bind(&id)
        .bind(body.name)
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO push_keys(key,sender_id) VALUES(?,?)")
        .bind(body.key)
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    s.notifications
        .audit(&actor, "sender.approved", &id)
        .await?;
    Ok(Json(json!({"id":id})))
}
async fn add_key(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<Key>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    public_key(&body.key)?;
    let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM push_senders WHERE id=?)")
        .bind(&id)
        .fetch_one(&s.db)
        .await?;
    if !exists {
        return Err(Error::bad("unknown sender"));
    }
    if sqlx::query("INSERT INTO push_keys(key,sender_id) VALUES(?,?) ON CONFLICT(key) DO NOTHING")
        .bind(body.key)
        .bind(&id)
        .execute(&s.db)
        .await?
        .rows_affected()
        == 0
    {
        return Err(Error::conflict(
            "key already registered; revoked keys cannot be reused",
        ));
    }
    s.notifications.audit(&actor, "key.approved", &id).await?;
    Ok(Json(json!({"ok":true})))
}
async fn revoke_key(
    State(s): State<AppState>,
    h: HeaderMap,
    Path((id, key)): Path<(String, String)>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    sqlx::query("UPDATE push_keys SET revoked=1 WHERE key=? AND sender_id=?")
        .bind(&key)
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE push_subscriptions SET revoked=1 WHERE vapid IN (SELECT key FROM push_keys WHERE revoked=1)").execute(&mut *tx).await?;
    sqlx::query("UPDATE push_messages SET state='revoked',payload=X'' WHERE state='pending' AND subscription_id IN (SELECT id FROM push_subscriptions WHERE revoked=1)").execute(&mut *tx).await?;
    tx.commit().await?;
    s.notifications.wake().await;
    s.notifications.audit(&actor, "key.revoked", &id).await?;
    Ok(Json(json!({"ok":true})))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SenderUpdate {
    name: String,
    enabled: bool,
    max_pending: i64,
    max_bytes: i64,
    rate: f64,
    burst: i64,
}
async fn update_sender(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<SenderUpdate>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    identifier(&body.name)?;
    if body.max_pending < 1
        || body.max_pending > 1000000
        || body.max_bytes < 4096
        || body.max_bytes > 1073741824
        || !body.rate.is_finite()
        || body.rate <= 0.0
        || body.rate > 10000.0
        || body.burst < 1
        || body.burst > 100000
    {
        return Err(Error::bad("invalid quotas"));
    }
    sqlx::query("UPDATE push_senders SET name=?,enabled=?,max_pending=?,max_bytes=?,rate=?,burst=?,tokens=min(tokens,?) WHERE id=?").bind(body.name).bind(body.enabled).bind(body.max_pending).bind(body.max_bytes).bind(body.rate).bind(body.burst).bind(body.burst).bind(&id).execute(&s.db).await?;
    s.notifications.audit(&actor, "sender.updated", &id).await?;
    s.notifications.wake().await;
    Ok(Json(json!({"ok":true})))
}
async fn overview(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Value>> {
    admin(&s, &h).await?;
    let rows=sqlx::query("SELECT s.*,(SELECT count(*) FROM push_subscriptions r JOIN push_keys k ON k.key=r.vapid WHERE k.sender_id=s.id AND r.revoked=0) AS registrations,(SELECT count(*) FROM push_messages m JOIN push_subscriptions r ON r.id=m.subscription_id JOIN push_keys k ON k.key=r.vapid WHERE k.sender_id=s.id AND m.state='pending') AS pending,(SELECT coalesce(sum(length(m.payload)),0) FROM push_messages m JOIN push_subscriptions r ON r.id=m.subscription_id JOIN push_keys k ON k.key=r.vapid WHERE k.sender_id=s.id AND m.state='pending') AS queued_bytes FROM push_senders s ORDER BY name").fetch_all(&s.db).await?;
    let mut senders = Vec::new();
    for r in rows {
        let id: String = r.get("id");
        let keys = sqlx::query("SELECT key,revoked FROM push_keys WHERE sender_id=? ORDER BY key")
            .bind(&id)
            .fetch_all(&s.db)
            .await?;
        senders.push(json!({"id":id,"name":r.get::<String,_>("name"),"enabled":r.get::<bool,_>("enabled"),"max_pending":r.get::<i64,_>("max_pending"),"max_bytes":r.get::<i64,_>("max_bytes"),"rate":r.get::<f64,_>("rate"),"burst":r.get::<i64,_>("burst"),"registrations":r.get::<i64,_>("registrations"),"pending":r.get::<i64,_>("pending"),"queued_bytes":r.get::<i64,_>("queued_bytes"),"keys":keys.iter().map(|k|json!({"key":k.get::<String,_>("key"),"revoked":k.get::<bool,_>("revoked")})).collect::<Vec<_>>()}));
    }
    let outcomes = sqlx::query("SELECT state,count(*) AS count FROM push_messages GROUP BY state")
        .fetch_all(&s.db)
        .await?;
    Ok(Json(
        json!({"senders":senders,"connections":s.notifications.connections.lock().await.len(),"outcomes":outcomes.iter().map(|r|json!({"state":r.get::<String,_>("state"),"count":r.get::<i64,_>("count")})).collect::<Vec<_>>()}),
    ))
}
