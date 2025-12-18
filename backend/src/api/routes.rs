use axum::{http::Method, middleware, routing::get, Router};
use tower_http::{cors::CorsLayer, trace::TraceLayer};

use super::{handlers, AppState};
use crate::metrics::track_metrics;

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(handlers::health_check))
        .nest("/api", api_routes())
        .layer(middleware::from_fn(track_metrics))
        .layer(TraceLayer::new_for_http())
        .layer(cors_layer())
        .with_state(state)
}

fn api_routes() -> Router<AppState> {
    Router::new()
        .route("/apps", get(handlers::list_apps))
        .route("/apps/{package_name}", get(handlers::get_app))
}

fn cors_layer() -> CorsLayer {
    CorsLayer::new()
        .allow_origin(tower_http::cors::Any)
        .allow_methods([Method::GET, Method::POST, Method::PUT, Method::DELETE])
        .allow_headers(tower_http::cors::Any)
}
