//! File response utilities for streaming files with Range header support

use axum::{
    body::Body,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
};
use std::path::Path;
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncSeekExt, SeekFrom};
use tokio_util::io::ReaderStream;

use crate::error::AppError;

/// Error type for range parsing
#[derive(Debug)]
pub enum RangeError {
    /// Invalid range format
    InvalidFormat,
    /// Range not satisfiable (e.g., start > file size)
    NotSatisfiable,
}

/// Parse Range header: "bytes=start-end" or "bytes=start-"
///
/// Returns (start, end) where end is inclusive.
/// If end is not specified, returns (start, file_size - 1).
pub fn parse_range_header(header: &str, file_size: u64) -> Result<(u64, u64), RangeError> {
    // Must start with "bytes="
    let range_spec = header
        .strip_prefix("bytes=")
        .ok_or(RangeError::InvalidFormat)?;

    // Split by '-'
    let parts: Vec<&str> = range_spec.split('-').collect();
    if parts.len() != 2 {
        return Err(RangeError::InvalidFormat);
    }

    let start_str = parts[0].trim();
    let end_str = parts[1].trim();

    // Handle "bytes=-500" (last 500 bytes) - suffix range
    if start_str.is_empty() {
        let suffix_len: u64 = end_str.parse().map_err(|_| RangeError::InvalidFormat)?;
        if suffix_len == 0 {
            return Err(RangeError::NotSatisfiable);
        }
        let start = file_size.saturating_sub(suffix_len);
        return Ok((start, file_size - 1));
    }

    let start: u64 = start_str.parse().map_err(|_| RangeError::InvalidFormat)?;

    // Validate start is within file
    if start >= file_size {
        return Err(RangeError::NotSatisfiable);
    }

    // Handle "bytes=500-" (from byte 500 to end)
    let end = if end_str.is_empty() {
        file_size - 1
    } else {
        let end: u64 = end_str.parse().map_err(|_| RangeError::InvalidFormat)?;
        // Cap end to file_size - 1
        end.min(file_size - 1)
    };

    // Validate range
    if start > end {
        return Err(RangeError::NotSatisfiable);
    }

    Ok((start, end))
}

/// Build a file response with optional range support
pub struct FileResponseBuilder {
    path: std::path::PathBuf,
    content_type: &'static str,
    filename: Option<String>,
    range: Option<(u64, u64)>,
}

impl FileResponseBuilder {
    /// Create a new file response builder
    pub fn new(path: impl AsRef<Path>, content_type: &'static str) -> Self {
        Self {
            path: path.as_ref().to_path_buf(),
            content_type,
            filename: None,
            range: None,
        }
    }

    /// Set the filename for Content-Disposition header
    pub fn with_filename(mut self, name: impl Into<String>) -> Self {
        self.filename = Some(name.into());
        self
    }

    /// Set the range for partial content response
    pub fn with_range(mut self, start: u64, end: u64) -> Self {
        self.range = Some((start, end));
        self
    }

    /// Build the response
    pub async fn build(self) -> Result<Response, AppError> {
        let mut file = File::open(&self.path)
            .await
            .map_err(|e| AppError::Internal(format!("Failed to open file: {}", e)))?;

        let metadata = file
            .metadata()
            .await
            .map_err(|e| AppError::Internal(format!("Failed to get file metadata: {}", e)))?;

        let file_size = metadata.len();

        let (status, content_length, content_range, start) = match self.range {
            Some((start, end)) => {
                // Seek to start position
                file.seek(SeekFrom::Start(start))
                    .await
                    .map_err(|e| AppError::Internal(format!("Failed to seek: {}", e)))?;

                let length = end - start + 1;
                let range_header = format!("bytes {}-{}/{}", start, end, file_size);

                (
                    StatusCode::PARTIAL_CONTENT,
                    length,
                    Some(range_header),
                    start,
                )
            }
            None => (StatusCode::OK, file_size, None, 0),
        };

        // Create a limited reader if we have a range
        let body = if let Some((_, end)) = self.range {
            let length = end - start + 1;
            let limited = file.take(length);
            Body::from_stream(ReaderStream::new(limited))
        } else {
            Body::from_stream(ReaderStream::new(file))
        };

        let mut response = Response::builder()
            .status(status)
            .header(header::CONTENT_TYPE, self.content_type)
            .header(header::CONTENT_LENGTH, content_length)
            .header(header::ACCEPT_RANGES, "bytes");

        if let Some(range) = content_range {
            response = response.header(header::CONTENT_RANGE, range);
        }

        if let Some(filename) = self.filename {
            let disposition = format!("attachment; filename=\"{}\"", filename);
            response = response.header(header::CONTENT_DISPOSITION, disposition);
        }

        response
            .body(body)
            .map_err(|e| AppError::Internal(format!("Failed to build response: {}", e)))
    }
}

/// Convenience function to serve a file with optional range support
pub async fn serve_file(
    path: impl AsRef<Path>,
    content_type: &'static str,
    filename: Option<String>,
    range_header: Option<&str>,
) -> Result<Response, AppError> {
    let path = path.as_ref();

    // Check file exists
    if !path.exists() {
        return Err(AppError::NotFound("File not found".to_string()));
    }

    let metadata = tokio::fs::metadata(path)
        .await
        .map_err(|e| AppError::Internal(format!("Failed to get metadata: {}", e)))?;

    let file_size = metadata.len();

    let mut builder = FileResponseBuilder::new(path, content_type);

    if let Some(name) = filename {
        builder = builder.with_filename(name);
    }

    // Parse range header if present
    if let Some(range) = range_header {
        match parse_range_header(range, file_size) {
            Ok((start, end)) => {
                builder = builder.with_range(start, end);
            }
            Err(RangeError::NotSatisfiable) => {
                return Ok((
                    StatusCode::RANGE_NOT_SATISFIABLE,
                    [(header::CONTENT_RANGE, format!("bytes */{}", file_size))],
                )
                    .into_response());
            }
            Err(RangeError::InvalidFormat) => {
                // Invalid range format - ignore and serve full file
            }
        }
    }

    builder.build().await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_range_simple() {
        // bytes=0-499 (first 500 bytes)
        let (start, end) = parse_range_header("bytes=0-499", 1000).unwrap();
        assert_eq!(start, 0);
        assert_eq!(end, 499);
    }

    #[test]
    fn test_parse_range_open_end() {
        // bytes=500- (from byte 500 to end)
        let (start, end) = parse_range_header("bytes=500-", 1000).unwrap();
        assert_eq!(start, 500);
        assert_eq!(end, 999);
    }

    #[test]
    fn test_parse_range_suffix() {
        // bytes=-500 (last 500 bytes)
        let (start, end) = parse_range_header("bytes=-500", 1000).unwrap();
        assert_eq!(start, 500);
        assert_eq!(end, 999);
    }

    #[test]
    fn test_parse_range_suffix_larger_than_file() {
        // bytes=-2000 when file is 1000 bytes (should return whole file)
        let (start, end) = parse_range_header("bytes=-2000", 1000).unwrap();
        assert_eq!(start, 0);
        assert_eq!(end, 999);
    }

    #[test]
    fn test_parse_range_end_exceeds_file() {
        // bytes=0-2000 when file is 1000 bytes (should cap to 999)
        let (start, end) = parse_range_header("bytes=0-2000", 1000).unwrap();
        assert_eq!(start, 0);
        assert_eq!(end, 999);
    }

    #[test]
    fn test_parse_range_start_at_end() {
        // bytes=999-999 (single byte at end)
        let (start, end) = parse_range_header("bytes=999-999", 1000).unwrap();
        assert_eq!(start, 999);
        assert_eq!(end, 999);
    }

    #[test]
    fn test_parse_range_invalid_format() {
        assert!(matches!(
            parse_range_header("invalid", 1000),
            Err(RangeError::InvalidFormat)
        ));
        assert!(matches!(
            parse_range_header("bytes=abc-def", 1000),
            Err(RangeError::InvalidFormat)
        ));
        assert!(matches!(
            parse_range_header("bytes=100-50-200", 1000),
            Err(RangeError::InvalidFormat)
        ));
    }

    #[test]
    fn test_parse_range_not_satisfiable() {
        // Start beyond file size
        assert!(matches!(
            parse_range_header("bytes=2000-3000", 1000),
            Err(RangeError::NotSatisfiable)
        ));

        // Empty suffix range
        assert!(matches!(
            parse_range_header("bytes=-0", 1000),
            Err(RangeError::NotSatisfiable)
        ));
    }

    #[test]
    fn test_parse_range_inverted() {
        // Start > end
        assert!(matches!(
            parse_range_header("bytes=500-100", 1000),
            Err(RangeError::NotSatisfiable)
        ));
    }
}
