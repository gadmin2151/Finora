#!/usr/bin/env python3
"""Encrypted, consistent backups. Restore only into a NEW isolated Compose project."""

import argparse
import fcntl
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path

from init import image_name

ROOT = Path(__file__).resolve().parents[1]
IDENTITY = ROOT / ".secrets" / "backup_identity.txt"


def command(args, cwd=ROOT, **kwargs):
    return subprocess.run(args, cwd=cwd, check=True, **kwargs)


def compose(*args, cwd=ROOT, **kwargs):
    return command(["docker", "compose", *args], cwd=cwd, **kwargs)


@lru_cache(maxsize=1)
def runtime_image():
    container = compose(
        "ps", "-a", "-q", "api", capture_output=True, text=True
    ).stdout.strip()
    if container:
        return command(
            ["docker", "inspect", "--format", "{{.Image}}", container],
            capture_output=True,
            text=True,
        ).stdout.strip()
    config = json.loads(
        compose("config", "--format", "json", capture_output=True).stdout
    )
    return config["services"]["api"]["image"]


def recovery_image():
    digests = (
        json.loads(
            command(
                [
                    "docker",
                    "image",
                    "inspect",
                    "--format",
                    "{{json .RepoDigests}}",
                    runtime_image(),
                ],
                capture_output=True,
                text=True,
            ).stdout
        )
        or []
    )
    return digests[0] if digests else "finora:local"


def ensure_identity():
    if not IDENTITY.exists():
        result = command(
            ["docker", "run", "--rm", "--entrypoint", "age-keygen", runtime_image()],
            capture_output=True,
        )
        fd = os.open(IDENTITY, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(result.stdout)
    with IDENTITY.open("rb") as key:
        return (
            command(
                [
                    "docker",
                    "run",
                    "--rm",
                    "-i",
                    "--entrypoint",
                    "age-keygen",
                    runtime_image(),
                    "-y",
                ],
                stdin=key,
                capture_output=True,
            )
            .stdout.decode()
            .strip()
        )


def backup(destination: Path):
    recipient = ensure_identity()
    if destination.exists():
        raise SystemExit("Backup destination already exists; refusing to overwrite it")
    destination.parent.mkdir(parents=True, exist_ok=True)
    running = compose(
        "ps", "--status", "running", "--services", capture_output=True, text=True
    ).stdout.splitlines()
    stopped = [name for name in ["worker", "api"] if name in running]
    containers = {
        name: compose("ps", "-q", name, capture_output=True, text=True).stdout.strip()
        for name in stopped
    }
    with tempfile.TemporaryDirectory(prefix="finora-backup-") as work:
        work = Path(work)
        archive = work / "backup.tar.gz"
        payload = work / "payload"
        payload.mkdir()
        try:
            if stopped:
                compose("stop", "-t", "30", *stopped)
            with (payload / "database.dump").open("wb") as out:
                compose(
                    "exec",
                    "-T",
                    "db",
                    "pg_dump",
                    "-U",
                    "finance",
                    "-d",
                    "finance",
                    "--format=custom",
                    stdout=out,
                )
            compose("cp", "api:/data/receipts", str(payload / "receipts"))
            shutil.copy2(ROOT / ".env", payload / "config.env")
            shutil.copy2(
                ROOT / ".secrets" / "admin_password", payload / "admin_password"
            )
            (payload / "manifest.json").write_text(
                json.dumps(
                    {
                        "format": "finora-backup-v1",
                        "created_at": datetime.now(UTC).isoformat(),
                        "postgres_major": 17,
                        "application_image": recovery_image(),
                    }
                )
            )
            with tarfile.open(archive, "w:gz") as tar:
                for path in payload.iterdir():
                    tar.add(path, arcname=path.name)
        finally:
            if stopped:
                # Resume the exact containers that were stopped, even if a newer
                # image has been built locally and awaits a schema migration.
                command(
                    [
                        "docker",
                        "start",
                        *(containers[name] for name in reversed(stopped)),
                    ]
                )
        partial = destination.with_suffix(destination.suffix + ".partial")
        try:
            with archive.open("rb") as source, partial.open("xb") as output:
                os.chmod(partial, 0o600)
                command(
                    [
                        "docker",
                        "run",
                        "--rm",
                        "-i",
                        "--entrypoint",
                        "age",
                        runtime_image(),
                        "-r",
                        recipient,
                    ],
                    stdin=source,
                    stdout=output,
                )
            partial.rename(destination)
        finally:
            partial.unlink(missing_ok=True)
    print(f"Encrypted backup: {destination}")
    print(f"Keep this private recovery key separately: {IDENTITY}")


def restore(source: Path, identity: Path, target: Path, port: int):
    target = target.resolve()
    if target.exists() and any(target.iterdir()):
        raise SystemExit(
            "Restore target must be a new empty directory; existing installations are never overwritten"
        )
    if not identity.is_file() or not source.is_file():
        raise SystemExit("Archive and recovery identity must exist")
    with tempfile.TemporaryDirectory(prefix="finora-restore-") as work:
        work = Path(work)
        archive = work / "backup.tar.gz"
        with source.open("rb") as inp, archive.open("wb") as out:
            command(
                [
                    "docker",
                    "run",
                    "--rm",
                    "-i",
                    "--user",
                    "0:0",
                    "-v",
                    f"{identity.resolve()}:/recovery-key:ro",
                    "--entrypoint",
                    "age",
                    runtime_image(),
                    "-d",
                    "-i",
                    "/recovery-key",
                ],
                stdin=inp,
                stdout=out,
            )
        payload = work / "payload"
        payload.mkdir()
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar.getmembers():
                parts = Path(member.name).parts
                if (
                    not (member.isfile() or member.isdir())
                    or not parts
                    or ".." in parts
                    or Path(member.name).is_absolute()
                ):
                    raise SystemExit("Unsafe backup member")
                if parts[0] not in {
                    "receipts",
                    "database.dump",
                    "config.env",
                    "admin_password",
                    "manifest.json",
                }:
                    raise SystemExit("Unexpected backup contents")
            # Extract only validated regular files/directories; also works with Debian 12 Python.
            for member in tar.getmembers():
                destination = payload / member.name
                if member.isdir():
                    destination.mkdir(parents=True, exist_ok=True)
                else:
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as inp, destination.open("xb") as out:
                        shutil.copyfileobj(inp, out)
        manifest = json.loads((payload / "manifest.json").read_text())
        if (
            manifest.get("format") != "finora-backup-v1"
            or manifest.get("postgres_major") != 17
        ):
            raise SystemExit("Unsupported backup format")
        target.mkdir(parents=True, exist_ok=True)
        for filename in [
            "compose.yaml",
            "compose.tls.yaml",
            "compose.cloudflare.yaml",
            "Caddyfile",
            "Dockerfile",
            ".dockerignore",
            ".gitignore",
            "README.md",
            "LICENSE",
            ".env.example",
        ]:
            if (ROOT / filename).is_file():
                shutil.copy2(ROOT / filename, target / filename)
        for directory in ["backend", "web", "scripts"]:
            shutil.copytree(
                ROOT / directory,
                target / directory,
                ignore=shutil.ignore_patterns(
                    "node_modules",
                    "dist",
                    "__pycache__",
                    ".pytest_cache",
                    ".ruff_cache",
                    "*.tsbuildinfo",
                ),
            )
        config = (payload / "config.env").read_text()
        changes = {
            "COMPOSE_PROJECT_NAME": "finora_restore_"
            + datetime.now(UTC).strftime("%Y%m%d_%H%M%S"),
            "APP_PORT": str(port),
            "APP_URL": f"http://localhost:{port}",
            "BIND_ADDRESS": "127.0.0.1",
            "COOKIE_SECURE": "false",
            "COMPOSE_FILE": "compose.yaml",
            "COMPOSE_PROFILES": "",
        }
        if manifest.get("application_image"):
            changes["FINORA_IMAGE"] = image_name(manifest["application_image"])
        for key, value in changes.items():
            config = (
                re.sub(rf"^{key}=.*$", key + "=" + value, config, flags=re.MULTILINE)
                if re.search(rf"^{key}=", config, re.MULTILINE)
                else config + f"\n{key}={value}\n"
            )
        (target / ".env").write_text(config)
        os.chmod(target / ".env", 0o600)
        (target / ".secrets").mkdir(mode=0o700)
        shutil.copy2(payload / "admin_password", target / ".secrets" / "admin_password")
        os.chmod(target / ".secrets" / "admin_password", 0o600)
        compose("up", "-d", "--wait", "db", cwd=target)
        # pg_restore without --clean intentionally fails if anything already exists.
        with (payload / "database.dump").open("rb") as inp:
            compose(
                "exec",
                "-T",
                "db",
                "pg_restore",
                "-U",
                "finance",
                "-d",
                "finance",
                "--exit-on-error",
                "--no-owner",
                stdin=inp,
                cwd=target,
            )
        compose("up", "--no-start", "--no-deps", "api", cwd=target)
        compose(
            "cp", str(payload / "receipts") + "/.", "api:/data/receipts", cwd=target
        )
        compose(
            "run",
            "--rm",
            "--no-deps",
            "--user",
            "0:0",
            "api",
            "chown",
            "-R",
            "10001:10001",
            "/data",
            cwd=target,
        )
        compose("up", "-d", "--wait", "api", "worker", cwd=target)
    print(f"Restored into isolated project: {target}")
    print(f"Open http://localhost:{port}; use the account credentials from the backup.")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    create = sub.add_parser("create")
    create.add_argument(
        "--output",
        type=Path,
        default=ROOT
        / "backups"
        / ("finora-" + datetime.now(UTC).strftime("%Y%m%d-%H%M%S") + ".tar.gz.age"),
    )
    recover = sub.add_parser("restore")
    recover.add_argument("archive", type=Path)
    recover.add_argument("--identity", type=Path, default=IDENTITY)
    recover.add_argument("--target", type=Path, required=True)
    recover.add_argument("--port", type=int, default=8089)
    args = parser.parse_args()
    with (ROOT / ".deploy.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error("Another deployment or backup is already running")
        if args.action == "create":
            backup(args.output.resolve())
        else:
            if not 1024 <= args.port <= 65535:
                parser.error("Port must be between 1024 and 65535")
            restore(args.archive.resolve(), args.identity, args.target, args.port)


if __name__ == "__main__":
    main()
