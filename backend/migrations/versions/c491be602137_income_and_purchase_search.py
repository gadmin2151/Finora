"""Income schedules and receipt history index.

Revision: c491be602137
"""

import sqlalchemy as sa
from alembic import op

revision = "c491be602137"
down_revision = "80003e0ee465"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        "bills", sa.Column("kind", sa.String(12), nullable=False, server_default="expense")
    )
    op.add_column("bills", sa.Column("version", sa.Integer(), nullable=False, server_default="1"))
    op.add_column("bills", sa.Column("creation_key", sa.String(100), nullable=True))
    op.add_column("bills", sa.Column("request_hash", sa.String(64), nullable=True))
    op.create_check_constraint("ck_bills_kind", "bills", "kind IN ('expense', 'income')")
    op.create_unique_constraint("uq_bills_creation_key", "bills", ["user_id", "creation_key"])
    op.create_index("ix_bills_user_kind", "bills", ["user_id", "kind"])
    op.create_index("ix_receipts_user_date", "receipts", ["user_id", "purchased_on"])


def downgrade():
    connection = op.get_bind()
    if connection.scalar(sa.text("SELECT count(*) FROM bills WHERE kind = 'income'")):
        raise RuntimeError("Income schedules exist; restore a backup instead of discarding them")
    op.drop_index("ix_receipts_user_date", table_name="receipts")
    op.drop_index("ix_bills_user_kind", table_name="bills")
    op.drop_constraint("uq_bills_creation_key", "bills", type_="unique")
    op.drop_constraint("ck_bills_kind", "bills", type_="check")
    for column in ["request_hash", "creation_key", "version", "kind"]:
        op.drop_column("bills", column)
