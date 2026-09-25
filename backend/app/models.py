import uuid
from datetime import UTC, date, datetime
from decimal import Decimal

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    false,
    true,
)
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


def uid() -> str:
    return str(uuid.uuid4())


def now() -> datetime:
    return datetime.now(UTC)


class Identity:
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)


class User(Identity, Base):
    __tablename__ = "users"
    username: Mapped[str] = mapped_column(String(80), unique=True)
    name: Mapped[str] = mapped_column(String(100))
    password_hash: Mapped[str] = mapped_column(Text)
    is_server_admin: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, server_default=true())
    avatar_version: Mapped[str | None] = mapped_column(String(36))
    avatar_data: Mapped[bytes | None] = mapped_column(LargeBinary, deferred=True)


class UserAudit(Identity, Base):
    __tablename__ = "user_audit"
    actor_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    action: Mapped[str] = mapped_column(String(60))
    details: Mapped[dict] = mapped_column(JSON, default=dict)


class Organization(Identity, Base):
    __tablename__ = "organizations"
    name: Mapped[str] = mapped_column(String(100))


class Membership(Identity, Base):
    __tablename__ = "memberships"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    organization_id: Mapped[str] = mapped_column(
        ForeignKey("organizations.id", ondelete="CASCADE"), index=True
    )
    role: Mapped[str] = mapped_column(String(12), default="user")
    __table_args__ = (
        UniqueConstraint("user_id", "organization_id", name="uq_membership"),
        CheckConstraint("role IN ('admin', 'user')", name="ck_membership_role"),
    )


class Session(Identity, Base):
    __tablename__ = "sessions"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    csrf: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    device: Mapped[str] = mapped_column(String(200))


class LoginAttempt(Identity, Base):
    __tablename__ = "login_attempts"
    key: Mapped[str] = mapped_column(String(64), index=True)


class Owned(Identity):
    organization_id: Mapped[str] = mapped_column(
        ForeignKey("organizations.id", ondelete="CASCADE"), index=True
    )


class Account(Owned, Base):
    __tablename__ = "accounts"
    name: Mapped[str] = mapped_column(String(100))
    currency: Mapped[str] = mapped_column(String(3), default="MDL")
    kind: Mapped[str] = mapped_column(String(20), default="card")
    opening_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    color: Mapped[str] = mapped_column(String(7), default="#16a69b")
    archived: Mapped[bool] = mapped_column(Boolean, default=False)


class Category(Owned, Base):
    __tablename__ = "categories"
    name: Mapped[str] = mapped_column(String(100))
    color: Mapped[str] = mapped_column(String(7), default="#16a69b")
    icon: Mapped[str] = mapped_column(String(32), default="tag")
    parent_id: Mapped[str | None] = mapped_column(ForeignKey("categories.id"))
    __table_args__ = (UniqueConstraint("organization_id", "name"),)


class Debt(Owned, Base):
    __tablename__ = "debts"
    person: Mapped[str] = mapped_column(String(100))
    direction: Mapped[str] = mapped_column(String(12))
    currency: Mapped[str] = mapped_column(String(3))
    initial_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    due_date: Mapped[date | None] = mapped_column(Date)
    note: Mapped[str] = mapped_column(Text, default="")
    creation_key: Mapped[str] = mapped_column(String(100))
    request_hash: Mapped[str] = mapped_column(String(64))
    __table_args__ = (UniqueConstraint("organization_id", "creation_key"),)


class Bill(Owned, Base):
    __tablename__ = "bills"
    kind: Mapped[str] = mapped_column(String(12), default="expense", server_default="expense")
    name: Mapped[str] = mapped_column(String(100))
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    currency: Mapped[str] = mapped_column(String(3))
    fx_rate: Mapped[Decimal] = mapped_column(Numeric(18, 8), default=1)
    category_id: Mapped[str | None] = mapped_column(ForeignKey("categories.id"))
    account_id: Mapped[str | None] = mapped_column(ForeignKey("accounts.id"))
    start_date: Mapped[date] = mapped_column(Date)
    recurrence: Mapped[str] = mapped_column(String(16))
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    version: Mapped[int] = mapped_column(Integer, default=1, server_default="1")
    creation_key: Mapped[str | None] = mapped_column(String(100))
    request_hash: Mapped[str | None] = mapped_column(String(64))
    __table_args__ = (
        CheckConstraint("kind IN ('expense', 'income')", name="ck_bills_kind"),
        UniqueConstraint("organization_id", "creation_key", name="uq_bills_creation_key"),
        Index("ix_bills_user_kind", "organization_id", "kind"),
    )


class Occurrence(Owned, Base):
    __tablename__ = "occurrences"
    bill_id: Mapped[str] = mapped_column(ForeignKey("bills.id"), index=True)
    due_date: Mapped[date] = mapped_column(Date)
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    currency: Mapped[str] = mapped_column(String(3))
    fx_rate: Mapped[Decimal] = mapped_column(Numeric(18, 8))
    skipped: Mapped[bool] = mapped_column(Boolean, default=False)
    __table_args__ = (UniqueConstraint("bill_id", "due_date"),)


class Receipt(Owned, Base):
    __tablename__ = "receipts"
    review_required: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    created_by: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    source: Mapped[str] = mapped_column(String(16))
    source_key: Mapped[str] = mapped_column(String(64))
    source_url: Mapped[str | None] = mapped_column(Text)
    merchant: Mapped[str] = mapped_column(String(200), default="")
    purchased_on: Mapped[date | None] = mapped_column(Date)
    currency: Mapped[str] = mapped_column(String(3), default="MDL")
    total_minor: Mapped[int | None] = mapped_column(BigInteger)
    status: Mapped[str] = mapped_column(String(24), default="queued")
    error: Mapped[str | None] = mapped_column(Text)
    original: Mapped[dict] = mapped_column(JSON, default=dict)
    warnings: Mapped[list] = mapped_column(JSON, default=list)
    file_names: Mapped[list] = mapped_column(JSON, default=list)
    account_id: Mapped[str | None] = mapped_column(ForeignKey("accounts.id"))
    fx_rate: Mapped[Decimal] = mapped_column(Numeric(18, 8), default=1)
    version: Mapped[int] = mapped_column(Integer, default=1)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    __table_args__ = (
        UniqueConstraint("organization_id", "source_key"),
        Index("ix_receipts_user_date", "organization_id", "purchased_on"),
    )


class ReceiptItem(Identity, Base):
    __tablename__ = "receipt_items"
    receipt_id: Mapped[str] = mapped_column(
        ForeignKey("receipts.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(300))
    normalized_name: Mapped[str] = mapped_column(String(300))
    quantity: Mapped[Decimal] = mapped_column(Numeric(18, 6), default=1)
    unit: Mapped[str] = mapped_column(String(12), default="шт")
    unit_price_minor: Mapped[int] = mapped_column(BigInteger)
    total_minor: Mapped[int] = mapped_column(BigInteger)
    category_id: Mapped[str | None] = mapped_column(ForeignKey("categories.id"))


class ReceiptComment(Owned, Base):
    __tablename__ = "receipt_comments"
    receipt_id: Mapped[str] = mapped_column(
        ForeignKey("receipts.id", ondelete="CASCADE"), index=True
    )
    author_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    text: Mapped[str] = mapped_column(String(3000))


class Transaction(Owned, Base):
    __tablename__ = "transactions"
    kind: Mapped[str] = mapped_column(String(32))
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    currency: Mapped[str] = mapped_column(String(3))
    fx_rate: Mapped[Decimal] = mapped_column(Numeric(18, 8), default=1)
    base_minor: Mapped[int] = mapped_column(BigInteger)
    occurred_on: Mapped[date] = mapped_column(Date, index=True)
    account_id: Mapped[str] = mapped_column(ForeignKey("accounts.id"), index=True)
    target_account_id: Mapped[str | None] = mapped_column(ForeignKey("accounts.id"))
    target_minor: Mapped[int | None] = mapped_column(BigInteger)
    category_id: Mapped[str | None] = mapped_column(ForeignKey("categories.id"))
    merchant: Mapped[str] = mapped_column(String(200), default="")
    note: Mapped[str] = mapped_column(Text, default="")
    debt_id: Mapped[str | None] = mapped_column(ForeignKey("debts.id"), index=True)
    occurrence_id: Mapped[str | None] = mapped_column(ForeignKey("occurrences.id"), unique=True)
    receipt_id: Mapped[str | None] = mapped_column(ForeignKey("receipts.id"), unique=True)
    refund_of: Mapped[str | None] = mapped_column(ForeignKey("transactions.id"))
    idempotency_key: Mapped[str] = mapped_column(String(100))
    request_hash: Mapped[str] = mapped_column(String(64))
    voided: Mapped[bool] = mapped_column(Boolean, default=False)
    version: Mapped[int] = mapped_column(Integer, default=1)
    __table_args__ = (
        UniqueConstraint("organization_id", "idempotency_key"),
        Index("ix_tx_user_date", "organization_id", "occurred_on"),
    )


class Posting(Identity, Base):
    __tablename__ = "postings"
    transaction_id: Mapped[str] = mapped_column(
        ForeignKey("transactions.id", ondelete="CASCADE"), index=True
    )
    account_id: Mapped[str] = mapped_column(ForeignKey("accounts.id"), index=True)
    amount_minor: Mapped[int] = mapped_column(BigInteger)


class Allocation(Identity, Base):
    __tablename__ = "allocations"
    transaction_id: Mapped[str] = mapped_column(
        ForeignKey("transactions.id", ondelete="CASCADE"), index=True
    )
    category_id: Mapped[str | None] = mapped_column(ForeignKey("categories.id"))
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    base_minor: Mapped[int] = mapped_column(BigInteger)


class Budget(Owned, Base):
    __tablename__ = "budgets"
    month: Mapped[str] = mapped_column(String(7))
    category_id: Mapped[str] = mapped_column(ForeignKey("categories.id"))
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    __table_args__ = (UniqueConstraint("organization_id", "month", "category_id"),)


class Rule(Owned, Base):
    __tablename__ = "rules"
    pattern: Mapped[str] = mapped_column(String(150))
    field: Mapped[str] = mapped_column(String(16), default="item")
    category_id: Mapped[str] = mapped_column(ForeignKey("categories.id"))


class Preferences(Owned, Base):
    __tablename__ = "preferences"
    provider: Mapped[str] = mapped_column(String(16), default="disabled")
    model: Mapped[str] = mapped_column(String(100), default="qwen3:4b-instruct")
    vision_model: Mapped[str] = mapped_column(String(100), default="gemma3:4b")
    openai_key: Mapped[str | None] = mapped_column(Text)
    monthly_request_limit: Mapped[int] = mapped_column(Integer, default=200)
    auto_post: Mapped[bool] = mapped_column(Boolean, default=True)
    default_account_id: Mapped[str | None] = mapped_column(ForeignKey("accounts.id"))
    __table_args__ = (UniqueConstraint("organization_id"),)


class AIUsage(Owned, Base):
    __tablename__ = "ai_usage"
    provider: Mapped[str] = mapped_column(String(16))
    purpose: Mapped[str] = mapped_column(String(32))
    success: Mapped[bool] = mapped_column(Boolean, default=False)
    input_tokens: Mapped[int] = mapped_column(Integer, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, default=0)


class Message(Owned, Base):
    __tablename__ = "messages"
    role: Mapped[str] = mapped_column(String(16))
    text: Mapped[str] = mapped_column(Text)
    receipt_id: Mapped[str | None] = mapped_column(ForeignKey("receipts.id"))
    details: Mapped[dict] = mapped_column(JSON, default=dict)


class Job(Owned, Base):
    __tablename__ = "jobs"
    kind: Mapped[str] = mapped_column(String(32))
    payload: Mapped[dict] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(20), default="queued", index=True)
    progress: Mapped[str] = mapped_column(String(200), default="В очереди")
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    available_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    result: Mapped[dict] = mapped_column(JSON, default=dict)


class Audit(Owned, Base):
    __tablename__ = "audit"
    action: Mapped[str] = mapped_column(String(60))
    entity_id: Mapped[str] = mapped_column(String(36))
    details: Mapped[dict] = mapped_column(JSON, default=dict)
    actor_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"))
