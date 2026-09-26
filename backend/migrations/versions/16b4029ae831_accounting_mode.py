"""Organization-wide separate or combined wallet accounting."""

import sqlalchemy as sa
from alembic import op

revision = "16b4029ae831"
down_revision = "9ad271c084fe"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        "preferences",
        sa.Column("accounting_mode", sa.String(12), nullable=False, server_default="separate"),
    )
    op.add_column(
        "preferences",
        sa.Column("accounting_version", sa.Integer(), nullable=False, server_default="1"),
    )
    with op.batch_alter_table("preferences") as batch:
        batch.create_check_constraint(
            "ck_accounting_mode", "accounting_mode IN ('separate', 'combined')"
        )


def downgrade():
    with op.batch_alter_table("preferences") as batch:
        batch.drop_constraint("ck_accounting_mode", type_="check")
        batch.drop_column("accounting_version")
        batch.drop_column("accounting_mode")
