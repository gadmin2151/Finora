"""Inactive push schema: importing registers metadata; no provider is configured."""

from datetime import date, datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    false,
)
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base
from .models import Identity


class PushDevice(Identity, Base):
    __tablename__ = "push_devices"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    session_id: Mapped[str] = mapped_column(
        ForeignKey("sessions.id", ondelete="CASCADE"), index=True
    )
    organization_id: Mapped[str] = mapped_column(
        ForeignKey("organizations.id", ondelete="CASCADE"), index=True
    )
    installation_id: Mapped[str] = mapped_column(String(36))
    installed_version_code: Mapped[int | None] = mapped_column(Integer)
    installed_version_name: Mapped[str | None] = mapped_column(String(64))
    all_organizations: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    provider: Mapped[str] = mapped_column(String(24))
    token_hash: Mapped[str] = mapped_column(String(64))
    token_ciphertext: Mapped[str] = mapped_column(Text)
    enabled: Mapped[bool] = mapped_column(Boolean)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    __table_args__ = (
        UniqueConstraint("provider", "token_hash", name="uq_push_token"),
        UniqueConstraint("user_id", "installation_id", name="uq_push_installation"),
    )


class PushPreference(Identity, Base):
    __tablename__ = "push_preferences"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    organization_id: Mapped[str] = mapped_column(
        ForeignKey("organizations.id", ondelete="CASCADE"), index=True
    )
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    payments: Mapped[bool] = mapped_column(Boolean, default=False)
    receipts: Mapped[bool] = mapped_column(Boolean, default=False)
    important: Mapped[bool] = mapped_column(Boolean, default=False)
    updates: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    timezone: Mapped[str] = mapped_column(String(64), default="UTC")
    quiet_start: Mapped[int | None] = mapped_column(Integer)
    quiet_end: Mapped[int | None] = mapped_column(Integer)
    payment_hour: Mapped[int] = mapped_column(Integer, default=9)
    lead_days: Mapped[int] = mapped_column(Integer, default=1)
    __table_args__ = (
        UniqueConstraint("user_id", "organization_id", name="uq_push_preference"),
        CheckConstraint(
            "lead_days BETWEEN 0 AND 30 AND payment_hour BETWEEN 0 AND 23",
            name="ck_push_schedule",
        ),
        CheckConstraint(
            "(quiet_start IS NULL AND quiet_end IS NULL) OR (quiet_start IS NOT NULL AND quiet_end IS NOT NULL AND quiet_start BETWEEN 0 AND 1439 AND quiet_end BETWEEN 0 AND 1439 AND quiet_start <> quiet_end)",
            name="ck_push_quiet",
        ),
    )


class PushEvent(Identity, Base):
    __tablename__ = "push_events"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    organization_id: Mapped[str] = mapped_column(
        ForeignKey("organizations.id", ondelete="CASCADE"), index=True
    )
    event_key: Mapped[str] = mapped_column(String(160))
    category: Mapped[str] = mapped_column(String(16))
    route: Mapped[str] = mapped_column(String(24))
    entity_id: Mapped[str | None] = mapped_column(String(36))
    due_date: Mapped[date | None] = mapped_column(Date)
    version_code: Mapped[int | None] = mapped_column(Integer)
    version_name: Mapped[str | None] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    __table_args__ = (
        UniqueConstraint("user_id", "organization_id", "event_key", name="uq_push_event"),
        CheckConstraint(
            "category IN ('payments','receipts','important','update_available')",
            name="ck_push_category",
        ),
        CheckConstraint("route IN ('bills','receipts','overview','updater')", name="ck_push_route"),
    )


class PushDelivery(Identity, Base):
    __tablename__ = "push_deliveries"
    event_id: Mapped[str] = mapped_column(
        ForeignKey("push_events.id", ondelete="CASCADE"), index=True
    )
    device_id: Mapped[str] = mapped_column(
        ForeignKey("push_devices.id", ondelete="CASCADE"), index=True
    )
    status: Mapped[str] = mapped_column(String(16), default="queued")
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    available_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    lease_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    lease_id: Mapped[str | None] = mapped_column(String(36))
    sent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_error: Mapped[str | None] = mapped_column(String(24))
    __table_args__ = (
        UniqueConstraint("event_id", "device_id", name="uq_push_delivery"),
        CheckConstraint(
            "status IN ('queued','sending','sent','skipped','failed')",
            name="ck_push_status",
        ),
    )


class PushReceiptCreation(Base):
    """Fresh creation marker only; migrations never backfill historical receipts."""

    __tablename__ = "push_receipt_creations"
    receipt_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("receipts.id", ondelete="CASCADE"), primary_key=True
    )
    eligible_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class PushAPKRelease(Base):
    """Trusted publisher attestation; no automatic discovery or network requests."""

    __tablename__ = "push_apk_releases"
    version_code: Mapped[int] = mapped_column(Integer, primary_key=True)
    version_name: Mapped[str] = mapped_column(String(64))
    apk_sha256: Mapped[str] = mapped_column(String(64))
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    withdrawn_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class PushUpdateNotice(Identity, Base):
    """Installation dedup survives registry/session deletion and org switching."""

    __tablename__ = "push_update_notices"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    installation_id: Mapped[str] = mapped_column(String(36))
    version_code: Mapped[int] = mapped_column(ForeignKey("push_apk_releases.version_code"))
    event_id: Mapped[str | None] = mapped_column(ForeignKey("push_events.id", ondelete="SET NULL"))
    __table_args__ = (
        UniqueConstraint(
            "user_id", "installation_id", "version_code", name="uq_push_update_notice"
        ),
    )
