use super::{
    api::{bearer, broker, claims},
    now, Connection, Error, Result,
};
use crate::api::AppState;
use serde_json::{json, Value};
use simple_server::web::{
    extract::State,
    http::HeaderMap,
    response::{IntoResponse, Response},
    ws::{Message, WebSocket, WebSocketUpgrade},
};
use sqlx::Row;
use std::{collections::HashMap, sync::Arc, time::Duration};
use tokio::sync::Notify;

pub async fn upgrade(
    State(state): State<AppState>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Result<Response> {
    let b = broker(&state)?;
    let credential = bearer(&headers)?;
    let (device, issuer, subject) = b.device(credential).await?;
    if !ws
        .requested_protocols()
        .any(|v| v == "lellostore.notifications.v1")
    {
        return Err(Error::bad("notification subprotocol required"));
    }
    let shutdown = state.catalog_events.shutdown.clone();
    let guard = state
        .catalog_events
        .admit_connection()
        .ok_or_else(Error::denied)?;
    Ok(ws.protocols(["lellostore.notifications.v1"]).max_message_size(65536).max_frame_size(65536).on_upgrade(move |mut socket|async move {
        let _guard=guard;
        let epoch=uuid::Uuid::new_v4().to_string();
        // A new socket only displaces an existing one after user authentication.
        let first=tokio::time::timeout(Duration::from_secs(10),socket.recv()).await;
        let Ok(Some(Ok(Message::Text(text))))=first else {return;};
        let Ok(frame)=serde_json::from_str::<Value>(&text) else {return;};
        let Some(token)=frame.get("access_token").and_then(Value::as_str) else {return;};
        let Ok(c)=claims(&state,token).await else {return;};
        if c.iss!=issuer || c.sub!=subject {return;}
        let connection=Connection {epoch:epoch.clone(),wake:Arc::new(Notify::new())};
        if let Some(old)=b.connections.lock().await.insert(device.clone(),connection.clone()) {old.wake.notify_one();}
        let work=serve(&state,&mut socket,&device,&issuer,&subject,c.exp as i64,&connection);
        tokio::select! { _=shutdown.requested()=>{}, result=work=>{if let Err(e)=result {tracing::debug!(status=%e.0,"Notification stream ended");}} }
        {
            let mut connections=b.connections.lock().await;
            if connections.get(&device).is_some_and(|c|c.epoch==epoch) { connections.remove(&device); }
        }
        // Never hold the connections mutex while waiting for SQLite: publishers use both.
        if let Err(e)=sqlx::query("UPDATE notification_deliveries SET state='offline',envelope='',bytes=0 WHERE online_only=1 AND state='pending' AND connection_epoch=?")
            .bind(&epoch).execute(&b.db).await {tracing::error!(error=%e,"Cannot finalize online-only deliveries");}
        let _=tokio::time::timeout(Duration::from_secs(2),socket.send(Message::Close(None))).await;
    }).into_response())
}
async fn send(socket: &mut WebSocket, value: Value) -> Result<()> {
    tokio::time::timeout(
        Duration::from_secs(10),
        socket.send(Message::Text(value.to_string().into())),
    )
    .await
    .map_err(|_| Error::bad("socket write timeout"))?
    .map_err(|_| Error::bad("socket closed"))
}
#[allow(clippy::too_many_arguments)]
async fn serve(
    state: &AppState,
    socket: &mut WebSocket,
    device: &str,
    issuer: &str,
    subject: &str,
    mut expires: i64,
    connection: &Connection,
) -> Result<()> {
    let b = &state.notifications;
    send(socket,json!({"kind":"ready","expires_at":expires,"device_id":device,"issuer":issuer,"subject":subject})).await?;
    // The catalog is reconstructible state: always repair hints missed across a restart.
    if b.catalog().await.is_err() { tracing::debug!("Catalog notification reconciliation deferred"); }
    let mut tick = tokio::time::interval(Duration::from_secs(15));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut last_incoming = tokio::time::Instant::now();
    let mut sent: HashMap<String, i64> = HashMap::new();
    let mut routes_before = String::new();
    let mut snapshots_before = String::new();
    loop {
        if expires <= now() || last_incoming.elapsed() > Duration::from_secs(2100) {
            return Ok(());
        }
        if !b
            .connections
            .lock()
            .await
            .get(device)
            .is_some_and(|c| c.epoch == connection.epoch)
        {
            return Ok(());
        }
        let active: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM notification_devices WHERE id=? AND revoked=0)",
        )
        .bind(device)
        .fetch_one(&b.db)
        .await?;
        if !active {
            return Ok(());
        }
        let rows=sqlx::query("SELECT s.id,s.package,s.generation,s.installation,s.component,s.certificate FROM notification_subscriptions s JOIN notification_senders p ON p.id=s.sender_id WHERE device_id=? AND s.revoked=0 AND s.confirmed=1 AND s.lease_until>? AND p.enabled=1 ORDER BY s.id").bind(device).bind(now()).fetch_all(&b.db).await?;
        let routes = json!({"kind":"routes","routes":rows.iter().map(|r|json!({"subscription_id":r.get::<String,_>("id"),"package":r.get::<String,_>("package"),"generation":r.get::<String,_>("generation"),"installation":r.get::<String,_>("installation"),"component":r.get::<String,_>("component"),"certificate":r.get::<String,_>("certificate")})).collect::<Vec<_>>()});
        let encoded = routes.to_string();
        if encoded != routes_before {
            send(socket, routes).await?;
            routes_before = encoded;
        }
        let snapshot_rows=sqlx::query("SELECT w.*,s.generation,s.installation,s.sender_id FROM notification_watermarks w JOIN notification_subscriptions s ON s.id=w.subscription_id JOIN notification_senders p ON p.id=s.sender_id WHERE s.device_id=? AND s.revoked=0 AND s.confirmed=1 AND s.lease_until>? AND p.enabled=1 ORDER BY s.id,w.type,w.replacement_key").bind(device).bind(now()).fetch_all(&b.db).await?;
        let snapshots:Vec<Value>=snapshot_rows.iter().map(|r|json!({"subscription_id":r.get::<String,_>("subscription_id"),"sender_id":r.get::<String,_>("sender_id"),"generation":r.get::<String,_>("generation"),"installation":r.get::<String,_>("installation"),"type":r.get::<String,_>("type"),"replacement_key":r.get::<String,_>("replacement_key"),"occurrence":r.get::<i64,_>("occurrence"),"revision":r.get::<i64,_>("revision"),"expired":r.get::<Option<i64>,_>("expires_at").is_some_and(|t|t<=now())})).collect();
        let fingerprint = super::hash(&serde_json::to_string(&snapshots)?);
        if fingerprint != snapshots_before {
            for chunk in snapshots.chunks(32) {
                send(socket, json!({"kind":"snapshots","states":chunk})).await?;
            }
            snapshots_before = fingerprint;
        }
        let pending = b.pending(device).await?;
        let ids: Vec<String> = pending
            .iter()
            .filter_map(|v| v["delivery_id"].as_str().map(str::to_owned))
            .collect();
        sent.retain(|id, _| ids.contains(id));
        for envelope in pending {
            let id = envelope["delivery_id"]
                .as_str()
                .ok_or_else(|| Error::bad("invalid stored delivery"))?
                .to_owned();
            if sent.get(&id).is_none_or(|t| now() - t >= 300) {
                send(socket, json!({"kind":"delivery","envelope":envelope})).await?;
                sent.insert(id, now());
            }
        }
        tokio::select! {
            _=tick.tick()=>{},
            _=connection.wake.notified()=>{},
            incoming=socket.recv()=>{
                last_incoming=tokio::time::Instant::now();
                match incoming {
                    Some(Ok(Message::Text(text)))=>{
                        let frame:Value=serde_json::from_str(&text)?;
                        match frame["kind"].as_str() {
                            Some("ping")=>send(socket,json!({"kind":"pong","nonce":frame["nonce"],"server_time":now()})).await?,
                            Some("authenticate")=>{
                                let c=claims(state,frame["access_token"].as_str().ok_or_else(Error::unauthorized)?).await?;
                                if c.iss!=issuer || c.sub!=subject {return Err(Error::denied());}
                                expires=c.exp as i64;
                                send(socket,json!({"kind":"authenticated","expires_at":expires})).await?;
                            },
                            Some("receipt")=>{
                                let id=frame["delivery_id"].as_str().ok_or_else(||Error::bad("missing delivery_id"))?;
                                let result=b.receipt(device,id,frame["presentation"].as_str()).await;
                                if let Err(e)=result {if e.0!=simple_server::web::http::StatusCode::CONFLICT {return Err(e);}}
                                sent.remove(id);
                                send(socket,json!({"kind":"receipt_ack","delivery_id":id,"presentation":frame["presentation"]})).await?;
                            },
                            _=>return Err(Error::bad("unknown frame kind")),
                        }
                    },
                    Some(Ok(Message::Ping(payload)))=>{socket.send(Message::Pong(payload)).await.map_err(|_|Error::bad("socket closed"))?;},
                    Some(Ok(Message::Pong(_)))=>{},
                    _=>return Ok(()),
                }
            }
        }
    }
}
