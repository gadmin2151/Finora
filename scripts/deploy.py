#!/usr/bin/env python3
"""Install/update the configured Compose project, backing up before migrations."""

import fcntl
import json
import os
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path

from backup import backup

ROOT = Path(__file__).resolve().parents[1]


def compose(*args: str, capture: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["docker", "compose", *args],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )


def config() -> dict:
    return json.loads(compose("config", "--format", "json", capture=True).stdout)


def deploy() -> None:
    if (
        not (ROOT / ".env").is_file()
        or not (ROOT / ".secrets/admin_password").is_file()
    ):
        raise SystemExit(
            "Run python3 scripts/init.py first; existing settings are never regenerated."
        )
    project = config()
    image = project["services"]["api"]["image"]
    print(f"Preparing application image: {image}", flush=True)
    if image == "finora:local":
        compose("build", "api")
    else:
        # Download before stopping anything. Failed registry access leaves the app running.
        compose("pull", "api")
    db_container = compose("ps", "-a", "-q", "db", capture=True).stdout.strip()
    running_db = compose(
        "ps", "--status", "running", "-q", "db", capture=True
    ).stdout.strip()
    if db_container and not running_db:
        compose("start", "--wait", "db")
    elif not db_container:
        compose("up", "-d", "--wait", "--wait-timeout", "90", "db")
    installed = (
        compose(
            "exec",
            "-T",
            "db",
            "psql",
            "-U",
            "finance",
            "-d",
            "finance",
            "-Atc",
            "SELECT to_regclass('public.users') IS NOT NULL",
            capture=True,
        ).stdout.strip()
        == "t"
    )
    backup_path = None
    if installed:
        if not compose("ps", "-a", "-q", "api", capture=True).stdout.strip():
            compose("up", "--no-start", "--no-deps", "--no-build", "api")
        backup_path = (
            ROOT
            / "backups"
            / (
                "finora-before-update-"
                + datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
                + ".tar.gz.age"
            )
        )
        backup(backup_path)
    compose("stop", "-t", "30", "worker", "api")
    try:
        # Always run migrations explicitly; a previous completed init container is not enough.
        compose("run", "--rm", "--no-deps", "init")
        services = ["api", "worker"]
        enabled = set(compose("config", "--services", capture=True).stdout.splitlines())
        services.extend(
            name for name in ["ollama", "caddy", "cloudflared"] if name in enabled
        )
        compose(
            "up",
            "-d",
            "--no-deps",
            "--no-build",
            "--wait",
            "--wait-timeout",
            "120",
            *services,
        )
        compose(
            "exec",
            "-T",
            "api",
            "python",
            "-c",
            "import json,os,urllib.request; "
            "port=os.environ.get('FINORA_HTTP_PORT','8088'); "
            "r=json.load(urllib.request.urlopen('http://127.0.0.1:'+port+'/api/health',timeout=5)); "
            "assert r['status']=='ok'; print('Database and API health: OK')",
        )
    except subprocess.CalledProcessError:
        print(
            "Deployment stopped. Do not run database downgrades or delete volumes.",
            file=sys.stderr,
        )
        if backup_path:
            print(f"Pre-update backup: {backup_path}", file=sys.stderr)
        print(
            "Check docker compose logs --tail=80 api worker; see README recovery instructions.",
            file=sys.stderr,
        )
        raise
    print(f"Finora is ready: {project['services']['api']['environment']['APP_URL']}")
    print(
        "Initial login: admin; password file: .secrets/admin_password (unchanged on updates)."
    )
    if "caddy" in services:
        print(
            "HTTPS certificate issuance requires working DNS and inbound ports 80/443."
        )


def main() -> None:
    os.umask(0o077)
    with (ROOT / ".deploy.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit(
                "Another Finora deployment or backup is already running."
            ) from None
        try:
            deploy()
        except subprocess.CalledProcessError as error:
            raise SystemExit(error.returncode) from None


if __name__ == "__main__":
    main()
