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
    if !ws.requested_protocols().any(|v| v == "lellostore.push.v1") {
        return Err(Error::bad("notification subprotocol required"));
    }
    let shutdown = state.catalog_events.shutdown.clone();
    let guard = state
        .catalog_events
        .admit_connection()
        .ok_or_else(Error::denied)?;
    Ok(ws.protocols(["lellostore.push.v1"]).max_message_size(65536).max_frame_size(65536).on_upgrade(move |mut socket|async move {
        let _guard=guard;
        let epoch=uuid::Uuid::new_v4().to_string();
        // A new socket only displaces an existing one after user authentication.
        let first=tokio::time::timeout(Duration::from_secs(10),socket.recv()).await;
        let Ok(Some(Ok(Message::Text(text))))=first else {return;};
        let Ok(frame)=serde_json::from_str::<Value>(&text) else {return;};
        let Some(token)=frame.get("access_token").and_then(Value::as_str) else {return;};
        let Ok(c)=claims(&state,token).await else {return;};
        if frame["kind"]!="authenticate" || c.iss!=issuer || c.sub!=subject {return;}
        let connection=Connection {epoch:epoch.clone(),wake:Arc::new(Notify::new()),expires:c.exp as i64};
        if let Some(old)=b.connections.lock().await.insert(device.clone(),connection.clone()) {old.wake.notify_one();}
        let work=serve(&state,&mut socket,&device,&issuer,&subject,c.exp as i64,&connection);
        tokio::select! { _=shutdown.requested()=>{}, result=work=>{if let Err(e)=result {tracing::debug!(status=%e.0,"Notification stream ended");}} }
        {
            let mut connections=b.connections.lock().await;
            if connections.get(&device).is_some_and(|c|c.epoch==epoch) { connections.remove(&device); }
        }
        // Never hold the connections mutex while waiting for SQLite: publishers use both.
        if let Err(e)=sqlx::query("UPDATE push_messages SET state='expired',payload=X'' WHERE state='pending' AND connection_epoch=?")
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
    send(
        socket,
        json!({"kind":"ready","expires_at":expires,"issuer":issuer,"subject":subject}),
    )
    .await?;
    let mut tick = tokio::time::interval(Duration::from_secs(5));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut last_incoming = tokio::time::Instant::now();
    let mut sent: HashMap<String, (i64, i64)> = HashMap::new();
    let mut routes_before = String::new();
    let mut catalog_before = 0;
    loop {
        if expires <= now() || last_incoming.elapsed() > Duration::from_secs(2100) {
            return Ok(());
        }
        if b.connections
            .lock()
            .await
            .get(device)
            .is_none_or(|c| c.epoch != connection.epoch)
        {
            return Ok(());
        }
        let active: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM push_devices WHERE id=? AND revoked=0)",
        )
        .bind(device)
        .fetch_one(&b.db)
        .await?;
        if !active {
            return Ok(());
        }
        let rows=sqlx::query("SELECT r.id,r.token,s.enabled,(r.revoked OR k.revoked) AS revoked FROM push_subscriptions r JOIN push_keys k ON k.key=r.vapid JOIN push_senders s ON s.id=k.sender_id WHERE r.device_id=? ORDER BY r.id").bind(device).fetch_all(&b.db).await?;
        let routes:Vec<Value>=rows.iter().map(|r|json!({"id":r.get::<String,_>("id"),"token":r.get::<String,_>("token"),"enabled":r.get::<bool,_>("enabled"),"revoked":r.get::<bool,_>("revoked")})).collect();
        let fingerprint = super::hash(&serde_json::to_string(&routes)?);
        if fingerprint != routes_before {
            send(socket, json!({"kind":"routes_begin"})).await?;
            for chunk in routes.chunks(64) {
                send(socket, json!({"kind":"routes","routes":chunk})).await?;
            }
            send(socket, json!({"kind":"routes_end"})).await?;
            routes_before = fingerprint;
        }
        let revision = b
            .catalog_revision
            .load(std::sync::atomic::Ordering::Relaxed);
        if catalog_before != revision {
            send(socket, json!({"kind":"catalog_changed"})).await?;
            catalog_before = revision;
        }
        let pending = b.pending(device, &connection.epoch).await?;
        let ids: Vec<String> = pending
            .iter()
            .filter_map(|v| v["id"].as_str().map(str::to_owned))
            .collect();
        sent.retain(|id, _| ids.contains(id));
        for message in pending {
            if expires <= now() {
                return Ok(());
            }
            let id = message["id"]
                .as_str()
                .ok_or_else(|| Error::bad("invalid message"))?
                .to_owned();
            let immediate = message["immediate"].as_bool() == Some(true);
            if sent
                .get(&id)
                .is_none_or(|(time, delay)| !immediate && now() - time >= *delay)
            {
                send(socket, json!({"kind":"delivery","message":message})).await?;
                let delay = sent.get(&id).map_or(30, |(_, d)| (d * 2).min(300));
                sent.insert(id, (now(), delay));
                if immediate {
                    sqlx::query("UPDATE push_messages SET state='dispatched',payload=X'' WHERE id=? AND state='pending'").bind(message["id"].as_str()).execute(&b.db).await?;
                }
            }
        }
        tokio::select! {
         _=tick.tick()=>{},
         _=tokio::time::sleep(Duration::from_secs((expires-now()).max(0) as u64))=>return Ok(()),
         _=connection.wake.notified()=>{},
         incoming=socket.recv()=>{
          last_incoming=tokio::time::Instant::now();
          match incoming {
           Some(Ok(Message::Text(text)))=>{
            let frame:Value=serde_json::from_str(&text)?;
            match frame["kind"].as_str() {
             Some("ping")=>send(socket,json!({"kind":"pong","nonce":frame["nonce"]})).await?,
             Some("authenticate")=>{
              let c=claims(state,frame["access_token"].as_str().ok_or_else(Error::unauthorized)?).await?;
              if c.iss!=issuer || c.sub!=subject {return Err(Error::denied());}
              expires=c.exp as i64;
              if let Some(active)=b.connections.lock().await.get_mut(device) {if active.epoch==connection.epoch {active.expires=expires;}}
              send(socket,json!({"kind":"authenticated","expires_at":expires})).await?;
             },
             Some("receipt")=>{
              let id=frame["id"].as_str().ok_or_else(||Error::bad("id required"))?;
              let token=frame["token"].as_str().ok_or_else(||Error::bad("token required"))?;
              b.receipt(device,id,token).await?;
              send(socket,json!({"kind":"receipt_ack","id":id})).await?;
             },
             _=>return Err(Error::bad("unknown frame")),
            }
           },
           Some(Ok(Message::Ping(bytes)))=>{socket.send(Message::Pong(bytes)).await.map_err(|_|Error::bad("socket closed"))?;},
           Some(Ok(Message::Pong(_)))=>{},
           _=>return Ok(()),
          }
         }
        }
    }
}
