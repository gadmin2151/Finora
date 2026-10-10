"""Synthetic receipt creation, multi-org access and no historical fanout."""

from datetime import timedelta
from decimal import Decimal
from uuid import uuid4

import pytest
from fastapi import HTTPException
from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from test_manual_receipts import entry
from test_push_backend import NOW, MockProvider
from test_push_backend import context as push_context_fixture

from app import models as m
from app.api import register_receipt
from app.db import SessionLocal
from app.push_api import available_event
from app.push_events import receipt_created, receipt_ready
from app.push_models import PushDevice, PushEvent, PushPreference, PushReceiptCreation
from app.push_service import register_device
from app.push_worker import deliver_one
from app.receipts import create_manual_receipt
from app.schemas import ManualReceipt


@pytest.fixture
def context(owner):
    return push_context_fixture.__wrapped__(owner)


def author(db, org):
    user = m.User(username="fictional-author", name="Author", password_hash="fictional")
    db.add(user)
    db.flush()
    db.add(m.Membership(user_id=user.id, organization_id=org, role="admin"))
    db.flush()
    return user.id


def receipt(db, context, *, author_id=None):
    row = m.Receipt(
        organization_id=context["organization_id"],
        created_by=author_id or author(db, context["organization_id"]),
        source="test",
        source_key=str(uuid4()),
        status="queued",
    )
    db.add(row)
    db.flush()
    return row


def test_new_receipt_excludes_author_stable_marker_and_neutral_payload(context):
    with SessionLocal() as db:
        row = receipt(db, context)
        events = receipt_created(db, row, now=NOW)
        assert len(events) == 1
        event = db.get(PushEvent, events[0])
        assert event.user_id == context["user_id"] != row.created_by
        assert event.event_key == f"receipt-created:{row.id}"
        assert db.get(PushReceiptCreation, row.id)
        assert receipt_created(db, row, now=NOW + timedelta(seconds=1)) == []
        for status in ("processing", "review", "posted"):
            row.status = status
            row.version += 1
            assert receipt_ready(db, row, now=NOW) is None
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0
        db.commit()
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) == "sent"
    assert len(provider.sent) == 1
    payload = provider.sent[0][1]
    assert payload.event_id == events[0]
    assert not any(
        word in repr(payload) for word in ("merchant", "amount", "items", row.source_key)
    )
    assert deliver_one(SessionLocal, provider, now=NOW) == "idle"


@pytest.mark.parametrize("suppression", ["prefs", "device", "membership", "session", "self"])
def test_suppression_persists_creation_marker_no_later_replay(context, suppression):
    with SessionLocal() as db:
        row = receipt(db, context, author_id=context["user_id"] if suppression == "self" else None)
        prefs = db.scalar(select(PushPreference))
        device = db.get(PushDevice, context["device_id"])
        if suppression == "prefs":
            prefs.receipts = False
        if suppression == "device":
            device.enabled = False
        if suppression == "membership":
            db.delete(
                db.scalar(select(m.Membership).where(m.Membership.user_id == context["user_id"]))
            )
        if suppression == "session":
            db.get(m.Session, context["session_id"]).expires_at = NOW
        db.flush()
        assert receipt_created(db, row, now=NOW) == []
        assert db.get(PushReceiptCreation, row.id)
        prefs.receipts = True
        device.enabled = True
        db.get(m.Session, context["session_id"]).expires_at = NOW + timedelta(days=1)
        assert receipt_created(db, row, now=NOW) == []
        row.status = "review"
        receipt_ready(db, row, now=NOW)
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 0
        db.commit()


def test_historical_review_and_reprocess_never_create_marker_or_event(context):
    with SessionLocal() as db:
        row = receipt(db, context)
        row.created_at = NOW - timedelta(days=365)
        db.commit()
        for status in ("queued", "processing", "review", "posted"):
            row.status = status
            receipt_ready(db, row, now=NOW)
        db.commit()
        assert not db.get(PushReceiptCreation, row.id)
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 0


def test_enqueue_failure_preserves_receipt_and_creation_marker(context, monkeypatch):
    def fail(*args, **kwargs):
        raise SQLAlchemyError("fictional failure")

    monkeypatch.setattr("app.push_events.enqueue", fail)
    with SessionLocal() as db:
        row = receipt(db, context)
        assert receipt_created(db, row, now=NOW) == []
        db.commit()
        assert db.get(m.Receipt, row.id) and db.get(PushReceiptCreation, row.id)
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0


def test_rollback_has_no_receipt_marker_event(context):
    with SessionLocal() as db:
        row = receipt(db, context)
        assert receipt_created(db, row, now=NOW)
        db.rollback()
    with SessionLocal() as db:
        for model in (m.Receipt, PushReceiptCreation, PushEvent):
            assert db.scalar(select(func.count()).select_from(model)) == 0


def test_upload_fresh_only_and_manual_duplicate(context, client, accounts, monkeypatch):
    monkeypatch.setattr("app.push_events.m.now", lambda: NOW)
    with SessionLocal() as db:
        creator = author(db, context["organization_id"])
        db.info["actor_id"] = creator
        kwargs = dict(
            organization_id=context["organization_id"],
            source="qr",
            key="fictional-upload",
            url=None,
            account_id=None,
            fx_rate=Decimal(1),
            images=[],
        )
        first = register_receipt(db, **kwargs)
        assert not first["duplicate"]
        assert register_receipt(db, **kwargs)["duplicate"]
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 1
        data = ManualReceipt(**entry(accounts[0]))
        manual = create_manual_receipt(db, context["organization_id"], data)
        assert not manual["duplicate"]
        assert create_manual_receipt(db, context["organization_id"], data)["duplicate"]
        db.commit()
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 2
        assert db.scalar(select(func.count()).select_from(PushReceiptCreation)) == 2


@pytest.mark.parametrize("all_orgs", [False, True])
def test_foreign_org_delivery_and_context_requires_capability(context, all_orgs, monkeypatch):
    monkeypatch.setattr("app.push_api.m.now", lambda: NOW)
    with SessionLocal() as db:
        other = m.Organization(name="Other synthetic organization")
        db.add(other)
        db.flush()
        db.add(m.Membership(user_id=context["user_id"], organization_id=other.id, role="user"))
        db.add(
            PushPreference(
                user_id=context["user_id"], organization_id=other.id, enabled=True, receipts=True
            )
        )
        device = db.get(PushDevice, context["device_id"])
        device.all_organizations = all_orgs
        foreign = dict(context, organization_id=other.id)
        row = receipt(db, foreign)
        events = receipt_created(db, row, now=NOW)
        db.commit()
        assert len(events) == int(all_orgs)
        if all_orgs:
            event = available_event(db, context["user_id"], context["session_id"], events[0])
            assert event.organization_id == other.id
            with pytest.raises(HTTPException) as error:
                available_event(db, row.created_by, context["session_id"], events[0])
            assert error.value.status_code == 404
            device.all_organizations = False
            db.commit()
            with pytest.raises(HTTPException):
                available_event(db, context["user_id"], context["session_id"], events[0])
            device.all_organizations = True
            db.commit()
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) == ("sent" if all_orgs else "idle")


@pytest.mark.parametrize(
    "revocation", ["membership", "deleted", "prefs", "session", "device", "expiry"]
)
def test_receipt_context_and_delivery_recheck_access(context, revocation, monkeypatch):
    monkeypatch.setattr("app.push_api.m.now", lambda: NOW)
    with SessionLocal() as db:
        row = receipt(db, context)
        event_id = receipt_created(db, row, now=NOW)[0]
        db.commit()
        assert available_event(db, context["user_id"], context["session_id"], event_id)
        if revocation == "membership":
            db.delete(
                db.scalar(select(m.Membership).where(m.Membership.user_id == context["user_id"]))
            )
        if revocation == "deleted":
            row.deleted_at = NOW
        if revocation == "prefs":
            db.scalar(select(PushPreference)).receipts = False
        if revocation == "session":
            db.get(m.Session, context["session_id"]).expires_at = NOW
        if revocation == "device":
            db.get(PushDevice, context["device_id"]).enabled = False
        if revocation == "expiry":
            db.get(PushEvent, event_id).expires_at = NOW
        db.commit()
        with pytest.raises(HTTPException) as error:
            available_event(db, context["user_id"], context["session_id"], event_id)
        assert error.value.status_code == 404
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) in ("skipped", "idle")
    assert not provider.sent


def test_selected_org_change_keeps_all_org_receipt_then_revocation_stops(context):
    from app.push_models import PushDelivery

    with SessionLocal() as db:
        device = db.get(PushDevice, context["device_id"])
        device.all_organizations = True
        row = receipt(db, context)
        event_id = receipt_created(db, row, now=NOW)[0]
        other = m.Organization(name="Synthetic target")
        db.add(other)
        db.flush()
        db.add(m.Membership(user_id=context["user_id"], organization_id=other.id, role="user"))
        db.flush()
        register_device(
            db,
            user_id=context["user_id"],
            session_id=context["session_id"],
            organization_id=other.id,
            installation_id=context["installation_id"],
            provider="mock",
            token="fictional-device-token",
            enabled=True,
            all_organizations=True,
            now=NOW,
        )
        db.commit()
        assert (
            db.scalar(select(PushDelivery.status).where(PushDelivery.event_id == event_id))
            == "queued"
        )
        db.delete(
            db.scalar(
                select(m.Membership).where(
                    m.Membership.user_id == context["user_id"],
                    m.Membership.organization_id == context["organization_id"],
                )
            )
        )
        db.commit()
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) == "skipped"
    assert not provider.sent


def test_context_http_without_org_and_identical_foreign_missing_rejection(
    context, client, monkeypatch
):
    monkeypatch.setattr("app.push_api.m.now", lambda: NOW)
    with SessionLocal() as db:
        device = db.get(PushDevice, context["device_id"])
        device.session_id = db.scalar(
            select(m.Session.id).where(m.Session.token_hash != "test-push-session")
        )
        row = receipt(db, context)
        event_id = receipt_created(db, row, now=NOW)[0]
        db.commit()
    client.headers.pop("X-Organization-ID")
    result = client.get(f"/api/push/events/{event_id}/context")
    assert result.status_code == 200
    assert result.json() == {
        "event_id": event_id,
        "category": "receipts",
        "organization_id": context["organization_id"],
    }
    missing = client.get(f"/api/push/events/{uuid4()}/context")
    with SessionLocal() as db:
        event = db.get(PushEvent, event_id)
        event.user_id = row.created_by
        db.commit()
    foreign = client.get(f"/api/push/events/{event_id}/context")
    assert missing.status_code == foreign.status_code == 404
    assert missing.json() == foreign.json()


def test_legacy_preferences_preserve_explicit_updates(context, client):
    with SessionLocal() as db:
        prefs = db.scalar(select(PushPreference))
        prefs.updates = True
        db.commit()
    result = client.put(
        "/api/push/preferences",
        json={"enabled": True, "receipts": True, "payments": True, "important": True},
    )
    assert result.status_code == 200 and result.json()["updates"]


def test_pre_rollout_queued_completion_event_is_never_delivered(context, monkeypatch):
    from app.push_service import enqueue

    monkeypatch.setattr("app.push_api.m.now", lambda: NOW)
    with SessionLocal() as db:
        row = receipt(db, context, author_id=context["user_id"])
        row.status = "review"
        event = enqueue(
            db,
            user_id=context["user_id"],
            organization_id=context["organization_id"],
            event_key=f"receipt:{row.id}:review:1",
            category="receipts",
            entity_id=row.id,
            now=NOW,
            expires_at=NOW + timedelta(days=1),
        )
        db.commit()
        assert event and not db.get(PushReceiptCreation, row.id)
        with pytest.raises(HTTPException):
            available_event(db, context["user_id"], context["session_id"], event.id)
    provider = MockProvider()
    assert deliver_one(SessionLocal, provider, now=NOW) == "skipped"
    assert not provider.sent
