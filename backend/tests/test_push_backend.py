"""Fictional, isolated data and synchronous fake provider only; no network delivery."""

from datetime import UTC, date, datetime, timedelta
from uuid import uuid4

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy import func, select

from app import models as m
from app.db import SessionLocal
from app.push_api import Preferences, Registration, router
from app.push_models import PushDelivery, PushDevice, PushEvent, PushPreference
from app.push_provider import InvalidToken, RetryDelivery
from app.push_schedule import after_quiet, payment_time
from app.push_scheduler import schedule_payments
from app.push_service import enqueue, register_device, revoke_device
from app.push_worker import deliver_one
from app.security import decrypt

NOW = datetime(2026, 10, 10, 9, tzinfo=UTC)


class MockProvider:
    name = "mock"
    active = True

    def __init__(self, error=None):
        self.sent = []
        self.error = error

    def send(self, token, payload, *, ttl_seconds):
        if self.error:
            raise self.error
        self.sent.append((token, payload, ttl_seconds))


@pytest.fixture
def context(owner):
    with SessionLocal() as db:
        org = db.scalar(
            select(m.Membership.organization_id).where(m.Membership.user_id == owner["id"])
        )
        session = m.Session(
            user_id=owner["id"],
            token_hash="test-push-session",
            csrf="fictional",
            device="test",
            expires_at=NOW + timedelta(days=14),
        )
        db.add(session)
        db.flush()
        prefs = PushPreference(
            user_id=owner["id"],
            organization_id=org,
            enabled=True,
            payments=True,
            receipts=True,
            important=True,
            timezone="UTC",
            payment_hour=9,
            lead_days=1,
        )
        db.add(prefs)
        device = register_device(
            db,
            user_id=owner["id"],
            session_id=session.id,
            organization_id=org,
            installation_id=str(uuid4()),
            provider="mock",
            token="fictional-device-token",
            enabled=True,
            now=NOW - timedelta(minutes=1),
        )
        db.commit()
        return {
            "user_id": owner["id"],
            "session_id": session.id,
            "organization_id": org,
            "device_id": device.id,
            "installation_id": device.installation_id,
        }


def queue(
    context, *, now=NOW, category="important", entity_id=None, due_date=None, key="test-event"
):
    with SessionLocal() as db:
        row = enqueue(
            db,
            user_id=context["user_id"],
            organization_id=context["organization_id"],
            event_key=key,
            category=category,
            entity_id=entity_id,
            due_date=due_date,
            now=now,
            expires_at=now + timedelta(days=2),
        )
        db.commit()
        return row.id if row else None


def delivery_status():
    with SessionLocal() as db:
        return db.scalar(select(PushDelivery.status))


def test_default_provider_does_not_claim(context):
    queue(context)
    assert deliver_one(SessionLocal, now=NOW) == "disabled"
    assert delivery_status() == "queued"


def test_mock_send_neutral_and_encrypted(context):
    event_id = queue(context)
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) == "sent"
    assert deliver_one(SessionLocal, provider, now=NOW) == "idle"
    token, payload, ttl = provider.sent[0]
    assert token == "fictional-device-token"
    assert vars(payload) == {
        "event_id": event_id,
        "title": "Finora",
        "body": "Open Finora to view a new notification.",
        "category": "important",
        "version_code": None,
        "version_name": None,
    }
    assert ttl == 172800
    with SessionLocal() as db:
        device = db.get(PushDevice, context["device_id"])
        assert device.token_ciphertext != token
        assert decrypt(device.token_ciphertext) == token


def test_enqueue_is_deduplicated(context):
    assert queue(context) == queue(context)
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 1
        assert db.scalar(select(func.count()).select_from(PushDelivery)) == 1


@pytest.mark.parametrize(
    "mutation",
    [
        "session_deleted",
        "session_expired",
        "membership_deleted",
        "user_disabled",
        "organization_deleted",
        "prefs_disabled",
        "category_disabled",
        "device_disabled",
    ],
)
def test_delivery_rechecks_authorization(context, mutation):
    queue(context)
    with SessionLocal() as db:
        if mutation == "session_deleted":
            db.delete(db.get(m.Session, context["session_id"]))
        elif mutation == "session_expired":
            db.get(m.Session, context["session_id"]).expires_at = NOW
        elif mutation == "membership_deleted":
            db.delete(
                db.scalar(select(m.Membership).where(m.Membership.user_id == context["user_id"]))
            )
        elif mutation == "user_disabled":
            db.get(m.User, context["user_id"]).is_active = False
        elif mutation == "organization_deleted":
            db.get(m.Organization, context["organization_id"]).deleted_at = NOW
        elif mutation in {"prefs_disabled", "category_disabled"}:
            prefs = db.scalar(select(PushPreference))
            setattr(prefs, "enabled" if mutation == "prefs_disabled" else "important", False)
        else:
            db.get(PushDevice, context["device_id"]).enabled = False
        db.commit()
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) in {"skipped", "idle"}
    assert provider.sent == []


def test_revoke_is_owned_idempotent_and_cancels(context):
    queue(context)
    with SessionLocal() as db:
        with pytest.raises(HTTPException) as error:
            revoke_device(db, "other-user", context["device_id"], NOW)
        assert error.value.status_code == 404
        revoke_device(db, context["user_id"], context["device_id"], NOW)
        revoke_device(db, context["user_id"], context["device_id"], NOW)
        db.commit()
        assert db.get(PushDevice, context["device_id"]).token_ciphertext == ""
    assert delivery_status() == "skipped"


def test_rotation_invalidates_pending_delivery(context):
    queue(context)
    with SessionLocal() as db:
        device = register_device(
            db,
            user_id=context["user_id"],
            session_id=context["session_id"],
            organization_id=context["organization_id"],
            installation_id=context["installation_id"],
            provider="mock",
            token="fictional-rotated-token",
            enabled=True,
            now=NOW,
        )
        db.commit()
        assert device.id == context["device_id"]
    assert delivery_status() == "skipped"
    # Historical event does not replay after rebinding.
    queue(context)
    assert delivery_status() == "skipped"


def test_registration_throttles_changes_but_accepts_idempotent_retry(context):
    kwargs = dict(
        user_id=context["user_id"],
        session_id=context["session_id"],
        organization_id=context["organization_id"],
        installation_id=context["installation_id"],
        provider="mock",
        token="fictional-first-rotation",
        enabled=True,
        now=NOW,
    )
    with SessionLocal() as db:
        register_device(db, **kwargs)
        db.commit()
        register_device(db, **kwargs)
        with pytest.raises(HTTPException) as error:
            register_device(db, **{**kwargs, "token": "fictional-changed-token"})
        assert error.value.status_code == 429


def test_registration_scope_and_token_cannot_be_taken(context, owner):
    with SessionLocal() as db:
        other = m.User(username="other-push", name="Other", password_hash="fictional")
        db.add(other)
        db.flush()
        other_session = m.Session(
            user_id=other.id,
            token_hash="other-session",
            csrf="fictional",
            device="test",
            expires_at=NOW + timedelta(days=1),
        )
        db.add_all(
            [
                other_session,
                m.Membership(
                    user_id=other.id, organization_id=context["organization_id"], role="user"
                ),
            ]
        )
        db.flush()
        with pytest.raises(HTTPException) as error:
            register_device(
                db,
                user_id=other.id,
                session_id=other_session.id,
                organization_id=context["organization_id"],
                installation_id=str(uuid4()),
                provider="mock",
                token="fictional-device-token",
                enabled=True,
                now=NOW,
            )
        assert error.value.status_code == 409
        with pytest.raises(HTTPException) as error:
            register_device(
                db,
                user_id=other.id,
                session_id=context["session_id"],
                organization_id=context["organization_id"],
                installation_id=str(uuid4()),
                provider="mock",
                token="fictional-new-token",
                enabled=True,
                now=NOW,
            )
        assert error.value.status_code == 403


def test_device_limit(context):
    with SessionLocal() as db:
        for number in range(9):
            register_device(
                db,
                user_id=context["user_id"],
                session_id=context["session_id"],
                organization_id=context["organization_id"],
                installation_id=str(uuid4()),
                provider="mock",
                token=f"fictional-token-{number:04d}",
                enabled=True,
                now=NOW + timedelta(seconds=11 * (number + 1)),
            )
        with pytest.raises(HTTPException) as error:
            register_device(
                db,
                user_id=context["user_id"],
                session_id=context["session_id"],
                organization_id=context["organization_id"],
                installation_id=str(uuid4()),
                provider="mock",
                token="fictional-eleventh-token",
                enabled=True,
                now=NOW + timedelta(minutes=10),
            )
        assert error.value.status_code == 429


def test_retry_sanitizes_errors_and_respects_retry_after(context, monkeypatch):
    monkeypatch.setattr("app.push_worker.secrets.randbelow", lambda bound: 0)
    queue(context)
    provider = MockProvider(RetryDelivery(120))
    assert deliver_one(SessionLocal, provider, now=NOW) == "queued"
    assert deliver_one(SessionLocal, provider, now=NOW + timedelta(seconds=119)) == "idle"
    provider.error = None
    assert deliver_one(SessionLocal, provider, now=NOW + timedelta(seconds=120)) == "sent"
    assert len(provider.sent) == 1


def test_invalid_token_is_disabled(context):
    queue(context)
    assert deliver_one(SessionLocal, MockProvider(InvalidToken()), now=NOW) == "failed"
    with SessionLocal() as db:
        device = db.get(PushDevice, context["device_id"])
        assert not device.enabled and device.revoked_at and device.token_ciphertext == ""


def test_expiry_and_quiet_preference_rechecked(context):
    queue(context)
    with SessionLocal() as db:
        prefs = db.scalar(select(PushPreference))
        prefs.quiet_start, prefs.quiet_end = 8 * 60, 10 * 60
        db.commit()
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) == "queued"
    assert not provider.sent
    assert deliver_one(SessionLocal, provider, now=NOW + timedelta(days=3)) == "skipped"


def test_crashed_lease_can_retry(context):
    queue(context)
    with SessionLocal() as db:
        row = db.scalar(select(PushDelivery))
        row.status, row.lease_id, row.lease_until = (
            "sending",
            str(uuid4()),
            NOW - timedelta(seconds=1),
        )
        db.commit()
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) == "sent"
    assert len(provider.sent) == 1


def test_quiet_midnight_and_dst():
    assert after_quiet(datetime(2026, 10, 10, 23, tzinfo=UTC), "UTC", 22 * 60, 7 * 60) == datetime(
        2026, 10, 11, 7, tzinfo=UTC
    )
    assert after_quiet(NOW, "UTC", 22 * 60, 7 * 60) == NOW
    # New York spring gap 02:00 advances to 03:00, fall fold chooses first 01:00.
    assert payment_time(date(2026, 3, 8), "America/New_York", 0, 2) == datetime(
        2026, 3, 8, 7, tzinfo=UTC
    )
    assert payment_time(date(2026, 11, 1), "America/New_York", 0, 1) == datetime(
        2026, 11, 1, 5, tzinfo=UTC
    )


@pytest.mark.parametrize(
    "data",
    [
        {"timezone": "invalid/zone"},
        {"quiet_start": 10},
        {"quiet_start": 10, "quiet_end": 10},
        {"lead_days": 31},
        {"payment_hour": 24},
        {"user_id": "injected"},
    ],
)
def test_invalid_preferences(data):
    with pytest.raises(ValidationError):
        Preferences(**data)


def test_registration_rejects_owner_injection():
    with pytest.raises(ValidationError):
        Registration(
            installation_id=uuid4(), provider="fcm", token="fictional-device-token", user_id="other"
        )


def test_scheduler_uses_virtual_occurrences_dedup_and_no_money_writes(context):
    with SessionLocal() as db:
        bill = m.Bill(
            organization_id=context["organization_id"],
            name="Fictional bill",
            kind="expense",
            amount_minor=100,
            currency="MDL",
            start_date=date(2026, 10, 11),
            recurrence="monthly",
            active=True,
        )
        db.add(bill)
        db.commit()
        assert schedule_payments(db, NOW) == (1, None)
        db.commit()
        assert schedule_payments(db, NOW) == (0, None)
        schedule_payments(db, NOW + timedelta(minutes=1))
        db.commit()
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 1
        assert db.scalar(select(func.count()).select_from(m.Occurrence)) == 0
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0
        assert db.scalar(select(PushEvent.entity_id)) == bill.id


def test_skipped_bill_and_posted_receipt_never_send(context):
    with SessionLocal() as db:
        bill = m.Bill(
            organization_id=context["organization_id"],
            name="Fictional bill",
            kind="expense",
            amount_minor=100,
            currency="MDL",
            start_date=date(2026, 10, 11),
            recurrence="monthly",
            active=True,
        )
        db.add(bill)
        db.flush()
        occurrence = m.Occurrence(
            organization_id=context["organization_id"],
            bill_id=bill.id,
            due_date=date(2026, 10, 11),
            amount_minor=100,
            currency="MDL",
            fx_rate=1,
            skipped=True,
        )
        db.add(occurrence)
        db.commit()
        assert schedule_payments(db, NOW) == (0, None)
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 0


def test_api_auth_csrf_scope_and_safe_capabilities(client):
    app = FastAPI()
    app.include_router(router)
    with TestClient(app) as push:
        assert push.get("/api/push/capabilities").status_code == 401
        push.cookies.update(client.cookies)
        push.headers.update(client.headers)
        assert push.get("/api/push/capabilities").json()["delivery_configured"] is False
        assert push.get("/api/push/preferences").json()["enabled"] is False
        response = push.put(
            "/api/push/preferences",
            json={"enabled": True, "receipts": True, "timezone": "Europe/Chisinau"},
        )
        assert response.status_code == 200
        headers = dict(push.headers)
        del push.headers["x-csrf-token"]
        assert push.put("/api/push/preferences", json={}).status_code == 403
        push.headers.update(headers)
        del push.headers["x-organization-id"]
        assert push.get("/api/push/preferences").status_code == 409
        push.headers["x-organization-id"] = str(uuid4())
        assert push.get("/api/push/preferences").status_code == 403


def test_send_cap_defers_without_spending_attempt(context):
    provider = MockProvider()
    for index in range(20):
        queue(context, key=f"cap-{index}")
        assert deliver_one(SessionLocal, provider, now=NOW) == "sent"
    queue(context, key="over-cap")
    assert deliver_one(SessionLocal, provider, now=NOW) == "queued"
    assert len(provider.sent) == 20
    assert deliver_one(SessionLocal, provider, now=NOW + timedelta(hours=1, seconds=1)) == "sent"
    assert len(provider.sent) == 21


def test_retry_limit_and_sensitive_error_not_saved(context):
    queue(context)
    provider = MockProvider(RuntimeError("SECRET fictional-device-token"))
    for attempt in range(5):
        result = deliver_one(SessionLocal, provider, now=NOW + timedelta(hours=attempt))
    assert result == "failed"
    with SessionLocal() as db:
        row = db.scalar(select(PushDelivery))
        assert row.attempts == 5 and row.last_error == "provider_retry"


def test_receipt_hook_atomic_and_does_not_post(context):
    from app.push_events import receipt_ready

    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=context["organization_id"],
            created_by=context["user_id"],
            source="test",
            source_key="test-push-receipt",
            status="review",
        )
        db.add(receipt)
        db.flush()
        event_id = receipt_ready(db, receipt, now=NOW)
        assert event_id is None  # completion never notifies historical receipts
        assert receipt.status == "review"
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0
        db.rollback()
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 0
        assert db.scalar(select(func.count()).select_from(m.Receipt)) == 0


def test_payment_cancelled_after_enqueue_is_not_delivered(context):
    with SessionLocal() as db:
        bill = m.Bill(
            organization_id=context["organization_id"],
            name="Fictional",
            amount_minor=100,
            currency="MDL",
            start_date=date(2026, 10, 11),
            recurrence="monthly",
            active=True,
        )
        db.add(bill)
        db.commit()
        assert schedule_payments(db, NOW) == (1, None)
        db.commit()
        bill.active = False
        db.commit()
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) == "skipped"
    assert provider.sent == []


def test_api_event_resolves_only_owner_scope_and_not_after_revoke(client, context):
    event_id = queue(context)
    app = FastAPI()
    app.include_router(router)
    with TestClient(app) as push:
        push.cookies.update(client.cookies)
        push.headers.update(client.headers)
        # Existing fixture push binding uses a different session; no event resolution.
        assert push.get(f"/api/push/events/{event_id}").status_code == 404
        with SessionLocal() as db:
            actual_session = db.scalar(
                select(m.Session).where(m.Session.token_hash != "test-push-session")
            )
            device = db.get(PushDevice, context["device_id"])
            device.session_id = actual_session.id
            db.commit()
        result = push.get(f"/api/push/events/{event_id}")
        assert result.status_code == 200
        assert result.json()["route"] == "overview"
        assert datetime.fromisoformat(result.json()["expires_at"]) == NOW + timedelta(days=2)
        assert "token" not in result.text
        with SessionLocal() as db:
            revoke_device(db, context["user_id"], context["device_id"], m.now())
            db.commit()
        assert push.get(f"/api/push/events/{event_id}").status_code == 404


def test_migration_metadata_without_applying(monkeypatch):
    import importlib.util
    from pathlib import Path
    from types import SimpleNamespace

    from sqlalchemy import CheckConstraint, Column, MetaData, Table, UniqueConstraint

    spec = importlib.util.spec_from_file_location(
        "push_migration",
        Path(__file__).parents[1] / "migrations/versions/a92e7810bc43_push_foundation.py",
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    tables, indices, drops = {}, {}, []
    monkeypatch.setattr(
        module,
        "op",
        SimpleNamespace(
            create_table=lambda name, *args: tables.update({name: args}),
            create_index=lambda name, table, columns: indices.update({name: (table, columns)}),
            drop_table=drops.append,
        ),
    )
    module.upgrade()  # Operations captured in memory; no connection or migration applied.
    # Compare the complete additive chain with current metadata, not the old foundation alone.
    spec2 = importlib.util.spec_from_file_location(
        "push_update_migration",
        Path(__file__).parents[1] / "migrations/versions/b73f9012dca4_push_updates.py",
    )
    additive = importlib.util.module_from_spec(spec2)
    spec2.loader.exec_module(additive)
    tables = {name: list(args) for name, args in tables.items()}

    def drop_constraint(name, table, **kwargs):
        tables[table] = [arg for arg in tables[table] if getattr(arg, "name", None) != name]

    monkeypatch.setattr(
        additive,
        "op",
        SimpleNamespace(
            add_column=lambda table, column: tables[table].append(column),
            drop_constraint=drop_constraint,
            create_check_constraint=lambda name, table, sql: tables[table].append(
                CheckConstraint(sql, name=name)
            ),
            create_table=lambda name, *args: tables.update({name: list(args)}),
        ),
    )
    additive.upgrade()
    from app.push_models import PushAPKRelease, PushReceiptCreation, PushUpdateNotice

    for cls in (
        PushDevice,
        PushPreference,
        PushEvent,
        PushDelivery,
        PushAPKRelease,
        PushReceiptCreation,
        PushUpdateNotice,
    ):
        table = cls.__table__
        Table(table.name, MetaData(), *tables[table.name])
        columns = {arg.name: arg for arg in tables[table.name] if isinstance(arg, Column)}
        assert columns.keys() == {column.name for column in table.columns}
        for expected in table.columns:
            actual = columns[expected.name]
            assert str(actual.type) == str(expected.type)
            assert actual.nullable == expected.nullable
            assert actual.primary_key == expected.primary_key
            assert {(fk.target_fullname, fk.ondelete) for fk in actual.foreign_keys} == {
                (fk.target_fullname, fk.ondelete) for fk in expected.foreign_keys
            }
        actual_checks = {
            str(arg.sqltext) for arg in tables[table.name] if isinstance(arg, CheckConstraint)
        }
        expected_checks = {
            str(arg.sqltext) for arg in table.constraints if isinstance(arg, CheckConstraint)
        }
        assert actual_checks == expected_checks
        actual_uniques = {
            tuple(column.name for column in arg.columns)
            for arg in tables[table.name]
            if isinstance(arg, UniqueConstraint)
        }
        expected_uniques = {
            tuple(column.name for column in arg.columns)
            for arg in table.constraints
            if isinstance(arg, UniqueConstraint)
        }
        assert actual_uniques == expected_uniques
        for index in table.indexes:
            assert indices[index.name] == (table.name, [column.name for column in index.columns])
    module.downgrade()
    assert drops == ["push_deliveries", "push_events", "push_preferences", "push_devices"]


def test_receipt_hook_enqueue_failure_does_not_fail_financial_transaction(context, monkeypatch):
    from sqlalchemy.exc import SQLAlchemyError

    from app import push_events

    def failed_enqueue(db, **kwargs):
        raise SQLAlchemyError("fictional enqueue failure")

    monkeypatch.setattr(push_events, "enqueue", failed_enqueue)
    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=context["organization_id"],
            created_by=context["user_id"],
            source="test",
            source_key="test-hook-failure",
            status="review",
        )
        db.add(receipt)
        assert push_events.receipt_ready(db, receipt, now=NOW) is None
        db.commit()
        assert db.get(m.Receipt, receipt.id).status == "review"


def test_dispatch_refreshes_clock_and_abandons_expired_lease(context, monkeypatch):
    queue(context)
    moments = iter([NOW, NOW + timedelta(minutes=3)])
    monkeypatch.setattr("app.push_worker.m.now", lambda: next(moments))
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider) == "queued"
    assert not provider.sent
