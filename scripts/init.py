#!/usr/bin/env python3
"""Generate deployment secrets once; never overwrite an existing installation."""

import argparse
import os
import re
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def private_file(path: Path, content: str) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as out:
        out.write(content)


def domain_name(value: str) -> str:
    value = value.lower().strip().rstrip(".")
    labels = value.split(".")
    if (
        len(value) > 253
        or len(labels) < 2
        or any(
            not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
            for label in labels
        )
    ):
        raise argparse.ArgumentTypeError(
            "Use a DNS name, for example finance.example.com"
        )
    return value


def image_name(value: str) -> str:
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._/:@-]{0,255}", value):
        raise argparse.ArgumentTypeError("Invalid container image reference")
    return value


def app_port(value: str) -> int:
    try:
        port = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("Port must be an integer") from error
    if not 1024 <= port <= 65535:
        raise argparse.ArgumentTypeError("Port must be between 1024 and 65535")
    return port


def initialize(
    root: Path,
    *,
    image: str = "finora:local",
    domain: str | None = None,
    port: int = 8088,
    local_ai: bool = False,
) -> bool:
    config = root / ".env"
    secret_dir = root / ".secrets"
    password_file = secret_dir / "admin_password"
    if config.exists():
        if not password_file.is_file():
            raise SystemExit(
                "Existing .env has no .secrets/admin_password. Restore your secret file."
            )
        print(
            "Existing configuration and passwords preserved. First-install options were not applied."
        )
        return False
    if password_file.exists():
        raise SystemExit(
            "Existing admin secret has no .env. Restore your configuration before continuing."
        )
    image_name(image)
    app_port(str(port))
    if domain:
        domain = domain_name(domain)
    secret_dir.mkdir(mode=0o700, exist_ok=True)
    values = {
        "COMPOSE_PROJECT_NAME": "finora",
        "COMPOSE_FILE": "compose.yaml:compose.tls.yaml" if domain else "compose.yaml",
        "COMPOSE_PROFILES": "local-ai" if local_ai else "",
        "FINORA_IMAGE": image,
        "BIND_ADDRESS": "127.0.0.1",
        "APP_PORT": str(port),
        "APP_URL": f"https://{domain}" if domain else f"http://localhost:{port}",
        "COOKIE_SECURE": "true" if domain else "false",
        "POSTGRES_PASSWORD": secrets.token_hex(24),
        "SECRET_KEY": secrets.token_urlsafe(48),
        "ADMIN_USERNAME": "admin",
        "OLLAMA_URL": "http://ollama:11434",
    }
    if domain:
        values["DOMAIN"] = domain
    private_file(password_file, secrets.token_urlsafe(24) + "\n")
    private_file(config, "".join(f"{key}={value}\n" for key, value in values.items()))
    print(
        "Configuration created. Login: admin. Initial password: .secrets/admin_password."
    )
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=image_name, default="finora:local")
    parser.add_argument("--domain", type=domain_name)
    parser.add_argument("--port", type=app_port, default=8088)
    parser.add_argument("--local-ai", action="store_true")
    args = parser.parse_args()
    initialize(
        ROOT,
        image=args.image,
        domain=args.domain,
        port=args.port,
        local_ai=args.local_ai,
    )


if __name__ == "__main__":
    main()
