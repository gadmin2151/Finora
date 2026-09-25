"""Server owner, account status, private avatars and account audit."""

import os

import sqlalchemy as sa
from alembic import op

revision = "7c9120efab34"
down_revision = "f30a8210de91"
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("users") as batch:
        batch.add_column(
            sa.Column("is_server_admin", sa.Boolean(), nullable=False, server_default=sa.false())
        )
        batch.add_column(
            sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true())
        )
        batch.add_column(sa.Column("avatar_version", sa.String(36), nullable=True))
        batch.add_column(sa.Column("avatar_data", sa.LargeBinary(), nullable=True))
    # Only the configured bootstrap owner receives server-wide privileges.
    op.get_bind().execute(
        sa.text("UPDATE users SET is_server_admin = true WHERE username = :username"),
        {"username": os.environ.get("ADMIN_USERNAME", "admin")},
    )
    op.create_table(
        "user_audit",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("actor_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("action", sa.String(60), nullable=False),
        sa.Column("details", sa.JSON(), nullable=False),
    )
    op.create_index("ix_user_audit_actor_id", "user_audit", ["actor_id"])
    op.create_index("ix_user_audit_user_id", "user_audit", ["user_id"])


def downgrade():
    op.drop_table("user_audit")
    with op.batch_alter_table("users") as batch:
        for column in ("avatar_data", "avatar_version", "is_active", "is_server_admin"):
            batch.drop_column(column)
