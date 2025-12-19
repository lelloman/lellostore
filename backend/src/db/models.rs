use serde::Serialize;

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct App {
    pub package_name: String,
    pub name: String,
    pub description: Option<String>,
    #[serde(skip_serializing)]
    pub icon_path: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppVersion {
    #[serde(skip_serializing)]
    pub id: i64,
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    #[serde(skip_serializing)]
    pub apk_path: String,
    pub size: i64,
    pub sha256: String,
    pub min_sdk: i64,
    pub uploaded_at: String,
}
