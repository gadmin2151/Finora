"""Server-wide allowlisted settings; secret ciphertext never belongs in response/audit."""

from datetime import datetime

from sqlalchemy import JSON, CheckConstraint, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base
from .models import Identity


class SystemSettingsState(Base):
    __tablename__ = "system_settings_state"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    revision: Mapped[int] = mapped_column(Integer, default=0)
    overrides: Mapped[dict] = mapped_column(JSON, default=dict)
    global_openai_key_mode: Mapped[str] = mapped_column(String(16), default="inherit")
    global_openai_key_ciphertext: Mapped[str | None] = mapped_column(Text)
    firebase_service_account_mode: Mapped[str] = mapped_column(String(16), default="inherit")
    firebase_service_account_ciphertext: Mapped[str | None] = mapped_column(Text)
    firebase_project_id: Mapped[str | None] = mapped_column(String(64))
    __table_args__ = (
        CheckConstraint("id = 1 AND revision >= 0", name="ck_system_settings_singleton"),
        CheckConstraint(
            "global_openai_key_mode IN ('inherit','override','disable')",
            name="ck_system_openai_mode",
        ),
        CheckConstraint(
            "firebase_service_account_mode IN ('inherit','override','disable')",
            name="ck_system_firebase_mode",
        ),
    )


class SystemSettingsAudit(Identity, Base):
    __tablename__ = "system_settings_audit"
    actor_id: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    revision: Mapped[int] = mapped_column(Integer)
    changed_fields: Mapped[list] = mapped_column(JSON)  # Names only; never input/effective values.


class SystemRuntimeObservation(Base):
    __tablename__ = "system_runtime_observations"
    service: Mapped[str] = mapped_column(String(16), primary_key=True)
    revision: Mapped[int] = mapped_column(Integer)
    seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    __table_args__ = (
        CheckConstraint(
            "service IN ('api','worker') AND revision >= 0", name="ck_system_runtime_observation"
        ),
    )
