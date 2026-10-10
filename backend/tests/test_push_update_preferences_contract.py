"""Isolated HTTP preference round-trip matching Android's full replacement contract."""

from sqlalchemy import select

from app.db import SessionLocal
from app.push_models import PushDevice, PushPreference


def test_android_updates_toggle_http_db_readback_without_provider_or_device(client, owner):
    capabilities = client.get("/api/push/capabilities")
    assert capabilities.status_code == 200
    assert capabilities.json()["update_notifications"] is True
    assert capabilities.json()["delivery_configured"] is False
    organization_id = client.headers["X-Organization-ID"]
    with SessionLocal() as db:
        assert db.scalar(select(PushDevice)) is None
    before = client.get("/api/push/preferences")
    assert before.status_code == 200
    assert before.json()["updates"] is False
    # Android reads existing preference fields and changes only the toggled category.
    enabled = before.json() | {
        "updates": True,
        "timezone": "Europe/Chisinau",
        "quiet_start": 1320,
        "quiet_end": 420,
    }
    saved = client.put("/api/push/preferences", json=enabled)
    assert saved.status_code == 200
    assert saved.json() == enabled
    with SessionLocal() as db:
        row = db.scalar(
            select(PushPreference).where(
                PushPreference.user_id == owner["id"],
                PushPreference.organization_id == organization_id,
            )
        )
        assert row is not None and row.updates is True
        assert row.enabled is False  # category preference can persist before master enablement
        assert (row.timezone, row.quiet_start, row.quiet_end) == ("Europe/Chisinau", 1320, 420)
    assert client.get("/api/push/preferences").json() == enabled
    # New-version full category PUT must preserve explicitly enabled updates.
    categories = enabled | {"enabled": True, "receipts": True, "payments": True}
    assert client.put("/api/push/preferences", json=categories).json() == categories
    assert client.get("/api/push/preferences").json() == categories
    # Older APKs omit updates; omission cannot reset a stored opt-in.
    legacy = {key: value for key, value in categories.items() if key != "updates"}
    legacy["important"] = True
    response = client.put("/api/push/preferences", json=legacy)
    assert response.status_code == 200
    assert response.json()["updates"] is True
    assert response.json()["important"] is True
    disabled = response.json() | {"updates": False}
    assert client.put("/api/push/preferences", json=disabled).status_code == 200
    assert client.get("/api/push/preferences").json()["updates"] is False
    with SessionLocal() as db:
        assert (
            db.scalar(
                select(PushPreference.updates).where(
                    PushPreference.user_id == owner["id"],
                    PushPreference.organization_id == organization_id,
                )
            )
            is False
        )


def test_updates_put_requires_scope_and_csrf_but_not_device(client):
    headers = dict(client.headers)
    del client.headers["X-CSRF-Token"]
    assert client.put("/api/push/preferences", json={"updates": True}).status_code == 403
    client.headers.update(headers)
    del client.headers["X-Organization-ID"]
    assert client.put("/api/push/preferences", json={"updates": True}).status_code == 409
