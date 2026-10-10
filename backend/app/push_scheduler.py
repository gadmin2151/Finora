"""Explicit scheduler tick; no daemon/startup hook and no financial-table writes."""

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models as m
from .finance import occurrence_dates
from .push_models import PushEvent, PushPreference
from .push_schedule import local_instant, payment_time, utc
from .push_service import enqueue, event_is_current, lock_owner


def schedule_payments(
    db: Session, now: datetime, *, limit: int = 100, after_id: str = ""
) -> tuple[int, str | None]:
    """Cursor page; caller repeats pages and commits. Startup catch-up limited to 24h."""
    now = utc(now)
    if not 1 <= limit <= 100:
        raise ValueError("invalid_scheduler_limit")
    prefs_rows = list(
        db.scalars(
            select(PushPreference)
            .where(
                PushPreference.enabled.is_(True),
                PushPreference.payments.is_(True),
                PushPreference.id > after_id,
            )
            .order_by(PushPreference.id)
            .limit(limit)
        )
    )
    created = 0
    for prefs in prefs_rows:
        lock_owner(db, prefs.user_id)
        zone = ZoneInfo(prefs.timezone)
        local_day = now.astimezone(zone).date()
        # Adjacent local dates cover DST and outage catch-up around midnight.
        start = local_day + timedelta(days=prefs.lead_days - 1)
        end = local_day + timedelta(days=prefs.lead_days)
        bills = db.scalars(
            select(m.Bill).where(
                m.Bill.organization_id == prefs.organization_id,
                m.Bill.active.is_(True),
                m.Bill.kind == "expense",
            )
        )
        for bill in bills:
            for due in occurrence_dates(bill, start, end):
                scheduled = payment_time(due, prefs.timezone, prefs.lead_days, prefs.payment_hour)
                if not now - timedelta(hours=24) <= scheduled <= now:
                    continue
                expires = local_instant(due + timedelta(days=1), 0, zone)
                if expires <= now:
                    continue
                # Validate paid/skipped and recurrence without generating financial rows.
                candidate = PushEvent(
                    organization_id=prefs.organization_id,
                    category="payments",
                    entity_id=bill.id,
                    due_date=due,
                )
                if not event_is_current(db, candidate):
                    continue
                key = f"payment:{bill.id}:{due.isoformat()}"
                prior = db.scalar(
                    select(PushEvent.id).where(
                        PushEvent.user_id == prefs.user_id,
                        PushEvent.organization_id == prefs.organization_id,
                        PushEvent.event_key == key,
                    )
                )
                event = enqueue(
                    db,
                    user_id=prefs.user_id,
                    organization_id=prefs.organization_id,
                    event_key=key,
                    category="payments",
                    entity_id=bill.id,
                    due_date=due,
                    now=now,
                    expires_at=expires,
                )
                if event and not prior:
                    created += 1
    return created, prefs_rows[-1].id if len(prefs_rows) == limit else None
