use axum::{http::Method, middleware, routing::get, Router};
use tower_http::{cors::CorsLayer, trace::TraceLayer};

use super::{handlers, AppState};
use crate::auth::{auth_middleware, AuthState};
use crate::metrics::track_metrics;

pub fn create_router(state: AppState) -> Router {
    let mut router = Router::new()
        .route("/health", get(handlers::health_check))
        .nest("/api", api_routes());

    // Add protected routes if auth is configured
    if let Some(auth_state) = &state.auth {
        router = router.nest("/api/admin", admin_routes(auth_state.clone()));
    }

    router
        .layer(middleware::from_fn(track_metrics))
        .layer(TraceLayer::new_for_http())
        .layer(cors_layer())
        .with_state(state)
}

/// Public API routes (no authentication required)
fn api_routes() -> Router<AppState> {
    Router::new()
        .route("/apps", get(handlers::list_apps))
        .route("/apps/{package_name}", get(handlers::get_app))
}

/// Admin routes (requires authentication and admin role)
fn admin_routes(auth_state: AuthState) -> Router<AppState> {
    Router::new()
        // Admin endpoints will be added here as we implement them
        // For now, just an empty router with auth middleware
        .layer(middleware::from_fn_with_state(auth_state, auth_middleware))
}

fn cors_layer() -> CorsLayer {
    CorsLayer::new()
        .allow_origin(tower_http::cors::Any)
        .allow_methods([Method::GET, Method::POST, Method::PUT, Method::DELETE])
        .allow_headers(tower_http::cors::Any)
}
