//! Store-native device enrollment (docs/DEVICE_ENROLLMENT.md).
//!
//! `POST /api/device-enrollments` and `POST /api/device-enrollments/poll` are
//! unauthenticated: the device has no credential yet, and the secret
//! `device_code` is what authorizes the poll. Their errors use the RFC 8628
//! shape `{"error": "...", "error_description": "..."}`. The administrator
//! endpoints require `AdminUser` and use the usual error body.
use super::AppState;
use crate::{
    auth::AdminUser,
    db::device_enrollments::{self, ApproveRequest, PollOutcome, StartOutcome, StartRequest},
    error::AppError,
};
use serde::Deserialize;
use serde_json::json;
use simple_server::web::{
    extract::{ConnectInfo, Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Bytes, Json, RejectionResponse,
};
use std::net::{IpAddr, SocketAddr};

fn oauth_error(status: StatusCode, code: &str, description: &str) -> Response {
    (
        status,
        [(simple_server::web::http::header::CACHE_CONTROL, "no-store")],
        Json(json!({"error": code, "error_description": description})),
    )
        .into_response()
}

fn header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(str::trim)
        .filter(|value| !value.is_empty())
}

/// The address quotas and cooldowns are keyed on. A direct client is its TCP
/// peer. A peer on a loopback or private network is taken to be the reverse
/// proxy (the deployment's Caddy), and then the last `X-Forwarded-For` entry,
/// the one that proxy appended, is the client. Without connection info (the
/// in-process test router) the forwarded header is used as is.
fn source_ip(headers: &HeaderMap, peer: Option<IpAddr>) -> String {
    let forwarded = header(headers, "x-forwarded-for")
        .and_then(|value| value.rsplit(',').next())
        .map(str::trim)
        .and_then(|value| value.parse::<IpAddr>().ok());
    let address = match peer {
        Some(peer) if is_proxy(peer) => forwarded.unwrap_or(peer),
        Some(peer) => peer,
        None => match forwarded {
            Some(forwarded) => forwarded,
            None => return "unknown".into(),
        },
    };
    address.to_canonical().to_string()
}

fn is_proxy(address: IpAddr) -> bool {
    match address.to_canonical() {
        IpAddr::V4(v4) => v4.is_loopback() || v4.is_private() || v4.is_link_local(),
        IpAddr::V6(v6) => v6.is_loopback() || (v6.segments()[0] & 0xfe00) == 0xfc00,
    }
}

/// The store's public origin as the device reached it, which is what the
/// device's `device-token.json` must name. Taken from the forwarded or the
/// plain `Host` header; the scheme defaults to HTTPS, which the Android
/// client requires.
fn public_origin(headers: &HeaderMap) -> String {
    let host = header(headers, "x-forwarded-host")
        .or_else(|| header(headers, "host"))
        .and_then(|value| value.split(',').next())
        .map(str::trim)
        .filter(|host| {
            host.len() <= 255
                && host
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || ".-:[]".contains(c))
        })
        .unwrap_or("localhost");
    let scheme = match header(headers, "x-forwarded-proto")
        .and_then(|value| value.split(',').next())
        .map(str::trim)
    {
        Some("http") => "http",
        _ => "https",
    };
    format!("{scheme}://{}", host.to_ascii_lowercase())
}

pub async fn start(
    State(state): State<AppState>,
    headers: HeaderMap,
    peer: Result<ConnectInfo<SocketAddr>, RejectionResponse>,
    body: Bytes,
) -> Response {
    let request: StartRequest = match serde_json::from_slice(&body) {
        Ok(request) => request,
        Err(_) => {
            return oauth_error(
                StatusCode::BAD_REQUEST,
                "invalid_request",
                "The body must be a JSON object with a name",
            )
        }
    };
    let source = source_ip(&headers, peer.ok().map(|info| info.0.ip()));
    match device_enrollments::start(&state.db, request, &source, &public_origin(&headers)).await {
        Ok(StartOutcome::Started(started)) => {
            tracing::info!(source_ip = %source, "Device enrollment started");
            (
                StatusCode::CREATED,
                [(simple_server::web::http::header::CACHE_CONTROL, "no-store")],
                Json(started),
            )
                .into_response()
        }
        Ok(StartOutcome::RateLimited) => oauth_error(
            StatusCode::TOO_MANY_REQUESTS,
            "slow_down",
            "Too many enrollment requests; try again later",
        ),
        Ok(StartOutcome::Blocked) => oauth_error(
            StatusCode::FORBIDDEN,
            "access_denied",
            "An administrator recently denied an enrollment from this address",
        ),
        Err(AppError::BadRequest(message)) => {
            oauth_error(StatusCode::BAD_REQUEST, "invalid_request", &message)
        }
        Err(error) => error.into_response(),
    }
}

#[derive(Deserialize)]
pub struct PollRequest {
    device_code: String,
}

pub async fn poll(State(state): State<AppState>, headers: HeaderMap, body: Bytes) -> Response {
    let Ok(request) = serde_json::from_slice::<PollRequest>(&body) else {
        return oauth_error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "The body must be a JSON object with a device_code",
        );
    };
    match device_enrollments::poll(&state.db, &request.device_code, &public_origin(&headers)).await
    {
        Ok(PollOutcome::Issued(file)) => (
            [(simple_server::web::http::header::CACHE_CONTROL, "no-store")],
            Json(file),
        )
            .into_response(),
        Ok(outcome) => {
            let description = match outcome {
                PollOutcome::Pending => "Waiting for an administrator to approve the code",
                PollOutcome::SlowDown => "Polling too fast; add 5 seconds to the interval",
                PollOutcome::Expired => "The code expired; start a new enrollment",
                PollOutcome::Denied => "An administrator denied this enrollment",
                _ => "Unknown or already used device code",
            };
            oauth_error(
                StatusCode::BAD_REQUEST,
                outcome.error_code().unwrap_or("invalid_grant"),
                description,
            )
        }
        Err(error) => error.into_response(),
    }
}

pub async fn lookup(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(user_code): Path<String>,
) -> Result<Json<device_enrollments::PendingEnrollment>, AppError> {
    Ok(Json(
        device_enrollments::lookup(&state.db, &admin.0.subject, &user_code).await?,
    ))
}

pub async fn approve(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(user_code): Path<String>,
    Json(request): Json<ApproveRequest>,
) -> Result<Json<device_enrollments::Approved>, AppError> {
    let approved =
        device_enrollments::approve(&state.db, &admin.0.subject, &user_code, request).await?;
    if !approved.rekeyed && !approved.device_token.groups.is_empty() {
        state.catalog_events.notify_catalog_changed();
    }
    tracing::info!(
        token_id = %approved.device_token.id,
        rekeyed = approved.rekeyed,
        actor = %admin.0.subject,
        "Device enrollment approved"
    );
    Ok(Json(approved))
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DenyQuery {
    /// Refuse new enrollments from the same address for 24 hours (default).
    #[serde(default = "default_true")]
    block_source: bool,
}

fn default_true() -> bool {
    true
}

pub async fn deny(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(user_code): Path<String>,
    Query(query): Query<DenyQuery>,
) -> Result<StatusCode, AppError> {
    device_enrollments::deny(&state.db, &admin.0.subject, &user_code, query.block_source).await?;
    tracing::info!(actor = %admin.0.subject, "Device enrollment denied");
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;
    use simple_server::web::http::HeaderValue;

    fn headers(pairs: &[(&'static str, &str)]) -> HeaderMap {
        let mut headers = HeaderMap::new();
        for (name, value) in pairs {
            headers.insert(*name, HeaderValue::from_str(value).unwrap());
        }
        headers
    }

    fn with_peer(peer: &str) -> Option<IpAddr> {
        Some(peer.parse::<SocketAddr>().unwrap().ip())
    }

    #[test]
    fn source_ip_trusts_forwarding_only_from_a_private_peer() {
        let forwarded = headers(&[("x-forwarded-for", "203.0.113.9, 198.51.100.7")]);
        assert_eq!(
            source_ip(&forwarded, with_peer("172.18.0.3:4000")),
            "198.51.100.7"
        );
        assert_eq!(
            source_ip(&forwarded, with_peer("[::1]:4000")),
            "198.51.100.7"
        );
        assert_eq!(
            source_ip(&forwarded, with_peer("192.0.2.1:4000")),
            "192.0.2.1"
        );
        assert_eq!(
            source_ip(&headers(&[]), with_peer("172.18.0.3:4000")),
            "172.18.0.3"
        );
        assert_eq!(
            source_ip(&headers(&[("x-forwarded-for", "garbage")]), None),
            "unknown"
        );
        assert_eq!(source_ip(&forwarded, None), "198.51.100.7");
    }

    #[test]
    fn public_origin_prefers_forwarded_host_and_defaults_to_https() {
        assert_eq!(
            public_origin(&headers(&[("host", "Store.Example.com")])),
            "https://store.example.com"
        );
        assert_eq!(
            public_origin(&headers(&[
                ("host", "backend:8080"),
                ("x-forwarded-host", "store.example.com"),
                ("x-forwarded-proto", "https")
            ])),
            "https://store.example.com"
        );
        assert_eq!(
            public_origin(&headers(&[
                ("host", "localhost:8080"),
                ("x-forwarded-proto", "http")
            ])),
            "http://localhost:8080"
        );
        assert_eq!(
            public_origin(&headers(&[("host", "evil/path?x")])),
            "https://localhost"
        );
    }
}
