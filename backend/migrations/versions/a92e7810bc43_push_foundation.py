"""Inactive provider-neutral push registry, preferences and outbox. Not applied."""

import sqlalchemy as sa
from alembic import op

revision = "a92e7810bc43"
down_revision = "16b4029ae831"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "push_devices",
        sa.Column(
            "user_id",
            sa.String(36),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column(
            "session_id",
            sa.String(36),
            sa.ForeignKey("sessions.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column(
            "organization_id",
            sa.String(36),
            sa.ForeignKey("organizations.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column("installation_id", sa.String(36), nullable=False, primary_key=False),
        sa.Column("provider", sa.String(24), nullable=False, primary_key=False),
        sa.Column("token_hash", sa.String(64), nullable=False, primary_key=False),
        sa.Column("token_ciphertext", sa.Text(), nullable=False, primary_key=False),
        sa.Column("enabled", sa.Boolean(), nullable=False, primary_key=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, primary_key=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True, primary_key=False),
        sa.Column("id", sa.String(36), nullable=False, primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, primary_key=False),
        sa.UniqueConstraint("user_id", "installation_id", name="uq_push_installation"),
        sa.UniqueConstraint("provider", "token_hash", name="uq_push_token"),
    )
    op.create_index("ix_push_devices_organization_id", "push_devices", ["organization_id"])
    op.create_index("ix_push_devices_session_id", "push_devices", ["session_id"])
    op.create_index("ix_push_devices_user_id", "push_devices", ["user_id"])
    op.create_table(
        "push_preferences",
        sa.Column(
            "user_id",
            sa.String(36),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column(
            "organization_id",
            sa.String(36),
            sa.ForeignKey("organizations.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column("enabled", sa.Boolean(), nullable=False, primary_key=False),
        sa.Column("payments", sa.Boolean(), nullable=False, primary_key=False),
        sa.Column("receipts", sa.Boolean(), nullable=False, primary_key=False),
        sa.Column("important", sa.Boolean(), nullable=False, primary_key=False),
        sa.Column("timezone", sa.String(64), nullable=False, primary_key=False),
        sa.Column("quiet_start", sa.Integer(), nullable=True, primary_key=False),
        sa.Column("quiet_end", sa.Integer(), nullable=True, primary_key=False),
        sa.Column("payment_hour", sa.Integer(), nullable=False, primary_key=False),
        sa.Column("lead_days", sa.Integer(), nullable=False, primary_key=False),
        sa.Column("id", sa.String(36), nullable=False, primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, primary_key=False),
        sa.CheckConstraint(
            "(quiet_start IS NULL AND quiet_end IS NULL) OR (quiet_start IS NOT NULL AND quiet_end IS NOT NULL AND quiet_start BETWEEN 0 AND 1439 AND quiet_end BETWEEN 0 AND 1439 AND quiet_start <> quiet_end)",
            name="ck_push_quiet",
        ),
        sa.CheckConstraint(
            "lead_days BETWEEN 0 AND 30 AND payment_hour BETWEEN 0 AND 23", name="ck_push_schedule"
        ),
        sa.UniqueConstraint("user_id", "organization_id", name="uq_push_preference"),
    )
    op.create_index("ix_push_preferences_organization_id", "push_preferences", ["organization_id"])
    op.create_index("ix_push_preferences_user_id", "push_preferences", ["user_id"])
    op.create_table(
        "push_events",
        sa.Column(
            "user_id",
            sa.String(36),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column(
            "organization_id",
            sa.String(36),
            sa.ForeignKey("organizations.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column("event_key", sa.String(160), nullable=False, primary_key=False),
        sa.Column("category", sa.String(16), nullable=False, primary_key=False),
        sa.Column("route", sa.String(24), nullable=False, primary_key=False),
        sa.Column("entity_id", sa.String(36), nullable=True, primary_key=False),
        sa.Column("due_date", sa.Date(), nullable=True, primary_key=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False, primary_key=False),
        sa.Column("id", sa.String(36), nullable=False, primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, primary_key=False),
        sa.CheckConstraint(
            "category IN ('payments','receipts','important')", name="ck_push_category"
        ),
        sa.CheckConstraint("route IN ('bills','receipts','overview')", name="ck_push_route"),
        sa.UniqueConstraint("user_id", "organization_id", "event_key", name="uq_push_event"),
    )
    op.create_index("ix_push_events_organization_id", "push_events", ["organization_id"])
    op.create_index("ix_push_events_user_id", "push_events", ["user_id"])
    op.create_table(
        "push_deliveries",
        sa.Column(
            "event_id",
            sa.String(36),
            sa.ForeignKey("push_events.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column(
            "device_id",
            sa.String(36),
            sa.ForeignKey("push_devices.id", ondelete="CASCADE"),
            nullable=False,
            primary_key=False,
        ),
        sa.Column("status", sa.String(16), nullable=False, primary_key=False),
        sa.Column("attempts", sa.Integer(), nullable=False, primary_key=False),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=False, primary_key=False),
        sa.Column("lease_until", sa.DateTime(timezone=True), nullable=True, primary_key=False),
        sa.Column("lease_id", sa.String(36), nullable=True, primary_key=False),
        sa.Column("sent_at", sa.DateTime(timezone=True), nullable=True, primary_key=False),
        sa.Column("last_error", sa.String(24), nullable=True, primary_key=False),
        sa.Column("id", sa.String(36), nullable=False, primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, primary_key=False),
        sa.CheckConstraint(
            "status IN ('queued','sending','sent','skipped','failed')", name="ck_push_status"
        ),
        sa.UniqueConstraint("event_id", "device_id", name="uq_push_delivery"),
    )
    op.create_index("ix_push_deliveries_available_at", "push_deliveries", ["available_at"])
    op.create_index("ix_push_deliveries_device_id", "push_deliveries", ["device_id"])
    op.create_index("ix_push_deliveries_event_id", "push_deliveries", ["event_id"])


def downgrade():
    op.drop_table("push_deliveries")
    op.drop_table("push_events")
    op.drop_table("push_preferences")
    op.drop_table("push_devices")
