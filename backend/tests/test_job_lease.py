import asyncio
from datetime import timedelta

import pytest

from app import models as m
from app import worker
from app.db import SessionLocal
from app.job_lease import Lease, LeaseLost, current_lease, renew, require_lease


def make_job(owner):
    with SessionLocal() as db:
        job = m.Job(
            organization_id=owner["id"],
            kind="chat",
            status="running",
            attempts=1,
            started_at=m.now() - timedelta(minutes=10),
        )
        db.add(job)
        db.commit()
        return job


def test_old_attempt_cannot_refresh_or_commit_results(owner):
    job = make_job(owner)
    old = Lease(job.id, 1)
    assert renew(old)
    with SessionLocal() as db:
        db.get(m.Job, job.id).attempts = 2
        db.commit()
    assert not renew(old)
    token = current_lease.set(old)
    try:
        with SessionLocal() as db, pytest.raises(LeaseLost):
            require_lease(db)
    finally:
        current_lease.reset(token)
    token = current_lease.set(Lease(job.id, 2))
    try:
        with SessionLocal() as db:
            require_lease(db)
    finally:
        current_lease.reset(token)


def test_running_work_heartbeats_and_stops_renewing_after_completion(owner, monkeypatch):
    job = make_job(owner)

    async def long_answer(value):
        assert value.id == job.id
        await asyncio.sleep(0.15)
        with SessionLocal() as db:
            stamp = db.get(m.Job, job.id).started_at
            assert stamp > job.started_at + timedelta(minutes=9)

    monkeypatch.setattr(worker, "answer", long_answer)
    asyncio.run(worker.run_job(job, heartbeat_seconds=0.02))
    with SessionLocal() as db:
        assert db.get(m.Job, job.id).status == "done"
    assert not renew(Lease(job.id, 1))


def test_worker_cancels_obsolete_work_without_marking_new_attempt_failed(owner, monkeypatch):
    job = make_job(owner)
    cancelled = []

    async def old_answer(value):
        with SessionLocal() as db:
            row = db.get(m.Job, value.id)
            row.attempts = 2
            row.progress = "New worker"
            db.commit()
        try:
            await asyncio.sleep(5)
        finally:
            cancelled.append(True)

    monkeypatch.setattr(worker, "answer", old_answer)
    asyncio.run(worker.run_job(job, heartbeat_seconds=0.02))
    assert cancelled
    with SessionLocal() as db:
        row = db.get(m.Job, job.id)
        assert row.status == "running" and row.attempts == 2 and row.progress == "New worker"
