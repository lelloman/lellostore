#!/usr/bin/env python3
"""Create a deployment configuration interactively or from explicit arguments."""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


def https_url(value: str, *, origin: bool = False) -> str:
    parsed = urllib.parse.urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or any(c.isspace() for c in value)
        or (origin and parsed.path not in ("", "/"))
    ):
        raise ValueError("Use an HTTPS " + ("origin without a path" if origin else "issuer URL") + ", without credentials, query, or fragment")
    if origin and parsed.port not in (None, 443):
        raise ValueError("The bundled HTTPS proxy requires the standard public port 443")
    return value.rstrip("/") if origin else value


def validate_text(value: str, label: str, *, maximum: int = 256, spaces: bool = False) -> str:
    if not value.strip() or len(value) > maximum or any(ord(c) < 32 or ord(c) == 127 for c in value) or "$" in value:
        raise ValueError(f"Invalid {label}: use a nonempty value without control characters or dollar expansion")
    if not spaces and any(c.isspace() for c in value):
        raise ValueError(f"Invalid {label}: whitespace is not allowed")
    return value


def deployment_config(args: argparse.Namespace) -> dict[str, str]:
    public_url = https_url(args.server_url, origin=True)
    issuer = https_url(args.issuer)
    if issuer == "https://example.com":
        raise ValueError("Replace the placeholder identity provider with your actual issuer")
    scopes = args.scopes.split()
    if "openid" not in scopes or len(scopes) > 32 or any(not re.fullmatch(r'[\x21\x23-\x5b\x5d-\x7e]{1,128}', scope) for scope in scopes):
        raise ValueError("OIDC scopes must include openid and contain valid OAuth scope names")
    values = {
        "STORE_HOST": urllib.parse.urlsplit(public_url).hostname,
        "PUBLIC_BASE_URL": public_url,
        "STORE_NAME": validate_text(args.name, "store name", maximum=120, spaces=True),
        "OIDC_ISSUER_URL": issuer,
        "OIDC_AUDIENCE": validate_text(args.audience, "audience"),
        "OIDC_ANDROID_CLIENT_ID": validate_text(args.android_client, "Android client ID"),
        "OIDC_WEB_CLIENT_ID": validate_text(args.web_client, "web client ID"),
        "OIDC_PUBLISHER_CLIENT_ID": validate_text(args.publisher_client, "publisher client ID"),
        "OIDC_SCOPES": " ".join(scopes),
        "OIDC_ADMIN_ROLE": validate_text(args.admin_role, "administrator role"),
        "OIDC_ROLE_CLAIM_PATH": validate_text(args.role_claim_path, "role claim path"),
        "LISTEN_ADDR": "0.0.0.0:8080",
        "DATABASE_URL": "sqlite:/app/data/store.db?mode=rwc",
        "STORAGE_PATH": "/app/data/storage",
        "NOTIFICATIONS_ENABLED": "true" if args.push else "false",
        "PUSH_PUBLIC_BASE_URL": public_url if args.push else "",
    }
    for value in values.values():
        if "$" in value or "\n" in value or "\r" in value:
            raise ValueError("Configuration values must not contain environment expansion or newlines")
    return values


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("The identity discovery URL redirected; configure its canonical issuer URL")


def verify_provider(issuer: str) -> None:
    endpoint = issuer.rstrip("/") + "/.well-known/openid-configuration"
    with urllib.request.build_opener(NoRedirect()).open(endpoint, timeout=15) as response:
        data = response.read(65537)
    if len(data) > 65536:
        raise ValueError("Identity provider metadata is too large")
    metadata = json.loads(data)
    if not isinstance(metadata, dict):
        raise ValueError("Identity provider metadata must be an object")
    if metadata.get("issuer") != issuer:
        raise ValueError("Identity provider metadata does not match the configured issuer")
    for key in ("authorization_endpoint", "token_endpoint", "jwks_uri", "device_authorization_endpoint"):
        https_url(metadata.get(key, ""))
    if "S256" not in metadata.get("code_challenge_methods_supported", []):
        raise ValueError("The provider must advertise PKCE S256 support")


def write_config(path: Path, values: dict[str, str]) -> None:
    # Never overwrite an existing deployment; an operator can review a new file.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as output:
        output.write("# Generated deployment configuration. Changes take effect after restart.\n")
        for key, value in values.items():
            output.write(f"{key}={json.dumps(value, ensure_ascii=False)}\n")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("deployment/store.env"))
    parser.add_argument("--non-interactive", action="store_true")
    parser.add_argument("--verify-provider", action="store_true", help="Check public OIDC metadata before writing")
    fields = [
        ("name", "Store name", None), ("server-url", "Public HTTPS store URL", None),
        ("issuer", "OIDC issuer URL", None), ("audience", "Access-token audience", "store"),
        ("android-client", "Android public client ID", "store-android"),
        ("web-client", "Web public client ID", "store-web"),
        ("publisher-client", "Publisher public client ID", "store-publisher"),
        ("admin-role", "Initial administrator role", "admin"),
        ("role-claim-path", "Role claim path", "realm_access.roles"),
        ("scopes", "OIDC scopes", "openid profile email"),
    ]
    for flag, _, _ in fields:
        parser.add_argument("--" + flag)
    parser.add_argument("--push", action="store_true", help="Enable the optional UnifiedPush broker")
    args = parser.parse_args(argv)
    try:
        for flag, prompt, default in fields:
            attr = flag.replace("-", "_")
            if getattr(args, attr) is not None:
                continue
            if args.non_interactive or not sys.stdin.isatty():
                if default is None:
                    raise ValueError(f"Missing --{flag}")
                value = default
            else:
                value = input(prompt + (f" [{default}]" if default else "") + ": ").strip() or default
                if value is None:
                    raise ValueError(f"Missing {prompt}")
            setattr(args, attr, value)
        values = deployment_config(args)
        if args.verify_provider:
            verify_provider(values["OIDC_ISSUER_URL"])
        write_config(args.output, values)
    except (OSError, ValueError, urllib.error.URLError) as error:
        print(f"Setup failed: {error}", file=sys.stderr)
        return 1
    print(f"Created {args.output}. Register the three public clients with your identity provider.")
    print(f"Web callback: {values['PUBLIC_BASE_URL']}/callback")
    print("Android callback: com.lelloman.store:/oauth2redirect")
    print(f"Assign role {values['OIDC_ADMIN_ROLE']} to the initial administrator in the configured claim.")
    print("Follow docs/DEPLOYMENT.md to start the store and verify access.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
