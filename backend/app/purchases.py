"""Read-only product history; comparisons never mix currencies, units or product names."""

from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal

from pydantic import Field, model_validator
from sqlalchemy import func, select

from . import models as m
from .receipts import normalized
from .schemas import Currency, Strict


class PurchaseFilters(Strict):
    search: str = Field(default="", max_length=100)
    merchant: str = Field(default="", max_length=100)
    category_id: str = Field(default="", max_length=36)
    account_id: str = Field(default="", max_length=36)
    currency: Currency | None = None
    unit: str = Field(default="", max_length=12)
    date_from: date | None = None
    date_to: date | None = None
    sort: Literal["newest", "oldest", "amount_desc", "price_asc", "price_desc"] = "newest"
    offset: int = Field(default=0, ge=0)
    limit: int = Field(default=30, ge=1, le=100)

    @model_validator(mode="after")
    def valid_period(self):
        if self.date_from and self.date_to and self.date_from > self.date_to:
            raise ValueError("Начало периода должно быть не позже конца")
        return self


def history(db, organization_id: str, filters: PurchaseFilters):
    item, receipt, tx = m.ReceiptItem, m.Receipt, m.Transaction
    query = (
        select(
            item.id,
            item.receipt_id,
            item.name,
            item.normalized_name,
            item.category_id,
            item.quantity,
            item.unit,
            item.total_minor,
            (item.total_minor / item.quantity).label("unit_minor"),
            receipt.merchant,
            receipt.currency,
            receipt.purchased_on,
            tx.account_id,
        )
        .join(receipt, receipt.id == item.receipt_id)
        .join(tx, tx.receipt_id == receipt.id)
        .where(
            receipt.organization_id == organization_id,
            tx.organization_id == organization_id,
            ~tx.voided,
            tx.kind == "expense",
            receipt.status == "posted",
            receipt.deleted_at.is_(None),
            item.quantity > 0,
        )
    )
    if filters.search:
        query = query.where(
            item.normalized_name.contains(normalized(filters.search), autoescape=True)
        )
    if filters.merchant:
        query = query.where(receipt.merchant.icontains(filters.merchant, autoescape=True))
    if filters.category_id:
        query = query.where(
            item.category_id.is_(None)
            if filters.category_id == "uncategorized"
            else item.category_id == filters.category_id
        )
    if filters.account_id:
        query = query.where(tx.account_id == filters.account_id)
    if filters.currency:
        query = query.where(receipt.currency == filters.currency)
    if filters.unit:
        query = query.where(item.unit == filters.unit)
    if filters.date_from:
        query = query.where(receipt.purchased_on >= filters.date_from)
    if filters.date_to:
        query = query.where(receipt.purchased_on <= filters.date_to)
    selected = query.subquery()
    total, receipts = db.execute(
        select(func.count(), func.count(func.distinct(selected.c.receipt_id)))
    ).one()
    totals = [
        {"currency": currency, "total_minor": int(value)}
        for currency, value in db.execute(
            select(selected.c.currency, func.sum(selected.c.total_minor))
            .group_by(selected.c.currency)
            .order_by(selected.c.currency)
        )
    ]
    sorts = {
        "newest": [selected.c.purchased_on.desc()],
        "oldest": [selected.c.purchased_on],
        "amount_desc": [selected.c.currency, selected.c.total_minor.desc()],
        "price_asc": [selected.c.currency, selected.c.unit_minor],
        "price_desc": [selected.c.currency, selected.c.unit_minor.desc()],
    }
    items = [
        dict(row)
        for row in db.execute(
            select(selected)
            .order_by(*sorts[filters.sort], selected.c.id)
            .offset(filters.offset)
            .limit(filters.limit)
        ).mappings()
    ]
    for row in items:
        row["quantity"], row["unit_minor"] = str(row["quantity"]), str(row["unit_minor"])
        row.pop("normalized_name")
    categories = [
        {"category_id": cat, "currency": currency, "total_minor": int(value)}
        for cat, currency, value in db.execute(
            select(
                selected.c.category_id,
                selected.c.currency,
                func.sum(selected.c.total_minor).label("spent"),
            )
            .group_by(selected.c.category_id, selected.c.currency)
            .order_by(selected.c.currency, func.sum(selected.c.total_minor).desc())
            .limit(20)
        )
    ]
    group = [selected.c.normalized_name, selected.c.unit, selected.c.currency]
    prices = (
        select(
            *group,
            func.min(selected.c.name).label("name"),
            func.min(selected.c.unit_minor).label("minimum"),
            func.max(selected.c.unit_minor).label("maximum"),
            func.sum(selected.c.quantity).label("quantity"),
            func.sum(selected.c.total_minor).label("spent"),
            func.count(func.distinct(selected.c.receipt_id)).label("receipt_count"),
        )
        .where(selected.c.total_minor > 0)
        .group_by(*group)
        .having(func.count(func.distinct(selected.c.receipt_id)) >= 2)
        .subquery()
    )
    ranked = (
        select(
            selected,
            func.row_number()
            .over(
                partition_by=group,
                order_by=[selected.c.unit_minor, selected.c.purchased_on.desc(), selected.c.id],
            )
            .label("rank"),
        )
        .where(selected.c.total_minor > 0)
        .subquery()
    )
    potential = prices.c.spent - prices.c.minimum * prices.c.quantity
    comparison = (
        select(prices, ranked.c.merchant, ranked.c.purchased_on)
        .join(
            ranked,
            (ranked.c.normalized_name == prices.c.normalized_name)
            & (ranked.c.unit == prices.c.unit)
            & (ranked.c.currency == prices.c.currency)
            & (ranked.c.rank == 1),
        )
        .where(prices.c.maximum > prices.c.minimum)
        .order_by(prices.c.currency, potential.desc())
        .limit(12)
    )
    comparisons = []
    for row in db.execute(comparison).mappings():
        comparisons.append(
            {
                "name": row["name"],
                "unit": row["unit"],
                "currency": row["currency"],
                "min_unit_minor": str(row["minimum"]),
                "max_unit_minor": str(row["maximum"]),
                "quantity": str(row["quantity"]),
                "receipt_count": row["receipt_count"],
                "best_merchant": row["merchant"],
                "best_on": row["purchased_on"],
                "potential_minor": int(
                    (Decimal(row["spent"]) - row["minimum"] * row["quantity"]).quantize(
                        Decimal(1), rounding=ROUND_HALF_UP
                    )
                ),
            }
        )
    return {
        "items": items,
        "total": total,
        "receipt_count": receipts,
        "totals": totals,
        "categories": categories,
        "comparisons": comparisons,
    }
