"""Organization ledgers, memberships and receipt moderation."""

import sqlalchemy as sa
from alembic import op

revision = "ea027bf81345"
down_revision = "c491be602137"
branch_labels = None
depends_on = None

TABLES = [
    "accounts",
    "categories",
    "debts",
    "bills",
    "occurrences",
    "receipts",
    "transactions",
    "budgets",
    "rules",
    "preferences",
    "ai_usage",
    "messages",
    "jobs",
    "audit",
]


def identity():
    return [
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    ]


def upgrade():
    op.create_table("organizations", *identity(), sa.Column("name", sa.String(100), nullable=False))
    op.create_table(
        "memberships",
        *identity(),
        sa.Column(
            "user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False
        ),
        sa.Column(
            "organization_id",
            sa.String(36),
            sa.ForeignKey("organizations.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("role", sa.String(12), nullable=False),
        sa.UniqueConstraint("user_id", "organization_id", name="uq_membership"),
        sa.CheckConstraint("role IN ('admin', 'user')", name="ck_membership_role"),
    )
    op.create_index("ix_memberships_user_id", "memberships", ["user_id"])
    op.create_index("ix_memberships_organization_id", "memberships", ["organization_id"])
    # Retain the old scope UUIDs, including receipt-storage directory names.
    op.execute(
        "INSERT INTO organizations (id, name, created_at) SELECT id, 'Моя организация', created_at FROM users"
    )
    op.execute(
        "INSERT INTO memberships (id, user_id, organization_id, role, created_at) SELECT id, id, id, 'admin', created_at FROM users"
    )
    connection = op.get_bind()
    inspector = sa.inspect(connection)
    for table in TABLES:
        for fk in inspector.get_foreign_keys(table):
            if fk["constrained_columns"] == ["user_id"]:
                op.drop_constraint(fk["name"], table, type_="foreignkey")
        op.alter_column(table, "user_id", new_column_name="organization_id")
        op.create_foreign_key(
            f"fk_{table}_organization",
            table,
            "organizations",
            ["organization_id"],
            ["id"],
            ondelete="CASCADE",
        )
        op.drop_index(f"ix_{table}_user_id", table_name=table)
        op.create_index(f"ix_{table}_organization_id", table, ["organization_id"])
    op.add_column("receipts", sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column(
        "audit", sa.Column("actor_id", sa.String(36), sa.ForeignKey("users.id"), nullable=True)
    )
    op.create_table(
        "receipt_comments",
        *identity(),
        sa.Column(
            "organization_id",
            sa.String(36),
            sa.ForeignKey("organizations.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "receipt_id",
            sa.String(36),
            sa.ForeignKey("receipts.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("author_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("text", sa.String(3000), nullable=False),
    )
    op.create_index("ix_receipt_comments_organization_id", "receipt_comments", ["organization_id"])
    op.create_index("ix_receipt_comments_receipt_id", "receipt_comments", ["receipt_id"])


def downgrade():
    raise RuntimeError(
        "Organizations may have shared financial data. Restore the encrypted backup to roll back safely."
    )
