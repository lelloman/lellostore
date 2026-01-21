#!/usr/bin/env python3
"""
Publish APKs to a Lellostore instance.

Usage:
    ./publish-to-lellostore.py configure          # First-time setup
    ./publish-to-lellostore.py <app.apk>          # Upload an APK
    ./publish-to-lellostore.py --help             # Show help

On first run, use 'configure' to set your store URL and OIDC issuer.
Authentication uses OAuth2 device flow - no passwords stored in the script.
"""

import sys
import os
import re
import json
import time
import hashlib
import urllib.request
import urllib.parse
import urllib.error
from pathlib import Path

# =============================================================================
# CONFIGURATION (set via 'configure' subcommand)
# =============================================================================
STORE_URL = "https://store.lelloman.com"
OIDC_ISSUER = "https://auth.lelloman.com"
CLIENT_ID = "22cd4a2d-a771-41e3-b76e-3f83ff8e9bbf"
# =============================================================================

CACHE_DIR = Path.home() / ".cache" / "lellostore"
TOKEN_FILE = CACHE_DIR / "token.json"


def is_configured() -> bool:
    """Check if the script has been configured."""
    return not (STORE_URL.startswith("__") or OIDC_ISSUER.startswith("__"))


def configure():
    """Interactive configuration - rewrites this script with user values."""
    print("Lellostore Publisher - Configuration\n")

    store_url = input("Store URL (e.g., https://store.example.com): ").strip().rstrip("/")
    if not store_url:
        print("Error: Store URL is required")
        sys.exit(1)

    oidc_issuer = input("OIDC Issuer URL (e.g., https://auth.example.com/realms/myrealm): ").strip().rstrip("/")
    if not oidc_issuer:
        print("Error: OIDC Issuer is required")
        sys.exit(1)

    client_id = input("OIDC Client ID [lellostore-cli]: ").strip() or "lellostore-cli"

    # Read ourselves
    script_path = Path(__file__).resolve()
    content = script_path.read_text()

    # Replace placeholders
    content = re.sub(r'STORE_URL = "[^"]*"', f'STORE_URL = "{store_url}"', content)
    content = re.sub(r'OIDC_ISSUER = "[^"]*"', f'OIDC_ISSUER = "{oidc_issuer}"', content)
    content = re.sub(r'CLIENT_ID = "[^"]*"', f'CLIENT_ID = "{client_id}"', content)

    # Write back
    script_path.write_text(content)

    print(f"\nConfigured successfully!")
    print(f"  Store:  {store_url}")
    print(f"  Issuer: {oidc_issuer}")
    print(f"  Client: {client_id}")
    print(f"\nYou can now commit this script to your repository.")
    print(f"Run '{script_path.name} <app.apk>' to upload.")


def http_request(url: str, data: dict = None, headers: dict = None, method: str = None) -> dict:
    """Make an HTTP request and return JSON response."""
    headers = headers or {}

    if data is not None:
        encoded_data = urllib.parse.urlencode(data).encode()
        headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
    else:
        encoded_data = None

    req = urllib.request.Request(url, data=encoded_data, headers=headers, method=method)

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode() if e.fp else ""
        raise RuntimeError(f"HTTP {e.code}: {body}") from e


def discover_oidc() -> dict:
    """Fetch OIDC discovery document."""
    url = f"{OIDC_ISSUER}/.well-known/openid-configuration"
    return http_request(url)


def get_cached_token() -> dict | None:
    """Load cached token if valid."""
    if not TOKEN_FILE.exists():
        return None

    try:
        token = json.loads(TOKEN_FILE.read_text())
        # Check if expired (with 60s buffer)
        if token.get("expires_at", 0) < time.time() + 60:
            return None
        return token
    except (json.JSONDecodeError, KeyError):
        return None


def save_token(token: dict):
    """Cache token to disk."""
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    # Add expiry timestamp
    token["expires_at"] = time.time() + token.get("expires_in", 3600)
    TOKEN_FILE.write_text(json.dumps(token))
    TOKEN_FILE.chmod(0o600)


def refresh_token(refresh_token_str: str, token_endpoint: str) -> dict | None:
    """Attempt to refresh an access token."""
    try:
        return http_request(token_endpoint, data={
            "grant_type": "refresh_token",
            "client_id": CLIENT_ID,
            "refresh_token": refresh_token_str,
        })
    except RuntimeError:
        return None


def device_flow_auth() -> str:
    """Authenticate using OAuth2 device flow. Returns access token."""
    # Check cache first
    cached = get_cached_token()
    if cached:
        return cached["access_token"]

    # Discover OIDC endpoints
    print("Discovering OIDC configuration...")
    oidc_config = discover_oidc()

    device_endpoint = oidc_config.get("device_authorization_endpoint")
    token_endpoint = oidc_config.get("token_endpoint")

    if not device_endpoint:
        print("Error: OIDC provider does not support device flow")
        print("Make sure device authorization is enabled for your client.")
        sys.exit(1)

    # Try refresh if we have an old token
    if TOKEN_FILE.exists():
        try:
            old_token = json.loads(TOKEN_FILE.read_text())
            if "refresh_token" in old_token:
                print("Refreshing token...")
                new_token = refresh_token(old_token["refresh_token"], token_endpoint)
                if new_token:
                    save_token(new_token)
                    return new_token["access_token"]
        except (json.JSONDecodeError, KeyError):
            pass

    # Start device flow
    print("Starting device authorization...")
    device_resp = http_request(device_endpoint, data={
        "client_id": CLIENT_ID,
        "scope": "openid",
    })

    device_code = device_resp["device_code"]
    user_code = device_resp.get("user_code", "")
    verification_uri = device_resp.get("verification_uri_complete") or device_resp.get("verification_uri")
    interval = device_resp.get("interval", 5)
    expires_in = device_resp.get("expires_in", 600)

    print(f"\n{'='*60}")
    print(f"Open this URL in your browser:\n")
    print(f"  {verification_uri}")
    if user_code:
        print(f"\nEnter code: {user_code}")
    print(f"{'='*60}\n")

    # Poll for token
    deadline = time.time() + expires_in
    while time.time() < deadline:
        time.sleep(interval)
        sys.stdout.write(".")
        sys.stdout.flush()

        try:
            token_resp = http_request(token_endpoint, data={
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                "client_id": CLIENT_ID,
                "device_code": device_code,
            })
            print(" authenticated!")
            save_token(token_resp)
            return token_resp["access_token"]
        except RuntimeError as e:
            error_msg = str(e)
            if "authorization_pending" in error_msg or "slow_down" in error_msg:
                continue
            elif "expired" in error_msg:
                print("\nError: Authorization expired. Please try again.")
                sys.exit(1)
            elif "access_denied" in error_msg:
                print("\nError: Authorization denied.")
                sys.exit(1)
            else:
                raise

    print("\nError: Authorization timed out.")
    sys.exit(1)


def upload_apk(apk_path: Path, token: str, name: str = None, description: str = None):
    """Upload an APK to the store."""
    if not apk_path.exists():
        print(f"Error: File not found: {apk_path}")
        sys.exit(1)

    if not apk_path.suffix.lower() in (".apk", ".aab"):
        print(f"Error: File must be an APK or AAB: {apk_path}")
        sys.exit(1)

    file_size = apk_path.stat().st_size
    print(f"Uploading {apk_path.name} ({file_size / 1024 / 1024:.1f} MB)...")

    # Build multipart form data
    boundary = f"----LellostoreBoundary{hashlib.md5(os.urandom(16)).hexdigest()}"

    body_parts = []

    # Add file field
    body_parts.append(f"--{boundary}".encode())
    body_parts.append(f'Content-Disposition: form-data; name="file"; filename="{apk_path.name}"'.encode())
    body_parts.append(b"Content-Type: application/octet-stream")
    body_parts.append(b"")
    body_parts.append(apk_path.read_bytes())

    # Add optional name field
    if name:
        body_parts.append(f"--{boundary}".encode())
        body_parts.append(b'Content-Disposition: form-data; name="name"')
        body_parts.append(b"")
        body_parts.append(name.encode())

    # Add optional description field
    if description:
        body_parts.append(f"--{boundary}".encode())
        body_parts.append(b'Content-Disposition: form-data; name="description"')
        body_parts.append(b"")
        body_parts.append(description.encode())

    body_parts.append(f"--{boundary}--".encode())
    body_parts.append(b"")

    body = b"\r\n".join(body_parts)

    # Make request
    url = f"{STORE_URL}/api/admin/apps"
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Authorization": f"Bearer {token}",
    })

    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            result = json.loads(resp.read().decode())
            print(f"\nSuccess!")
            print(f"  Package: {result.get('packageName')}")
            print(f"  Name:    {result.get('name')}")
            print(f"  Version: {result.get('version', {}).get('versionName')} ({result.get('version', {}).get('versionCode')})")
            return result
    except urllib.error.HTTPError as e:
        body = e.read().decode() if e.fp else ""
        if e.code == 401:
            print(f"\nError: Authentication failed. Try deleting {TOKEN_FILE} and re-authenticating.")
        elif e.code == 403:
            print(f"\nError: Permission denied. Your account may not have admin access.")
        elif e.code == 409:
            print(f"\nError: This version already exists in the store.")
        elif e.code == 413:
            print(f"\nError: File too large.")
        else:
            print(f"\nError: Upload failed (HTTP {e.code})")
            if body:
                print(f"  {body}")
        sys.exit(1)


def print_help():
    """Print usage information."""
    script_name = Path(__file__).name
    print(__doc__)
    print(f"Examples:")
    print(f"  {script_name} configure                    # Set up store URL and auth")
    print(f"  {script_name} app-release.apk              # Upload an APK")
    print(f"  {script_name} app.apk --name 'My App'      # Upload with custom name")
    print(f"  {script_name} --logout                     # Clear cached auth token")

    if is_configured():
        print(f"\nCurrent configuration:")
        print(f"  Store:  {STORE_URL}")
        print(f"  Issuer: {OIDC_ISSUER}")
        print(f"  Client: {CLIENT_ID}")
    else:
        print(f"\nNot configured yet. Run '{script_name} configure' first.")


def logout():
    """Clear cached authentication."""
    if TOKEN_FILE.exists():
        TOKEN_FILE.unlink()
        print("Logged out. Token cache cleared.")
    else:
        print("No cached token found.")


def main():
    args = sys.argv[1:]

    if not args or args[0] in ("--help", "-h", "help"):
        print_help()
        sys.exit(0)

    if args[0] == "configure":
        configure()
        sys.exit(0)

    if args[0] == "--logout":
        logout()
        sys.exit(0)

    if not is_configured():
        print("Error: Script not configured yet.")
        print(f"Run '{Path(__file__).name} configure' first.")
        sys.exit(1)

    # Parse upload arguments
    apk_path = None
    name = None
    description = None

    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "--name" and i + 1 < len(args):
            name = args[i + 1]
            i += 2
        elif arg == "--description" and i + 1 < len(args):
            description = args[i + 1]
            i += 2
        elif not arg.startswith("-"):
            apk_path = Path(arg)
            i += 1
        else:
            print(f"Unknown option: {arg}")
            sys.exit(1)

    if not apk_path:
        print("Error: No APK file specified")
        print_help()
        sys.exit(1)

    # Authenticate and upload
    token = device_flow_auth()
    upload_apk(apk_path, token, name, description)


if __name__ == "__main__":
    main()
