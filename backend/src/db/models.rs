use serde::Serialize;

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct App {
    pub package_name: String,
    pub name: String,
    pub description: Option<String>,
    #[serde(skip_serializing)]
    pub icon_path: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub distribution_mode: String,
    pub publication_revision: i64,
}

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct AppVersion {
    #[serde(skip_serializing)]
    pub id: i64,
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    #[serde(skip_serializing)]
    pub apk_path: String,
    pub archived: bool,
    pub artifact_removed: bool,
    pub size: i64,
    pub sha256: String,
    pub min_sdk: i64,
    pub uploaded_at: String,
    pub is_beta: bool,
    pub publication_state: String,
    pub distribution_mode: String,
    pub release_notes: String,
    pub published_at: Option<String>,
    #[serde(skip_serializing)]
    pub proposed_name: Option<String>,
    #[serde(skip_serializing)]
    pub proposed_description: Option<String>,
}
