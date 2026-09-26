"""Bounded, organization-scoped reclassification without changing money or source evidence."""

from collections import defaultdict
from decimal import Decimal

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from . import models as m
from .category_catalog import DAILY_CATEGORIES, DEFAULT_CATEGORIES
from .finance import apportion_minor, audit, lock_organization, minor
from .receipts import categorize, category_context, normalized, parse_mev, receipt_header

BATCH_SIZE = 50


def install_daily_categories(db: Session, organization_id: str) -> int:
    lock_organization(db, organization_id)
    existing = {
        name.casefold()
        for name in db.scalars(
            select(m.Category.name).where(m.Category.organization_id == organization_id)
        )
    }
    added = 0
    for name, icon, color in DAILY_CATEGORIES:
        if name.casefold() not in existing:
            db.add(m.Category(organization_id=organization_id, name=name, color=color, icon=icon))
            existing.add(name.casefold())
            added += 1
    db.flush()
    if added:
        audit(db, organization_id, "categories.expanded", organization_id, {"added": added})
    return added


def source_name_repairs(
    receipt: m.Receipt, items: list[m.ReceiptItem]
) -> tuple[dict[str, str], str | None, str | None]:
    """Repair only fiscal-header contamination, with unambiguous original evidence."""
    raw = receipt.original.get("mev_text") or receipt.original.get("ocr_text")
    if not isinstance(raw, str) or not raw:
        return {}, None, None
    parsed = parse_mev(raw[:50000])
    if not parsed["readable"] or len(parsed["items"]) != len(items):
        return {}, None, None
    if minor(parsed["total"]) != receipt.total_minor or parsed["currency"] != receipt.currency:
        return {}, None, None
    candidates = defaultdict(list)
    for line in parsed["items"]:
        candidates[
            (Decimal(line["quantity"]), minor(line["unit_price"]), minor(line["total"]))
        ].append(line["name"])
    repairs = {}
    for item in items:
        if not receipt_header(item.name):
            continue
        names = candidates.get((item.quantity, item.unit_price_minor, item.total_minor), [])
        # Identical-price products cannot be matched reliably by amount alone.
        if len(names) == 1 and 0 < len(names[0]) <= 300 and not receipt_header(names[0]):
            repairs[item.id] = names[0]
    merchant = (
        parsed["merchant"]
        if receipt_header(receipt.merchant) and not receipt_header(parsed["merchant"])
        else None
    )
    address = (
        parsed.get("merchant_address") if not receipt.original.get("merchant_address") else None
    )
    return repairs, merchant, address or None


def refresh_receipt_categories(db: Session, organization_id: str, after: str = "") -> dict:
    lock_organization(db, organization_id)
    result = {
        "categories_added": install_daily_categories(db, organization_id),
        "scanned": 0,
        "receipts_updated": 0,
        "items_updated": 0,
        "names_repaired": 0,
        "merchants_repaired": 0,
        "addresses_added": 0,
        "skipped": {},
        "next_cursor": None,
    }
    receipts = list(
        db.scalars(
            select(m.Receipt)
            .where(
                m.Receipt.organization_id == organization_id,
                m.Receipt.deleted_at.is_(None),
                m.Receipt.id > after,
            )
            .order_by(m.Receipt.id)
            .limit(BATCH_SIZE + 1)
            .with_for_update()
        )
    )
    if len(receipts) > BATCH_SIZE:
        receipts = receipts[:BATCH_SIZE]
        result["next_cursor"] = receipts[-1].id
    result["scanned"] = len(receipts)
    if not receipts:
        return result
    ids = [receipt.id for receipt in receipts]
    items_by_receipt = defaultdict(list)
    for item in db.scalars(
        select(m.ReceiptItem)
        .where(m.ReceiptItem.receipt_id.in_(ids))
        .order_by(m.ReceiptItem.created_at, m.ReceiptItem.id)
    ):
        items_by_receipt[item.receipt_id].append(item)
    transactions = {
        tx.receipt_id: tx
        for tx in db.scalars(
            select(m.Transaction)
            .where(
                m.Transaction.organization_id == organization_id, m.Transaction.receipt_id.in_(ids)
            )
            .with_for_update()
        )
    }
    refunded = set(
        db.scalars(
            select(m.Transaction.refund_of).where(
                m.Transaction.organization_id == organization_id,
                m.Transaction.refund_of.in_([tx.id for tx in transactions.values()]),
                ~m.Transaction.voided,
            )
        )
    )
    allocations = defaultdict(list)
    for allocation in db.scalars(
        select(m.Allocation).where(
            m.Allocation.transaction_id.in_([tx.id for tx in transactions.values()])
        )
    ):
        allocations[allocation.transaction_id].append(allocation)
    context = category_context(db, organization_id)
    builtins = {name for name, _, _ in DEFAULT_CATEGORIES}
    custom_names = {cid: name for name, cid in context[1].items() if name not in builtins}

    def skip(reason: str):
        result["skipped"][reason] = result["skipped"].get(reason, 0) + 1

    for receipt in receipts:
        if receipt.status not in {"posted", "review"}:
            skip("processing")
            continue
        items = items_by_receipt[receipt.id]
        tx = transactions.get(receipt.id)
        if not items or (
            receipt.status == "posted" and (not tx or tx.voided or tx.kind != "expense")
        ):
            skip("inactive")
            continue
        # A refund doesn't identify the returned products. Don't invent its new
        # category shares and leave a negative old category in the reports.
        if tx and tx.id in refunded:
            skip("refund")
            continue
        if tx and (
            tx.amount_minor != sum(i.total_minor for i in items)
            or tx.amount_minor != receipt.total_minor
            or tx.currency != receipt.currency
            or tx.amount_minor <= 0
            or any(i.total_minor < 0 for i in items)
            or sum(a.amount_minor for a in allocations[tx.id]) != tx.amount_minor
            or sum(a.base_minor for a in allocations[tx.id]) != tx.base_minor
        ):
            skip("amount_mismatch")
            continue
        repairs, merchant, address = source_name_repairs(receipt, items)
        changes = []
        splits = defaultdict(int)
        for item in items:
            name = repairs.get(item.id, item.name)
            category = (
                categorize(
                    db,
                    organization_id,
                    name,
                    merchant or receipt.merchant,
                    custom_names.get(item.category_id, ""),
                    context,
                )
                or item.category_id
            )
            splits[category] += item.total_minor
            if category != item.category_id or name != item.name:
                changes.append(
                    {
                        "item_id": item.id,
                        "old_category_id": item.category_id,
                        "category_id": category,
                        **({"old_name": item.name, "name": name} if name != item.name else {}),
                    }
                )
                result["items_updated"] += int(category != item.category_id)
                result["names_repaired"] += int(name != item.name)
                item.category_id = category
                item.name, item.normalized_name = name, normalized(name)
        bases = apportion_minor(tx.base_minor, list(splits.values())) if tx else []
        expected_allocations = (
            {
                category: (amount, base)
                for (category, amount), base in zip(splits.items(), bases, strict=True)
            }
            if tx
            else {}
        )
        current_allocations = defaultdict(lambda: (0, 0))
        if tx:
            for allocation in allocations[tx.id]:
                amount, base = current_allocations[allocation.category_id]
                current_allocations[allocation.category_id] = (
                    amount + allocation.amount_minor,
                    base + allocation.base_minor,
                )
        allocations_changed = bool(tx and expected_allocations != current_allocations)
        if not changes and not merchant and not address and not allocations_changed:
            continue
        if tx:
            db.execute(delete(m.Allocation).where(m.Allocation.transaction_id == tx.id))
            for (category, amount), base in zip(splits.items(), bases, strict=True):
                db.add(
                    m.Allocation(
                        transaction_id=tx.id,
                        category_id=category,
                        amount_minor=amount,
                        base_minor=base,
                    )
                )
            tx.category_id = next(iter(splits)) if len(splits) == 1 else None
            if merchant and tx.merchant == receipt.merchant:
                tx.merchant = merchant
            tx.version += 1
        audit(
            db,
            organization_id,
            "receipt.reclassified",
            receipt.id,
            {
                "items": changes,
                "allocations_updated": allocations_changed,
                **({"old_merchant": receipt.merchant, "merchant": merchant} if merchant else {}),
                **({"merchant_address": address} if address else {}),
            },
        )
        if merchant:
            receipt.merchant = merchant
            result["merchants_repaired"] += 1
        if address:
            receipt.original = {**receipt.original, "merchant_address": address}
            result["addresses_added"] += 1
        receipt.version += 1
        result["receipts_updated"] += 1
    db.flush()
    return result
