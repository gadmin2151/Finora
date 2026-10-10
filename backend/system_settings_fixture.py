"""Persistent synthetic system-admin HTTP fixture, loopback only, outbound prohibited."""

import argparse
import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--directory", type=Path)
args = parser.parse_args()
TEMP = (
    args.directory.resolve()
    if args.directory
    else Path(tempfile.mkdtemp(prefix="finora-test-system-settings-18084-")).resolve()
)
if (
    not TEMP.name.startswith("finora-test-system-settings-18084-")
    or TEMP.parent != Path(tempfile.gettempdir()).resolve()
):
    raise RuntimeError("Only this disposable fixture directory is allowed")
TEMP.mkdir(mode=0o700, exist_ok=True)
keyfile = TEMP / "test-bootstrap-key"
if not keyfile.exists():
    descriptor = os.open(keyfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        output.write(secrets.token_urlsafe(48))
os.environ.update(
    DATABASE_URL=f"sqlite:///{TEMP / 'fixture.db'}",
    SECRET_KEY=keyfile.read_text(),
    DATA_DIR=str(TEMP / "data"),
    TESTING="true",
    COOKIE_SECURE="false",
    APP_URL="http://127.0.0.1:5184",
    PUSH_ENABLED="false",
    PUSH_WORKER_ENABLED="false",
    OPENAI_API_KEY="",
    GOOGLE_APPLICATION_CREDENTIALS="",
)
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
_original_connect = socket.socket.connect
_original_dns = socket.getaddrinfo


def connect_local(self, address):
    if isinstance(address, tuple) and address[0] not in {"localhost", "127.0.0.1", "::1"}:
        raise RuntimeError("Fixture outbound prohibited")
    return _original_connect(self, address)


def dns_local(host, *args, **kwargs):
    if host not in {None, "localhost", "127.0.0.1", "::1", b"localhost", b"127.0.0.1"}:
        raise RuntimeError("Fixture outbound DNS prohibited")
    return _original_dns(host, *args, **kwargs)


socket.socket.connect = connect_local
socket.getaddrinfo = dns_local
import uvicorn  # noqa:E402
from fastapi import Header, HTTPException  # noqa:E402
from sqlalchemy import delete, select  # noqa:E402

from app import models as m  # noqa:E402
from app import push_runtime  # noqa:E402
from app.config import settings  # noqa:E402
from app.db import Base, SessionLocal, engine  # noqa:E402
from app.main import app  # noqa:E402
from app.security import hasher  # noqa:E402
from app.system_settings_models import (  # noqa:E402
    SystemRuntimeObservation,
    SystemSettingsAudit,
    SystemSettingsState,
)
from app.system_settings_service import read_effective  # noqa:E402


class FixtureProvider:
    active = True
    name = "fcm"

    def send(self, *args, **kwargs):
        raise RuntimeError("Fixture send prohibited")


push_runtime.create_fcm_provider = lambda *args, **kwargs: FixtureProvider()
ORG = "18084000-0000-4000-8000-000000000004"
IDENTITIES = {
    name: f"18084000-0000-4000-8000-{number:012d}"
    for number, name in enumerate(("systemadmin", "orgadmin", "member"), start=1)
}
PASSWORD = "Fixture-Only-18084!"
KEY = "fixture-only-18084"
Base.metadata.create_all(engine)
with SessionLocal() as db:
    if db.get(m.User, IDENTITIES["systemadmin"]) is None:
        db.add(m.Organization(id=ORG, name="Synthetic settings organization"))
        db.flush()
        for name, identifier in IDENTITIES.items():
            db.add(
                m.User(
                    id=identifier,
                    username=name,
                    name=f"Synthetic {name}",
                    password_hash=hasher.hash(PASSWORD),
                    is_server_admin=name == "systemadmin",
                )
            )
            db.flush()
            db.add(
                m.Membership(
                    user_id=identifier,
                    organization_id=ORG,
                    role="admin" if name != "member" else "user",
                )
            )
        db.add(m.Preferences(organization_id=ORG))
        db.add(SystemSettingsState(id=1, revision=0, overrides={}))
        db.commit()


def authorize(key):
    if key != KEY:
        raise HTTPException(403, "Fixture key required")


@app.get("/api/__fixture/state")
def fixture_state(x_fixture_key: str = Header(default="")):
    authorize(x_fixture_key)
    with SessionLocal() as db:
        row = db.get(SystemSettingsState, 1)
        snapshot = read_effective(settings(), db)
        return dict(
            revision=snapshot.revision,
            overrides=row.overrides if row else {},
            effective=snapshot.values,
            secret_modes=snapshot.secret_modes,
            secret_configured={
                name: bool(getattr(row, name + "_ciphertext")) if row else False
                for name in snapshot.secret_modes
            },
            audits=[
                dict(revision=r.revision, changed_fields=r.changed_fields)
                for r in db.scalars(select(SystemSettingsAudit))
            ],
            observations=[
                dict(service=o.service, revision=o.revision)
                for o in db.scalars(select(SystemRuntimeObservation))
            ],
            provider_sends=0,
            external_calls=0,
            financial_transactions=len(list(db.scalars(select(m.Transaction.id)))),
        )


@app.post("/api/__fixture/reset")
def fixture_reset(x_fixture_key: str = Header(default="")):
    authorize(x_fixture_key)
    assert engine.url.database == str(TEMP / "fixture.db")
    with SessionLocal() as db:
        for model in (SystemSettingsAudit, SystemRuntimeObservation, SystemSettingsState):
            db.execute(delete(model))
        db.add(SystemSettingsState(id=1, revision=0, overrides={}))
        db.commit()
    push_runtime.configured_provider.cache_clear()
    return fixture_state(x_fixture_key)


@app.post("/api/__fixture/consume-worker")
def fixture_worker(x_fixture_key: str = Header(default="")):
    authorize(x_fixture_key)
    # A distinct fresh interpreter demonstrates durable DB/worker reload. Its only
    # secret outputs are booleans; no providers, credential files or external requests.
    code = """import json
from app.config import settings
from app.db import SessionLocal
from app.system_settings_service import read_effective,record_runtime_observation
with SessionLocal() as db:
 s=read_effective(settings(),db)
 configured={name:bool(s.secret_getter(name)) for name in s.secret_modes}
 record_runtime_observation(db,'worker',s.revision)
 db.commit()
 print(json.dumps(dict(revision=s.revision,values=s.values,secret_configured=configured,consumer='synthetic-runtime-worker',provider_calls=0)))
"""
    completed = subprocess.run(
        [sys.executable, "-c", code],
        cwd=str(ROOT),
        env=os.environ.copy(),
        capture_output=True,
        text=True,
        timeout=10,
    )
    if completed.returncode:
        raise HTTPException(500, "Synthetic worker consumption failed")
    return json.loads(completed.stdout)


app.router.routes[:] = app.router.routes[-3:] + app.router.routes[:-3]
metadata = dict(
    host="127.0.0.1",
    port=18084,
    directory=str(TEMP),
    database=str(TEMP / "fixture.db"),
    identities=IDENTITIES,
    password=PASSWORD,
    organization_id=ORG,
    fixture_key=KEY,
    provider="fake-no-send",
    external_network="blocked",
    restart_command=f".venv/bin/python backend/system_settings_fixture.py --directory {TEMP}",
)
(TEMP / "fixture-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
print(json.dumps(metadata), flush=True)
uvicorn.run(app, host="127.0.0.1", port=18084, access_log=False)
