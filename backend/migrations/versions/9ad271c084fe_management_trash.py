"""Recoverable organization and account deletion."""

import sqlalchemy as sa
from alembic import op

revision = "9ad271c084fe"
down_revision = "7c9120efab34"
branch_labels = None
depends_on = None


def upgrade():
    for table in ("organizations", "users"):
        op.add_column(table, sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True))


def downgrade():
    for table in ("users", "organizations"):
        op.drop_column(table, "deleted_at")
