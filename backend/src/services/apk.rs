use image::imageops::FilterType;
use image::ImageFormat;
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;
use thiserror::Error;
use tokio::process::Command;
use tracing::warn;
use zip::ZipArchive;

#[derive(Debug, Error)]
pub enum ApkError {
    #[error("aapt2 not found. Please install Android SDK build-tools or set AAPT2_PATH")]
    Aapt2NotFound,

    #[error("aapt2 execution failed: {0}")]
    Aapt2Failed(String),

    #[error("aapt2 execution timed out")]
    TimedOut,

    #[error("Failed to parse APK metadata: {0}")]
    ParseError(String),

    #[error("Invalid APK file: {0}")]
    InvalidApk(String),

    #[error("Icon extraction failed: {0}")]
    IconError(String),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Clone)]
pub struct ApkMetadata {
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    pub min_sdk: i64,
    pub app_name: String,
    pub icon_data: Option<Vec<u8>>,
}

pub struct ApkParser {
    aapt2_path: PathBuf,
    command_timeout: Duration,
}

const DEFAULT_AAPT2_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_ICON_SIZE: u64 = 10 * 1024 * 1024;

impl ApkParser {
    /// Create a new APK parser with explicit aapt2 path
    pub fn new(aapt2_path: PathBuf) -> Self {
        Self::with_timeout(aapt2_path, DEFAULT_AAPT2_TIMEOUT)
    }

    pub fn with_timeout(aapt2_path: PathBuf, command_timeout: Duration) -> Self {
        Self {
            aapt2_path,
            command_timeout,
        }
    }

    /// Detect aapt2 location from common paths or PATH
    pub fn detect_aapt2() -> Result<PathBuf, ApkError> {
        // Check common locations
        let common_paths = [
            // Android SDK locations
            "/usr/local/lib/android/sdk/build-tools/34.0.0/aapt2",
            "/usr/local/lib/android/sdk/build-tools/33.0.0/aapt2",
            "/opt/android-sdk/build-tools/34.0.0/aapt2",
            "/opt/android-sdk/build-tools/33.0.0/aapt2",
            // Homebrew on macOS
            "/opt/homebrew/bin/aapt2",
            // Linux package manager
            "/usr/bin/aapt2",
            "/usr/bin/aapt",
        ];

        for path in common_paths {
            let p = PathBuf::from(path);
            if p.exists() {
                return Ok(p);
            }
        }

        // Check ANDROID_HOME environment variable
        if let Ok(android_home) = std::env::var("ANDROID_HOME") {
            let build_tools = PathBuf::from(&android_home).join("build-tools");
            if build_tools.exists() {
                // Find the newest version
                if let Ok(entries) = std::fs::read_dir(&build_tools) {
                    let mut versions: Vec<_> = entries
                        .filter_map(|e| e.ok())
                        .filter(|e| e.path().is_dir())
                        .collect();
                    versions.sort_by_key(|e| std::cmp::Reverse(e.file_name()));

                    for version in versions {
                        let aapt2 = version.path().join("aapt2");
                        if aapt2.exists() {
                            return Ok(aapt2);
                        }
                    }
                }
            }
        }

        // Check PATH
        if let Ok(output) = std::process::Command::new("which").arg("aapt2").output() {
            if output.status.success() {
                let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if !path.is_empty() {
                    return Ok(PathBuf::from(path));
                }
            }
        }

        Err(ApkError::Aapt2NotFound)
    }

    /// Create parser with auto-detected aapt2
    pub fn auto_detect() -> Result<Self, ApkError> {
        let aapt2_path = Self::detect_aapt2()?;
        Ok(Self::new(aapt2_path))
    }

    /// Parse APK metadata using aapt2
    pub async fn parse(&self, apk_path: &Path) -> Result<ApkMetadata, ApkError> {
        // Run aapt2 dump badging
        let mut command = Command::new(&self.aapt2_path);
        command
            .arg("dump")
            .arg("badging")
            .arg(apk_path)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let output = tokio::time::timeout(self.command_timeout, command.output())
            .await
            .map_err(|_| ApkError::TimedOut)??;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(ApkError::Aapt2Failed(stderr.to_string()));
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        let parsed = parse_aapt2_output(&stdout)?;

        // Extract the first usable icon. aapt2 often reports an adaptive-icon XML
        // for every density, so fall back to the raster variants in resources.arsc.
        let icon_data = if !parsed.icon_paths.is_empty() {
            match self.extract_best_icon(apk_path, &parsed.icon_paths).await {
                Ok(data) => Some(data),
                Err(e) => {
                    tracing::warn!("Failed to extract icon: {}", e);
                    None
                }
            }
        } else {
            None
        };

        Ok(ApkMetadata {
            package_name: parsed.package_name,
            version_code: parsed.version_code,
            version_name: parsed.version_name,
            min_sdk: parsed.min_sdk,
            app_name: parsed.app_name,
            icon_data,
        })
    }

    async fn extract_best_icon(
        &self,
        apk_path: &Path,
        icon_paths: &[String],
    ) -> Result<Vec<u8>, ApkError> {
        let mut last_error = None;

        // Prefer a directly decodable badging entry when one is available.
        for icon_path in icon_paths {
            match self.extract_icon(apk_path, icon_path).await {
                Ok(icon) => return Ok(icon),
                Err(error) => last_error = Some(error),
            }
        }

        // Adaptive icons are XML. Resolve their resource-table entry to legacy
        // PNG/WebP variants. This also works when resource shrinking has renamed
        // files, where matching by filename would be unreliable.
        let resource_output = self.dump_resources(apk_path).await?;
        for icon_path in icon_paths {
            for fallback_path in parse_resource_file_variants(&resource_output, icon_path) {
                match self.extract_icon(apk_path, &fallback_path).await {
                    Ok(icon) => return Ok(icon),
                    Err(error) => last_error = Some(error),
                }
            }
        }

        for icon_path in icon_paths.iter().filter(|path| path.ends_with(".xml")) {
            match self
                .render_xml_launcher_icon(apk_path, icon_path, &resource_output)
                .await
            {
                Ok(icon) => return Ok(icon),
                Err(error) => last_error = Some(error),
            }
        }

        Err(last_error.unwrap_or_else(|| {
            ApkError::IconError("No decodable launcher icon resource found".to_string())
        }))
    }

    async fn dump_resources(&self, apk_path: &Path) -> Result<String, ApkError> {
        let mut command = Command::new(&self.aapt2_path);
        command
            .arg("dump")
            .arg("resources")
            .arg(apk_path)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let output = tokio::time::timeout(self.command_timeout, command.output())
            .await
            .map_err(|_| ApkError::TimedOut)??;

        if !output.status.success() {
            return Err(ApkError::Aapt2Failed(
                String::from_utf8_lossy(&output.stderr).to_string(),
            ));
        }

        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    }

    async fn dump_xmltree(&self, apk_path: &Path, xml_path: &str) -> Result<String, ApkError> {
        let mut command = Command::new(&self.aapt2_path);
        command
            .arg("dump")
            .arg("xmltree")
            .arg(apk_path)
            .arg("--file")
            .arg(xml_path)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let output = tokio::time::timeout(self.command_timeout, command.output())
            .await
            .map_err(|_| ApkError::TimedOut)??;

        if !output.status.success() {
            return Err(ApkError::Aapt2Failed(
                String::from_utf8_lossy(&output.stderr).to_string(),
            ));
        }

        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    }

    async fn render_xml_launcher_icon(
        &self,
        apk_path: &Path,
        icon_path: &str,
        resources: &str,
    ) -> Result<Vec<u8>, ApkError> {
        let tree = self.dump_xmltree(apk_path, icon_path).await?;
        match root_element_name(&tree) {
            Some("vector") | Some("shape") => render_xml_layer(&tree),
            Some("adaptive-icon") => {
                let (background_id, foreground_id) = parse_adaptive_icon_refs(&tree)?;
                let background_path =
                    resource_file_for_id(resources, &background_id).ok_or_else(|| {
                        ApkError::IconError("Adaptive icon background not found".into())
                    })?;
                let foreground_path =
                    resource_file_for_id(resources, &foreground_id).ok_or_else(|| {
                        ApkError::IconError("Adaptive icon foreground not found".into())
                    })?;
                let background_tree = self.dump_xmltree(apk_path, &background_path).await?;
                let foreground_tree = self.dump_xmltree(apk_path, &foreground_path).await?;
                let background = render_xml_layer_image(&background_tree)?;
                let foreground = render_xml_layer_image(&foreground_tree)?;
                let mut composed = background;
                image::imageops::overlay(&mut composed, &foreground, 0, 0);
                encode_png(composed)
            }
            Some(kind) => Err(ApkError::IconError(format!(
                "Unsupported launcher drawable type: {kind}"
            ))),
            None => Err(ApkError::IconError(
                "Could not decode launcher drawable XML".into(),
            )),
        }
    }

    /// Extract icon from APK (which is a ZIP file)
    async fn extract_icon(&self, apk_path: &Path, icon_path: &str) -> Result<Vec<u8>, ApkError> {
        // Open the archive from disk instead of buffering the whole APK again.
        let file = std::fs::File::open(apk_path)?;
        let mut archive = ZipArchive::new(file).map_err(|e| ApkError::InvalidApk(e.to_string()))?;

        // Find and read the icon file
        let mut icon_file = archive
            .by_name(icon_path)
            .map_err(|e| ApkError::IconError(format!("Icon not found: {}", e)))?;

        if icon_file.size() > MAX_ICON_SIZE {
            return Err(ApkError::IconError(format!(
                "Icon expands beyond the {} byte limit",
                MAX_ICON_SIZE
            )));
        }

        let mut icon_data = Vec::with_capacity(icon_file.size() as usize);
        std::io::Read::take(&mut icon_file, MAX_ICON_SIZE + 1)
            .read_to_end(&mut icon_data)
            .map_err(|e| ApkError::IconError(e.to_string()))?;
        if icon_data.len() as u64 > MAX_ICON_SIZE {
            return Err(ApkError::IconError(format!(
                "Icon expands beyond the {} byte limit",
                MAX_ICON_SIZE
            )));
        }

        // Convert to PNG and resize to 192x192
        let processed = process_icon(&icon_data)?;

        Ok(processed)
    }
}

/// Parsed output from aapt2 dump badging
struct ParsedAapt2Output {
    package_name: String,
    version_code: i64,
    version_name: String,
    min_sdk: i64,
    app_name: String,
    icon_paths: Vec<String>,
}

/// Parse aapt2 dump badging output
fn parse_aapt2_output(output: &str) -> Result<ParsedAapt2Output, ApkError> {
    let mut package_name = None;
    let mut version_code = None;
    let mut version_name = None;
    let mut min_sdk = None;
    let mut app_name = None;
    let mut icon_paths: Vec<(i32, String)> = Vec::new();

    for line in output.lines() {
        // package: name='com.example.app' versionCode='10' versionName='1.0.0' ...
        if line.starts_with("package:") {
            if let Some(name) = extract_quoted_value(line, "name=") {
                package_name = Some(name);
            }
            if let Some(code) = extract_quoted_value(line, "versionCode=") {
                version_code = code.parse().ok();
            }
            if let Some(name) = extract_quoted_value(line, "versionName=") {
                version_name = Some(name);
            }
        }

        // sdkVersion:'26'
        if line.starts_with("sdkVersion:") {
            if let Some(sdk) = extract_quoted_value_colon(line) {
                min_sdk = sdk.parse().ok();
            }
        }

        // application-label:'My App'
        if line.starts_with("application-label:") {
            if let Some(label) = extract_quoted_value_colon(line) {
                app_name = Some(label);
            }
        }

        // application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'
        if line.starts_with("application-icon-") {
            if let Some((density, path)) = parse_icon_line(line) {
                icon_paths.push((density, path));
            }
        }

        // Some aapt versions only emit the non-density-specific application line.
        if line.starts_with("application:") {
            if let Some(path) = extract_quoted_value(line, "icon=").filter(|path| !path.is_empty())
            {
                icon_paths.push((0, path));
            }
        }
    }

    // Prefer directly decodable raster entries and then the highest density.
    // Density 65534 is aapt's anydpi sentinel and commonly points to XML.
    icon_paths.sort_by_key(|(density, path)| (is_raster_path(path), *density));
    icon_paths.reverse();
    let mut icon_paths: Vec<String> = icon_paths.into_iter().map(|(_, path)| path).collect();
    icon_paths.dedup();

    let package_name =
        package_name.ok_or_else(|| ApkError::ParseError("Missing package name".to_string()))?;
    let version_code: i64 =
        version_code.ok_or_else(|| ApkError::ParseError("Missing version code".to_string()))?;

    let version_name = version_name.unwrap_or_else(|| {
        warn!(
            "APK {} missing version_name, using version_code as fallback",
            package_name
        );
        version_code.to_string()
    });

    let min_sdk = min_sdk.unwrap_or_else(|| {
        warn!(
            "APK {} missing minSdkVersion, defaulting to 21 (Android 5.0)",
            package_name
        );
        21
    });

    let app_name = app_name.unwrap_or_else(|| {
        warn!(
            "APK {} missing application-label, using package name as fallback",
            package_name
        );
        package_name.clone()
    });

    // Validate icon paths don't contain path traversal.
    icon_paths.retain(|path| {
        if path.contains("..") || path.starts_with('/') || path.starts_with('\\') {
            warn!(
                "APK {} has suspicious icon path '{}', ignoring",
                package_name, path
            );
            false
        } else {
            true
        }
    });

    Ok(ParsedAapt2Output {
        package_name,
        version_code,
        version_name,
        min_sdk,
        app_name,
        icon_paths,
    })
}

fn is_raster_path(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    lower.ends_with(".png")
        || lower.ends_with(".webp")
        || lower.ends_with(".jpg")
        || lower.ends_with(".jpeg")
}

/// Find other configured files belonging to the same resource as `target_path`.
/// `aapt2 dump resources` groups all density variants below one `resource` line.
fn parse_resource_file_variants(output: &str, target_path: &str) -> Vec<String> {
    fn finish_group(
        files: &mut Vec<(i32, String)>,
        target_path: &str,
        result: &mut Vec<(i32, String)>,
    ) {
        if files.iter().any(|(_, path)| path == target_path) {
            result.extend(files.drain(..).filter(|(_, path)| {
                path != target_path
                    && is_raster_path(path)
                    && !path.contains("..")
                    && !path.starts_with('/')
                    && !path.starts_with('\\')
            }));
        } else {
            files.clear();
        }
    }

    let mut files = Vec::new();
    let mut matches = Vec::new();

    for line in output.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("resource ") {
            finish_group(&mut files, target_path, &mut matches);
            continue;
        }

        let Some(file_marker) = trimmed.find("(file) ") else {
            continue;
        };
        let remainder = &trimmed[file_marker + "(file) ".len()..];
        let Some(path) = remainder.split_whitespace().next() else {
            continue;
        };
        let config = trimmed
            .strip_prefix('(')
            .and_then(|value| value.split(')').next());
        files.push((density_score(config.unwrap_or_default()), path.to_string()));
    }
    finish_group(&mut files, target_path, &mut matches);

    matches.sort_by_key(|(density, _)| std::cmp::Reverse(*density));
    matches.dedup_by(|left, right| left.1 == right.1);
    matches.into_iter().map(|(_, path)| path).collect()
}

fn density_score(config: &str) -> i32 {
    for (name, density) in [
        ("xxxhdpi", 640),
        ("xxhdpi", 480),
        ("xhdpi", 320),
        ("hdpi", 240),
        ("mdpi", 160),
        ("ldpi", 120),
    ] {
        if config.split('-').any(|part| part == name) {
            return density;
        }
    }
    config
        .split('-')
        .find_map(|part| part.strip_suffix("dpi")?.parse().ok())
        .unwrap_or(0)
}

#[derive(Debug)]
struct XmlElement {
    indent: usize,
    name: String,
    attributes: Vec<(String, String)>,
}

fn parse_xmltree_elements(output: &str) -> Vec<XmlElement> {
    let mut elements: Vec<XmlElement> = Vec::new();
    for line in output.lines() {
        let trimmed = line.trim_start();
        let indent = line.len() - trimmed.len();
        if let Some(name) = trimmed.strip_prefix("E: ") {
            let name = name.split_whitespace().next().unwrap_or_default();
            elements.push(XmlElement {
                indent,
                name: name.to_string(),
                attributes: Vec::new(),
            });
        } else if let Some(attribute) = trimmed.strip_prefix("A: ") {
            let Some(element) = elements.last_mut() else {
                continue;
            };
            let Some(paren) = attribute.find('(') else {
                continue;
            };
            let name = attribute[..paren]
                .rsplit(':')
                .next()
                .unwrap_or_default()
                .to_string();
            let Some(value_start) = attribute.find(")=") else {
                continue;
            };
            let value = attribute[value_start + 2..]
                .split(" (Raw:")
                .next()
                .unwrap_or_default()
                .trim()
                .trim_matches('"')
                .to_string();
            element.attributes.push((name, value));
        }
    }
    elements
}

fn root_element_name(output: &str) -> Option<&str> {
    output.lines().find_map(|line| {
        line.trim_start()
            .strip_prefix("E: ")?
            .split_whitespace()
            .next()
    })
}

fn attribute<'a>(element: &'a XmlElement, name: &str) -> Option<&'a str> {
    element
        .attributes
        .iter()
        .find_map(|(candidate, value)| (candidate == name).then_some(value.as_str()))
}

fn parse_adaptive_icon_refs(output: &str) -> Result<(String, String), ApkError> {
    let elements = parse_xmltree_elements(output);
    let reference = |name: &str| {
        elements
            .iter()
            .find(|element| element.name == name)
            .and_then(|element| attribute(element, "drawable"))
            .and_then(|value| value.strip_prefix('@'))
            .map(str::to_string)
    };
    Ok((
        reference("background")
            .ok_or_else(|| ApkError::IconError("Adaptive icon has no background".into()))?,
        reference("foreground")
            .ok_or_else(|| ApkError::IconError("Adaptive icon has no foreground".into()))?,
    ))
}

fn resource_file_for_id(resources: &str, wanted_id: &str) -> Option<String> {
    let mut matches = false;
    for line in resources.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("resource ") {
            matches = trimmed
                .split_whitespace()
                .nth(1)
                .is_some_and(|id| id == wanted_id);
        } else if matches {
            if let Some(marker) = trimmed.find("(file) ") {
                return trimmed[marker + "(file) ".len()..]
                    .split_whitespace()
                    .next()
                    .map(str::to_string);
            }
        }
    }
    None
}

fn render_xml_layer(output: &str) -> Result<Vec<u8>, ApkError> {
    encode_png(render_xml_layer_image(output)?)
}

fn render_xml_layer_image(output: &str) -> Result<image::RgbaImage, ApkError> {
    let elements = parse_xmltree_elements(output);
    let root = elements
        .first()
        .ok_or_else(|| ApkError::IconError("Drawable XML is empty".into()))?;
    match root.name.as_str() {
        "shape" => {
            let color = elements
                .iter()
                .find(|element| element.name == "solid")
                .and_then(|element| attribute(element, "color"))
                .ok_or_else(|| ApkError::IconError("Shape has no solid color".into()))?;
            let rgba = android_color(color)?;
            Ok(image::RgbaImage::from_pixel(192, 192, image::Rgba(rgba)))
        }
        "vector" => render_vector(&elements),
        kind => Err(ApkError::IconError(format!(
            "Unsupported adaptive icon layer: {kind}"
        ))),
    }
}

fn render_vector(elements: &[XmlElement]) -> Result<image::RgbaImage, ApkError> {
    let vector = elements
        .first()
        .ok_or_else(|| ApkError::IconError("Vector drawable is empty".into()))?;
    let viewport_width = attribute(vector, "viewportWidth")
        .and_then(|value| value.parse::<f32>().ok())
        .ok_or_else(|| ApkError::IconError("Vector has no viewportWidth".into()))?;
    let viewport_height = attribute(vector, "viewportHeight")
        .and_then(|value| value.parse::<f32>().ok())
        .ok_or_else(|| ApkError::IconError("Vector has no viewportHeight".into()))?;
    let mut svg = format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" width="192" height="192" viewBox="0 0 {viewport_width} {viewport_height}">"#
    );
    let mut groups: Vec<(usize, String)> = Vec::new();

    for element in elements.iter().skip(1) {
        while groups
            .last()
            .is_some_and(|(indent, _)| *indent >= element.indent)
        {
            svg.push_str("</g>");
            groups.pop();
        }

        if element.name == "group" {
            let transform = group_transform(element);
            svg.push_str("<g");
            if !transform.is_empty() {
                svg.push_str(" transform=\"");
                svg.push_str(&transform);
                svg.push('"');
            }
            svg.push('>');
            groups.push((element.indent, transform));
        } else if element.name == "path" {
            let Some(path_data) = attribute(element, "pathData") else {
                continue;
            };
            svg.push_str("<path d=\"");
            svg.push_str(&escape_xml(path_data));
            svg.push('"');
            append_svg_paint(&mut svg, element, "fillColor", "fill", "fillAlpha");
            append_svg_paint(&mut svg, element, "strokeColor", "stroke", "strokeAlpha");
            if let Some(width) = attribute(element, "strokeWidth") {
                svg.push_str(" stroke-width=\"");
                svg.push_str(width);
                svg.push('"');
            }
            if attribute(element, "fillType") == Some("1") {
                svg.push_str(" fill-rule=\"evenodd\"");
            }
            svg.push_str("/>");
        }
    }
    for _ in groups {
        svg.push_str("</g>");
    }
    svg.push_str("</svg>");

    let tree = resvg::usvg::Tree::from_str(&svg, &resvg::usvg::Options::default())
        .map_err(|error| ApkError::IconError(format!("Invalid vector drawable: {error}")))?;
    let mut pixmap = resvg::tiny_skia::Pixmap::new(192, 192)
        .ok_or_else(|| ApkError::IconError("Could not allocate icon canvas".into()))?;
    resvg::render(
        &tree,
        resvg::tiny_skia::Transform::identity(),
        &mut pixmap.as_mut(),
    );
    image::RgbaImage::from_raw(192, 192, pixmap.data().to_vec())
        .ok_or_else(|| ApkError::IconError("Could not create rendered icon".into()))
}

fn group_transform(element: &XmlElement) -> String {
    let pivot_x = attribute(element, "pivotX").unwrap_or("0");
    let pivot_y = attribute(element, "pivotY").unwrap_or("0");
    let rotation = attribute(element, "rotation").unwrap_or("0");
    let scale_x = attribute(element, "scaleX").unwrap_or("1");
    let scale_y = attribute(element, "scaleY").unwrap_or("1");
    let translate_x = attribute(element, "translateX").unwrap_or("0");
    let translate_y = attribute(element, "translateY").unwrap_or("0");
    format!(
        "translate({translate_x} {translate_y}) rotate({rotation} {pivot_x} {pivot_y}) translate({pivot_x} {pivot_y}) scale({scale_x} {scale_y}) translate(-{pivot_x} -{pivot_y})"
    )
}

fn append_svg_paint(
    svg: &mut String,
    element: &XmlElement,
    color_attribute: &str,
    svg_attribute: &str,
    alpha_attribute: &str,
) {
    let Some(color) = attribute(element, color_attribute) else {
        if svg_attribute == "fill" {
            svg.push_str(" fill=\"none\"");
        }
        return;
    };
    if let Ok(rgba) = android_color(color) {
        svg.push(' ');
        svg.push_str(svg_attribute);
        svg.push_str("=\"");
        svg.push_str(&format!("#{:02x}{:02x}{:02x}", rgba[0], rgba[1], rgba[2]));
        svg.push('"');
        let attribute_alpha = attribute(element, alpha_attribute)
            .and_then(|value| value.parse::<f32>().ok())
            .unwrap_or(1.0);
        let alpha = (rgba[3] as f32 / 255.0) * attribute_alpha;
        if alpha < 1.0 {
            svg.push(' ');
            svg.push_str(svg_attribute);
            svg.push_str("-opacity=\"");
            svg.push_str(&alpha.to_string());
            svg.push('"');
        }
    }
}

fn android_color(value: &str) -> Result<[u8; 4], ApkError> {
    let hex = value
        .strip_prefix('#')
        .ok_or_else(|| ApkError::IconError(format!("Unsupported drawable color: {value}")))?;
    let parsed = u32::from_str_radix(hex, 16)
        .map_err(|_| ApkError::IconError(format!("Invalid drawable color: {value}")))?;
    match hex.len() {
        8 => Ok([
            (parsed >> 16) as u8,
            (parsed >> 8) as u8,
            parsed as u8,
            (parsed >> 24) as u8,
        ]),
        6 => Ok([(parsed >> 16) as u8, (parsed >> 8) as u8, parsed as u8, 255]),
        _ => Err(ApkError::IconError(format!(
            "Unsupported drawable color: {value}"
        ))),
    }
}

fn escape_xml(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn encode_png(image: image::RgbaImage) -> Result<Vec<u8>, ApkError> {
    let mut output = Vec::new();
    image::DynamicImage::ImageRgba8(image)
        .write_to(&mut Cursor::new(&mut output), ImageFormat::Png)
        .map_err(|error| ApkError::IconError(format!("Failed to encode PNG: {error}")))?;
    Ok(output)
}

/// Extract a quoted value after a key like: name='value'
fn extract_quoted_value(line: &str, key: &str) -> Option<String> {
    let start = line.find(key)? + key.len();
    let rest = &line[start..];

    let quote_char = rest.chars().next()?;
    if quote_char != '\'' && quote_char != '"' {
        return None;
    }

    let value_start = 1;
    let value_end = rest[value_start..].find(quote_char)? + value_start;

    Some(rest[value_start..value_end].to_string())
}

/// Extract a quoted value after a colon like: sdkVersion:'26'
fn extract_quoted_value_colon(line: &str) -> Option<String> {
    let colon_pos = line.find(':')?;
    let rest = &line[colon_pos + 1..];

    let quote_char = rest.chars().next()?;
    if quote_char != '\'' && quote_char != '"' {
        return None;
    }

    let value_start = 1;
    let value_end = rest[value_start..].find(quote_char)? + value_start;

    Some(rest[value_start..value_end].to_string())
}

/// Parse an icon line like: application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'
fn parse_icon_line(line: &str) -> Option<(i32, String)> {
    // Extract density from "application-icon-640"
    let prefix = "application-icon-";
    if !line.starts_with(prefix) {
        return None;
    }

    let colon_pos = line.find(':')?;
    let density_str = &line[prefix.len()..colon_pos];
    let density: i32 = density_str.parse().ok()?;

    let path = extract_quoted_value_colon(line)?;

    Some((density, path))
}

/// Process icon: convert to PNG and resize to 192x192
fn process_icon(data: &[u8]) -> Result<Vec<u8>, ApkError> {
    // Try to load the image (supports PNG, WebP, etc.)
    let img = image::load_from_memory(data)
        .map_err(|e| ApkError::IconError(format!("Invalid image: {}", e)))?;

    // Resize to 192x192 (standard launcher icon size)
    let resized = img.resize_exact(192, 192, FilterType::Lanczos3);

    // Convert to PNG
    let mut output = Vec::new();
    resized
        .write_to(&mut Cursor::new(&mut output), ImageFormat::Png)
        .map_err(|e| ApkError::IconError(format!("Failed to encode PNG: {}", e)))?;

    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::GenericImageView;
    use std::time::{Duration, Instant};
    use tempfile::tempdir;

    #[test]
    fn test_extract_quoted_value() {
        let line = "package: name='com.example.app' versionCode='10' versionName='1.0.0'";

        assert_eq!(
            extract_quoted_value(line, "name="),
            Some("com.example.app".to_string())
        );
        assert_eq!(
            extract_quoted_value(line, "versionCode="),
            Some("10".to_string())
        );
        assert_eq!(
            extract_quoted_value(line, "versionName="),
            Some("1.0.0".to_string())
        );
        assert_eq!(extract_quoted_value(line, "missing="), None);
    }

    #[test]
    fn test_extract_quoted_value_colon() {
        assert_eq!(
            extract_quoted_value_colon("sdkVersion:'26'"),
            Some("26".to_string())
        );
        assert_eq!(
            extract_quoted_value_colon("application-label:'My App'"),
            Some("My App".to_string())
        );
        assert_eq!(
            extract_quoted_value_colon("application-label:\"My App\""),
            Some("My App".to_string())
        );
    }

    #[test]
    fn test_parse_icon_line() {
        let line = "application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'";
        let result = parse_icon_line(line);
        assert_eq!(
            result,
            Some((640, "res/mipmap-xxxhdpi-v4/ic_launcher.png".to_string()))
        );

        let line2 = "application-icon-160:'res/mipmap-mdpi/ic_launcher.png'";
        let result2 = parse_icon_line(line2);
        assert_eq!(
            result2,
            Some((160, "res/mipmap-mdpi/ic_launcher.png".to_string()))
        );
    }

    #[test]
    fn test_parse_aapt2_output() {
        let output = r#"package: name='com.example.myapp' versionCode='42' versionName='2.1.0' compileSdkVersion='34'
sdkVersion:'26'
targetSdkVersion:'34'
application-label:'My Awesome App'
application-icon-160:'res/mipmap-mdpi-v4/ic_launcher.png'
application-icon-240:'res/mipmap-hdpi-v4/ic_launcher.png'
application-icon-320:'res/mipmap-xhdpi-v4/ic_launcher.png'
application-icon-480:'res/mipmap-xxhdpi-v4/ic_launcher.png'
application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'
"#;

        let parsed = parse_aapt2_output(output).unwrap();

        assert_eq!(parsed.package_name, "com.example.myapp");
        assert_eq!(parsed.version_code, 42);
        assert_eq!(parsed.version_name, "2.1.0");
        assert_eq!(parsed.min_sdk, 26);
        assert_eq!(parsed.app_name, "My Awesome App");
        assert_eq!(
            parsed.icon_paths,
            vec![
                "res/mipmap-xxxhdpi-v4/ic_launcher.png".to_string(),
                "res/mipmap-xxhdpi-v4/ic_launcher.png".to_string(),
                "res/mipmap-xhdpi-v4/ic_launcher.png".to_string(),
                "res/mipmap-hdpi-v4/ic_launcher.png".to_string(),
                "res/mipmap-mdpi-v4/ic_launcher.png".to_string(),
            ]
        );
    }

    #[test]
    fn test_parse_aapt2_output_minimal() {
        let output = "package: name='com.test' versionCode='1'\n";

        let parsed = parse_aapt2_output(output).unwrap();

        assert_eq!(parsed.package_name, "com.test");
        assert_eq!(parsed.version_code, 1);
        assert_eq!(parsed.version_name, "1"); // Falls back to version code
        assert_eq!(parsed.min_sdk, 21); // Default
        assert_eq!(parsed.app_name, "com.test"); // Falls back to package name
        assert!(parsed.icon_paths.is_empty());
    }

    #[test]
    fn test_parse_generic_application_icon() {
        let output = "package: name='com.test' versionCode='1'\n\
                      application: label='Test' icon='res/mipmap/icon.webp'\n";

        let parsed = parse_aapt2_output(output).unwrap();

        assert_eq!(parsed.icon_paths, vec!["res/mipmap/icon.webp"]);
    }

    #[test]
    fn test_parse_resource_file_variants_for_minified_adaptive_icon() {
        let output = r#"
    resource 0x7f0c0000 mipmap/ic_launcher
      (mdpi) (file) res/d2.webp
      (hdpi) (file) res/MO.webp
      (xhdpi) (file) res/qs.webp
      (xxhdpi) (file) res/Sn.webp
      (xxxhdpi) (file) res/sK.webp
      (anydpi-v26) (file) res/BW.xml type=XML
    resource 0x7f0c0001 mipmap/ic_launcher_round
      (xxxhdpi) (file) res/other.webp
"#;

        assert_eq!(
            parse_resource_file_variants(output, "res/BW.xml"),
            vec![
                "res/sK.webp".to_string(),
                "res/Sn.webp".to_string(),
                "res/qs.webp".to_string(),
                "res/MO.webp".to_string(),
                "res/d2.webp".to_string(),
            ]
        );
    }

    #[test]
    fn vector_drawable_is_rendered_to_png() {
        let xmltree = r##"
  E: vector
    A: android:viewportWidth(0x01010402)=48
    A: android:viewportHeight(0x01010403)=48
      E: path
        A: android:fillColor(0x01010404)=#ff006a67
        A: android:pathData(0x01010405)="M0,0h48v48h-48z" (Raw: "M0,0h48v48h-48z")
      E: path
        A: android:fillColor(0x01010404)=#ffffffff
        A: android:pathData(0x01010405)="M8,23.5L24,10l16,13.5v15a2,2 0,0 1,-2 2h-9v-11h-10v11h-9a2,2 0,0 1,-2 -2z"
"##;

        let icon = render_xml_layer(xmltree).unwrap();
        let decoded = image::load_from_memory(&icon).unwrap();
        assert_eq!((decoded.width(), decoded.height()), (192, 192));
        assert_eq!(decoded.get_pixel(0, 0), image::Rgba([0, 106, 103, 255]));
        assert_eq!(decoded.get_pixel(96, 96), image::Rgba([255, 255, 255, 255]));
    }

    #[test]
    fn adaptive_icon_references_resolve_to_xml_files() {
        let xmltree = r#"
  E: adaptive-icon
      E: background
        A: android:drawable(0x01010199)=@0x7f060007
      E: foreground
        A: android:drawable(0x01010199)=@0x7f060008
"#;
        let resources = r#"
    resource 0x7f060007 drawable/ic_launcher_background
      () (file) res/background.xml type=XML
    resource 0x7f060008 drawable/ic_launcher_foreground
      () (file) res/foreground.xml type=XML
"#;

        let (background, foreground) = parse_adaptive_icon_refs(xmltree).unwrap();
        assert_eq!(
            resource_file_for_id(resources, &background).as_deref(),
            Some("res/background.xml")
        );
        assert_eq!(
            resource_file_for_id(resources, &foreground).as_deref(),
            Some("res/foreground.xml")
        );
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn adaptive_icon_uses_highest_density_raster_variant() {
        use image::{DynamicImage, GenericImageView, Rgba, RgbaImage};
        use std::io::Write;
        use std::os::unix::fs::PermissionsExt;
        use zip::write::SimpleFileOptions;

        let temp = tempdir().unwrap();
        let apk = temp.path().join("adaptive.apk");
        let file = std::fs::File::create(&apk).unwrap();
        let mut archive = zip::ZipWriter::new(file);
        let options = SimpleFileOptions::default();
        archive.start_file("res/icon.xml", options).unwrap();
        archive.write_all(b"not a raster image").unwrap();

        let mut raster = Vec::new();
        DynamicImage::ImageRgba8(RgbaImage::from_pixel(2, 2, Rgba([1, 2, 3, 255])))
            .write_to(&mut Cursor::new(&mut raster), ImageFormat::Png)
            .unwrap();
        archive.start_file("res/icon-640.png", options).unwrap();
        archive.write_all(&raster).unwrap();
        archive.finish().unwrap();

        let fake_aapt2 = temp.path().join("aapt2");
        std::fs::write(
            &fake_aapt2,
            "#!/bin/sh\n\
             if [ \"$2\" = badging ]; then\n\
               printf \"package: name='com.test' versionCode='1'\\napplication-label:'Test'\\napplication-icon-65534:'res/icon.xml'\\n\"\n\
             else\n\
               printf \"resource 0x7f010000 mipmap/icon\\n  (xxxhdpi) (file) res/icon-640.png\\n  (anydpi-v26) (file) res/icon.xml type=XML\\n\"\n\
             fi\n",
        )
        .unwrap();
        std::fs::set_permissions(&fake_aapt2, std::fs::Permissions::from_mode(0o755)).unwrap();

        let metadata = ApkParser::new(fake_aapt2).parse(&apk).await.unwrap();
        let icon = metadata
            .icon_data
            .expect("raster fallback should be extracted");
        let decoded = image::load_from_memory(&icon).unwrap();
        assert_eq!((decoded.width(), decoded.height()), (192, 192));
        assert_eq!(decoded.get_pixel(0, 0), Rgba([1, 2, 3, 255]));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn aapt2_process_is_terminated_when_it_times_out() {
        use std::os::unix::fs::PermissionsExt;

        let temp = tempdir().unwrap();
        let fake_aapt2 = temp.path().join("slow-aapt2");
        std::fs::write(&fake_aapt2, "#!/bin/sh\nexec sleep 10\n").unwrap();
        std::fs::set_permissions(&fake_aapt2, std::fs::Permissions::from_mode(0o755)).unwrap();
        let apk = temp.path().join("app.apk");
        std::fs::write(&apk, b"PK").unwrap();
        let parser = ApkParser::with_timeout(fake_aapt2, Duration::from_millis(50));

        let started = Instant::now();
        let result = parser.parse(&apk).await;

        assert!(matches!(result, Err(ApkError::TimedOut)));
        assert!(started.elapsed() < Duration::from_secs(2));
    }
}
