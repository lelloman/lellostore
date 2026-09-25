use super::AppState;
use crate::{
    db::paravoid::{Contract, Stream, VpkRelease},
    error::AppError,
    paravoid::MAX_INTEGER,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde_json::json;
use sha2::{Digest, Sha256};
use simple_server::axum::{
    body::Body,
    extract::{Path, RawQuery, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
};
use std::collections::{BTreeMap, HashSet};

struct Scope {
    contract: String,
    channel: String,
    sdk: i64,
    abis: Vec<String>,
}
impl Scope {
    fn parse(query: Option<String>) -> Result<Self, AppError> {
        let query =
            query.ok_or_else(|| AppError::BadRequest("Missing head request scope".into()))?;
        if query.len() > 4096 {
            return Err(AppError::BadRequest("Head query too long".into()));
        }
        let url = reqwest::Url::parse(&format!("https://scope.invalid/?{query}"))
            .map_err(|_| AppError::BadRequest("Invalid scope".into()))?;
        let mut fields = BTreeMap::new();
        for (key, value) in url.query_pairs() {
            if fields.insert(key.to_string(), value.to_string()).is_some() {
                return Err(AppError::BadRequest("Duplicate scope parameter".into()));
            }
        }
        let required = [
            "contract", "channel", "sdk", "abis", "runtime", "format", "protocol",
        ];
        if fields.len() != required.len() || required.iter().any(|key| !fields.contains_key(*key)) {
            return Err(AppError::BadRequest(
                "Unknown or missing scope parameter".into(),
            ));
        }
        if ["runtime", "format", "protocol"]
            .iter()
            .any(|k| fields[*k] != "1")
        {
            return Err(AppError::BadRequest("Unsupported Paravoid protocol".into()));
        }
        let sdk: i64 = fields["sdk"]
            .parse()
            .map_err(|_| AppError::BadRequest("Invalid SDK".into()))?;
        if sdk < 30 || sdk as u64 > MAX_INTEGER {
            return Err(AppError::BadRequest("Unsupported SDK".into()));
        }
        let abis: Vec<String> = if fields["abis"].is_empty() {
            vec![]
        } else {
            fields["abis"].split(',').map(str::to_owned).collect()
        };
        let unique: HashSet<_> = abis.iter().collect();
        if unique.len() != abis.len()
            || abis
                .iter()
                .any(|v| !["arm64-v8a", "armeabi-v7a", "x86", "x86_64"].contains(&v.as_str()))
        {
            return Err(AppError::BadRequest("Invalid ABI list".into()));
        }
        Ok(Self {
            contract: fields["contract"].clone(),
            channel: fields["channel"].clone(),
            sdk,
            abis,
        })
    }
}
async fn authorize(
    conn: &mut sqlx::SqliteConnection,
    contract: &Contract,
    headers: &HeaderMap,
) -> Result<(), AppError> {
    if contract.verification_state != "verified" {
        return Err(AppError::NotFound("Verified stream not found".into()));
    }
    if contract.authentication == "public" {
        return Ok(());
    }
    if headers.get_all(header::AUTHORIZATION).iter().count() != 1 {
        return Err(AppError::Unauthorized);
    }
    let key = headers
        .get(header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok())
        .and_then(|h| h.strip_prefix("Bearer "))
        .ok_or(AppError::Unauthorized)?;
    let decoded = URL_SAFE_NO_PAD
        .decode(key)
        .map_err(|_| AppError::Unauthorized)?;
    if decoded.len() != 32 || URL_SAFE_NO_PAD.encode(&decoded) != key {
        return Err(AppError::Unauthorized);
    }
    let hash = hex::encode(Sha256::digest(key.as_bytes()));
    let now = chrono::Utc::now().timestamp();
    let grant:Option<(String,String)>=sqlx::query_as("SELECT id,user_subject FROM paravoid_grants WHERE credential_sha256 = ? AND package_name = ? AND contract_id = ? AND revoked_at IS NULL AND (expires_at = 0 OR expires_at > ?)")
        .bind(hash).bind(&contract.package_name).bind(&contract.contract_id).bind(now).fetch_optional(&mut *conn).await?;
    let (id, user) = grant.ok_or(AppError::Forbidden)?;
    let allowed:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM user_app_grants WHERE user_subject = ? AND package_name = ? AND (access_level = 'beta' OR ? = 'stable')) OR EXISTS(SELECT 1 FROM user_app_group_memberships m JOIN app_groups ag ON ag.id = m.group_id LEFT JOIN app_group_grants g ON g.group_id = m.group_id AND g.package_name = ? WHERE m.user_subject = ? AND (ag.system_kind = 'all' OR g.access_level = 'beta' OR (g.access_level = 'stable' AND ? = 'stable')))")
        .bind(&user).bind(&contract.package_name).bind(&contract.channel).bind(&contract.package_name).bind(&user).bind(&contract.channel).fetch_one(&mut *conn).await?;
    if !allowed {
        return Err(AppError::Forbidden);
    }
    sqlx::query("UPDATE paravoid_grants SET last_used_at = ?, request_count = request_count + 1 WHERE id = ?").bind(now).bind(id).execute(conn).await?;
    Ok(())
}
fn headers_ok(headers: &HeaderMap) -> Result<(), AppError> {
    if let Some(encoding) = headers.get(header::ACCEPT_ENCODING) {
        if encoding.to_str().unwrap_or("") != "identity" {
            return Err(AppError::BadRequest(
                "Paravoid transfers require identity encoding".into(),
            ));
        }
    }
    Ok(())
}
pub async fn head(
    State(state): State<AppState>,
    Path(package): Path<String>,
    RawQuery(query): RawQuery,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    headers_ok(&headers)?;
    let scope = Scope::parse(query)?;
    let signing = state
        .paravoid_signing
        .as_ref()
        .ok_or_else(|| AppError::Config("Paravoid signing is not configured".into()))?;
    let mut tx = state.db.begin().await?;
    // Serialize head refresh and publication. Every scope at this revision uses
    // identical issued/expiry times and deterministic per-scope signed bytes.
    sqlx::query("UPDATE paravoid_streams SET revision = revision WHERE package_name = ? AND contract_id = ?").bind(&package).bind(&scope.contract).execute(&mut *tx).await?;
    let contract: Contract = sqlx::query_as(
        "SELECT * FROM paravoid_contracts WHERE package_name = ? AND contract_id = ?",
    )
    .bind(&package)
    .bind(&scope.contract)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| AppError::NotFound("Stream not found".into()))?;
    if contract.channel != scope.channel {
        return Err(AppError::BadRequest(
            "Channel differs from installed policy".into(),
        ));
    }
    authorize(&mut tx, &contract, &headers).await?;
    let policy = contract.policy()?;
    let mut stream: Stream =
        sqlx::query_as("SELECT * FROM paravoid_streams WHERE package_name = ? AND contract_id = ?")
            .bind(&package)
            .bind(&scope.contract)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| AppError::NotFound("Stream not published".into()))?;
    let now = chrono::Utc::now().timestamp();
    if now + 300 < stream.issued_at {
        return Err(AppError::Config(
            "Server clock precedes authenticated time".into(),
        ));
    }
    if stream.expires_at <= now {
        if stream.revision as u64 >= MAX_INTEGER {
            return Err(AppError::Config("Stream revision exhausted".into()));
        }
        stream.revision = (stream.revision + 1).max(policy.minimum_head_revision() as i64);
        stream.issued_at = now.max(stream.issued_at);
        stream.expires_at = stream.issued_at + 3600;
        sqlx::query("UPDATE paravoid_streams SET revision = ?, issued_at = ?, expires_at = ? WHERE package_name = ? AND contract_id = ?")
            .bind(stream.revision).bind(stream.issued_at).bind(stream.expires_at).bind(&package).bind(&scope.contract).execute(&mut *tx).await?;
    }
    let scope_key = json!({"sdk":scope.sdk,"abis":scope.abis}).to_string();
    let cached:Option<(Vec<u8>,String)>=sqlx::query_as("SELECT envelope,sha256 FROM paravoid_heads WHERE package_name = ? AND contract_id = ? AND revision = ? AND scope = ?")
        .bind(&package).bind(&scope.contract).bind(stream.revision).bind(&scope_key).fetch_optional(&mut *tx).await?;
    let (envelope, hash) = if let Some(cached) = cached {
        cached
    } else {
        let offers:Vec<VpkRelease>=sqlx::query_as("SELECT * FROM vpk_releases WHERE package_name = ? AND contract_id = ? AND publication_state = 'published' AND validation_state = 'verified' AND min_sdk <= ? AND (max_sdk = 0 OR max_sdk >= ?) ORDER BY payload_version DESC")
            .bind(&package).bind(&scope.contract).bind(scope.sdk).bind(scope.sdk).fetch_all(&mut *tx).await?;
        let mut selected = None;
        if stream.status == "active" {
            for offer in offers {
                if offer.payload_version < policy.minimum_payload_version() as i64 {
                    continue;
                }
                let abis: Vec<String> = serde_json::from_str(&offer.abis_json)
                    .map_err(|_| AppError::Internal("Invalid stored ABI inventory".into()))?;
                if abis.is_empty() || scope.abis.iter().any(|abi| abis.contains(abi)) {
                    selected = Some(offer);
                    break;
                }
            }
        }
        let status = if stream.status == "retired" {
            "shell-update-required"
        } else if selected.is_some() {
            "available"
        } else {
            "no-compatible-release"
        };
        let release=selected.map(|r|json!({"releaseId":r.release_id,"payloadVersion":r.payload_version,"manifestSha256":r.manifest_sha256,"archiveSha256":r.archive_sha256,"archiveSize":r.archive_size}));
        let body = json!({"version":1,"applicationId":package,"shellContractId":scope.contract,"channel":scope.channel,"sdk":scope.sdk,"abis":scope.abis,"runtimeAbi":1,"formatVersion":1,"headRevision":stream.revision,"issuedAt":stream.issued_at,"expiresAt":stream.expires_at,"status":status,"release":release});
        let envelope = signing
            .sign_head(&body, &contract.policy()?, scope.sdk as u64, &scope.abis)
            .map_err(|_| AppError::Config("Cannot sign a head trusted by this shell".into()))?;
        let hash = hex::encode(Sha256::digest(&envelope));
        sqlx::query("INSERT INTO paravoid_heads(package_name,contract_id,revision,scope,envelope,sha256,expires_at) VALUES (?,?,?,?,?,?,?)")
            .bind(&package).bind(&scope.contract).bind(stream.revision).bind(scope_key).bind(&envelope).bind(&hash).bind(stream.expires_at).execute(&mut *tx).await?;
        (envelope, hash)
    };
    tx.commit().await?;
    let etag = format!("\"{hash}\"");
    let unchanged = headers
        .get(header::IF_NONE_MATCH)
        .and_then(|h| h.to_str().ok())
        == Some(etag.as_str());
    let mut response = if unchanged {
        StatusCode::NOT_MODIFIED.into_response()
    } else {
        Response::new(Body::from(envelope))
    };
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    response.headers_mut().insert(
        header::ETAG,
        HeaderValue::from_str(&etag).map_err(|_| AppError::Internal("Invalid ETag".into()))?,
    );
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("private, no-cache"),
    );
    response
        .headers_mut()
        .insert(header::VARY, HeaderValue::from_static("Authorization"));
    Ok(response)
}
pub async fn download(
    State(state): State<AppState>,
    Path((package, id)): Path<(String, String)>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    headers_ok(&headers)?;
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE apps SET publication_revision = publication_revision WHERE package_name = ?",
    )
    .bind(&package)
    .execute(&mut *tx)
    .await?;
    // Retained published identities remain downloadable after withdrawal so
    // outstanding signed heads and already-authorized resumptions remain useful.
    let release:VpkRelease=sqlx::query_as("SELECT v.* FROM vpk_releases v JOIN published_vpk_identities p ON p.package_name = v.package_name AND p.release_id = v.release_id WHERE v.package_name = ? AND v.release_id = ? AND v.validation_state = 'verified'")
        .bind(&package).bind(&id).fetch_optional(&mut *tx).await?.ok_or_else(||AppError::NotFound("Payload not found".into()))?;
    let contract: Contract = sqlx::query_as(
        "SELECT * FROM paravoid_contracts WHERE package_name = ? AND contract_id = ?",
    )
    .bind(&package)
    .bind(&release.contract_id)
    .fetch_one(&mut *tx)
    .await?;
    authorize(&mut tx, &contract, &headers).await?;
    tx.commit().await?;
    super::file_response::serve_immutable_file(
        state.config.storage_path.join(&release.archive_path),
        "application/vnd.paravoid.vpk",
        format!("{}.vpk", release.release_id),
        &release.archive_sha256,
        release.archive_size as u64,
        &headers,
    )
    .await
}

/// Store adapter for the transport-neutral Paravoid update hint protocol.
/// Authentication uses the installed shell grant, independently of browser OIDC.
pub async fn push_events(
    State(state): State<AppState>,
    headers: HeaderMap,
    ws: simple_server::axum::extract::ws::WebSocketUpgrade,
) -> Response {
    let Some(guard) = state.catalog_events.admit_connection() else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    let mut changes = state.catalog_events.subscribe();
    let shutdown = state.catalog_events.shutdown.clone();
    ws.protocols(["paravoid.updates.v1"])
        .max_message_size(4096)
        .max_frame_size(4096)
        .on_upgrade(move |mut socket| async move {
            use simple_server::axum::extract::ws::Message;
            use std::time::Duration;
            let _guard = guard;
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields, rename_all = "camelCase")]
            struct Subscription {
                version: u32,
                #[serde(rename = "type")]
                kind: String,
                application_id: String,
                shell_contract_id: String,
                channel: String,
            }
            let subscription = tokio::select! {
                _ = shutdown.requested() => return,
                message = tokio::time::timeout(Duration::from_secs(15),socket.recv()) => {
                    let Ok(Some(Ok(Message::Text(text)))) = message else { return; };
                    let Ok(value) = serde_json::from_str::<Subscription>(&text) else { return; };
                    if value.version != 1 || value.kind != "subscribe" { return; }
                    value
                }
            };
            let mut interval = tokio::time::interval(Duration::from_secs(60));
            let mut announce = true;
            loop {
                // Recheck revocation and stream ownership before every hint and periodically.
                let authorized = async {
                    let mut conn = state.db.acquire().await?;
                    let contract: Contract = sqlx::query_as("SELECT * FROM paravoid_contracts WHERE package_name = ? AND contract_id = ?")
                        .bind(&subscription.application_id).bind(&subscription.shell_contract_id)
                        .fetch_optional(&mut *conn).await?
                        .ok_or(AppError::Forbidden)?;
                    if contract.channel != subscription.channel { return Err(AppError::Forbidden); }
                    authorize(&mut conn, &contract, &headers).await
                };
                let result = tokio::select! {
                    _ = shutdown.requested() => return,
                    result = authorized => result,
                };
                if result.is_err() { break; }
                if announce {
                    // Also sent on subscription/reconnect: missed messages need no replay log.
                    let payload = json!({"version":1,"type":"updates_changed",
                        "applicationId":subscription.application_id,"shellContractId":subscription.shell_contract_id,
                        "channel":subscription.channel,"eventId":uuid::Uuid::new_v4().to_string()}).to_string();
                    if !matches!(tokio::time::timeout(Duration::from_secs(10),socket.send(Message::Text(payload.into()))).await, Ok(Ok(()))) { break; }
                }
                announce = false;
                tokio::select! {
                    _ = shutdown.requested() => break,
                    _ = interval.tick() => {},
                    event = changes.recv() => {
                        if matches!(event,Err(tokio::sync::broadcast::error::RecvError::Closed)) { break; }
                        announce = true;
                    },
                    message = socket.recv() => match message {
                        Some(Ok(Message::Ping(payload))) => {
                            if !matches!(tokio::time::timeout(Duration::from_secs(10),socket.send(Message::Pong(payload))).await, Ok(Ok(()))) { break; }
                        },
                        Some(Ok(Message::Pong(_))) => {},
                        _ => break,
                    }
                }
            }
            let _ = tokio::time::timeout(Duration::from_secs(1),socket.send(Message::Close(None))).await;
        })
}
