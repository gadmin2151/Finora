"""Copy a reviewed monthly budget without replacing limits or posting money."""

import hashlib
import json
from datetime import timedelta

from sqlalchemy import select

from . import models as m
from .finance import audit, fail, lock_organization, month_range


def copy_preview(db, organization_id: str, month: str) -> dict:
    start, _ = month_range(month)
    source_month = (start - timedelta(days=1)).strftime("%Y-%m")
    rows = db.execute(
        select(m.Budget, m.Category.name)
        .join(m.Category, m.Category.id == m.Budget.category_id)
        .where(
            m.Budget.organization_id == organization_id,
            m.Category.organization_id == organization_id,
            m.Budget.month.in_([source_month, month]),
        )
        .order_by(m.Budget.month, m.Budget.category_id)
    ).all()
    existing = {row.category_id for row, _ in rows if row.month == month}
    items = [
        {"category_id": row.category_id, "name": name, "amount_minor": row.amount_minor}
        for row, name in rows
        if row.month == source_month and row.category_id not in existing
    ]
    snapshot = [[row.id, row.month, row.category_id, row.amount_minor, name] for row, name in rows]
    fingerprint = hashlib.sha256(
        json.dumps([organization_id, month, snapshot], ensure_ascii=False).encode()
    ).hexdigest()
    return {
        "month": month,
        "source_month": source_month,
        "fingerprint": fingerprint,
        "items": items,
        "total_minor": sum(item["amount_minor"] for item in items),
        "preserved_count": len(existing),
    }


def copy_previous_month(db, organization_id: str, month: str, fingerprint: str) -> dict:
    lock_organization(db, organization_id)
    preview = copy_preview(db, organization_id, month)
    if preview["fingerprint"] != fingerprint:
        fail("Лимиты изменились. Закройте окно и проверьте перенос заново", 409)
    for item in preview["items"]:
        if not 0 < item["amount_minor"] <= 100000000000:
            fail("Проверьте лимиты предыдущего месяца")
        db.add(
            m.Budget(
                organization_id=organization_id,
                month=month,
                category_id=item["category_id"],
                amount_minor=item["amount_minor"],
            )
        )
    if preview["items"]:
        audit(
            db,
            organization_id,
            "budget.copied",
            month,
            {"source_month": preview["source_month"], "count": len(preview["items"])},
        )
    return {"copied_count": len(preview["items"]), "month": month}
