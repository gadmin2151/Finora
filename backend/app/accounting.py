"""Wallet preferences and audited receipt reassignment; amounts and originals stay intact."""

from collections import defaultdict

from sqlalchemy import select

from . import models as m
from .finance import audit, fail, lock_organization, owned
from .schemas import AccountingInput


def accounting_settings(db, organization_id: str):
    prefs = db.scalar(select(m.Preferences).where(m.Preferences.organization_id == organization_id))
    return {
        "mode": prefs.accounting_mode,
        "default_account_id": prefs.default_account_id,
        "version": prefs.accounting_version,
    }


def save_accounting(db, organization_id: str, data: AccountingInput):
    lock_organization(db, organization_id)
    prefs = db.scalar(
        select(m.Preferences)
        .where(m.Preferences.organization_id == organization_id)
        .execution_options(populate_existing=True)
    )
    if prefs.accounting_version != data.version:
        fail("Настройки учёта изменились. Обновите данные и повторите", 409)
    account = None
    if data.default_account_id:
        account = owned(db, m.Account, data.default_account_id, organization_id)
        if account.archived:
            fail("Выберите действующий счёт")
    if data.mode == "combined" and (account is None or account.currency != "MDL"):
        fail("Выберите основной счёт MDL в настройках учёта")
    if data.move_existing_receipts and data.mode != "combined":
        fail("Перенос чеков доступен только в общем режиме")
    before = accounting_settings(db, organization_id)
    moved = 0
    if data.move_existing_receipts:
        # Lock the scope before receipts, matching the confirmation/deletion lock order.
        receipt_query = select(m.Receipt).where(
            m.Receipt.organization_id == organization_id,
            m.Receipt.deleted_at.is_(None),
            m.Receipt.currency == "MDL",
            (m.Receipt.account_id.is_(None)) | (m.Receipt.account_id != account.id),
        )
        receipts = list(db.scalars(receipt_query.order_by(m.Receipt.id).with_for_update()))
        transaction_query = select(m.Transaction).where(
            m.Transaction.organization_id == organization_id,
            m.Transaction.receipt_id.in_(receipt_query.with_only_columns(m.Receipt.id)),
            ~m.Transaction.voided,
        )
        transactions = {tx.receipt_id: tx for tx in db.scalars(transaction_query.with_for_update())}
        postings_by_transaction = defaultdict(list)
        for posting in db.scalars(
            select(m.Posting)
            .where(
                m.Posting.transaction_id.in_(transaction_query.with_only_columns(m.Transaction.id))
            )
            .with_for_update()
        ):
            postings_by_transaction[posting.transaction_id].append(posting)
        for receipt in receipts:
            previous = receipt.account_id
            tx = transactions.get(receipt.id)
            if receipt.status == "posted" and tx is None:
                fail("Не удалось сверить проводку чека. Перенос отменён", 409)
            transaction_before = None
            if tx:
                postings = postings_by_transaction[tx.id]
                if (
                    tx.kind != "expense"
                    or tx.currency != "MDL"
                    or tx.amount_minor != receipt.total_minor
                    or len(postings) != 1
                    or postings[0].account_id != tx.account_id
                    or postings[0].amount_minor != -tx.amount_minor
                ):
                    fail("Не удалось сверить проводку чека. Перенос отменён", 409)
                transaction_before = tx.account_id
                tx.account_id = account.id
                tx.version += 1
                postings[0].account_id = account.id
            receipt.account_id = account.id
            receipt.version += 1
            audit(
                db,
                organization_id,
                "receipt.account_moved",
                receipt.id,
                {
                    "from_account_id": previous,
                    "to_account_id": account.id,
                    "transaction_id": tx.id if tx else None,
                    "transaction_from_account_id": transaction_before,
                },
            )
            moved += 1
    prefs.accounting_mode = data.mode
    prefs.default_account_id = data.default_account_id
    prefs.accounting_version += 1
    audit(
        db,
        organization_id,
        "accounting.updated",
        prefs.id,
        {
            "before": before,
            "after": accounting_settings(db, organization_id),
            "receipts_moved": moved,
        },
    )
    db.flush()
    return {**accounting_settings(db, organization_id), "receipts_moved": moved}
