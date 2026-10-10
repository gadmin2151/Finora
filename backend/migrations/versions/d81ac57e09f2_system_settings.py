"""Server-admin typed configuration and private encrypted overrides; no financial DML."""

import sqlalchemy as sa
from alembic import op

revision = "d81ac57e09f2"
down_revision = "b73f9012dca4"
branch_labels = None
depends_on = None


def upgrade():
    table = op.create_table(
        "system_settings_state",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("overrides", sa.JSON(), nullable=False),
        sa.Column("global_openai_key_mode", sa.String(16), nullable=False),
        sa.Column("global_openai_key_ciphertext", sa.Text(), nullable=True),
        sa.Column("firebase_service_account_mode", sa.String(16), nullable=False),
        sa.Column("firebase_service_account_ciphertext", sa.Text(), nullable=True),
        sa.Column("firebase_project_id", sa.String(64), nullable=True),
        sa.CheckConstraint("id = 1 AND revision >= 0", name="ck_system_settings_singleton"),
        sa.CheckConstraint(
            "global_openai_key_mode IN ('inherit','override','disable')",
            name="ck_system_openai_mode",
        ),
        sa.CheckConstraint(
            "firebase_service_account_mode IN ('inherit','override','disable')",
            name="ck_system_firebase_mode",
        ),
    )
    op.bulk_insert(
        table,
        [
            dict(
                id=1,
                revision=0,
                overrides={},
                global_openai_key_mode="inherit",
                firebase_service_account_mode="inherit",
            )
        ],
    )
    op.create_table(
        "system_settings_audit",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "actor_id", sa.String(36), sa.ForeignKey("users.id", ondelete="SET NULL"), nullable=True
        ),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("changed_fields", sa.JSON(), nullable=False),
    )
    op.create_table(
        "system_runtime_observations",
        sa.Column("service", sa.String(16), primary_key=True),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("seen_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "service IN ('api','worker') AND revision >= 0", name="ck_system_runtime_observation"
        ),
    )


def downgrade():
    op.drop_table("system_runtime_observations")
    op.drop_table("system_settings_audit")
    op.drop_table("system_settings_state")
