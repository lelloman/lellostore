use super::AppState;
use crate::{
    auth::AuthenticatedUser,
    db::acquisitions::{self, Acquisition, AcquisitionRequest},
    error::AppError,
};
use serde::Serialize;
use simple_server::axum::{
    extract::{Path, State},
    http::HeaderMap,
    response::Response,
    Json,
};

#[derive(Serialize)]
pub struct AcquisitionResponse {
    #[serde(flatten)]
    acquisition: Acquisition,
    apk_url: String,
}

fn response(acquisition: Acquisition) -> Json<AcquisitionResponse> {
    let apk_url = format!("/api/acquisitions/{}/apk", acquisition.id);
    Json(AcquisitionResponse {
        acquisition,
        apk_url,
    })
}

pub async fn create(
    user: AuthenticatedUser,
    State(state): State<AppState>,
    Path(package): Path<String>,
    Json(request): Json<AcquisitionRequest>,
) -> Result<Json<AcquisitionResponse>, AppError> {
    Ok(response(
        acquisitions::create(&state.db, &user.0.subject, &package, &request).await?,
    ))
}

pub async fn get(
    user: AuthenticatedUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<AcquisitionResponse>, AppError> {
    Ok(response(
        acquisitions::get(&state.db, &user.0.subject, &id).await?,
    ))
}

pub async fn download(
    user: AuthenticatedUser,
    State(state): State<AppState>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    let acquisition = acquisitions::get(&state.db, &user.0.subject, &id).await?;
    super::file_response::serve_immutable_file(
        state.config.storage_path.join(&acquisition.apk_path),
        "application/vnd.android.package-archive",
        format!(
            "{}-{}.apk",
            acquisition.package_name, acquisition.version_code
        ),
        &acquisition.sha256,
        acquisition.size as u64,
        &headers,
    )
    .await
}
