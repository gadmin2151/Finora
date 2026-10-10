"""Transaction-owned push operations: callers commit with their domain changes."""

import hashlib
import re
from datetime import date, datetime, timedelta

from fastapi import HTTPException
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from . import models as m
from .push_models import PushDelivery, PushDevice, PushEvent, PushPreference, PushReceiptCreation
from .push_schedule import after_quiet, utc
from .security import encrypt

MAX_DEVICES = 10
CATEGORIES = {"payments": "bills", "receipts": "receipts", "important": "overview"}


def authorized(
    db: Session, user_id: str, session_id: str, organization_id: str, now: datetime
) -> bool:
    user = db.get(m.User, user_id)
    session = db.get(m.Session, session_id)
    organization = db.get(m.Organization, organization_id)
    membership = db.scalar(
        select(m.Membership.id).where(
            m.Membership.user_id == user_id,
            m.Membership.organization_id == organization_id,
        )
    )
    return bool(
        user
        and user.is_active
        and not user.deleted_at
        and session
        and session.user_id == user_id
        and utc(session.expires_at) > utc(now)
        and organization
        and not organization.deleted_at
        and membership
    )


def lock_owner(db: Session, user_id: str) -> None:
    db.scalar(select(m.User).where(m.User.id == user_id).with_for_update())


def register_device(
    db: Session,
    *,
    user_id: str,
    session_id: str,
    organization_id: str,
    installation_id: str,
    provider: str,
    token: str,
    enabled: bool,
    now: datetime,
    all_organizations: bool = False,
    installed_version_code: int | None = None,
    installed_version_name: str | None = None,
) -> PushDevice:
    lock_owner(db, user_id)
    if not authorized(db, user_id, session_id, organization_id, now):
        raise HTTPException(403, "Push scope unavailable")
    if (
        not re.fullmatch(r"[a-z][a-z0-9_-]{0,23}", provider)
        or not 16 <= len(token) <= 4096
        or any(c.isspace() for c in token)
    ):
        raise HTTPException(422, "Invalid push token")
    digest = hashlib.sha256(token.encode()).hexdigest()
    existing = db.scalar(
        select(PushDevice).where(PushDevice.provider == provider, PushDevice.token_hash == digest)
    )
    if existing and (existing.user_id != user_id or existing.installation_id != installation_id):
        raise HTTPException(409, "Push token already bound")
    device = db.scalar(
        select(PushDevice).where(
            PushDevice.user_id == user_id, PushDevice.installation_id == installation_id
        )
    )
    changed = not device or (
        device.token_hash,
        device.provider,
        device.session_id,
        device.organization_id,
        device.enabled,
        device.revoked_at,
        device.all_organizations,
    ) != (digest, provider, session_id, organization_id, enabled, None, all_organizations)
    if device and not changed:
        device.installed_version_code = installed_version_code
        device.installed_version_name = installed_version_name
        db.flush()
        return device
    if changed:
        latest = db.scalar(
            select(func.max(PushDevice.updated_at)).where(PushDevice.user_id == user_id)
        )
        if latest and utc(latest) > utc(now) - timedelta(seconds=10):
            raise HTTPException(
                429,
                "Wait before changing push registration",
                headers={"Retry-After": "10"},
            )
    if device is None:
        count = db.scalar(
            select(func.count())
            .select_from(PushDevice)
            .where(PushDevice.user_id == user_id, PushDevice.revoked_at.is_(None))
        )
        if count >= MAX_DEVICES:
            raise HTTPException(429, "Push device limit reached")
        device = PushDevice(user_id=user_id, installation_id=installation_id)
        db.add(device)
    if device.id and changed:
        preserve_receipts = bool(
            device.token_hash == digest
            and device.provider == provider
            and device.session_id == session_id
            and device.enabled
            and enabled
            and device.revoked_at is None
            and device.all_organizations
            and all_organizations
        )
        # Only same-session/all-org receipt delivery may survive a selected workspace change.
        for delivery in db.scalars(
            select(PushDelivery).where(
                PushDelivery.device_id == device.id,
                PushDelivery.status.in_(["queued", "sending"]),
            )
        ):
            event = db.get(PushEvent, delivery.event_id)
            if (
                preserve_receipts
                and event
                and event.category == "receipts"
                and authorized(db, user_id, session_id, event.organization_id, now)
            ):
                continue
            delivery.status = "skipped"
            delivery.lease_id = None
            delivery.lease_until = None
    device.session_id = session_id
    device.organization_id = organization_id
    device.provider = provider
    device.token_hash = digest
    device.token_ciphertext = encrypt(token)
    device.enabled = enabled
    device.all_organizations = all_organizations
    device.installed_version_code = installed_version_code
    device.installed_version_name = installed_version_name
    device.revoked_at = None
    device.updated_at = now
    db.flush()
    return device


def revoke_device(db: Session, user_id: str, device_id: str, now: datetime) -> None:
    lock_owner(db, user_id)
    device = db.scalar(
        select(PushDevice)
        .where(PushDevice.id == device_id, PushDevice.user_id == user_id)
        .with_for_update()
    )
    if device is None:
        raise HTTPException(404, "Push device unavailable")
    device.enabled = False
    device.revoked_at = now
    device.token_ciphertext = ""
    for delivery in db.scalars(
        select(PushDelivery).where(
            PushDelivery.device_id == device_id,
            PushDelivery.status.in_(["queued", "sending"]),
        )
    ):
        delivery.status = "skipped"
        delivery.lease_id = None
        delivery.lease_until = None


def revoke_session(db: Session, user_id: str, session_id: str, now: datetime) -> None:
    for device in list(
        db.scalars(
            select(PushDevice).where(
                PushDevice.user_id == user_id, PushDevice.session_id == session_id
            )
        )
    ):
        revoke_device(db, user_id, device.id, now)


def preference(db: Session, user_id: str, organization_id: str) -> PushPreference | None:
    return db.scalar(
        select(PushPreference).where(
            PushPreference.user_id == user_id,
            PushPreference.organization_id == organization_id,
        )
    )


def enqueue(
    db: Session,
    *,
    user_id: str,
    organization_id: str,
    event_key: str,
    category: str,
    entity_id: str | None,
    now: datetime,
    expires_at: datetime,
    due_date: date | None = None,
) -> PushEvent | None:
    if category not in CATEGORIES or not 1 <= len(event_key) <= 160 or utc(expires_at) <= utc(now):
        raise ValueError("invalid_push_event")
    lock_owner(db, user_id)
    prefs = preference(db, user_id, organization_id)
    if not prefs or not prefs.enabled or not getattr(prefs, category):
        return None
    event = db.scalar(
        select(PushEvent).where(
            PushEvent.user_id == user_id,
            PushEvent.organization_id == organization_id,
            PushEvent.event_key == event_key,
        )
    )
    if event:
        return event  # Old events are never replayed onto newly registered devices.
    devices = [
        device
        for device in db.scalars(
            select(PushDevice).where(
                PushDevice.user_id == user_id,
                or_(
                    PushDevice.organization_id == organization_id,
                    (PushDevice.all_organizations.is_(True)) if category == "receipts" else False,
                ),
                PushDevice.enabled.is_(True),
                PushDevice.revoked_at.is_(None),
            )
        )
        if authorized(db, user_id, device.session_id, organization_id, now)
    ]
    if not devices:
        return None
    event = PushEvent(
        created_at=now,
        user_id=user_id,
        organization_id=organization_id,
        event_key=event_key,
        category=category,
        route=CATEGORIES[category],
        entity_id=entity_id,
        expires_at=expires_at,
        due_date=due_date,
    )
    db.add(event)
    db.flush()
    available = after_quiet(now, prefs.timezone, prefs.quiet_start, prefs.quiet_end)
    for device in devices:
        db.add(
            PushDelivery(
                event_id=event.id,
                device_id=device.id,
                available_at=available,
                status="queued",
                attempts=0,
            )
        )
    db.flush()
    return event


def event_is_current(db: Session, event: PushEvent) -> bool:
    if event.category == "update_available":
        from .push_updates import update_event_current

        return update_event_current(db, event, now=m.now())
    if event.category == "payments":
        from .finance import occurrence_dates

        bill = db.get(m.Bill, event.entity_id)
        if (
            not bill
            or bill.organization_id != event.organization_id
            or not bill.active
            or bill.kind != "expense"
            or not event.due_date
        ):
            return False
        if not occurrence_dates(bill, event.due_date, event.due_date):
            return False
        occurrence = db.scalar(
            select(m.Occurrence).where(
                m.Occurrence.bill_id == bill.id, m.Occurrence.due_date == event.due_date
            )
        )
        if occurrence is None:
            return True  # Scheduler does not create or mutate ledger occurrences.
        paid = db.scalar(
            select(m.Transaction.id).where(
                m.Transaction.occurrence_id == occurrence.id,
                m.Transaction.voided.is_(False),
            )
        )
        return not occurrence.skipped and not paid
    if event.category == "receipts":
        receipt = db.get(m.Receipt, event.entity_id)
        return bool(
            receipt
            and receipt.organization_id == event.organization_id
            and not receipt.deleted_at
            and receipt.created_by != event.user_id
            and event.event_key == f"receipt-created:{receipt.id}"
            and db.get(PushReceiptCreation, receipt.id)
        )
    return event.category == "important" and event.entity_id is None


def device_allows_event(device: PushDevice, event: PushEvent) -> bool:
    """Legacy installations stay bound; new receipt taps may resolve another membership."""
    return device.organization_id == event.organization_id or (
        device.all_organizations and event.category == "receipts"
    )


def category_enabled(prefs: PushPreference, category: str) -> bool:
    return bool(getattr(prefs, "updates" if category == "update_available" else category, False))
