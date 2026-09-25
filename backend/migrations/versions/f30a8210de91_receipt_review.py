"""Receipt preview before posting and author-scoped acceptance."""

import sqlalchemy as sa
from alembic import op

revision = "f30a8210de91"
down_revision = "ea027bf81345"
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("receipts") as batch:
        batch.add_column(
            sa.Column("review_required", sa.Boolean(), nullable=False, server_default=sa.false())
        )
        batch.add_column(sa.Column("created_by", sa.String(36), nullable=True))
        batch.create_foreign_key(
            "fk_receipts_created_by", "users", ["created_by"], ["id"], ondelete="SET NULL"
        )


def downgrade():
    with op.batch_alter_table("receipts") as batch:
        batch.drop_constraint("fk_receipts_created_by", type_="foreignkey")
        batch.drop_column("created_by")
        batch.drop_column("review_required")
