import hashlib

from sqlalchemy import delete, func, select, update

from . import models as m
from .finance import (
    audit,
    create_transaction,
    debt_remaining,
    fail,
    lock_organization,
    minor,
    owned,
    rate_for,
)
from .schemas import BillPayment, DebtInput, DebtPayment, TransactionEdit, TransactionInput


def add_debt(db, organization_id: str, data: DebtInput):
    lock_organization(db, organization_id)
    fingerprint = hashlib.sha256(data.model_dump_json().encode()).hexdigest()
    prior = db.scalar(
        select(m.Debt).where(
            m.Debt.organization_id == organization_id, m.Debt.creation_key == data.idempotency_key
        )
    )
    if prior:
        if prior.request_hash != fingerprint:
            fail("Повторный запрос содержит другие данные", 409)
        return prior
    debt = m.Debt(
        organization_id=organization_id,
        person=data.person,
        direction=data.direction,
        currency=data.currency,
        initial_minor=minor(data.amount) if data.mode == "existing" else 0,
        due_date=data.due_date,
        note=data.note,
        creation_key=data.idempotency_key,
        request_hash=fingerprint,
    )
    db.add(debt)
    db.flush()
    if data.mode == "new":
        if not data.account_id:
            fail("Выберите счёт, с которого выдали или на который получили деньги")
        account = owned(db, m.Account, data.account_id, organization_id)
        if account.currency != data.currency:
            fail("Валюты долга и счёта должны совпадать")
        create_transaction(
            db,
            organization_id,
            TransactionInput(
                amount=data.amount,
                account_id=data.account_id,
                occurred_on=data.occurred_on,
                fx_rate=data.fx_rate,
                note=data.note,
                merchant=data.person,
                idempotency_key=data.idempotency_key,
            ),
            internal_kind="debt_lend" if data.direction == "lent" else "debt_borrow",
            debt_id=debt.id,
        )
    audit(db, organization_id, "debt.created", debt.id)
    return debt


def repay_debt(db, organization_id: str, debt_id: str, data: DebtPayment):
    lock_organization(db, organization_id)
    debt = owned(db, m.Debt, debt_id, organization_id, True)
    account = owned(db, m.Account, data.account_id, organization_id)
    if account.currency != debt.currency:
        fail("Валюта счёта должна совпадать с валютой долга")
    prior = db.scalar(
        select(m.Transaction).where(
            m.Transaction.organization_id == organization_id,
            m.Transaction.idempotency_key == data.idempotency_key,
        )
    )
    if not prior and minor(data.amount) > debt_remaining(db, debt):
        fail("Сумма возврата превышает остаток долга")
    return create_transaction(
        db,
        organization_id,
        TransactionInput(
            amount=data.amount,
            account_id=data.account_id,
            occurred_on=data.occurred_on,
            fx_rate=data.fx_rate,
            merchant=debt.person,
            idempotency_key=data.idempotency_key,
        ),
        internal_kind="debt_repayment_in" if debt.direction == "lent" else "debt_repayment_out",
        debt_id=debt.id,
    )


def pay_bill(db, organization_id: str, occurrence_id: str, data: BillPayment):
    lock_organization(db, organization_id)
    occurrence = owned(db, m.Occurrence, occurrence_id, organization_id, True)
    bill = owned(db, m.Bill, occurrence.bill_id, organization_id)
    if bill.kind != "expense":
        fail("Это поступление дохода, а не расход", 404)
    existing = db.scalar(
        select(m.Transaction).where(
            m.Transaction.occurrence_id == occurrence.id, ~m.Transaction.voided
        )
    )
    if existing:
        return existing
    account = owned(db, m.Account, data.account_id, organization_id)
    if account.currency != occurrence.currency:
        fail("Валюта счёта должна совпадать с валютой платежа")
    if data.transaction_id:
        tx = owned(db, m.Transaction, data.transaction_id, organization_id, True)
        if (
            tx.kind != "expense"
            or tx.voided
            or tx.occurrence_id
            or tx.account_id != account.id
            or tx.amount_minor != minor(data.amount)
            or tx.occurred_on != data.occurred_on
        ):
            fail("Выберите расход с тем же счётом, датой и суммой, ещё не связанный с платежом")
        tx.occurrence_id = occurrence.id
        tx.version += 1
    else:
        tx = create_transaction(
            db,
            organization_id,
            TransactionInput(
                amount=data.amount,
                account_id=data.account_id,
                occurred_on=data.occurred_on,
                fx_rate=data.fx_rate,
                category_id=bill.category_id,
                merchant=bill.name,
                idempotency_key=data.idempotency_key,
            ),
            occurrence_id=occurrence.id,
        )
    occurrence.skipped = False
    audit(db, organization_id, "bill.paid", occurrence.id)
    return tx


def void_transaction(db, organization_id: str, tx_id: str, version: int):
    lock_organization(db, organization_id)
    tx = owned(db, m.Transaction, tx_id, organization_id, True)
    if tx.voided:
        return tx
    if version != tx.version:
        fail("Операция уже изменена. Обновите страницу", 409)
    if db.scalar(
        select(func.count())
        .select_from(m.Transaction)
        .where(m.Transaction.refund_of == tx.id, ~m.Transaction.voided)
    ):
        fail("Сначала отмените связанные возвраты")
    if tx.debt_id and tx.kind in {"debt_lend", "debt_borrow"}:
        debt = owned(db, m.Debt, tx.debt_id, organization_id)
        if debt_remaining(db, debt) < tx.amount_minor:
            fail("Сначала отмените возвраты по этому долгу")
    if tx.receipt_id:
        receipt = owned(db, m.Receipt, tx.receipt_id, organization_id)
        receipt.status, receipt.version = "review", receipt.version + 1
        tx.receipt_id = None
    tx.occurrence_id = None
    tx.voided, tx.version = True, tx.version + 1
    audit(db, organization_id, "transaction.voided", tx.id)
    return tx


def edit_transaction(db, organization_id: str, tx_id: str, data: TransactionEdit):
    lock_organization(db, organization_id)
    tx = owned(db, m.Transaction, tx_id, organization_id, True)
    if tx.version != data.version or tx.voided:
        fail("Операция уже изменилась. Обновите страницу", 409)
    if tx.debt_id or tx.receipt_id or tx.occurrence_id or tx.kind == "refund":
        fail("Связанную операцию можно отменить и внести заново из её карточки")
    if db.scalar(
        select(func.count())
        .select_from(m.Transaction)
        .where(m.Transaction.refund_of == tx.id, ~m.Transaction.voided)
    ):
        fail("Нельзя менять покупку со связанными возвратами")
    payload = TransactionInput(**data.model_dump(exclude={"version"}))
    if db.scalar(
        select(m.Transaction.id).where(
            m.Transaction.organization_id == organization_id,
            m.Transaction.idempotency_key == payload.idempotency_key,
        )
    ):
        fail("Используйте новый ключ для изменения операции", 409)
    replacement = create_transaction(db, organization_id, payload, record_audit=False)
    for model in [m.Posting, m.Allocation]:
        db.execute(delete(model).where(model.transaction_id == tx.id))
        db.execute(
            update(model).where(model.transaction_id == replacement.id).values(transaction_id=tx.id)
        )
    for key in [
        "kind",
        "amount_minor",
        "currency",
        "fx_rate",
        "base_minor",
        "occurred_on",
        "account_id",
        "target_account_id",
        "target_minor",
        "category_id",
        "merchant",
        "note",
        "refund_of",
    ]:
        setattr(tx, key, getattr(replacement, key))
    tx.version += 1
    db.delete(replacement)
    audit(db, organization_id, "transaction.edited", tx.id)
    db.flush()
    return tx


def save_bill(db, organization_id: str, data, bill_id: str | None = None, kind: str = "expense"):
    lock_organization(db, organization_id)
    if not 1990 <= data.start_date.year <= 2100:
        fail("Дата платежа должна быть между 1990 и 2100 годом")
    if data.category_id:
        owned(db, m.Category, data.category_id, organization_id)
    if (
        data.account_id
        and owned(db, m.Account, data.account_id, organization_id).currency != data.currency
    ):
        fail("Валюты счёта и платежа должны совпадать")
    bill = (
        owned(db, m.Bill, bill_id, organization_id)
        if bill_id
        else m.Bill(organization_id=organization_id, kind=kind)
    )
    if bill.kind != kind:
        fail("Источник не найден", 404)
    for key in ["name", "currency", "category_id", "account_id", "start_date", "recurrence"]:
        setattr(bill, key, getattr(data, key))
    bill.amount_minor, bill.fx_rate = minor(data.amount), rate_for(data.currency, data.fx_rate)
    db.add(bill)
    db.flush()
    return bill
