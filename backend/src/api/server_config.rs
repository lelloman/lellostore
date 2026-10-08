use super::AppState;
use serde::Serialize;
use simple_server::web::{
    extract::State,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    Json,
};

#[derive(Serialize)]
struct ServerConfig<'a> {
    schema_version: u32,
    name: &'a str,
    auth: Authentication<'a>,
    capabilities: Capabilities,
}

#[derive(Serialize)]
struct Authentication<'a> {
    method: &'static str,
    issuer_url: &'a str,
    clients: Clients<'a>,
    scopes: &'a [String],
}

#[derive(Serialize)]
struct Clients<'a> {
    android: &'a str,
    web: &'a str,
    publisher: &'a str,
}

#[derive(Serialize)]
struct Capabilities {
    push: bool,
    paravoid: bool,
}

pub async fn get(State(state): State<AppState>) -> Response {
    let config = &state.config;
    let clients = &config.clients;
    let headers = [(header::CACHE_CONTROL, "no-store")];
    if !clients.is_configured(&config.oidc.issuer_url) {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            headers,
            Json(serde_json::json!({
                "code": "server_not_configured",
                "message": "The operator must finish configuring this store before clients can connect."
            })),
        )
            .into_response();
    }
    (
        headers,
        Json(ServerConfig {
            schema_version: 1,
            name: &clients.store_name,
            auth: Authentication {
                method: "oidc",
                issuer_url: &config.oidc.issuer_url,
                clients: Clients {
                    android: &clients.android_client_id,
                    web: &clients.web_client_id,
                    publisher: &clients.publisher_client_id,
                },
                scopes: &clients.scopes,
            },
            capabilities: Capabilities {
                push: config.notifications_enabled,
                paravoid: state.paravoid_signing.is_some(),
            },
        }),
    )
        .into_response()
}
