use serde_json::Value;
use std::collections::HashMap;
use tracing::debug;

use super::validator::TokenClaims;

/// Authenticated user extracted from a validated JWT
#[derive(Debug, Clone)]
pub struct User {
    /// Unique user identifier (sub claim)
    pub subject: String,
    /// User email (if present in token)
    pub email: Option<String>,
    /// User roles extracted from configured claim path
    pub roles: Vec<String>,
    /// Whether user has admin role
    pub is_admin: bool,
}

impl User {
    /// Create a User from validated token claims
    pub fn from_claims(claims: &TokenClaims, role_claim_path: &str, admin_role: &str) -> Self {
        let roles = extract_roles(&claims.extra, role_claim_path);
        let is_admin = roles.iter().any(|r| r == admin_role);

        debug!(
            "User {} roles: {:?}, is_admin: {}",
            claims.sub, roles, is_admin
        );

        Self {
            subject: claims.sub.clone(),
            email: claims.email.clone(),
            roles,
            is_admin,
        }
    }
}

/// Extract roles from claims using a dot-separated path
///
/// Supports paths like:
/// - `roles` -> claims["roles"]
/// - `realm_access.roles` -> claims["realm_access"]["roles"]
fn extract_roles(claims: &HashMap<String, Value>, path: &str) -> Vec<String> {
    let parts: Vec<&str> = path.split('.').collect();

    if parts.is_empty() {
        return Vec::new();
    }

    // Start with the first part
    let mut current: Option<&Value> = claims.get(parts[0]);

    // Navigate through nested objects
    for part in &parts[1..] {
        current = current.and_then(|v| v.get(part));
    }

    // Extract roles from the final value
    match current {
        Some(Value::Array(arr)) => arr
            .iter()
            .filter_map(|v| v.as_str())
            .map(String::from)
            .collect(),
        Some(Value::String(s)) => vec![s.clone()],
        _ => Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn make_claims(extra: serde_json::Value) -> HashMap<String, Value> {
        match extra {
            Value::Object(map) => map.into_iter().collect(),
            _ => HashMap::new(),
        }
    }

    #[test]
    fn test_extract_roles_simple() {
        let claims = make_claims(json!({
            "roles": ["user", "admin"]
        }));

        let roles = extract_roles(&claims, "roles");
        assert_eq!(roles, vec!["user", "admin"]);
    }

    #[test]
    fn test_extract_roles_nested() {
        let claims = make_claims(json!({
            "realm_access": {
                "roles": ["user", "editor"]
            }
        }));

        let roles = extract_roles(&claims, "realm_access.roles");
        assert_eq!(roles, vec!["user", "editor"]);
    }

    #[test]
    fn test_extract_roles_deeply_nested() {
        let claims = make_claims(json!({
            "resource_access": {
                "my-app": {
                    "roles": ["app-admin"]
                }
            }
        }));

        let roles = extract_roles(&claims, "resource_access.my-app.roles");
        assert_eq!(roles, vec!["app-admin"]);
    }

    #[test]
    fn test_extract_roles_single_string() {
        let claims = make_claims(json!({
            "role": "admin"
        }));

        let roles = extract_roles(&claims, "role");
        assert_eq!(roles, vec!["admin"]);
    }

    #[test]
    fn test_extract_roles_missing() {
        let claims = make_claims(json!({
            "other": "value"
        }));

        let roles = extract_roles(&claims, "roles");
        assert!(roles.is_empty());
    }

    #[test]
    fn test_extract_roles_wrong_type() {
        let claims = make_claims(json!({
            "roles": 123
        }));

        let roles = extract_roles(&claims, "roles");
        assert!(roles.is_empty());
    }

    #[test]
    fn test_user_from_claims_admin() {
        let claims = TokenClaims {
            sub: "user-123".to_string(),
            iss: "https://auth.example.com".to_string(),
            aud: vec!["my-app".to_string()],
            exp: 1700000000,
            iat: 1699999000,
            email: Some("user@example.com".to_string()),
            extra: make_claims(json!({
                "realm_access": {
                    "roles": ["user", "admin"]
                }
            })),
        };

        let user = User::from_claims(&claims, "realm_access.roles", "admin");
        assert_eq!(user.subject, "user-123");
        assert_eq!(user.email, Some("user@example.com".to_string()));
        assert!(user.is_admin);
        assert!(user.roles.contains(&"admin".to_string()));
    }

    #[test]
    fn test_user_from_claims_not_admin() {
        let claims = TokenClaims {
            sub: "user-456".to_string(),
            iss: "https://auth.example.com".to_string(),
            aud: vec!["my-app".to_string()],
            exp: 1700000000,
            iat: 1699999000,
            email: None,
            extra: make_claims(json!({
                "roles": ["user", "viewer"]
            })),
        };

        let user = User::from_claims(&claims, "roles", "admin");
        assert_eq!(user.subject, "user-456");
        assert!(!user.is_admin);
    }
}
