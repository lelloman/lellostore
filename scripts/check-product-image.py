#!/usr/bin/env python3
"""Verify two isolated configurations of one image; no live deployment is used.

This smoke test intentionally uses unreachable issuers to verify discovery and
fail-closed API behavior during a provider outage. It does not prove real login.
"""
import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def get(url):
    try:
        response = urllib.request.urlopen(url, timeout=2)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image")
    args = parser.parse_args()
    containers = []
    try:
        for label in ("alpha", "beta"):
            name = "store-product-check-" + uuid.uuid4().hex[:12]
            issuer = f"https://127.0.0.1:1/{label}"
            settings = {
                "STORE_NAME": f"{label.title()} Store", "OIDC_ISSUER_URL": issuer,
                "OIDC_AUDIENCE": label, "OIDC_ANDROID_CLIENT_ID": label + "-android",
                "OIDC_WEB_CLIENT_ID": label + "-web", "OIDC_PUBLISHER_CLIENT_ID": label + "-publisher",
                "NOTIFICATIONS_ENABLED": "false",
            }
            env = [arg for key, value in settings.items() for arg in ("-e", f"{key}={value}")]
            docker("run", "-d", "--name", name, "-p", "127.0.0.1::8080", *env, args.image)
            containers.append(name)
            port = docker("port", name, "8080/tcp").rsplit(":", 1)[1]
            origin = f"http://127.0.0.1:{port}"
            deadline = time.monotonic() + 45
            while True:
                try:
                    status, headers, body = get(origin + "/api/server-config")
                    break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Store did not start:\n" + docker("logs", name))
                    time.sleep(0.5)
            assert status == 200, (status, body)
            data = json.loads(body)
            assert data["schema_version"] == 1
            assert data["name"] == settings["STORE_NAME"]
            assert data["auth"]["issuer_url"] == issuer
            assert data["auth"]["clients"]["android"] == label + "-android"
            assert data["auth"]["clients"]["web"] == label + "-web"
            assert data["capabilities"]["push"] is False
            assert headers["Cache-Control"] == "no-store"
            assert get(origin + "/api/apps")[0] == 503
            assert get(origin + "/health")[0] == 200
            page_status, _, page = get(origin + "/")
            assert page_status == 200 and b'<div id="app">' in page
            print(f"{label}: discovery, embedded UI, health and fail-closed API passed", flush=True)
        assert len(containers) == 2
    finally:
        for name in containers:
            subprocess.run(["docker", "rm", "-f", "-v", name], check=True, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
