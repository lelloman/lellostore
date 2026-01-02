use axum::{
    body::Body,
    http::{header, HeaderValue, Response, StatusCode, Uri},
    response::IntoResponse,
};
use rust_embed::Embed;

#[derive(Embed)]
#[folder = "../frontend/dist"]
struct Assets;

/// Serves embedded static files from the frontend dist folder.
/// For SPA routing, returns index.html for paths that don't match a static file.
pub async fn serve_static(uri: Uri) -> impl IntoResponse {
    let path = uri.path().trim_start_matches('/');

    // Try to serve the exact file path
    if let Some(content) = Assets::get(path) {
        return serve_file(path, &content.data);
    }

    // For SPA routes, serve index.html
    // Only return 404 for paths that look like static asset requests (ending with known extensions)
    let filename = path.rsplit_once('/').map(|(_, f)| f).unwrap_or(path);
    let is_static_asset = filename
        .rsplit_once('.')
        .map(|(_, ext)| {
            matches!(
                ext.to_lowercase().as_str(),
                "js" | "css" | "html" | "json" | "png" | "jpg" | "jpeg" | "gif" | "svg" | "ico"
                    | "woff" | "woff2" | "ttf" | "eot" | "map" | "webp" | "avif"
            )
        })
        .unwrap_or(false);

    if !is_static_asset {
        if let Some(content) = Assets::get("index.html") {
            return serve_file("index.html", &content.data);
        }
    }

    // File not found
    (StatusCode::NOT_FOUND, "Not Found").into_response()
}

fn serve_file(path: &str, data: &[u8]) -> Response<Body> {
    let mime = mime_guess::from_path(path).first_or_octet_stream();

    Response::builder()
        .status(StatusCode::OK)
        .header(
            header::CONTENT_TYPE,
            HeaderValue::from_str(mime.as_ref()).unwrap(),
        )
        .header(header::CONTENT_LENGTH, data.len())
        .body(Body::from(data.to_vec()))
        .unwrap()
}

/// Handler for the root path
pub async fn serve_index() -> impl IntoResponse {
    if let Some(content) = Assets::get("index.html") {
        serve_file("index.html", &content.data)
    } else {
        Response::builder()
            .status(StatusCode::NOT_FOUND)
            .body(Body::from("Frontend not embedded"))
            .unwrap()
    }
}
