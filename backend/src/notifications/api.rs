use super::{hash, model::*, now, secret, validate_secret, Broker, Error, Result};
use crate::{api::AppState, auth::TokenClaims};
use serde::Deserialize;
use serde_json::{json, Value};
use simple_server::web::{
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    routing::{delete, get, post, put},
    Json, Router,
};
use sqlx::Row;
use std::sync::Arc;
use uuid::Uuid;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(
            "/api/notifications/v1/senders/register",
            post(register_sender),
        )
        .route("/api/notifications/v1/sender/manifest", put(manifest))
        .route("/api/notifications/v1/sender/rotate", post(rotate))
        .route("/api/notifications/v1/devices", post(device))
        .route("/api/notifications/v1/device", delete(revoke_device))
        .route("/api/notifications/v1/enrollments", post(enrollment))
        .route("/api/notifications/v1/subscriptions", post(subscribe))
        .route("/api/notifications/v1/subscriptions/renew", post(renew))
        .route(
            "/api/notifications/v1/subscriptions/{id}/confirm",
            post(confirm),
        )
        .route(
            "/api/notifications/v1/subscriptions/{id}",
            delete(revoke_subscription),
        )
        .route("/api/notifications/v1/messages", post(publish))
        .route("/api/notifications/v1/messages/{id}", get(outcome))
        .route("/api/notifications/v1/stream", get(super::stream::upgrade))
        .route("/api/admin/notifications", get(overview))
        .route("/api/admin/notifications/invitations", post(invite))
        .route("/api/admin/notifications/senders/{id}", put(admin_sender))
        .route(
            "/api/admin/notifications/senders/{id}/cancel",
            post(cancel_pending),
        )
        .route(
            "/api/admin/notifications/senders/{id}/credentials",
            post(admin_rotate),
        )
        .route(
            "/api/admin/notifications/senders/{id}/revoke",
            post(revoke_sender),
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
struct Invitation {
    name: String,
    applications: Vec<Application>,
}
async fn invite(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Invitation>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    identifier(&body.name)?;
    validate_apps(&body.applications)?;
    let token = secret();
    sqlx::query(
        "INSERT INTO notification_invitations(hash,name,applications,expires_at) VALUES(?,?,?,?)",
    )
    .bind(hash(&token))
    .bind(&body.name)
    .bind(serde_json::to_string(&body.applications)?)
    .bind(now() + 1800)
    .execute(&s.db)
    .await?;
    s.notifications
        .audit(&actor, "sender.invited", &body.name)
        .await?;
    Ok(Json(json!({"invitation":token,"expires_at":now()+1800})))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Registration {
    invitation: String,
    registration_id: String,
    credential: String,
}
// Clients generate and persist the credential before redemption. A lost HTTP reply
// can be retried without storing a recoverable copy of the sender secret on Store.
async fn register_sender(
    State(s): State<AppState>,
    Json(body): Json<Registration>,
) -> Result<Json<Value>> {
    broker(&s)?;
    identifier(&body.registration_id)?;
    validate_secret(&body.credential)?;
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    let row = sqlx::query("SELECT * FROM notification_invitations WHERE hash=? AND expires_at>?")
        .bind(hash(&body.invitation))
        .bind(now())
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(Error::denied)?;
    let credential_hash = hash(&body.credential);
    if let Some(id) = row.get::<Option<String>, _>("sender_id") {
        if row.get::<Option<String>, _>("registration_id").as_deref() != Some(&body.registration_id)
            || row.get::<Option<String>, _>("credential_hash").as_deref() != Some(&credential_hash)
        {
            return Err(Error::denied());
        }
        return Ok(Json(json!({"sender_id":id})));
    }
    let id = Uuid::new_v4().to_string();
    sqlx::query("INSERT INTO notification_senders(id,name,applications) VALUES(?,?,?)")
        .bind(&id)
        .bind(row.get::<String, _>("name"))
        .bind(row.get::<String, _>("applications"))
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO notification_credentials(hash,sender_id) VALUES(?,?)")
        .bind(&credential_hash)
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE notification_invitations SET sender_id=?,registration_id=?,credential_hash=? WHERE hash=?").bind(&id).bind(body.registration_id).bind(credential_hash).bind(hash(&body.invitation)).execute(&mut *tx).await?;
    tx.commit().await?;
    s.notifications.audit(&id, "sender.registered", &id).await?;
    Ok(Json(json!({"sender_id":id})))
}
async fn manifest(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Manifest>,
) -> Result<Json<Value>> {
    let b = broker(&s)?;
    let id = b.sender(bearer(&h)?).await?;
    let apps: String = sqlx::query_scalar(
        "SELECT applications FROM notification_senders WHERE id=? AND enabled=1",
    )
    .bind(&id)
    .fetch_one(&s.db)
    .await?;
    body.validate(&serde_json::from_str::<Vec<Application>>(&apps)?)?;
    sqlx::query(
        "UPDATE notification_senders SET manifest=?,policy_version=policy_version+1 WHERE id=?",
    )
    .bind(serde_json::to_string(&body)?)
    .bind(&id)
    .execute(&s.db)
    .await?;
    b.audit(&id, "manifest.updated", &id).await?;
    Ok(Json(json!({"ok":true})))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Rotation {
    request_id: String,
    credential: String,
}
async fn rotate(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Rotation>,
) -> Result<Json<Value>> {
    let b = broker(&s)?;
    let id = b.sender(bearer(&h)?).await?;
    rotate_credential(&s, &id, body).await
}
async fn admin_rotate(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<Rotation>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    if id == "lellostore" {
        return Err(Error::denied());
    }
    let result = rotate_credential(&s, &id, body).await?;
    s.notifications
        .audit(&actor, "credential.rotated", &id)
        .await?;
    Ok(result)
}
async fn rotate_credential(s: &AppState, id: &str, body: Rotation) -> Result<Json<Value>> {
    identifier(&body.request_id)?;
    validate_secret(&body.credential)?;
    let next = hash(&body.credential);
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    if let Some(previous) = sqlx::query_scalar::<_, String>(
        "SELECT credential_hash FROM notification_rotations WHERE sender_id=? AND request_id=?",
    )
    .bind(&id)
    .bind(&body.request_id)
    .fetch_optional(&mut *tx)
    .await?
    {
        if previous != next {
            return Err(Error::conflict("rotation request changed"));
        }
    } else {
        sqlx::query("UPDATE notification_credentials SET expires_at=CASE WHEN expires_at IS NULL THEN ? ELSE min(expires_at,?) END WHERE sender_id=?").bind(now()+86400).bind(now()+86400).bind(&id).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO notification_credentials(hash,sender_id) VALUES(?,?)")
            .bind(&next)
            .bind(&id)
            .execute(&mut *tx)
            .await?;
        sqlx::query("INSERT INTO notification_rotations VALUES(?,?,?)")
            .bind(&id)
            .bind(&body.request_id)
            .bind(next)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    Ok(Json(json!({"sender_id":id})))
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
            "SELECT issuer,subject,credential_hash,revoked FROM notification_devices WHERE id=?",
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
        sqlx::query("INSERT INTO notification_devices(id,credential_hash,issuer,subject,package,created_at) VALUES(?,?,?,?,?,?)").bind(&body.installation).bind(hash(&body.credential)).bind(&c.iss).bind(&c.sub).bind(&body.package).bind(now()).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO notification_subscriptions(id,sender_id,device_id,package,certificate,component,installation,generation,subject,lease_until,confirmed) VALUES(?,'lellostore',?,?,'','self',?,?,?,9223372036854775807,1)")
            .bind(format!("store:{}",body.installation)).bind(&body.installation).bind(&body.package).bind(&body.installation).bind(&body.installation).bind(&c.sub).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    Ok(Json(
        json!({"device_id":body.installation,"issuer":c.iss,"subject":c.sub}),
    ))
}
async fn revoke_device(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Value>> {
    // A device credential can only revoke itself, including after user logout.
    let (id, _, _) = broker(&s)?.device(bearer(&h)?).await?;
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    sqlx::query("UPDATE notification_devices SET revoked=1 WHERE id=?")
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE notification_subscriptions SET revoked=1 WHERE device_id=?")
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE notification_deliveries SET state='revoked',envelope='',bytes=0 WHERE subscription_id IN (SELECT id FROM notification_subscriptions WHERE device_id=?) AND state='pending'").bind(&id).execute(&mut *tx).await?;
    tx.commit().await?;
    s.notifications.wake().await;
    Ok(Json(json!({"ok":true})))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Enrollment {
    package: String,
    certificate: String,
    component: String,
    installation: String,
    generation: String,
}
async fn enrollment(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Enrollment>,
) -> Result<Json<Value>> {
    let (device, _) = owned_device(&s, &h).await?;
    for v in [
        &body.package,
        &body.component,
        &body.installation,
        &body.generation,
    ] {
        identifier(v)?;
    }
    if !body.component.starts_with(&format!("{}/", body.package)) {
        return Err(Error::bad("component outside package"));
    }
    let token = secret();
    sqlx::query("INSERT INTO notification_enrollments(proof_hash,device_id,package,certificate,component,installation,generation,expires_at) VALUES(?,?,?,?,?,?,?,?)")
        .bind(hash(&token)).bind(device).bind(body.package).bind(body.certificate).bind(body.component).bind(body.installation).bind(body.generation).bind(now()+300).execute(&s.db).await?;
    Ok(Json(json!({"proof":token,"expires_at":now()+300})))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Subscription {
    proof: String,
    issuer: String,
    subject: String,
}
async fn subscribe(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Subscription>,
) -> Result<Json<Value>> {
    let b = broker(&s)?;
    let sender = b.sender(bearer(&h)?).await?;
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    let proof=sqlx::query("SELECT e.*,d.issuer,d.subject FROM notification_enrollments e JOIN notification_devices d ON d.id=e.device_id WHERE proof_hash=? AND expires_at>? AND d.revoked=0")
        .bind(hash(&body.proof)).bind(now()).fetch_optional(&mut *tx).await?.ok_or_else(Error::denied)?;
    if body.issuer != proof.get::<String, _>("issuer")
        || body.subject != proof.get::<String, _>("subject")
    {
        return Err(Error::denied());
    }
    let package: String = proof.get("package");
    let cert: String = proof.get("certificate");
    let apps: String = sqlx::query_scalar(
        "SELECT applications FROM notification_senders WHERE id=? AND enabled=1",
    )
    .bind(&sender)
    .fetch_one(&mut *tx)
    .await?;
    if !serde_json::from_str::<Vec<Application>>(&apps)?
        .iter()
        .any(|a| a.package == package && a.certificates.contains(&cert))
    {
        return Err(Error::denied());
    }
    let device: String = proof.get("device_id");
    if let Some(id) = proof.get::<Option<String>, _>("subscription_id") {
        let valid:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM notification_subscriptions WHERE id=? AND sender_id=? AND revoked=0)").bind(&id).bind(&sender).fetch_one(&mut *tx).await?;
        if !valid {
            return Err(Error::denied());
        }
        return Ok(Json(json!({"subscription_id":id,"application":package})));
    }
    let installation: String = proof.get("installation");
    let generation: String = proof.get("generation");
    sqlx::query("UPDATE notification_subscriptions SET revoked=1 WHERE device_id=? AND package=? AND sender_id=? AND (installation<>? OR generation<>?)")
        .bind(&device).bind(&package).bind(&sender).bind(&installation).bind(&generation).execute(&mut *tx).await?;
    let existing:Option<String>=sqlx::query_scalar("SELECT id FROM notification_subscriptions WHERE device_id=? AND package=? AND sender_id=? AND installation=? AND generation=? AND revoked=0")
        .bind(&device).bind(&package).bind(&sender).bind(&installation).bind(&generation).fetch_optional(&mut *tx).await?;
    if existing.is_none() {
        let count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM notification_subscriptions WHERE device_id=? AND revoked=0",
        )
        .bind(&device)
        .fetch_one(&mut *tx)
        .await?;
        if count >= 64 {
            return Err(Error::full());
        }
    }
    let id = existing.unwrap_or_else(|| Uuid::new_v4().to_string());
    sqlx::query("INSERT INTO notification_subscriptions(id,sender_id,device_id,package,certificate,component,installation,generation,subject,lease_until) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET lease_until=excluded.lease_until")
        .bind(&id).bind(&sender).bind(device).bind(&package).bind(cert).bind(proof.get::<String,_>("component")).bind(installation).bind(generation).bind(body.subject).bind(now()+300).execute(&mut *tx).await?;
    sqlx::query("UPDATE notification_enrollments SET subscription_id=? WHERE proof_hash=?")
        .bind(&id)
        .bind(hash(&body.proof))
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    b.wake().await;
    Ok(Json(
        json!({"subscription_id":id,"application":package,"lease_until":now()+300}),
    ))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Renew {
    subscriptions: Vec<String>,
}
async fn renew(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Renew>,
) -> Result<Json<Value>> {
    let b = broker(&s)?;
    let sender = b.sender(bearer(&h)?).await?;
    if body.subscriptions.len() > 1000 {
        return Err(Error::bad("batch exceeds 1000"));
    }
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    for id in body.subscriptions {
        if sqlx::query("UPDATE notification_subscriptions SET lease_until=? WHERE id=? AND sender_id=? AND revoked=0")
            .bind(now()+300).bind(id).bind(&sender).execute(&mut *tx).await?.rows_affected()!=1 {return Err(Error::denied());}
    }
    tx.commit().await?;
    b.wake().await;
    Ok(Json(json!({"lease_until":now()+300})))
}
async fn confirm(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<Enrollment>,
) -> Result<Json<Value>> {
    let (device, _) = owned_device(&s, &h).await?;
    if sqlx::query("UPDATE notification_subscriptions SET confirmed=1 WHERE id=? AND device_id=? AND package=? AND certificate=? AND component=? AND installation=? AND generation=? AND revoked=0").bind(id).bind(device).bind(body.package).bind(body.certificate).bind(body.component).bind(body.installation).bind(body.generation).execute(&s.db).await?.rows_affected()!=1 {return Err(Error::denied());}
    s.notifications.wake().await;
    Ok(Json(json!({"ok":true})))
}
async fn revoke_subscription(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Value>> {
    let b = broker(&s)?;
    let authorized = if h.contains_key("x-device-credential") {
        let (device, _) = owned_device(&s, &h).await?;
        sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS(SELECT 1 FROM notification_subscriptions WHERE id=? AND device_id=?)",
        )
        .bind(&id)
        .bind(device)
        .fetch_one(&s.db)
        .await?
    } else {
        let sender = b.sender(bearer(&h)?).await?;
        sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS(SELECT 1 FROM notification_subscriptions WHERE id=? AND sender_id=?)",
        )
        .bind(&id)
        .bind(sender)
        .fetch_one(&s.db)
        .await?
    };
    if !authorized {
        return Err(Error::denied());
    }
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    sqlx::query("UPDATE notification_subscriptions SET revoked=1 WHERE id=?")
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE notification_deliveries SET state='revoked',envelope='',bytes=0 WHERE subscription_id=? AND state='pending'").bind(&id).execute(&mut *tx).await?;
    tx.commit().await?;
    b.wake().await;
    Ok(Json(json!({"ok":true})))
}
async fn publish(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(body): Json<Publication>,
) -> Result<Json<Value>> {
    let b = broker(&s)?;
    let sender = b.sender(bearer(&h)?).await?;
    Ok(Json(b.publish(&sender, &body).await?))
}
async fn outcome(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Value>> {
    let b = broker(&s)?;
    let sender = b.sender(bearer(&h)?).await?;
    Ok(Json(b.outcome(&sender, &id).await?))
}
async fn overview(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Value>> {
    admin(&s, &h).await?;
    let rows=sqlx::query("SELECT s.*, (SELECT count(*) FROM notification_deliveries n WHERE n.sender_id=s.id AND n.state='pending') AS pending,(SELECT coalesce(sum(bytes),0) FROM notification_deliveries n WHERE n.sender_id=s.id AND n.state='pending') AS queued_bytes FROM notification_senders s ORDER BY name").fetch_all(&s.db).await?;
    let senders:Vec<Value>=rows.iter().map(|r| json!({"id":r.get::<String,_>("id"),"name":r.get::<String,_>("name"),"enabled":r.get::<bool,_>("enabled"),"applications":serde_json::from_str::<Value>(r.get("applications")).unwrap_or(Value::Null),"manifest":serde_json::from_str::<Value>(r.get("manifest")).unwrap_or(Value::Null),"overrides":serde_json::from_str::<Value>(r.get("overrides")).unwrap_or(Value::Null),"max_pending":r.get::<i64,_>("max_pending"),"max_bytes":r.get::<i64,_>("max_bytes"),"rate":r.get::<f64,_>("rate"),"burst":r.get::<i64,_>("burst"),"pending":r.get::<i64,_>("pending"),"queued_bytes":r.get::<i64,_>("queued_bytes")})).collect();
    let states =
        sqlx::query("SELECT state,count(*) AS count FROM notification_deliveries GROUP BY state")
            .fetch_all(&s.db)
            .await?;
    Ok(Json(
        json!({"senders":senders,"connections":s.notifications.connections.lock().await.len(),"outcomes":states.iter().map(|r|json!({"state":r.get::<String,_>("state"),"count":r.get::<i64,_>("count")})).collect::<Vec<_>>()}),
    ))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SenderUpdate {
    enabled: bool,
    applications: Vec<Application>,
    overrides: Vec<Rule>,
    max_pending: i64,
    max_bytes: i64,
    rate: f64,
    burst: i64,
}
async fn admin_sender(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    Json(mut body): Json<SenderUpdate>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    if id != "lellostore" {
        validate_apps(&body.applications)?;
    } else {
        body.applications = vec![
            Application {
                package: "com.lelloman.store".into(),
                certificates: vec![],
            },
            Application {
                package: "com.lelloman.store.debug".into(),
                certificates: vec![],
            },
        ];
    }
    validate_rules(&body.overrides, &body.applications)?;
    if !(1..=1000000).contains(&body.max_pending)
        || !(16384..=1073741824).contains(&body.max_bytes)
        || !(0.1..=1000.0).contains(&body.rate)
        || !(1..=10000).contains(&body.burst)
    {
        return Err(Error::bad("invalid quota"));
    }
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    sqlx::query("UPDATE notification_senders SET enabled=?,applications=?,overrides=?,policy_version=policy_version+1,max_pending=?,max_bytes=?,rate=?,burst=? WHERE id=?")
        .bind(body.enabled).bind(serde_json::to_string(&body.applications)?).bind(serde_json::to_string(&body.overrides)?).bind(body.max_pending).bind(body.max_bytes).bind(body.rate).bind(body.burst).bind(&id).execute(&mut *tx).await?;
    if id != "lellostore" {
        let subscriptions=sqlx::query("SELECT id,package,certificate FROM notification_subscriptions WHERE sender_id=? AND revoked=0").bind(&id).fetch_all(&mut *tx).await?;
        for subscription in subscriptions {
            let package: String = subscription.get("package");
            let certificate: String = subscription.get("certificate");
            if !body
                .applications
                .iter()
                .any(|a| a.package == package && a.certificates.contains(&certificate))
            {
                let sub: String = subscription.get("id");
                sqlx::query("UPDATE notification_subscriptions SET revoked=1 WHERE id=?")
                    .bind(&sub)
                    .execute(&mut *tx)
                    .await?;
                sqlx::query("UPDATE notification_deliveries SET state='revoked',envelope='',bytes=0 WHERE subscription_id=? AND state='pending'").bind(&sub).execute(&mut *tx).await?;
            }
        }
    }
    tx.commit().await?;
    s.notifications.audit(&actor, "sender.updated", &id).await?;
    s.notifications.wake().await;
    Ok(Json(json!({"ok":true})))
}
async fn cancel_pending(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    sqlx::query("UPDATE notification_deliveries SET state='cancelled',envelope='',bytes=0 WHERE sender_id=? AND state='pending'").bind(&id).execute(&s.db).await?;
    s.notifications
        .audit(&actor, "queue.cancelled", &id)
        .await?;
    s.notifications.wake().await;
    Ok(Json(json!({"ok":true})))
}
async fn revoke_sender(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Value>> {
    let actor = admin(&s, &h).await?;
    if id == "lellostore" {
        return Err(Error::bad("built-in sender can be suspended, not revoked"));
    }
    let mut tx = s.db.begin_with("BEGIN IMMEDIATE").await?;
    sqlx::query("UPDATE notification_senders SET enabled=0 WHERE id=?")
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE notification_credentials SET revoked=1 WHERE sender_id=?")
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE notification_subscriptions SET revoked=1 WHERE sender_id=?")
        .bind(&id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE notification_deliveries SET state='revoked',envelope='',bytes=0 WHERE sender_id=? AND state='pending'").bind(&id).execute(&mut *tx).await?;
    tx.commit().await?;
    s.notifications.audit(&actor, "sender.revoked", &id).await?;
    s.notifications.wake().await;
    Ok(Json(json!({"ok":true})))
}
