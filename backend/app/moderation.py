from collections import defaultdict

from sqlalchemy import delete, select

from . import models as m
from .finance import apportion_minor, audit, base_value, fail, lock_organization, owned
from .ledger import void_transaction


def editable_receipt(db, organization_id, key, version):
    lock_organization(db, organization_id)
    receipt = owned(db, m.Receipt, key, organization_id, True)
    if receipt.version != version:
        fail("Чек изменился. Откройте его заново", 409)
    if receipt.status in {"queued", "processing"}:
        fail("Дождитесь окончания распознавания перед удалением", 409)
    return receipt


def remove_receipt(db, organization_id, key, version):
    receipt = editable_receipt(db, organization_id, key, version)
    tx = db.scalar(
        select(m.Transaction).where(m.Transaction.receipt_id == key, ~m.Transaction.voided)
    )
    if tx:
        void_transaction(db, organization_id, tx.id, tx.version)
    receipt.deleted_at, receipt.status = m.now(), "deleted"
    receipt.version += 1
    audit(
        db,
        organization_id,
        "receipt.deleted",
        key,
        {"total_minor": receipt.total_minor, "transaction_id": tx.id if tx else None},
    )


def remove_item(db, organization_id, key, item_id, version):
    receipt = editable_receipt(db, organization_id, key, version)
    rows = list(
        db.scalars(
            select(m.ReceiptItem)
            .where(m.ReceiptItem.receipt_id == key)
            .order_by(m.ReceiptItem.created_at, m.ReceiptItem.id)
        )
    )
    item = next((row for row in rows if row.id == item_id), None)
    if item is None:
        fail("Товар не найден", 404)
    remaining = [row for row in rows if row.id != item_id]
    if not remaining or sum(row.total_minor for row in remaining) <= 0:
        fail("Нельзя оставить чек без положительной суммы. Удалите чек целиком", 409)
    tx = db.scalar(
        select(m.Transaction).where(m.Transaction.receipt_id == key, ~m.Transaction.voided)
    )
    if tx:
        if db.scalar(
            select(m.Transaction.id)
            .where(m.Transaction.refund_of == tx.id, ~m.Transaction.voided)
            .limit(1)
        ):
            fail("Сначала отмените связанные возвраты", 409)
        before = tx.amount_minor
        tx.amount_minor = sum(row.total_minor for row in remaining)
        tx.base_minor = base_value(tx.amount_minor, tx.fx_rate)
        tx.version += 1
        splits = defaultdict(int)
        for row in remaining:
            splits[row.category_id] += row.total_minor
        splits = {category: value for category, value in splits.items() if value > 0}
        tx.category_id = next(iter(splits)) if len(splits) == 1 else None
        db.execute(delete(m.Posting).where(m.Posting.transaction_id == tx.id))
        db.execute(delete(m.Allocation).where(m.Allocation.transaction_id == tx.id))
        db.add(
            m.Posting(transaction_id=tx.id, account_id=tx.account_id, amount_minor=-tx.amount_minor)
        )
        bases = apportion_minor(tx.base_minor, list(splits.values()))
        for (category, value), base in zip(splits.items(), bases, strict=True):
            db.add(
                m.Allocation(
                    transaction_id=tx.id, category_id=category, amount_minor=value, base_minor=base
                )
            )
        audit(
            db,
            organization_id,
            "transaction.receipt_corrected",
            tx.id,
            {"before_minor": before, "after_minor": tx.amount_minor},
        )
    audit(
        db,
        organization_id,
        "receipt.item_deleted",
        key,
        {
            "item_id": item.id,
            "name": item.name,
            "quantity": str(item.quantity),
            "total_minor": item.total_minor,
            "category_id": item.category_id,
        },
    )
    db.delete(item)
    receipt.total_minor = sum(row.total_minor for row in remaining)
    receipt.version += 1
    db.flush()
