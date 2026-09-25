#!/usr/bin/env python3
"""Read-only ledger smoke check with login/logout against an explicit test URL."""

import argparse
import http.cookiejar
import json
import urllib.error
import urllib.request
from pathlib import Path


def check(url: str, username: str, password: str) -> None:
    client = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
    )
    headers = {"X-Finora-Client": "web", "Origin": url}

    def request(path: str, payload: dict | None = None):
        req = urllib.request.Request(
            url + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={**headers, "Content-Type": "application/json"},
        )
        with client.open(req, timeout=15) as response:
            return json.load(response)

    assert request("/api/health")["status"] == "ok"
    user = request("/api/auth/login", {"username": username, "password": password})
    assert user["username"] == username and user["organizations"]
    headers["X-CSRF-Token"] = user["csrf"]
    headers["X-Organization-ID"] = user["organizations"][0]["id"]
    try:
        assert isinstance(request("/api/accounts"), list)
        assert request("/api/auth/me")["id"] == user["id"]
        with client.open(url, timeout=15) as response:
            assert b'<div id="root"' in response.read()
    finally:
        request("/api/auth/logout", {})
    try:
        request("/api/accounts")
    except urllib.error.HTTPError as error:
        assert error.code == 401
    else:
        raise AssertionError("Authenticated endpoint was accessible after logout")
    print(
        "PASS: health, frontend, login, organization accounts, logout and 401 after logout"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--username", default="admin")
    args = parser.parse_args()
    try:
        check(
            args.url.rstrip("/"), args.username, args.password_file.read_text().strip()
        )
    except (urllib.error.URLError, AssertionError, OSError, KeyError) as error:
        raise SystemExit(f"Smoke check failed: {type(error).__name__}") from None


if __name__ == "__main__":
    main()
