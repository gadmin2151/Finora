"""Loopback-only actual API for synthetic Android Host taps; no external delivery."""

import json
import os
import secrets
import socket
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TEMP = Path(tempfile.mkdtemp(prefix="finora-test-push-host-18083-"))
os.environ.update(
    DATABASE_URL=f"sqlite:///{TEMP / 'fixture.db'}",
    SECRET_KEY=secrets.token_urlsafe(48),
    DATA_DIR=str(TEMP / "data"),
    TESTING="true",
    COOKIE_SECURE="false",
    APP_URL="http://127.0.0.1:18083",
    PUSH_ENABLED="false",
    PUSH_WORKER_ENABLED="false",
)
sys.path.insert(0, str(ROOT))

# App adapters may not initiate connections or resolve names outside loopback.
_original_connect = socket.socket.connect
_original_getaddrinfo = socket.getaddrinfo


def local_connect(self, address):
    if isinstance(address, tuple) and address[0] not in {"127.0.0.1", "::1", "localhost"}:
        raise RuntimeError("Fixture outbound network blocked")
    return _original_connect(self, address)


def local_getaddrinfo(host, *args, **kwargs):
    if host not in {None, "127.0.0.1", "::1", "localhost", b"127.0.0.1", b"localhost"}:
        raise RuntimeError("Fixture outbound DNS blocked")
    return _original_getaddrinfo(host, *args, **kwargs)


socket.socket.connect = local_connect
socket.getaddrinfo = local_getaddrinfo

import uvicorn  # noqa: E402
from fastapi import Header, HTTPException  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from sqlalchemy import delete, func, select  # noqa: E402

from app import models as m  # noqa: E402
from app import push_models as pm  # noqa: E402
from app import push_runtime  # noqa: E402
from app.db import Base, SessionLocal, engine  # noqa: E402
from app.push_api import Preferences  # noqa: E402
from app.security import hasher  # noqa: E402


class FakeProvider:
    name = "fcm"
    active = True

    def send(self, *args, **kwargs):
        raise RuntimeError("Fixture provider delivery prohibited")


push_runtime.configured_provider = lambda: FakeProvider()
from app.main import app  # noqa: E402

USER_ID = "18083000-0000-4000-8000-000000000001"
ORG_ID = "18083000-0000-4000-8000-000000000002"
SECOND_ORG_ID = "18083000-0000-4000-8000-000000000003"
ORG_IDS = (ORG_ID, SECOND_ORG_ID)
KEY = "fixture-only-18083"
failure_state = {"remaining": 0}
Base.metadata.create_all(engine)
with SessionLocal() as db:
    db.add_all(
        [
            m.User(
                id=USER_ID,
                username="push-fixture",
                name="Synthetic fixture",
                password_hash=hasher.hash("Fixture-Only-18083!"),
            ),
            m.Organization(id=ORG_ID, name="Synthetic fixture org"),
            m.Organization(id=SECOND_ORG_ID, name="Synthetic second org"),
        ]
    )
    db.flush()
    for org in ORG_IDS:
        db.add(m.Membership(user_id=USER_ID, organization_id=org, role="admin"))
        db.add(
            pm.PushPreference(
                user_id=USER_ID, organization_id=org, enabled=True, timezone="Europe/Chisinau"
            )
        )
    db.commit()


def require_fixture_key(key):
    if key != KEY:
        raise HTTPException(403, "Fixture key required")


@app.get("/api/__fixture/state")
def fixture_state(x_fixture_key: str = Header(default=""), organization_id: str = ORG_ID):
    require_fixture_key(x_fixture_key)
    if organization_id not in ORG_IDS:
        raise HTTPException(404, "Unknown fixture organization")
    with SessionLocal() as db:
        row = db.scalar(
            select(pm.PushPreference).where(
                pm.PushPreference.user_id == USER_ID,
                pm.PushPreference.organization_id == organization_id,
            )
        )
        return dict(
            user_id=USER_ID,
            organization_id=organization_id,
            second_organization_id=SECOND_ORG_ID,
            organization_preferences={
                org: Preferences.model_validate(
                    db.scalar(
                        select(pm.PushPreference).where(
                            pm.PushPreference.user_id == USER_ID,
                            pm.PushPreference.organization_id == org,
                        )
                    ),
                    from_attributes=True,
                ).model_dump()
                for org in ORG_IDS
            },
            fail_next_preferences_put_remaining=failure_state["remaining"],
            preferences=Preferences.model_validate(row, from_attributes=True).model_dump(),
            devices=db.scalar(select(func.count()).select_from(pm.PushDevice)),
            devices_metadata=[
                {
                    "enabled": device.enabled,
                    "installed_version_code": device.installed_version_code,
                    "installed_version_name": device.installed_version_name,
                    "all_organizations": device.all_organizations,
                    "organization_id": device.organization_id,
                }
                for device in db.scalars(select(pm.PushDevice).order_by(pm.PushDevice.id))
            ],
            deliveries=db.scalar(select(func.count()).select_from(pm.PushDelivery)),
            provider_sends=0,
            financial_transactions=db.scalar(select(func.count()).select_from(m.Transaction)),
        )


@app.post("/api/__fixture/reset")
def fixture_reset(x_fixture_key: str = Header(default="")):
    require_fixture_key(x_fixture_key)
    failure_state["remaining"] = 0
    with SessionLocal() as db:
        # Exact throwaway SQLite database created by this process only; sessions survive reset.
        assert engine.url.database == str(TEMP / "fixture.db")
        for model in (
            pm.PushDelivery,
            pm.PushUpdateNotice,
            pm.PushEvent,
            pm.PushDevice,
            pm.PushPreference,
            pm.PushAPKRelease,
            pm.PushReceiptCreation,
        ):
            db.execute(delete(model))
        for org in ORG_IDS:
            db.add(
                pm.PushPreference(
                    user_id=USER_ID, organization_id=org, enabled=True, timezone="Europe/Chisinau"
                )
            )
        db.commit()
    return fixture_state(x_fixture_key)


@app.post("/api/__fixture/fail-next-preferences-put")
def fail_next_preferences_put(x_fixture_key: str = Header(default="")):
    require_fixture_key(x_fixture_key)
    failure_state["remaining"] = 1
    return {"fail_next_preferences_put_remaining": 1}


@app.middleware("http")
async def fail_one_preference_write(request, call_next):
    if (
        request.method == "PUT"
        and request.url.path == "/api/push/preferences"
        and failure_state["remaining"]
    ):
        failure_state["remaining"] -= 1
        return JSONResponse({"detail": "Synthetic fixture one-shot PUT failure"}, status_code=500)
    return await call_next(request)


# Fixture routes must precede the production SPA catch-all.
app.router.routes[:] = app.router.routes[-3:] + app.router.routes[:-3]
metadata = dict(
    host="127.0.0.1",
    port=18083,
    user_id=USER_ID,
    organization_id=ORG_ID,
    second_organization_id=SECOND_ORG_ID,
    username="push-fixture",
    password="Fixture-Only-18083!",
    fixture_key=KEY,
    database=str(TEMP / "fixture.db"),
    provider="fake-fcm-never-sends",
    external_network="blocked",
)
(TEMP / "fixture-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
print(json.dumps(metadata), flush=True)
uvicorn.run(app, host="127.0.0.1", port=18083, access_log=False)
