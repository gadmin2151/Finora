"""Opt-in APK updates, cross-org capability and fresh receipt markers; no backfill."""

import sqlalchemy as sa
from alembic import op

revision = "b73f9012dca4"
down_revision = "a92e7810bc43"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("push_devices", sa.Column("installed_version_code", sa.Integer(), nullable=True))
    op.add_column("push_devices", sa.Column("installed_version_name", sa.String(64), nullable=True))
    op.add_column(
        "push_devices",
        sa.Column("all_organizations", sa.Boolean(), nullable=False, server_default=sa.false()),
    )
    op.add_column(
        "push_preferences",
        sa.Column("updates", sa.Boolean(), nullable=False, server_default=sa.false()),
    )
    op.add_column("push_events", sa.Column("version_code", sa.Integer(), nullable=True))
    op.add_column("push_events", sa.Column("version_name", sa.String(64), nullable=True))
    op.drop_constraint("ck_push_category", "push_events", type_="check")
    op.drop_constraint("ck_push_route", "push_events", type_="check")
    op.create_check_constraint(
        "ck_push_category",
        "push_events",
        "category IN ('payments','receipts','important','update_available')",
    )
    op.create_check_constraint(
        "ck_push_route", "push_events", "route IN ('bills','receipts','overview','updater')"
    )
    op.create_table(
        "push_receipt_creations",
        sa.Column(
            "receipt_id",
            sa.String(36),
            sa.ForeignKey("receipts.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("eligible_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "push_apk_releases",
        sa.Column("version_code", sa.Integer(), primary_key=True),
        sa.Column("version_name", sa.String(64), nullable=False),
        sa.Column("apk_sha256", sa.String(64), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("withdrawn_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_table(
        "push_update_notices",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False
        ),
        sa.Column("installation_id", sa.String(36), nullable=False),
        sa.Column(
            "version_code",
            sa.Integer(),
            sa.ForeignKey("push_apk_releases.version_code"),
            nullable=False,
        ),
        sa.Column(
            "event_id",
            sa.String(36),
            sa.ForeignKey("push_events.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.UniqueConstraint(
            "user_id", "installation_id", "version_code", name="uq_push_update_notice"
        ),
    )


def downgrade():
    # Caller must remove new-category outbox rows before downgrading; never silently delete data.
    op.drop_table("push_update_notices")
    op.drop_table("push_apk_releases")
    op.drop_table("push_receipt_creations")
    op.drop_constraint("ck_push_category", "push_events", type_="check")
    op.drop_constraint("ck_push_route", "push_events", type_="check")
    op.create_check_constraint(
        "ck_push_category", "push_events", "category IN ('payments','receipts','important')"
    )
    op.create_check_constraint(
        "ck_push_route", "push_events", "route IN ('bills','receipts','overview')"
    )
    op.drop_column("push_events", "version_name")
    op.drop_column("push_events", "version_code")
    op.drop_column("push_preferences", "updates")
    op.drop_column("push_devices", "all_organizations")
    op.drop_column("push_devices", "installed_version_name")
    op.drop_column("push_devices", "installed_version_code")
