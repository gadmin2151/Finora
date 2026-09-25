from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import income, moderation
from . import models as m
from . import schemas as s
from .db import get_db
from .finance import audit, fail, lock_organization, owned, transaction_dict
from .purchases import PurchaseFilters, history
from .receipts import receipt_dict
from .security import current_organization, current_user

router = APIRouter(prefix="/api")
DB = Depends(get_db)
SCOPE = Depends(current_organization)


@router.get("/purchases")
def purchases(
    filters: Annotated[PurchaseFilters, Query()], org: m.Organization = SCOPE, db: Session = DB
):
    return history(db, org.id, filters)


@router.get("/income")
def income_report(month: str, org: m.Organization = SCOPE, db: Session = DB):
    result = income.report(db, org.id, month)
    db.commit()
    return result


@router.get("/income/templates")
def income_templates(org: m.Organization = SCOPE, db: Session = DB):
    return [
        income.plan_dict(row)
        for row in db.scalars(
            select(m.Bill)
            .where(m.Bill.organization_id == org.id, m.Bill.kind == "income")
            .order_by(m.Bill.created_at)
        )
    ]


@router.post("/income/templates")
def create_income(data: s.IncomePlanInput, org: m.Organization = SCOPE, db: Session = DB):
    row = income.save_plan(db, org.id, data)
    db.commit()
    return income.plan_dict(row)


@router.put("/income/templates/{key}")
def edit_income(key: str, data: s.IncomePlanInput, org: m.Organization = SCOPE, db: Session = DB):
    row = income.save_plan(db, org.id, data, key)
    db.commit()
    return income.plan_dict(row)


@router.put("/income/templates/{key}/active")
def income_active(key: str, data: s.IncomeActive, org: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, org.id)
    row = income.owned_plan(db, org.id, key)
    if row.version != data.version:
        fail("Источник изменился. Обновите страницу", 409)
    row.active, row.version = data.active, row.version + 1
    audit(db, org.id, "income.active_changed", key, {"active": data.active})
    db.commit()
    return income.plan_dict(row)


@router.post("/income/occurrences/{key}/receive")
def receive_income(key: str, data: s.BillPayment, org: m.Organization = SCOPE, db: Session = DB):
    result = income.receive(db, org.id, key, data)
    db.commit()
    return transaction_dict(result)


@router.post("/income/occurrences/{key}/skip")
def skip_income(key: str, org: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, org.id)
    row = owned(db, m.Occurrence, key, org.id, True)
    income.owned_plan(db, org.id, row.bill_id)
    if db.scalar(
        select(m.Transaction.id).where(m.Transaction.occurrence_id == key, ~m.Transaction.voided)
    ):
        fail("Доход уже получен", 409)
    row.skipped = not row.skipped
    audit(db, org.id, "income.occurrence_skipped", key, {"skipped": row.skipped})
    db.commit()
    return {"skipped": row.skipped}


@router.get("/receipts/{key}/comments")
def comments(key: str, offset: int = Query(0, ge=0), org: m.Organization = SCOPE, db: Session = DB):
    owned(db, m.Receipt, key, org.id)
    return [
        dict(row)
        for row in db.execute(
            select(
                m.ReceiptComment.id,
                m.ReceiptComment.text,
                m.ReceiptComment.created_at,
                m.User.name.label("author"),
            )
            .join(m.User, m.User.id == m.ReceiptComment.author_id)
            .where(m.ReceiptComment.receipt_id == key, m.ReceiptComment.organization_id == org.id)
            .order_by(m.ReceiptComment.created_at.desc(), m.ReceiptComment.id)
            .offset(offset)
            .limit(50)
        ).mappings()
    ]


@router.post("/receipts/{key}/comments")
def add_comment(
    key: str,
    data: s.CommentInput,
    user: m.User = Depends(current_user),
    org: m.Organization = SCOPE,
    db: Session = DB,
):
    lock_organization(db, org.id)
    owned(db, m.Receipt, key, org.id)
    row = m.ReceiptComment(
        organization_id=org.id, receipt_id=key, author_id=user.id, text=data.text
    )
    db.add(row)
    db.flush()
    audit(db, org.id, "receipt.commented", key)
    db.commit()
    return {"id": row.id}


@router.delete("/receipts/{key}")
def delete_receipt(
    key: str, version: int = Query(ge=1), org: m.Organization = SCOPE, db: Session = DB
):
    moderation.remove_receipt(db, org.id, key, version)
    db.commit()
    return {"ok": True}


@router.delete("/receipts/{key}/items/{item_id}")
def delete_item(
    key: str,
    item_id: str,
    version: int = Query(ge=1),
    org: m.Organization = SCOPE,
    db: Session = DB,
):
    moderation.remove_item(db, org.id, key, item_id, version)
    db.commit()
    return receipt_dict(db, owned(db, m.Receipt, key, org.id))
