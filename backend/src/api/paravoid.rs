use super::AppState;
use crate::{auth::AdminUser, paravoid::signing::PublicSigningConfiguration};
use serde::Serialize;
use simple_server::axum::{extract::State, Json};

#[derive(Serialize)]
pub struct ConfigurationResponse {
    configured: bool,
    distribution_enabled: bool,
    signing: Option<PublicSigningConfiguration>,
}

/// Export only public trust material. Private key paths/bytes never enter API DTOs.
pub async fn configuration(
    _admin: AdminUser,
    State(state): State<AppState>,
) -> Json<ConfigurationResponse> {
    Json(ConfigurationResponse {
        configured: state.paravoid_signing.is_some(),
        distribution_enabled: state.paravoid_signing.is_some(),
        signing: state
            .paravoid_signing
            .as_ref()
            .map(|keys| keys.public_configuration()),
    })
}
