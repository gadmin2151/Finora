"""Explicit delivery tick, inactive by default; at-least-once, generic payload only."""

import secrets
from datetime import datetime, timedelta
from uuid import uuid4

from sqlalchemy import func, or_, select

from . import models as m
from .push_models import PushDelivery, PushDevice, PushEvent
from .push_provider import (
    DisabledProvider,
    InvalidToken,
    NeutralPayload,
    PermanentDelivery,
    RetryDelivery,
)
from .push_schedule import after_quiet, utc
from .push_service import (
    authorized,
    category_enabled,
    device_allows_event,
    event_is_current,
    lock_owner,
    preference,
)
from .security import decrypt

MAX_ATTEMPTS = 5
MAX_DEVICE_SENDS_PER_HOUR = 20


def deliver_one(session_factory, provider=None, *, now: datetime | None = None) -> str:
    provider = provider or DisabledProvider()
    if not provider.active:
        return "disabled"  # Does not read/claim queued events when provider is absent.
    fixed_now = now is not None
    now = utc(now or m.now())
    lease_id = str(uuid4())
    with session_factory() as db:
        row = db.scalar(
            select(PushDelivery)
            .where(
                or_(
                    PushDelivery.status == "queued",
                    (PushDelivery.status == "sending") & (PushDelivery.lease_until <= now),
                ),
                PushDelivery.available_at <= now,
            )
            .order_by(PushDelivery.available_at, PushDelivery.id)
            .with_for_update(skip_locked=True)
            .limit(1)
        )
        if not row:
            return "idle"
        row.status = "sending"
        row.lease_id = lease_id
        row.lease_until = now + timedelta(minutes=2)
        row.attempts += 1
        delivery_id, device_id = row.id, row.device_id
        db.commit()
    with session_factory() as db:
        device = db.get(PushDevice, device_id)
        if device is None:
            return "skipped"
        # Order owner before device/delivery, matching registration/revocation.
        lock_owner(db, device.user_id)
        device = db.scalar(
            select(PushDevice)
            .where(PushDevice.id == device_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
        row = db.scalar(
            select(PushDelivery)
            .where(PushDelivery.id == delivery_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
        if not row or row.status != "sending" or row.lease_id != lease_id:
            return "skipped"
        if not fixed_now:
            now = utc(m.now())  # Refresh after waiting for owner/device locks.
        if not row.lease_until or utc(row.lease_until) <= now:
            row.status, row.lease_id, row.lease_until = "queued", None, None
            db.commit()
            return "queued"
        event = db.get(PushEvent, row.event_id)
        prefs = preference(db, device.user_id, event.organization_id) if device and event else None
        valid = bool(
            device
            and event
            and device.enabled
            and device.revoked_at is None
            and device.user_id == event.user_id
            and device_allows_event(device, event)
            and device.provider == provider.name
            and authorized(db, device.user_id, device.session_id, event.organization_id, now)
            and prefs
            and prefs.enabled
            and category_enabled(prefs, event.category)
            and utc(event.expires_at) > now
            and event_is_current(db, event)
        )
        if valid and event.category == "update_available":
            from .push_updates import update_delivery_eligible

            valid = update_delivery_eligible(db, event, device, now=now)
        if not valid:
            row.status = "skipped"
        elif row.attempts > MAX_ATTEMPTS:
            row.status, row.last_error = "failed", "attempt_limit"
        else:
            available = after_quiet(now, prefs.timezone, prefs.quiet_start, prefs.quiet_end)
            recent = db.execute(
                select(func.count(), func.min(PushDelivery.sent_at)).where(
                    PushDelivery.device_id == device.id,
                    PushDelivery.status == "sent",
                    PushDelivery.sent_at > now - timedelta(hours=1),
                )
            ).one()
            if recent[0] >= MAX_DEVICE_SENDS_PER_HOUR:
                available = max(available, utc(recent[1]) + timedelta(hours=1, seconds=1))
            if available > now:
                row.status, row.available_at = "queued", available
                row.attempts -= 1
            else:
                try:
                    # Provider must bound its call below 2min; no financial fields leave backend.
                    provider.send(
                        decrypt(device.token_ciphertext),
                        NeutralPayload(
                            event.id,
                            category=event.category,
                            version_code=event.version_code,
                            version_name=event.version_name,
                        ),
                        ttl_seconds=max(1, int((utc(event.expires_at) - now).total_seconds())),
                    )
                    row.status, row.last_error, row.sent_at = "sent", None, now
                except InvalidToken:
                    device.enabled, device.revoked_at, device.token_ciphertext = (
                        False,
                        now,
                        "",
                    )
                    row.status, row.last_error = "failed", "invalid_token"
                except PermanentDelivery as error:
                    row.status, row.last_error = "failed", error.code
                except Exception as error:
                    backoff = min(3600, 30 * 2**row.attempts)
                    delay = max(
                        backoff, error.retry_after if isinstance(error, RetryDelivery) else 0
                    )
                    delay += secrets.randbelow(min(60, backoff // 4) + 1)
                    row.available_at = now + timedelta(seconds=delay)
                    row.status = "failed" if row.attempts >= MAX_ATTEMPTS else "queued"
                    row.last_error = "provider_retry"  # Never persist provider text/token.
        row.lease_id = row.lease_until = None
        db.commit()
        return row.status
