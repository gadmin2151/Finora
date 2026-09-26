import secrets

from fastapi.testclient import TestClient
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal
from app.main import app
from app.security import hasher


def managed_user(client, org, role="user"):
    password = secrets.token_urlsafe(20)
    with SessionLocal() as db:
        user = m.User(
            username="member-" + secrets.token_hex(5),
            name="Участник",
            password_hash=hasher.hash(password),
        )
        db.add(user)
        db.flush()
        db.add(m.Membership(user_id=user.id, organization_id=org, role=role))
        db.commit()
        key, username = user.id, user.username
    member = TestClient(app)
    reply = member.post(
        "/api/auth/login",
        json={"username": username, "password": password},
        headers={"X-Finora-Client": "web"},
    )
    assert reply.status_code == 200
    member.headers.update({"X-CSRF-Token": reply.json()["csrf"], "X-Organization-ID": org})
    return key, member, username, password


def promote(owner):
    with SessionLocal() as db:
        db.get(m.User, owner["id"]).is_server_admin = True
        db.commit()


def test_organization_delete_closes_access_preserves_data_and_restores(client, owner, accounts):
    org = owner["id"]
    _, member, _, _ = managed_user(client, org)
    created = client.post("/api/organizations", json={"name": "Other"}).json()["id"]
    assert member.delete(f"/api/organizations/{org}").status_code == 403
    assert member.get(f"/api/organizations/{org}/members").status_code == 403
    assert member.post(f"/api/organizations/{org}/restore").status_code == 403
    assert client.delete(f"/api/organizations/{org}").status_code == 200
    assert member.get("/api/accounts").status_code == 403
    assert client.get("/api/accounts").status_code == 403
    assert client.get("/api/auth/me").json()["organizations"][0]["id"] == created
    assert member.get("/api/organizations/manage?status=deleted").json() == []
    trash = client.get("/api/organizations/manage?status=deleted").json()
    assert [row["id"] for row in trash] == [org]
    assert client.put(f"/api/organizations/{org}", json={"name": "hidden"}).status_code == 404
    assert client.post(f"/api/organizations/{org}/restore").status_code == 200
    assert member.get("/api/accounts").json() == accounts
    assert client.get("/api/organizations/manage?status=deleted").json() == []
    member.close()


def test_management_works_without_active_organization_and_is_target_scoped(client, owner):
    org = owner["id"]
    assert client.delete(f"/api/organizations/{org}").status_code == 200
    assert client.get("/api/auth/me").json()["organizations"] == []
    new = client.post("/api/organizations", json={"name": "После удаления"})
    assert new.status_code == 200
    key = new.json()["id"]
    assert (
        client.put(f"/api/organizations/{key}", json={"name": "Новое название"}).status_code == 200
    )
    members = client.get(f"/api/organizations/{key}/members").json()
    assert len(members) == 1
    assert (
        client.put(
            f"/api/organizations/{key}/members/{members[0]['id']}", json={"role": "admin"}
        ).status_code
        == 200
    )
    assert client.delete(f"/api/organizations/{key}/members/{members[0]['id']}").status_code == 409
    assert client.post(f"/api/organizations/{org}/restore").status_code == 200


def test_archive_waits_for_pending_receipts(client, owner):
    with SessionLocal() as db:
        db.add(m.Job(organization_id=owner["id"], kind="receipt", payload={}, status="queued"))
        db.commit()
    assert client.delete(f"/api/organizations/{owner['id']}").status_code == 409
    assert client.get("/api/accounts").status_code == 200


def test_deleted_user_loses_sessions_restores_blocked_and_keeps_authorship(client, owner):
    promote(owner)
    org = owner["id"]
    key, member, username, password = managed_user(client, org)
    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=org, source="photo", source_key="owned", created_by=key, status="review"
        )
        db.add(receipt)
        db.commit()
        rid = receipt.id
    assert member.delete(f"/api/admin/users/{owner['id']}").status_code == 403
    assert client.delete(f"/api/admin/users/{key}").status_code == 200
    assert member.get("/api/auth/me").status_code == 401
    assert client.get("/api/admin/users", params={"q": username}).json()["total"] == 0
    assert (
        client.get("/api/admin/users", params={"q": username, "status": "deleted"}).json()["total"]
        == 1
    )
    assert client.get(f"/api/organizations/{org}/members").json()[0]["user_id"] == owner["id"]
    assert (
        client.post(f"/api/organizations/{org}/members", json={"username": username}).status_code
        == 409
    )
    assert client.put(f"/api/admin/users/{key}/status", json={"is_active": True}).status_code == 404
    with SessionLocal() as db:
        assert db.get(m.Receipt, rid).created_by == key
    assert client.post(f"/api/admin/users/{key}/restore").status_code == 200
    assert (
        member.post(
            "/api/auth/login",
            json={"username": username, "password": password},
            headers={"X-Finora-Client": "web"},
        ).status_code
        == 401
    )
    assert client.put(f"/api/admin/users/{key}/status", json={"is_active": True}).status_code == 200
    assert (
        member.post(
            "/api/auth/login",
            json={"username": username, "password": password},
            headers={"X-Finora-Client": "web"},
        ).status_code
        == 200
    )
    actions = [row["action"] for row in client.get(f"/api/admin/users/{key}/audit").json()]
    assert "user.deleted" in actions and "user.restored" in actions
    member.close()


def test_protects_owner_last_admin_and_restores_adminless_trash(client, owner):
    promote(owner)
    key, member, _, _ = managed_user(client, owner["id"], role="admin")
    org = member.post("/api/organizations", json={"name": "Only member administers"}).json()["id"]
    assert client.delete(f"/api/admin/users/{owner['id']}").status_code == 409
    assert (
        client.put(f"/api/admin/users/{owner['id']}/status", json={"is_active": False}).status_code
        == 409
    )
    assert client.delete(f"/api/admin/users/{key}").status_code == 409
    assert (
        client.put(f"/api/admin/users/{key}/status", json={"is_active": False}).status_code == 409
    )
    assert member.delete(f"/api/organizations/{org}").status_code == 200
    assert client.delete(f"/api/admin/users/{key}").status_code == 200
    # The server owner can recover an organization after its sole admin was removed.
    assert client.post(f"/api/organizations/{org}/restore").status_code == 200
    members = client.get(f"/api/organizations/{org}/members").json()
    assert any(row["user_id"] == owner["id"] and row["role"] == "admin" for row in members)
    assert (
        client.post(
            "/api/admin/users",
            json={
                "name": "X",
                "username": "new-user",
                "password": secrets.token_urlsafe(20),
                "memberships": [{"organization_id": "missing", "role": "user"}],
            },
        ).status_code
        == 404
    )
    member.close()


def test_cannot_assign_new_users_to_trashed_organizations(client, owner):
    promote(owner)
    org = owner["id"]
    assert client.delete(f"/api/organizations/{org}").status_code == 200
    assert client.get("/api/admin/organizations").json() == []
    response = client.post(
        "/api/admin/users",
        json={
            "name": "X",
            "username": "new-user",
            "password": secrets.token_urlsafe(20),
            "memberships": [{"organization_id": org, "role": "user"}],
        },
    )
    assert response.status_code == 409
    with SessionLocal() as db:
        assert db.scalar(select(m.User).where(m.User.username == "new-user")) is None
