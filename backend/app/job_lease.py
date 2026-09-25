"""A worker may persist results only while it owns the current job attempt."""

from contextvars import ContextVar
from dataclasses import dataclass

from sqlalchemy import select, update

from . import models as m
from .db import SessionLocal


@dataclass(frozen=True)
class Lease:
    job_id: str
    attempt: int


current_lease: ContextVar[Lease | None] = ContextVar("finora_job_lease", default=None)


class LeaseLost(Exception):
    pass


def require_lease(db) -> None:
    """Call before other write locks; retain the job lock through result commit."""
    lease = current_lease.get()
    if lease is None:
        return
    row = db.scalar(
        select(m.Job.id)
        .where(
            m.Job.id == lease.job_id,
            m.Job.attempts == lease.attempt,
            m.Job.status == "running",
        )
        .with_for_update()
    )
    if row is None:
        raise LeaseLost()


def renew(lease: Lease) -> bool:
    with SessionLocal() as db:
        result = db.execute(
            update(m.Job)
            .where(
                m.Job.id == lease.job_id,
                m.Job.attempts == lease.attempt,
                m.Job.status == "running",
            )
            .values(started_at=m.now())
        )
        db.commit()
        return result.rowcount == 1
