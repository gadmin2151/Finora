"""Read debt movements from the existing ledger without changing their financial effect."""

from datetime import UTC

from sqlalchemy import and_, case, func, select, true
from sqlalchemy.orm import Session

from . import models as m
from .finance import owned


def history(db: Session, organization_id: str, debt_id: str, *, offset: int, limit: int):
    debt = owned(db, m.Debt, debt_id, organization_id)
    scope = (m.Transaction.organization_id == organization_id, m.Transaction.debt_id == debt.id)
    increase = m.Transaction.kind.in_(("debt_lend", "debt_borrow"))
    change = case(
        (m.Transaction.voided, 0),
        (increase, m.Transaction.amount_minor),
        else_=-m.Transaction.amount_minor,
    )
    summary = (
        select(
            func.count().label("total"),
            func.coalesce(func.sum(change), 0).label("movement_minor"),
            func.coalesce(
                func.sum(
                    case(
                        (and_(~m.Transaction.voided, increase), m.Transaction.amount_minor), else_=0
                    )
                ),
                0,
            ).label("increased_minor"),
            func.coalesce(
                func.sum(
                    case(
                        (and_(~m.Transaction.voided, ~increase), m.Transaction.amount_minor),
                        else_=0,
                    )
                ),
                0,
            ).label("repaid_minor"),
        )
        .where(*scope)
        .subquery()
    )
    movements = (
        select(
            m.Transaction.id,
            m.Transaction.kind,
            m.Transaction.occurred_on,
            m.Transaction.created_at,
            m.Transaction.amount_minor,
            m.Transaction.account_id,
            m.Account.name.label("account_name"),
            m.Transaction.note,
            m.Transaction.voided,
            case(
                (
                    and_(
                        increase,
                        m.Transaction.idempotency_key == debt.creation_key,
                        debt.initial_minor == 0,
                    ),
                    "initial",
                ),
                (increase, "increase"),
                else_="repayment",
            ).label("operation"),
            (
                debt.initial_minor
                + func.sum(change).over(
                    order_by=(m.Transaction.created_at, m.Transaction.id), rows=(None, 0)
                )
            ).label("balance_after_minor"),
        )
        .outerjoin(
            m.Account,
            and_(
                m.Account.id == m.Transaction.account_id,
                m.Account.organization_id == organization_id,
            ),
        )
        .where(*scope)
        .subquery()
    )
    page = (
        select(movements)
        .order_by(movements.c.created_at.desc(), movements.c.id.desc())
        .offset(offset)
        .limit(limit)
        .subquery()
    )
    # One statement keeps totals, running balances and the page on the same DB snapshot.
    # The outer join also preserves the summary for an empty or out-of-range page.
    rows = (
        db.execute(
            select(summary, page)
            .select_from(summary.outerjoin(page, true()))
            .order_by(page.c.created_at.desc(), page.c.id.desc())
        )
        .mappings()
        .all()
    )
    totals = rows[0]
    items = [
        {
            "id": row["id"],
            "kind": row["kind"],
            "operation": row["operation"],
            "occurred_on": row["occurred_on"].isoformat(),
            "created_at": (
                row["created_at"].replace(tzinfo=UTC)
                if row["created_at"].tzinfo is None
                else row["created_at"].astimezone(UTC)
            ).isoformat(),
            "amount_minor": int(row["amount_minor"]),
            "balance_after_minor": int(row["balance_after_minor"]),
            "account_id": row["account_id"],
            "account_name": row["account_name"],
            "note": row["note"],
            "voided": row["voided"],
        }
        for row in rows
        if row["id"] is not None
    ]
    return {
        "debt_id": debt.id,
        "currency": debt.currency,
        "initial_minor": debt.initial_minor,
        "remaining_minor": debt.initial_minor + int(totals["movement_minor"]),
        "increased_minor": int(totals["increased_minor"]),
        "repaid_minor": int(totals["repaid_minor"]),
        "items": items,
        "total": totals["total"],
        "offset": offset,
        "limit": limit,
    }
