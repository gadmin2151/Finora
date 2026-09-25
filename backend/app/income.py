"""Income plans use the same schedule and posting ledger as expense plans."""

import hashlib

from sqlalchemy import delete, func, select

from . import models as m
from .finance import (
    audit,
    bills_for_month,
    create_transaction,
    fail,
    lock_organization,
    minor,
    month_range,
    owned,
    rate_for,
)
from .ledger import save_bill
from .schemas import BillPayment, IncomePlanInput, TransactionInput


def plan_dict(row: m.Bill):
    return {
        **{
            key: getattr(row, key)
            for key in [
                "id",
                "name",
                "amount_minor",
                "currency",
                "account_id",
                "recurrence",
                "active",
                "version",
            ]
        },
        "start_date": row.start_date.isoformat(),
        "fx_rate": str(row.fx_rate),
    }


def owned_plan(db, organization_id, key):
    row = owned(db, m.Bill, key, organization_id)
    if row.kind != "income":
        fail("Источник дохода не найден", 404)
    return row


def save_plan(db, organization_id: str, data: IncomePlanInput, key: str | None = None):
    lock_organization(db, organization_id)
    rate = rate_for(data.currency, data.fx_rate)
    fingerprint = hashlib.sha256(data.model_dump_json(exclude={"version"}).encode()).hexdigest()
    if key:
        row = owned_plan(db, organization_id, key)
        if row.version != data.version:
            fail("Источник изменился. Обновите страницу", 409)
        paid = select(m.Transaction.occurrence_id).where(
            m.Transaction.organization_id == organization_id,
            ~m.Transaction.voided,
            m.Transaction.occurrence_id.is_not(None),
        )
        schedule_changed = row.start_date != data.start_date or row.recurrence != data.recurrence
        if schedule_changed:
            if db.scalar(
                select(m.Occurrence.id)
                .where(m.Occurrence.bill_id == key, m.Occurrence.id.in_(paid))
                .limit(1)
            ):
                fail(
                    "По этому расписанию уже получен доход. Для новой периодичности остановите источник и создайте новый"
                )
            db.execute(delete(m.Occurrence).where(m.Occurrence.bill_id == key))
        else:
            for occurrence in db.scalars(
                select(m.Occurrence).where(
                    m.Occurrence.bill_id == key, m.Occurrence.id.not_in(paid)
                )
            ):
                occurrence.amount_minor = minor(data.amount)
                occurrence.currency = data.currency
                occurrence.fx_rate = rate
        row.version += 1
    else:
        old = db.scalar(
            select(m.Bill).where(
                m.Bill.organization_id == organization_id,
                m.Bill.creation_key == data.idempotency_key,
            )
        )
        if old:
            if old.request_hash != fingerprint:
                fail("Ключ запроса уже использован для другого источника", 409)
            return old
        if (
            db.scalar(
                select(func.count())
                .select_from(m.Bill)
                .where(m.Bill.organization_id == organization_id, m.Bill.kind == "income")
            )
            >= 200
        ):
            fail("Достигнут лимит 200 источников дохода")
    row = save_bill(db, organization_id, data, key, kind="income")
    if not key:
        row.creation_key, row.request_hash = data.idempotency_key, fingerprint
    audit(db, organization_id, "income.updated" if key else "income.created", row.id)
    return row


def report(db, organization_id: str, month: str):
    start, end = month_range(month)
    rows = bills_for_month(db, organization_id, month, "income")
    received = {
        tx.id: tx
        for tx in db.scalars(
            select(m.Transaction).where(
                m.Transaction.organization_id == organization_id,
                m.Transaction.id.in_(
                    [row["transaction_id"] for row in rows if row["transaction_id"]]
                ),
                ~m.Transaction.voided,
            )
        )
    }
    for row in rows:
        if row["status"] == "paid":
            row["status"] = "received"
            row["received_minor"] = received[row["transaction_id"]].amount_minor
            row["received_on"] = received[row["transaction_id"]].occurred_on.isoformat()
        elif not row["active"]:
            row["status"] = "paused"
    regular = (
        select(m.Occurrence.id)
        .join(m.Bill)
        .where(
            m.Bill.organization_id == organization_id,
            m.Bill.kind == "income",
            m.Bill.recurrence != "once",
        )
    )
    totals = dict(
        db.execute(
            select(m.Transaction.occurrence_id.in_(regular), func.sum(m.Transaction.base_minor))
            .where(
                m.Transaction.organization_id == organization_id,
                m.Transaction.kind == "income",
                ~m.Transaction.voided,
                m.Transaction.occurred_on.between(start, end),
            )
            .group_by(m.Transaction.occurrence_id.in_(regular))
        ).all()
    )
    regular_minor = int(totals.get(True, 0))
    occasional_minor = int(totals.get(False, 0) or 0) + int(totals.get(None, 0) or 0)
    expected = sum(row["base_minor"] for row in rows if row["status"] in {"overdue", "upcoming"})
    return {
        "received_minor": regular_minor + occasional_minor,
        "regular_minor": regular_minor,
        "occasional_minor": occasional_minor,
        "expected_minor": expected,
        "occurrences": rows,
    }


def receive(db, organization_id: str, key: str, data: BillPayment):
    lock_organization(db, organization_id)
    occurrence = owned(db, m.Occurrence, key, organization_id, True)
    plan = owned_plan(db, organization_id, occurrence.bill_id)
    existing = db.scalar(
        select(m.Transaction).where(m.Transaction.occurrence_id == key, ~m.Transaction.voided)
    )
    if existing:
        return existing
    account = owned(db, m.Account, data.account_id, organization_id)
    if account.currency != occurrence.currency or account.archived:
        fail("Выберите действующий счёт в валюте дохода")
    if data.transaction_id:
        tx = owned(db, m.Transaction, data.transaction_id, organization_id, True)
        if (
            tx.kind != "income"
            or tx.voided
            or tx.occurrence_id
            or tx.account_id != account.id
            or tx.amount_minor != minor(data.amount)
            or tx.occurred_on != data.occurred_on
        ):
            fail("Выберите доход с тем же счётом, датой и суммой, ещё не связанный с планом")
        tx.occurrence_id = key
        tx.version += 1
    else:
        tx = create_transaction(
            db,
            organization_id,
            TransactionInput(
                kind="income",
                amount=data.amount,
                account_id=account.id,
                occurred_on=data.occurred_on,
                fx_rate=data.fx_rate,
                merchant=plan.name,
                idempotency_key=data.idempotency_key,
            ),
            occurrence_id=key,
        )
    occurrence.skipped = False
    audit(db, organization_id, "income.received", key)
    return tx
