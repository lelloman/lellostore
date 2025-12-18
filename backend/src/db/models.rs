use serde::Serialize;

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
pub struct App {
    pub package_name: String,
    pub name: String,
    pub description: Option<String>,
    pub icon_path: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
pub struct AppVersion {
    pub id: i64,
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    pub apk_path: String,
    pub size: i64,
    pub sha256: String,
    pub min_sdk: i64,
    pub uploaded_at: String,
}
