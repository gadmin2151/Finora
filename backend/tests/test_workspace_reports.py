import asyncio
import json
from datetime import date
from uuid import uuid4

import pytest
from sqlalchemy import func, select
from test_assistant_reports import add_tx, fetch_report, queued_job
from test_receipt_authors import manual

from app import ai, assistant
from app import models as m
from app.db import SessionLocal
from app.finance import create_transaction
from app.schemas import TransactionInput


def test_balance_and_debt_snapshots_are_exact_scoped_and_bounded(client, accounts):
    org = client.headers["X-Organization-ID"]
    with SessionLocal() as db:
        db.get(m.Account, accounts[0]["id"]).opening_minor = 100000
        debt = m.Debt(
            organization_id=org,
            person="Alex",
            direction="lent",
            currency="MDL",
            initial_minor=10000,
            creation_key=str(uuid4()),
            request_hash="test",
        )
        foreign = m.Organization(name="Foreign")
        db.add_all([debt, foreign])
        db.flush()
        db.add(m.Account(organization_id=foreign.id, name="Private", opening_minor=99999999))
        db.add(
            m.Debt(
                organization_id=foreign.id,
                person="Private debt",
                direction="lent",
                currency="MDL",
                initial_minor=99999999,
                creation_key=str(uuid4()),
                request_hash="test",
            )
        )
        create_transaction(
            db,
            org,
            TransactionInput(
                account_id=accounts[0]["id"],
                amount="25",
                occurred_on="2025-02-15",
                idempotency_key=str(uuid4()),
            ),
            internal_kind="debt_repayment_in",
            debt_id=debt.id,
        )
        db.commit()
    add_tx(client, accounts, amount="50")
    report = fetch_report(client, "accounts")
    assert report["as_of"] is not None
    assert report["metrics"][1]["value"] == "975.00 MDL"
    assert "Private" not in json.dumps(report)
    debts = fetch_report(client, "debts")
    assert debts["metrics"][1]["value"] == "75.00 MDL"
    assert debts["rows"][0]["label"] == "Alex"
    assert "request_hash" not in json.dumps(debts)
    assert fetch_report(client, "debts", search="%' OR 1=1 --")["total_rows"] == 0


def test_user_comparison_refunds_and_category_shares(client, accounts, categories, owner):
    first = manual(client, accounts[0], categories[0], "100")
    second = manual(client, accounts[0], categories[1], "25")
    with SessionLocal() as db:
        other = m.User(username=uuid4().hex, name="Maria", password_hash="unused-test-hash")
        db.add(other)
        db.flush()
        db.get(m.Receipt, second["id"]).created_by = other.id
        allocation = db.scalar(
            select(m.Allocation).where(m.Allocation.transaction_id == first["transaction_id"])
        )
        allocation.amount_minor = allocation.base_minor = 7000
        db.add(
            m.Allocation(
                transaction_id=first["transaction_id"],
                category_id=categories[1]["id"],
                amount_minor=3000,
                base_minor=3000,
            )
        )
        db.commit()
    add_tx(client, accounts, kind="refund", amount="10", refund_of=first["transaction_id"])
    add_tx(client, accounts, amount="999")  # Manual transactions have no receipt author.
    report = fetch_report(client, "users")
    assert report["metrics"][0]["value"] == "115.00 MDL"
    assert [r["value"] for r in report["rows"]] == ["90.00 MDL", "25.00 MDL"]
    assert [
        row["value"]
        for row in fetch_report(client, "users", category_id=categories[1]["id"])["rows"]
    ] == ["27.00 MDL", "25.00 MDL"]
    assert (
        fetch_report(client, "users", created_by=owner["id"])["metrics"][0]["value"] == "90.00 MDL"
    )
    assert (
        fetch_report(client, "categories", created_by=owner["id"])["rows"][0]["value"]
        == "63.00 MDL"
    )
    assert fetch_report(client, "users", created_by=str(uuid4()))["total_rows"] == 0


def test_plans_and_budgets_do_not_write_occurrences_or_omit_unspent_limits(client, categories):
    org = client.headers["X-Organization-ID"]
    with SessionLocal() as db:
        db.add_all(
            [
                m.Bill(
                    organization_id=org,
                    name="Rent",
                    kind="expense",
                    amount_minor=10000,
                    currency="MDL",
                    start_date=date(2025, 1, 31),
                    recurrence="monthly",
                ),
                m.Bill(
                    organization_id=org,
                    name="Salary",
                    kind="income",
                    amount_minor=100000,
                    currency="MDL",
                    start_date=date(2025, 2, 10),
                    recurrence="monthly",
                ),
                m.Budget(
                    organization_id=org,
                    month="2025-02",
                    category_id=categories[0]["id"],
                    amount_minor=30000,
                ),
            ]
        )
        db.commit()
    assert fetch_report(client, "bills")["metrics"][1]["value"] == "100.00 MDL"
    assert fetch_report(client, "income_plans")["metrics"][1]["value"] == "1000.00 MDL"
    budget = fetch_report(client, "budgets")
    assert budget["metrics"][0]["value"] == "300.00 MDL"
    assert budget["rows"][0]["value"] == "0.00 MDL"
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Occurrence)) == 0


def test_transactions_support_all_history_and_receipt_comments_are_scoped(
    client, accounts, categories, owner
):
    receipt = manual(client, accounts[0], categories[0])
    with SessionLocal() as db:
        db.add(
            m.ReceiptComment(
                organization_id=client.headers["X-Organization-ID"],
                receipt_id=receipt["id"],
                author_id=owner["id"],
                text="Bought for the family",
            )
        )
        db.commit()
    add_tx(client, accounts, kind="income", amount="100", occurred_on="2001-01-01")
    tx = fetch_report(client, "transactions", date_from="1990-01-01", transaction_kind="income")
    assert tx["total_rows"] == 1 and tx["metrics"][1]["value"] == "100.00 MDL"
    receipts = fetch_report(client, "receipts", created_by=owner["id"])
    assert "Bought for the family" in receipts["rows"][0]["detail"]
    assert "source_url" not in json.dumps(receipts) and "password" not in json.dumps(receipts)
    for fields in [
        {"kind": "accounts", "created_by": owner["id"]},
        {"kind": "summary", "transaction_kind": "income"},
        {"kind": "budgets", "currency": "USD"},
    ]:
        response = client.get(
            "/api/reports", params={"date_from": "2025-02-01", "date_to": "2025-02-28", **fields}
        )
        assert response.status_code == 422


def test_assistant_receives_page_requester_authors_and_safe_navigation(client, owner, monkeypatch):
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "openai"
        db.commit()
    seen = []

    async def generate(org, purpose, system, text, **kwargs):
        context = json.loads(text)
        seen.append(context)
        assert context["current_page"] == "budgets"
        assert context["requester"]["id"] == owner["id"]
        assert "authors" in context and "history_period" in context
        assert "password" not in text and "openai_key" not in text
        return {
            "queries": [],
            "clarification": "Открываю чеки",
            "navigate_to": "receipts",
        }, "openai"

    monkeypatch.setattr(ai, "generate", generate)
    asyncio.run(assistant.answer(queued_job(client, page="budgets", text="Перейди в чеки")))
    answer = client.get("/api/chat").json()[-1]
    assert answer["details"]["navigate_to"] == "receipts"
    assert len(seen) == 1


def test_navigation_does_not_expand_member_permissions(client, owner, monkeypatch):
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "openai"
        db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"])).role = "user"
        db.commit()

    async def generate(*args, **kwargs):
        return {"queries": [], "clarification": "", "navigate_to": "debts"}, "openai"

    monkeypatch.setattr(ai, "generate", generate)
    with pytest.raises(ai.AIError, match="недоступен"):
        asyncio.run(assistant.answer(queued_job(client)))


def test_navigation_requires_current_command_not_model_or_history_instruction(client, monkeypatch):
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "openai"
        db.commit()

    async def generate(*args, **kwargs):
        return {"queries": [], "clarification": "", "navigate_to": "receipts"}, "openai"

    monkeypatch.setattr(ai, "generate", generate)
    with pytest.raises(ai.AIError, match="попросите явно"):
        asyncio.run(assistant.answer(queued_job(client, text="Кто потратил больше?")))
    for text in ["Открой чеки", "open receipts", "perekini menya v cheki", "deschide bonuri"]:
        assert assistant.navigation_requested(text)
    for text in ["Сколько расходов?", "Compare users", "Покажи статистику"]:
        assert not assistant.navigation_requested(text)
