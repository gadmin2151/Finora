"""Authenticated push API; delivery remains disabled without explicit valid configuration."""

from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models as m
from .db import get_db
from .push_models import PushDelivery, PushDevice, PushEvent, PushPreference
from .push_runtime import delivery_capabilities
from .push_schedule import utc
from .push_service import (
    authorized,
    category_enabled,
    device_allows_event,
    event_is_current,
    lock_owner,
    preference,
    register_device,
    revoke_device,
)
from .security import current_user

router = APIRouter(prefix="/api/push", tags=["push"])


class Registration(BaseModel):
    model_config = ConfigDict(extra="forbid")
    installation_id: UUID
    provider: str = Field(pattern=r"^[a-z][a-z0-9_-]{0,23}$")
    token: str = Field(min_length=16, max_length=4096, repr=False)
    enabled: bool = False
    all_organizations: bool = False
    installed_version_code: int | None = Field(default=None, ge=1, le=2100000000)
    installed_version_name: str | None = Field(
        default=None, max_length=64, pattern=r"^\d+\.\d+\.\d+$"
    )

    @model_validator(mode="after")
    def version_pair(self):
        if (self.installed_version_code is None) != (self.installed_version_name is None):
            raise ValueError("Supply installed version code and name together")
        return self


class Preferences(BaseModel):
    model_config = ConfigDict(extra="forbid")
    enabled: bool = False
    payments: bool = False
    receipts: bool = False
    important: bool = False
    updates: bool = False
    timezone: str = Field(default="UTC", max_length=64)
    quiet_start: int | None = Field(default=None, ge=0, le=1439)
    quiet_end: int | None = Field(default=None, ge=0, le=1439)
    payment_hour: int = Field(default=9, ge=0, le=23)
    lead_days: int = Field(default=1, ge=0, le=30)

    @field_validator("timezone")
    @classmethod
    def valid_timezone(cls, value):
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError) as error:
            raise ValueError("Invalid IANA timezone") from error
        return value

    @model_validator(mode="after")
    def valid_quiet(self):
        if (self.quiet_start is None) != (self.quiet_end is None) or (
            self.quiet_start is not None and self.quiet_start == self.quiet_end
        ):
            raise ValueError("Supply distinct quiet start and end, or neither")
        return self


def scope(
    request: Request,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
) -> str:
    # Own preferences are allowed for ordinary members; no financial mutation permission.
    org = request.headers.get("x-organization-id")
    if not org:
        raise HTTPException(409, "Select a push organization")
    if not authorized(db, user.id, request.state.session.id, org, m.now()):
        raise HTTPException(403, "Push scope unavailable")
    return org


@router.get("/capabilities")
def capabilities(user: m.User = Depends(current_user)):
    return {
        "schema_version": 1,
        **delivery_capabilities(),
        "scope": "selected_organization",
        "receipt_notifications_all_organizations": True,
        "update_notifications": True,
    }


@router.post("/devices")
def register(
    data: Registration,
    request: Request,
    organization_id: str = Depends(scope),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    device = register_device(
        db,
        user_id=user.id,
        session_id=request.state.session.id,
        organization_id=organization_id,
        installation_id=str(data.installation_id),
        provider=data.provider,
        token=data.token,
        enabled=data.enabled,
        all_organizations=data.all_organizations,
        installed_version_code=data.installed_version_code,
        installed_version_name=data.installed_version_name,
        now=m.now(),
    )
    db.commit()
    return {
        "device_id": device.id,
        "organization_id": device.organization_id,
        "enabled": device.enabled,
        "delivery_configured": delivery_capabilities()["delivery_configured"],
    }


@router.delete("/devices/{device_id}")
def revoke(device_id: UUID, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    revoke_device(db, user.id, str(device_id), m.now())
    db.commit()
    return {"ok": True}


@router.get("/preferences")
def read_preferences(
    organization_id: str = Depends(scope),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    row = preference(db, user.id, organization_id)
    return Preferences.model_validate(row, from_attributes=True) if row else Preferences()


@router.put("/preferences")
def write_preferences(
    data: Preferences,
    organization_id: str = Depends(scope),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    lock_owner(db, user.id)
    row = preference(db, user.id, organization_id)
    if row is None:
        row = PushPreference(user_id=user.id, organization_id=organization_id)
        db.add(row)
    for key, value in data.model_dump().items():
        if key == "updates" and key not in data.model_fields_set and row.id:
            continue  # Older APKs cannot silently disable a new explicit preference.
        setattr(row, key, value)
    db.commit()
    return Preferences.model_validate(row, from_attributes=True)


def available_event(db: Session, user_id: str, session_id: str, event_id: str) -> PushEvent:
    now = m.now()
    event = db.scalar(
        select(PushEvent).where(PushEvent.id == event_id, PushEvent.user_id == user_id)
    )
    if (
        event is None
        or utc(event.expires_at) <= now
        or not event_is_current(db, event)
        or not authorized(db, user_id, session_id, event.organization_id, now)
    ):
        raise HTTPException(404, "Push event unavailable")
    prefs = preference(db, user_id, event.organization_id)
    if not prefs or not prefs.enabled or not category_enabled(prefs, event.category):
        raise HTTPException(404, "Push event unavailable")
    devices = db.scalars(
        select(PushDevice)
        .join(PushDelivery, PushDelivery.device_id == PushDevice.id)
        .where(
            PushDelivery.event_id == event.id,
            PushDevice.user_id == user_id,
            PushDevice.session_id == session_id,
            PushDevice.enabled.is_(True),
            PushDevice.revoked_at.is_(None),
        )
    )
    from .push_updates import update_delivery_eligible

    if not any(
        device_allows_event(device, event)
        and (
            event.category != "update_available"
            or update_delivery_eligible(db, event, device, now=now)
        )
        for device in devices
    ):
        raise HTTPException(404, "Push event unavailable")
    return event


@router.get("/subscriptions")
def subscriptions(
    request: Request, user: m.User = Depends(current_user), db: Session = Depends(get_db)
):
    now = m.now()
    orgs = db.scalars(
        select(PushPreference.organization_id)
        .where(
            PushPreference.user_id == user.id,
            PushPreference.enabled.is_(True),
            PushPreference.receipts.is_(True),
        )
        .order_by(PushPreference.organization_id)
    )
    return {
        "receipt_organizations": [
            org for org in orgs if authorized(db, user.id, request.state.session.id, org, now)
        ]
    }


@router.get("/events/{event_id}/context")
def resolve_event_context(
    event_id: UUID,
    request: Request,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    # No organization header is trusted: event ownership and live event membership decide scope.
    event = available_event(db, user.id, request.state.session.id, str(event_id))
    return {
        "event_id": event.id,
        "category": event.category,
        "organization_id": event.organization_id,
    }


@router.get("/events/{event_id}")
def resolve_event(
    event_id: UUID,
    request: Request,
    organization_id: str = Depends(scope),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    event = available_event(db, user.id, request.state.session.id, str(event_id))
    if event.organization_id != organization_id:
        raise HTTPException(404, "Push event unavailable")
    return {
        "event_id": event.id,
        "category": event.category,
        "route": event.route,
        "entity_id": event.entity_id,
        "organization_id": organization_id,
        "due_date": event.due_date,
        "expires_at": utc(event.expires_at),
        "version_code": event.version_code,
        "version_name": event.version_name,
    }
