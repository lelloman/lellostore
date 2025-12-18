use axum::{
    extract::{Path, State},
    Json,
};
use serde_json::{json, Value};

use crate::db;
use crate::error::AppError;

use super::AppState;

pub async fn health_check() -> Json<Value> {
    Json(json!({ "status": "healthy" }))
}

pub async fn list_apps(State(state): State<AppState>) -> Result<Json<Value>, AppError> {
    let apps = db::get_all_apps(&state.db).await?;
    Ok(Json(json!({ "apps": apps })))
}

pub async fn get_app(
    State(state): State<AppState>,
    Path(package_name): Path<String>,
) -> Result<Json<Value>, AppError> {
    let app = db::get_app(&state.db, &package_name)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("App '{}' not found", package_name)))?;

    let versions = db::get_app_versions(&state.db, &package_name).await?;

    Ok(Json(json!({
        "app": app,
        "versions": versions
    })))
}
