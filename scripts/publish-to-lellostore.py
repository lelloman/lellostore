#!/usr/bin/env python3
"""Publish APK or AAB artifacts to a LelloStore instance.

Configuration is supplied with command-line options or these environment
variables: LELLOSTORE_URL, LELLOSTORE_OIDC_ISSUER, and LELLOSTORE_CLIENT_ID.
Authentication uses the OIDC Device Authorization Grant.
"""

import argparse
import hashlib
import http.client
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Mapping


VERSION = "2.0.0"
DEFAULT_CACHE_DIR = Path.home() / ".cache" / "lellostore" / "tokens"
UPLOAD_CHUNK_SIZE = 1024 * 1024


class PublisherError(Exception):
    def __init__(self, message: str, exit_code: int = 1):
        super().__init__(message)
        self.exit_code = exit_code


class HttpError(PublisherError):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body}" if body else f"HTTP {status}")
        self.status = status
        self.body = body


class PublisherConfig:
    def __init__(
        self,
        store_url: str,
        issuer: str,
        client_id: str,
        allow_insecure_http: bool = False,
    ):
        self.store_url = store_url.rstrip("/")
        self.issuer = issuer.rstrip("/")
        self.client_id = client_id
        self.allow_insecure_http = allow_insecure_http

    @classmethod
    def resolve(
        cls,
        store_url: str | None,
        issuer: str | None,
        client_id: str | None,
        environ: Mapping[str, str],
        allow_insecure_http: bool = False,
    ) -> "PublisherConfig":
        values = {
            "store_url": (store_url or environ.get("LELLOSTORE_URL", "")).strip(),
            "issuer": (issuer or environ.get("LELLOSTORE_OIDC_ISSUER", "")).strip(),
            "client_id": (client_id or environ.get("LELLOSTORE_CLIENT_ID", "")).strip(),
        }
        missing = [
            variable
            for key, variable in (
                ("store_url", "LELLOSTORE_URL"),
                ("issuer", "LELLOSTORE_OIDC_ISSUER"),
                ("client_id", "LELLOSTORE_CLIENT_ID"),
            )
            if not values[key]
        ]
        if missing:
            raise PublisherError(
                "Missing configuration: " + ", ".join(missing) + ". "
                "Set environment variables or pass the corresponding options."
            )

        normalized_store = _normalize_url(
            values["store_url"],
            "store URL",
            allow_insecure_http,
        )
        normalized_issuer = _normalize_url(
            values["issuer"],
            "OIDC issuer",
            allow_insecure_http,
        )
        return cls(
            normalized_store,
            normalized_issuer,
            values["client_id"],
            allow_insecure_http,
        )


def _normalize_url(url: str, label: str, allow_insecure_http: bool) -> str:
    parsed = urllib.parse.urlsplit(url.rstrip("/"))
    if not parsed.hostname or parsed.username or parsed.password:
        raise PublisherError(f"Invalid {label}: {url}")
    if parsed.query or parsed.fragment:
        raise PublisherError(f"Invalid {label}: query strings and fragments are not allowed")
    if parsed.scheme != "https" and not (
        allow_insecure_http and parsed.scheme == "http"
    ):
        raise PublisherError(
            f"{label.capitalize()} must use HTTPS "
            "(use --allow-insecure-http only for local development)"
        )
    return urllib.parse.urlunsplit(parsed).rstrip("/")


def token_file_for(
    config: PublisherConfig,
    cache_dir: Path = DEFAULT_CACHE_DIR,
) -> Path:
    identity = f"{config.issuer}\0{config.client_id}".encode()
    cache_key = hashlib.sha256(identity).hexdigest()
    return cache_dir / f"{cache_key}.json"


def load_token(token_file: Path) -> dict | None:
    if not token_file.exists():
        return None
    try:
        token = json.loads(token_file.read_text())
    except (OSError, json.JSONDecodeError):
        return None
    return token if isinstance(token, dict) else None


def get_cached_access_token(token_file: Path) -> str | None:
    token = load_token(token_file)
    if not token or token.get("expires_at", 0) < time.time() + 60:
        return None
    access_token = token.get("access_token")
    return access_token if isinstance(access_token, str) else None


def save_token(token_file: Path, token: dict) -> None:
    token_file.parent.mkdir(parents=True, exist_ok=True)
    stored = dict(token)
    stored["expires_at"] = time.time() + stored.get("expires_in", 3600)
    temporary = token_file.with_suffix(f".{os.getpid()}.tmp")
    temporary.write_text(json.dumps(stored))
    temporary.chmod(0o600)
    temporary.replace(token_file)


def http_request(
    url: str,
    data: Mapping[str, str] | None = None,
    timeout: int = 30,
) -> dict:
    encoded_data = urllib.parse.urlencode(data).encode() if data is not None else None
    headers = (
        {"Content-Type": "application/x-www-form-urlencoded"}
        if data is not None
        else {}
    )
    request = urllib.request.Request(url, data=encoded_data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        body = error.read().decode(errors="replace") if error.fp else ""
        raise HttpError(error.code, body) from error
    except (urllib.error.URLError, TimeoutError) as error:
        raise PublisherError(f"Request failed for {url}: {error}") from error
    except json.JSONDecodeError as error:
        raise PublisherError(f"Invalid JSON response from {url}") from error
    if not isinstance(payload, dict):
        raise PublisherError(f"Unexpected response from {url}")
    return payload


def _log(message: str, json_output: bool = False, end: str = "\n") -> None:
    stream = sys.stderr if json_output else sys.stdout
    print(message, file=stream, end=end, flush=True)


def discover_oidc(config: PublisherConfig) -> dict:
    return http_request(f"{config.issuer}/.well-known/openid-configuration")


def _validate_discovered_endpoint(url: str, config: PublisherConfig, label: str) -> str:
    return _normalize_url(url, label, config.allow_insecure_http)


def refresh_access_token(
    refresh_token: str,
    token_endpoint: str,
    config: PublisherConfig,
) -> dict | None:
    try:
        return http_request(
            token_endpoint,
            data={
                "grant_type": "refresh_token",
                "client_id": config.client_id,
                "refresh_token": refresh_token,
            },
        )
    except PublisherError:
        return None


def device_flow_auth(
    config: PublisherConfig,
    json_output: bool = False,
    cache_dir: Path = DEFAULT_CACHE_DIR,
) -> str:
    token_file = token_file_for(config, cache_dir)
    cached_access_token = get_cached_access_token(token_file)
    if cached_access_token:
        return cached_access_token

    _log("Discovering OIDC configuration...", json_output)
    discovery = discover_oidc(config)
    raw_device_endpoint = discovery.get("device_authorization_endpoint")
    raw_token_endpoint = discovery.get("token_endpoint")
    if not isinstance(raw_device_endpoint, str):
        raise PublisherError(
            "OIDC provider does not advertise a device authorization endpoint"
        )
    if not isinstance(raw_token_endpoint, str):
        raise PublisherError("OIDC provider does not advertise a token endpoint")
    device_endpoint = _validate_discovered_endpoint(
        raw_device_endpoint,
        config,
        "device authorization endpoint",
    )
    token_endpoint = _validate_discovered_endpoint(
        raw_token_endpoint,
        config,
        "token endpoint",
    )

    old_token = load_token(token_file)
    if old_token and isinstance(old_token.get("refresh_token"), str):
        _log("Refreshing token...", json_output)
        refreshed = refresh_access_token(
            old_token["refresh_token"],
            token_endpoint,
            config,
        )
        if refreshed and isinstance(refreshed.get("access_token"), str):
            save_token(token_file, refreshed)
            return refreshed["access_token"]

    _log("Starting device authorization...", json_output)
    device_response = http_request(
        device_endpoint,
        data={"client_id": config.client_id, "scope": "openid"},
    )
    try:
        device_code = device_response["device_code"]
        expires_in = int(device_response.get("expires_in", 600))
        interval = max(1, int(device_response.get("interval", 5)))
    except (KeyError, TypeError, ValueError) as error:
        raise PublisherError("Invalid device authorization response") from error
    verification_uri = device_response.get("verification_uri_complete") or device_response.get(
        "verification_uri"
    )
    if not isinstance(device_code, str) or not isinstance(verification_uri, str):
        raise PublisherError("Invalid device authorization response")

    user_code = device_response.get("user_code")
    _log("", json_output)
    _log(f"Open this URL in your browser:\n  {verification_uri}", json_output)
    if isinstance(user_code, str) and user_code:
        _log(f"Enter code: {user_code}", json_output)

    deadline = time.time() + expires_in
    while time.time() < deadline:
        time.sleep(interval)
        _log(".", json_output, end="")
        try:
            token = http_request(
                token_endpoint,
                data={
                    "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id": config.client_id,
                    "device_code": device_code,
                },
            )
        except HttpError as error:
            if "authorization_pending" in error.body:
                continue
            if "slow_down" in error.body:
                interval += 5
                continue
            if "expired" in error.body:
                raise PublisherError("Device authorization expired") from error
            if "access_denied" in error.body:
                raise PublisherError("Device authorization was denied") from error
            raise
        access_token = token.get("access_token")
        if not isinstance(access_token, str):
            raise PublisherError("Token response did not contain an access token")
        _log(" authenticated!", json_output)
        save_token(token_file, token)
        return access_token
    raise PublisherError("Device authorization timed out")


def validate_artifact(artifact: Path) -> dict:
    artifact = artifact.expanduser().resolve()
    if not artifact.is_file():
        raise PublisherError(f"Artifact not found: {artifact}")
    artifact_type = artifact.suffix.lower().lstrip(".")
    if artifact_type not in {"apk", "aab"}:
        raise PublisherError(f"Artifact must be an APK or AAB: {artifact}")
    size = artifact.stat().st_size
    if size == 0:
        raise PublisherError(f"Artifact is empty: {artifact}")
    return {
        "status": "valid",
        "artifact": str(artifact),
        "artifact_type": artifact_type,
        "size": size,
    }


def _multipart_field(boundary: str, name: str, value: str) -> bytes:
    return (
        f"\r\n--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
        f"{value}"
    ).encode()


def _open_connection(parsed_url, timeout: int = 300):
    connection_type = (
        http.client.HTTPSConnection
        if parsed_url.scheme == "https"
        else http.client.HTTPConnection
    )
    return connection_type(parsed_url.hostname, parsed_url.port, timeout=timeout)


def upload_artifact(
    artifact: Path,
    token: str,
    config: PublisherConfig,
    name: str | None = None,
    description: str | None = None,
    json_output: bool = False,
) -> dict:
    artifact_info = validate_artifact(artifact)
    artifact = Path(artifact_info["artifact"])
    boundary = f"LelloStore-{secrets.token_hex(16)}"
    safe_filename = artifact.name.replace('"', "_").replace("\r", "_").replace("\n", "_")
    file_header = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{safe_filename}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    trailing_parts = []
    if name:
        trailing_parts.append(_multipart_field(boundary, "name", name))
    if description:
        trailing_parts.append(_multipart_field(boundary, "description", description))
    trailing_parts.append(f"\r\n--{boundary}--\r\n".encode())
    content_length = len(file_header) + artifact_info["size"] + sum(
        len(part) for part in trailing_parts
    )

    parsed_store = urllib.parse.urlsplit(config.store_url)
    upload_path = f"{parsed_store.path.rstrip('/')}/api/admin/apps"
    connection = _open_connection(parsed_store, timeout=300)
    _log(
        f"Uploading {artifact.name} ({artifact_info['size'] / 1024 / 1024:.1f} MB)...",
        json_output,
    )
    try:
        connection.putrequest("POST", upload_path)
        connection.putheader("Content-Type", f"multipart/form-data; boundary={boundary}")
        connection.putheader("Content-Length", str(content_length))
        connection.putheader("Authorization", f"Bearer {token}")
        connection.endheaders()
        connection.send(file_header)
        with artifact.open("rb") as artifact_stream:
            while chunk := artifact_stream.read(UPLOAD_CHUNK_SIZE):
                connection.send(chunk)
        for part in trailing_parts:
            connection.send(part)
        response = connection.getresponse()
        body = response.read().decode(errors="replace")
    except (OSError, http.client.HTTPException) as error:
        raise PublisherError(f"Upload request failed: {error}") from error
    finally:
        connection.close()

    if not 200 <= response.status < 300:
        friendly_errors = {
            401: "Authentication failed; run the logout command and authenticate again",
            403: "Permission denied; the authenticated user needs the administrator role",
            409: "This application version already exists",
            413: "The artifact exceeds the server upload limit",
            415: "The server rejected the artifact type",
        }
        message = friendly_errors.get(
            response.status,
            f"Upload failed with HTTP {response.status} {response.reason}",
        )
        if body and response.status not in friendly_errors:
            message = f"{message}: {body}"
        raise PublisherError(message)
    try:
        result = json.loads(body)
    except json.JSONDecodeError as error:
        raise PublisherError("Upload succeeded but returned invalid JSON") from error
    if not isinstance(result, dict):
        raise PublisherError("Upload succeeded but returned an unexpected response")

    if json_output:
        print(json.dumps(result, sort_keys=True))
    else:
        version = result.get("version") or {}
        print("\nSuccess!")
        print(f"  Package: {result.get('package_name')}")
        print(f"  Name:    {result.get('name')}")
        print(
            f"  Version: {version.get('version_name')} "
            f"({version.get('version_code')})"
        )
    return result


def _add_configuration_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--store-url", help="LelloStore base URL (LELLOSTORE_URL)")
    parser.add_argument(
        "--issuer",
        help="OIDC issuer URL (LELLOSTORE_OIDC_ISSUER)",
    )
    parser.add_argument(
        "--client-id",
        help="OIDC public client ID (LELLOSTORE_CLIENT_ID)",
    )
    parser.add_argument(
        "--allow-insecure-http",
        action="store_true",
        help="Allow HTTP endpoints for local development only",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="version", version=f"%(prog)s {VERSION}")
    commands = parser.add_subparsers(dest="command", required=True)

    upload = commands.add_parser("upload", help="Upload an APK or AAB")
    upload.add_argument("artifact", type=Path)
    upload.add_argument("--name", help="Override the application name")
    upload.add_argument("--description", help="Override the application description")
    upload.add_argument("--dry-run", action="store_true", help="Validate without authenticating or uploading")
    upload.add_argument("--json", action="store_true", help="Print the result as JSON")
    upload.add_argument("--yes", action="store_true", help="Skip the interactive upload confirmation")
    _add_configuration_arguments(upload)

    logout = commands.add_parser("logout", help="Delete the cached token for this issuer and client")
    logout.add_argument("--json", action="store_true", help="Print the result as JSON")
    _add_configuration_arguments(logout)
    return parser


def _prepare_legacy_invocation(arguments: list[str]) -> list[str]:
    if arguments and arguments[0] not in {"upload", "logout", "-h", "--help", "--version"}:
        if not arguments[0].startswith("-"):
            return ["upload", *arguments]
    return arguments


def _confirm_upload(artifact: Path) -> None:
    if not sys.stdin.isatty():
        raise PublisherError(
            "Upload confirmation requires an interactive terminal; pass --yes only after approval"
        )
    answer = input(f"Upload {artifact} to LelloStore? Type 'upload' to continue: ")
    if answer.strip().lower() != "upload":
        raise PublisherError("Upload cancelled", exit_code=2)


def main(
    argv: list[str] | None = None,
    environ: Mapping[str, str] | None = None,
) -> int:
    arguments = _prepare_legacy_invocation(list(sys.argv[1:] if argv is None else argv))
    parser = build_parser()
    parsed = parser.parse_args(arguments)
    environment = os.environ if environ is None else environ
    json_output = bool(getattr(parsed, "json", False))
    try:
        config = PublisherConfig.resolve(
            parsed.store_url,
            parsed.issuer,
            parsed.client_id,
            environment,
            parsed.allow_insecure_http,
        )
        if parsed.command == "logout":
            token_file = token_file_for(config)
            removed = token_file.exists()
            if removed:
                token_file.unlink()
            result = {"status": "logged_out", "token_removed": removed}
            print(json.dumps(result, sort_keys=True) if json_output else "Token cache cleared." if removed else "No cached token found.")
            return 0

        artifact_info = validate_artifact(parsed.artifact)
        if parsed.dry_run:
            if json_output:
                print(json.dumps(artifact_info, sort_keys=True))
            else:
                print(
                    f"Valid {artifact_info['artifact_type'].upper()} artifact: "
                    f"{artifact_info['artifact']} ({artifact_info['size']} bytes)"
                )
            return 0
        if not parsed.yes:
            _confirm_upload(Path(artifact_info["artifact"]))
        token = device_flow_auth(config, json_output=json_output)
        upload_artifact(
            Path(artifact_info["artifact"]),
            token,
            config,
            name=parsed.name,
            description=parsed.description,
            json_output=json_output,
        )
        return 0
    except PublisherError as error:
        if json_output:
            print(
                json.dumps(
                    {"error": "publisher_error", "message": str(error)},
                    sort_keys=True,
                )
            )
        else:
            print(f"Error: {error}", file=sys.stderr)
        return error.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
