import io
import secrets

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal
from app.main import app
from app.security import verify_password


@pytest.fixture
def admin(client, owner):
    with SessionLocal() as db:
        db.get(m.User, owner["id"]).is_server_admin = True
        db.commit()
    return client


def new_user(admin, organizations=None):
    password = secrets.token_urlsafe(20)
    username = "qa-" + secrets.token_hex(5)
    body = {
        "username": username,
        "name": "Новый участник",
        "password": password,
        "memberships": organizations or [],
    }
    response = admin.post("/api/admin/users", json=body)
    assert response.status_code == 201, response.text
    return response.json()["id"], username, password


def login(username, password):
    client = TestClient(app)
    result = client.post(
        "/api/auth/login",
        json={"username": username, "password": password},
        headers={"X-Finora-Client": "web"},
    )
    assert result.status_code == 200
    client.headers["X-CSRF-Token"] = result.json()["csrf"]
    return client


def test_org_admin_cannot_manage_global_accounts(client, owner):
    assert client.get("/api/admin/users").status_code == 403
    assert client.get("/api/admin/organizations").status_code == 403
    assert (
        client.post(
            f"/api/admin/users/{owner['id']}/password", json={"password": secrets.token_urlsafe(20)}
        ).status_code
        == 403
    )
    assert (
        client.put("/api/auth/profile", json={"name": "X", "is_server_admin": True}).status_code
        == 422
    )


def test_create_assign_search_edit_and_revoke_access(admin):
    org = admin.headers["X-Organization-ID"]
    other = admin.post("/api/organizations", json={"name": "Вторая организация"}).json()["id"]
    memberships = [
        {"organization_id": org, "role": "user"},
        {"organization_id": other, "role": "admin"},
    ]
    key, username, password = new_user(admin, memberships)
    with login(username, password) as member:
        assert len(member.get("/api/auth/me").json()["organizations"]) == 2
        assert member.get("/api/admin/users").status_code == 403
        assert (
            member.post(
                "/api/admin/users", json={"username": "bad", "name": "X", "password": password}
            ).status_code
            == 403
        )
        found = admin.get("/api/admin/users", params={"q": username}).json()
        assert found["total"] == 1 and len(found["items"][0]["memberships"]) == 2
        assert found["items"][0]["sessions"] == 1
        assert "password" not in str(found) and "avatar_data" not in str(found)
        # An unknown organization does not partly save the new name or remove old access.
        invalid = admin.put(
            f"/api/admin/users/{key}",
            json={
                "name": "Do not save",
                "memberships": [{"organization_id": "missing", "role": "user"}],
            },
        )
        assert invalid.status_code == 404
        assert member.get("/api/auth/me").json()["name"] == "Новый участник"
        changed = admin.put(
            f"/api/admin/users/{key}",
            json={"name": "Переименован", "memberships": [memberships[0]]},
        )
        assert changed.status_code == 200
        assert len(member.get("/api/auth/me").json()["organizations"]) == 1
        assert (
            member.get(
                "/api/dashboard?month=2026-09", headers={"X-Organization-ID": other}
            ).status_code
            == 403
        )
        blocked = admin.put(
            f"/api/admin/users/{key}",
            json={"name": "Переименован", "memberships": [memberships[0]], "is_active": False},
        )
        assert blocked.status_code == 200
        assert member.get("/api/auth/me").status_code == 401
        assert admin.get("/api/admin/users?status=blocked").json()["total"] == 1
    with TestClient(app) as guest:
        assert (
            guest.post(
                "/api/auth/login",
                json={"username": username, "password": password},
                headers={"X-Finora-Client": "web"},
            ).status_code
            == 401
        )
    assert (
        admin.put(
            f"/api/admin/users/{key}",
            json={"name": "Возвращён", "memberships": [memberships[0]], "is_active": True},
        ).status_code
        == 200
    )
    with login(username, password) as member:
        assert member.get("/api/auth/me").status_code == 200


def test_password_reset_hashes_and_revokes_every_session(admin):
    key, username, old = new_user(admin)
    replacement = secrets.token_urlsafe(22)
    with login(username, old) as first, login(username, old) as second:
        assert (
            admin.post(
                f"/api/admin/users/{key}/password", json={"password": replacement}
            ).status_code
            == 200
        )
        assert first.get("/api/auth/me").status_code == 401
        assert second.get("/api/auth/me").status_code == 401
    with SessionLocal() as db:
        user = db.get(m.User, key)
        assert user.password_hash != replacement
        assert verify_password(replacement, user.password_hash)
        assert not verify_password(old, user.password_hash)
        entries = list(db.scalars(select(m.UserAudit).where(m.UserAudit.user_id == key)))
        assert "password_reset" in entries[-1].action
        assert replacement not in str([entry.details for entry in entries])
    with login(username, replacement) as member:
        assert member.get("/api/auth/me").status_code == 200


def test_cannot_block_owner_or_remove_last_active_org_admin(admin, owner):
    org = admin.headers["X-Organization-ID"]
    assert (
        admin.put(
            f"/api/admin/users/{owner['id']}",
            json={"name": "Owner", "memberships": [], "is_active": False},
        ).status_code
        == 409
    )
    assert (
        admin.put(
            f"/api/admin/users/{owner['id']}", json={"name": "Owner", "memberships": []}
        ).status_code
        == 409
    )
    assert (
        admin.post(
            f"/api/admin/users/{owner['id']}/password", json={"password": secrets.token_urlsafe(20)}
        ).status_code
        == 409
    )
    duplicate = [{"organization_id": org, "role": "user"}] * 2
    assert (
        admin.post(
            "/api/admin/users",
            json={
                "username": "duplicate",
                "name": "No",
                "password": secrets.token_urlsafe(20),
                "memberships": duplicate,
            },
        ).status_code
        == 422
    )
    assert admin.get("/api/admin/users?q=duplicate").json()["total"] == 0


def photo():
    out = io.BytesIO()
    image = Image.new("RGB", (960, 600), "#82ddb5")
    exif = Image.Exif()
    exif[270] = "Private photo metadata"
    image.save(out, "JPEG", exif=exif)
    return out.getvalue()


def test_private_avatar_reencoding_and_profile(admin):
    key, username, password = new_user(admin)
    _, unrelated, unrelated_password = new_user(admin)
    with login(username, password) as member, login(unrelated, unrelated_password) as stranger:
        response = member.post(
            "/api/auth/avatar", files={"file": ("profile.jpg", photo(), "image/jpeg")}
        )
        assert response.status_code == 200
        url = response.json()["avatar_url"]
        assert response.json()["is_server_admin"] is False
        result = member.get(url)
        assert result.status_code == 200 and result.headers["content-type"] == "image/jpeg"
        assert result.headers["cache-control"] == "no-store"
        image = Image.open(io.BytesIO(result.content))
        assert image.size == (512, 512) and not image.getexif()
        assert stranger.get(url).status_code == 404
        assert admin.get(url).status_code == 200
        with TestClient(app) as guest:
            assert guest.get(url).status_code == 401
        assert (
            member.post(
                "/api/auth/avatar",
                files={"file": ("fake.svg", b'<svg onload="alert(1)"/>', "image/jpeg")},
            ).status_code
            == 422
        )
        assert member.get(url).status_code == 200  # Failed replacement keeps the old photo.
        assert (
            member.post(
                "/api/auth/avatar",
                files={"file": ("big.jpg", b"x" * (5 * 1024 * 1024 + 1), "image/jpeg")},
            ).status_code
            == 413
        )
        assert (
            member.put("/api/auth/profile", json={"name": "Мой профиль"}).json()["name"]
            == "Мой профиль"
        )
        assert member.delete("/api/auth/avatar").json()["avatar_url"] is None
        assert member.get(url).status_code == 404
