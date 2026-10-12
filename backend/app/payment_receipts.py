"""Attach receipt views to paid bills without creating or rewriting financial movements."""

import argparse
import json
from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal
from uuid import UUID

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from . import models as m
from .db import SessionLocal
from .finance import audit, lock_organization
from .receipts import normalized


class PaymentReceiptError(Exception):
    """A safe diagnostic code; never include receipt contents or credentials."""


@dataclass
class PaymentDocuments:
    receipts: dict[str, m.Receipt]
    generated: dict[str, m.Receipt]
    allocations: dict[str, list[m.Allocation]]
    authors: dict[str, str | None]


def load_documents(db: Session, organization_id: str, transactions: list[m.Transaction]):
    ids = [tx.id for tx in transactions]
    receipts = list(
        db.scalars(
            select(m.Receipt).where(
                m.Receipt.organization_id == organization_id,
                or_(
                    m.Receipt.id.in_([tx.receipt_id for tx in transactions if tx.receipt_id]),
                    m.Receipt.source_key.in_([f"payment:{key}" for key in ids]),
                ),
            )
        )
    )
    allocations = defaultdict(list)
    for allocation, category_org in db.execute(
        select(m.Allocation, m.Category.organization_id)
        .outerjoin(m.Category, m.Category.id == m.Allocation.category_id)
        .where(m.Allocation.transaction_id.in_(ids))
        .order_by(m.Allocation.created_at, m.Allocation.id)
    ):
        if allocation.category_id is not None and category_org != organization_id:
            raise PaymentReceiptError("allocation_category_scope")
        allocations[allocation.transaction_id].append(allocation)
    authors = {}
    for entry in db.scalars(
        select(m.Audit)
        .where(
            m.Audit.organization_id == organization_id,
            m.Audit.entity_id.in_(ids),
            m.Audit.action == "transaction.created",
        )
        .order_by(m.Audit.created_at, m.Audit.id)
    ):
        authors.setdefault(entry.entity_id, entry.actor_id)
    return PaymentDocuments(
        {receipt.id: receipt for receipt in receipts},
        {receipt.source_key: receipt for receipt in receipts},
        dict(allocations),
        authors,
    )


def attach_receipt(
    db: Session,
    tx: m.Transaction,
    occurrence: m.Occurrence,
    bill: m.Bill,
    *,
    apply: bool = True,
    increment_version: bool = True,
    documents: PaymentDocuments | None = None,
) -> str:
    """Caller holds the organization lock; return create/mark/unchanged without posting money."""
    if (
        tx.kind != "expense"
        or tx.voided
        or bill.kind != "expense"
        or tx.occurrence_id != occurrence.id
        or occurrence.bill_id != bill.id
        or len({tx.organization_id, occurrence.organization_id, bill.organization_id}) != 1
    ):
        raise PaymentReceiptError("payment_scope")
    documents = documents or load_documents(db, tx.organization_id, [tx])
    metadata = {
        "bill_id": bill.id,
        "occurrence_id": occurrence.id,
        "due_date": occurrence.due_date.isoformat(),
    }
    receipt = documents.receipts.get(tx.receipt_id) if tx.receipt_id else None
    if tx.receipt_id and receipt is None:
        raise PaymentReceiptError("receipt_scope")
    if receipt is not None:
        if receipt.deleted_at or receipt.status != "posted":
            raise PaymentReceiptError("receipt_not_posted")
        if receipt.currency != tx.currency or receipt.total_minor != tx.amount_minor:
            raise PaymentReceiptError("receipt_amount_mismatch")
        if receipt.original.get("mandatory_payment") == metadata:
            return "unchanged"
        if apply:
            receipt.original = {**receipt.original, "mandatory_payment": metadata}
            receipt.version += 1
            audit(
                db, tx.organization_id, "bill.receipt_marked", receipt.id, {"transaction_id": tx.id}
            )
        return "marked"
    source_key = f"payment:{tx.id}"
    if source_key in documents.generated:
        # An orphan or deleted generated document is not safe to revive automatically.
        raise PaymentReceiptError("unlinked_payment_receipt")
    allocations = documents.allocations.get(tx.id, [])
    if (
        tx.amount_minor <= 0
        or not allocations
        or any(row.amount_minor <= 0 or row.base_minor < 0 for row in allocations)
        or sum(row.amount_minor for row in allocations) != tx.amount_minor
        or sum(row.base_minor for row in allocations) != tx.base_minor
    ):
        raise PaymentReceiptError("allocation_amount_mismatch")
    if not apply:
        return "created"
    receipt = m.Receipt(
        organization_id=tx.organization_id,
        created_at=tx.created_at,
        created_by=documents.authors.get(tx.id),
        source="manual",
        source_key=source_key,
        merchant=tx.merchant or bill.name,
        purchased_on=tx.occurred_on,
        currency=tx.currency,
        total_minor=tx.amount_minor,
        status="posted",
        account_id=tx.account_id,
        fx_rate=tx.fx_rate,
        original={
            "document_type": "manual_receipt",
            "generated_payment_receipt": True,
            "mandatory_payment": metadata,
        },
    )
    db.add(receipt)
    db.flush()
    for allocation in allocations:
        db.add(
            m.ReceiptItem(
                receipt_id=receipt.id,
                created_at=tx.created_at,
                name=bill.name,
                normalized_name=normalized(bill.name),
                quantity=Decimal(1),
                unit="шт",
                unit_price_minor=allocation.amount_minor,
                total_minor=allocation.amount_minor,
                category_id=allocation.category_id,
            )
        )
    tx.receipt_id = receipt.id
    if increment_version:
        tx.version += 1
    audit(db, tx.organization_id, "bill.receipt_created", receipt.id, {"transaction_id": tx.id})
    db.flush()
    return "created"


def backfill(
    *,
    apply: bool,
    organization_id: str | None = None,
    batch_size: int = 100,
    session_factory=SessionLocal,
):
    if not 1 <= batch_size <= 500:
        raise ValueError("batch_size must be between 1 and 500")
    result = {
        "mode": "apply" if apply else "dry-run",
        "scanned": 0,
        "created": 0,
        "marked": 0,
        "unchanged": 0,
    }
    after_org = ""
    while True:
        with session_factory() as db:
            query = select(m.Organization.id).where(m.Organization.id > after_org)
            if organization_id:
                query = query.where(m.Organization.id == organization_id)
            org = db.scalar(query.order_by(m.Organization.id).limit(1))
            if org is None:
                if organization_id and not after_org:
                    raise PaymentReceiptError("organization_not_found")
                return result
        after_tx = ""
        while True:
            with session_factory() as db:
                if apply:
                    lock_organization(db, org)
                rows = db.execute(
                    select(m.Transaction, m.Occurrence, m.Bill)
                    .join(m.Occurrence, m.Occurrence.id == m.Transaction.occurrence_id)
                    .join(m.Bill, m.Bill.id == m.Occurrence.bill_id)
                    .where(
                        m.Transaction.organization_id == org,
                        m.Transaction.kind == "expense",
                        ~m.Transaction.voided,
                        m.Bill.kind == "expense",
                        m.Transaction.id > after_tx,
                    )
                    .order_by(m.Transaction.id)
                    .limit(batch_size)
                ).all()
                if not rows:
                    break
                documents = load_documents(db, org, [tx for tx, _, _ in rows])
                counts = defaultdict(int)
                for tx, occurrence, bill in rows:
                    action = attach_receipt(
                        db, tx, occurrence, bill, apply=apply, documents=documents
                    )
                    counts[action] += 1
                if apply:
                    db.commit()
                for action, count in counts.items():
                    result[action] += count
                result["scanned"] += len(rows)
                after_tx = rows[-1][0].id
        after_org = org


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true")
    parser.add_argument("--organization-id", type=UUID)
    parser.add_argument(
        "--batch-size", type=int, default=100, choices=range(1, 501), metavar="1..500"
    )
    args = parser.parse_args()
    try:
        result = backfill(
            apply=args.apply,
            organization_id=str(args.organization_id) if args.organization_id else None,
            batch_size=args.batch_size,
        )
    except PaymentReceiptError as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1
    except Exception as error:
        print(json.dumps({"ok": False, "error": type(error).__name__}))
        return 1
    print(json.dumps({"ok": True, **result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
