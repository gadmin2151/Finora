import hashlib
import secrets
from concurrent.futures import ThreadPoolExecutor, TimeoutError
from threading import Event, Lock

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app import api, user_management
from app import models as m
from app.db import SessionLocal, engine
from app.main import app
from app.schemas import UserEdit
from app.security import hasher

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="Cross-transaction row locks require PostgreSQL"
)


def attempt(owner, password):
    with TestClient(app) as client:
        return client.post(
            "/api/auth/login",
            json={"username": owner["username"], "password": password},
            headers={"X-Finora-Client": "web"},
        )


def test_concurrent_failed_logins_cannot_exceed_remaining_admission(owner, monkeypatch):
    keys = [
        hashlib.sha256(v.encode()).hexdigest()
        for v in ["ip:testclient", "user:" + owner["username"]]
    ]
    with SessionLocal() as db:
        db.add_all(m.LoginAttempt(key=key) for key in keys for _ in range(7))
        db.commit()
    entered, release = Event(), Event()
    verify = api.verify_password
    mutex = Lock()
    calls = 0

    def held_verify(password, hashed):
        nonlocal calls
        with mutex:
            calls += 1
            first = calls == 1
        if first:
            entered.set()
            assert release.wait(5)
        return verify(password, hashed)

    monkeypatch.setattr(api, "verify_password", held_verify)
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(attempt, owner, "wrong-password")
        assert entered.wait(5)
        second = pool.submit(attempt, owner, "wrong-password")
        try:
            with pytest.raises(TimeoutError):
                second.result(timeout=0.2)
        finally:
            release.set()
        assert sorted(
            [first.result(timeout=5).status_code, second.result(timeout=5).status_code]
        ) == [401, 429]
    assert calls == 1
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.LoginAttempt)) == 16


@pytest.mark.parametrize("change", ["reset", "disable"])
def test_credentials_changed_during_verification_do_not_issue_session(owner, monkeypatch, change):
    with SessionLocal() as db:
        admin = m.User(
            username="audit-owner",
            name="Audit",
            password_hash=hasher.hash(secrets.token_urlsafe(20)),
            is_server_admin=True,
        )
        db.add(admin)
        db.commit()
        admin_id = admin.id
    verified, release = Event(), Event()
    verify = api.verify_password

    def held_verify(password, hashed):
        result = verify(password, hashed)
        verified.set()
        assert release.wait(5)
        return result

    monkeypatch.setattr(api, "verify_password", held_verify)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(attempt, owner, owner["password"])
        assert verified.wait(5)
        try:
            with SessionLocal() as db:
                actor, target = db.get(m.User, admin_id), db.get(m.User, owner["id"])
                if change == "reset":
                    user_management.reset_password(db, actor, target, secrets.token_urlsafe(24))
                else:
                    # Another active administrator preserves the organization's last-admin rule.
                    db.add(
                        m.Membership(user_id=admin_id, organization_id=owner["id"], role="admin")
                    )
                    db.commit()
                    user_management.edit_account(
                        db, actor, target, UserEdit(name=target.name, is_active=False)
                    )
        finally:
            release.set()
        response = pending.result(timeout=5)
    assert response.status_code == 401
    assert "finance_session" not in response.cookies
    with SessionLocal() as db:
        assert (
            db.scalar(
                select(func.count()).select_from(m.Session).where(m.Session.user_id == owner["id"])
            )
            == 0
        )


def test_credential_lock_allows_membership_foreign_key(owner):
    from app.security import lock_user_credentials

    with SessionLocal() as db:
        org = m.Organization(name="Independent")
        db.add(org)
        db.commit()
        org_id = org.id
    with SessionLocal() as locked:
        lock_user_credentials(locked, owner["id"])

        def add_membership():
            with SessionLocal() as db:
                db.add(m.Membership(user_id=owner["id"], organization_id=org_id, role="user"))
                db.commit()

        with ThreadPoolExecutor(max_workers=1) as pool:
            done = pool.submit(add_membership)
            try:
                done.result(timeout=2)
            finally:
                locked.rollback()
