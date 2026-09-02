use axum::{
    extract::DefaultBodyLimit,
    http::Method,
    middleware,
    routing::{delete, get, post, put},
    Router,
};
use tower_http::{cors::CorsLayer, trace::TraceLayer};

use super::{handlers, static_files, AppState};
use crate::auth::{auth_middleware, AuthState};
use crate::metrics::track_metrics;

pub fn create_router(state: AppState) -> Router {
    create_router_inner(state, false)
}

/// Build an unauthenticated router for handler-level integration tests.
///
/// Production callers must use [`create_router`], which fails closed when
/// OIDC is unavailable. This function exists only to isolate handler tests.
#[doc(hidden)]
pub fn create_test_router(state: AppState) -> Router {
    create_router_inner(state, true)
}

fn create_router_inner(state: AppState, allow_unauthenticated_for_tests: bool) -> Router {
    let max_upload_size = state.config.max_upload_size;
    let mut router = Router::new().route("/health", get(handlers::health_check));

    // Add protected routes if auth is configured
    if let Some(auth_state) = &state.auth {
        // User routes require authentication (any valid user)
        router = router.nest("/api", user_routes(auth_state.clone()));
        // Admin routes require authentication AND admin role
        router = router.nest(
            "/api/admin",
            admin_routes(auth_state.clone(), max_upload_size),
        );
    } else if allow_unauthenticated_for_tests {
        router = router.nest("/api", public_routes());
    } else {
        // Authentication is mandatory. If initialization failed, keep health
        // and static assets available but never expose catalog contents.
        router = router.nest("/api", Router::new().fallback(handlers::auth_unavailable));
    }

    // Add static file serving for embedded frontend
    // This must come after API routes so API takes priority
    router = router
        .route("/", get(static_files::serve_index))
        .fallback(static_files::serve_static);

    router
        .layer(middleware::from_fn(track_metrics))
        .layer(TraceLayer::new_for_http())
        .layer(cors_layer())
        .with_state(state)
}

/// Public API routes (used when auth is disabled)
fn public_routes() -> Router<AppState> {
    Router::new()
        .route("/apps", get(handlers::list_apps))
        .route("/apps/:package_name", get(handlers::get_app))
        .route("/apps/:package_name/icon", get(handlers::get_icon))
        .route(
            "/apps/:package_name/versions/:version_code/apk",
            get(handlers::download_apk),
        )
}

/// User API routes (requires authentication, any valid user)
fn user_routes(auth_state: AuthState) -> Router<AppState> {
    Router::new()
        .route("/apps", get(handlers::list_authorized_apps))
        .route("/apps/:package_name", get(handlers::get_authorized_app))
        .route(
            "/apps/:package_name/icon",
            get(handlers::get_authorized_icon),
        )
        .route(
            "/apps/:package_name/versions/:version_code/apk",
            get(handlers::download_authorized_apk),
        )
        .layer(middleware::from_fn_with_state(auth_state, auth_middleware))
}

/// Admin routes (requires authentication and admin role)
fn admin_routes(auth_state: AuthState, max_upload_size: u64) -> Router<AppState> {
    let multipart_body_limit = max_upload_size
        .saturating_add(1024 * 1024)
        .min(usize::MAX as u64) as usize;
    Router::new()
        .route(
            "/apps",
            get(handlers::list_admin_apps).post(handlers::upload_app),
        )
        .route("/apps/:package_name", put(handlers::update_app))
        .route("/apps/:package_name", delete(handlers::delete_app))
        .route("/apps/:package_name/icon", post(handlers::upload_icon))
        .route(
            "/apps/:package_name/versions/:version_code",
            delete(handlers::delete_version).put(handlers::set_admin_release_channel),
        )
        .route("/users", get(handlers::list_admin_users))
        .route(
            "/users/:subject/access",
            get(handlers::get_admin_user_access),
        )
        .route(
            "/users/:subject/apps/:package_name",
            put(handlers::set_admin_direct_grant).delete(handlers::remove_admin_direct_grant),
        )
        .route(
            "/app-groups",
            get(handlers::list_admin_groups).post(handlers::create_admin_group),
        )
        .route(
            "/app-groups/:group_id",
            put(handlers::rename_admin_group).delete(handlers::delete_admin_group),
        )
        .route(
            "/app-groups/:group_id/apps/:package_name",
            put(handlers::set_admin_group_grant).delete(handlers::remove_admin_group_grant),
        )
        .route(
            "/app-groups/:group_id/users/:subject",
            put(handlers::add_admin_group_member).delete(handlers::remove_admin_group_member),
        )
        .layer(DefaultBodyLimit::max(multipart_body_limit))
        .layer(middleware::from_fn_with_state(auth_state, auth_middleware))
}

fn cors_layer() -> CorsLayer {
    CorsLayer::new()
        .allow_origin(tower_http::cors::Any)
        .allow_methods([Method::GET, Method::POST, Method::PUT, Method::DELETE])
        .allow_headers(tower_http::cors::Any)
}
