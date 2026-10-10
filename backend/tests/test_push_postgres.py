"""PostgreSQL-specific concurrency; existing isolated fixture guard is mandatory."""

import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from sqlalchemy import func, select
from test_push_backend import NOW, MockProvider, queue
from test_push_backend import context as context  # noqa: F401

from app import models as m
from app.db import SessionLocal, engine
from app.push_models import PushDelivery, PushDevice, PushEvent
from app.push_service import enqueue, lock_owner, register_device, revoke_device
from app.push_worker import deliver_one

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="Requires disposable PostgreSQL"
)


def parallel(count, action):
    barrier = threading.Barrier(count)

    def run(index):
        barrier.wait(timeout=5)
        return action(index)

    with ThreadPoolExecutor(max_workers=count) as pool:
        return list(pool.map(run, range(count), timeout=15))


def test_parallel_enqueue_deduplicates_on_owner_lock(context):
    def action(index):
        with SessionLocal() as db:
            row = enqueue(
                db,
                user_id=context["user_id"],
                organization_id=context["organization_id"],
                event_key="concurrent-important",
                category="important",
                entity_id=None,
                now=NOW,
                expires_at=NOW + timedelta(days=1),
            )
            db.commit()
            return row.id

    ids = parallel(4, action)
    assert len(set(ids)) == 1
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 1
        assert db.scalar(select(func.count()).select_from(PushDelivery)) == 1


def test_parallel_rotation_is_idempotent(context):
    queue(context)

    def action(index):
        with SessionLocal() as db:
            row = register_device(
                db,
                user_id=context["user_id"],
                session_id=context["session_id"],
                organization_id=context["organization_id"],
                installation_id=context["installation_id"],
                provider="mock",
                token="fictional-parallel-rotated-token",
                enabled=True,
                now=NOW,
            )
            db.commit()
            return row.id

    assert set(parallel(4, action)) == {context["device_id"]}
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(PushDevice)) == 1
        assert db.scalar(select(PushDelivery.status)) == "skipped"


def test_parallel_delivery_claims_once(context):
    for index in range(12):
        queue(context, key=f"parallel-event-{index}")
    provider = MockProvider()

    def action(index):
        results = []
        for _iteration in range(12):
            status = deliver_one(SessionLocal, provider, now=NOW)
            results.append(status)
            if status == "idle":
                break
        return results

    parallel(4, action)
    assert len(provider.sent) == 12
    assert len({payload.event_id for token, payload, ttl in provider.sent}) == 12
    with SessionLocal() as db:
        assert (
            db.scalar(
                select(func.count()).select_from(PushDelivery).where(PushDelivery.status == "sent")
            )
            == 12
        )


def test_skip_locked_does_not_block_other_delivery(context):
    queue(context, key="locked-first")
    queue(context, key="unlocked-second")
    provider = MockProvider()
    with SessionLocal() as db:
        locked = db.scalar(
            select(PushDelivery).order_by(PushDelivery.id).with_for_update().limit(1)
        )
        assert locked
        with ThreadPoolExecutor(max_workers=1) as pool:
            assert (
                pool.submit(deliver_one, SessionLocal, provider, now=NOW).result(timeout=5)
                == "sent"
            )
        db.commit()
    assert deliver_one(SessionLocal, provider, now=NOW) == "sent"
    assert len(provider.sent) == 2


def test_revoke_wins_before_provider_send(context):
    queue(context)
    provider = MockProvider()
    with SessionLocal() as db:
        lock_owner(db, context["user_id"])
        with ThreadPoolExecutor(max_workers=1) as pool:
            result = pool.submit(deliver_one, SessionLocal, provider, now=NOW)
            deadline = time.monotonic() + 5
            claimed = False
            while time.monotonic() < deadline:
                with SessionLocal() as observer:
                    claimed = observer.scalar(select(PushDelivery.status)) == "sending"
                if claimed:
                    break
                time.sleep(0.02)
            assert claimed
            assert not provider.sent
            revoke_device(db, context["user_id"], context["device_id"], NOW)
            db.commit()
            assert result.result(timeout=5) == "skipped"
    assert provider.sent == []


def test_session_delete_cascades_registration_and_delivery(context):
    queue(context)
    with SessionLocal() as db:
        db.delete(db.get(m.Session, context["session_id"]))
        db.commit()
        assert db.scalar(select(func.count()).select_from(PushDevice)) == 0
        assert db.scalar(select(func.count()).select_from(PushDelivery)) == 0
    assert deliver_one(SessionLocal, MockProvider(), now=NOW) == "idle"
