"""An actual account merge and current-balance reconciliation preserve the ledger."""

from concurrent.futures import ThreadPoolExecutor
from datetime import date

import pytest
from sqlalchemy import select
from test_accounting import configure
from test_wallet_balance import adjustment, balance, post_tx

from app import models as m
from app.db import SessionLocal, engine

URL = "/api/wallet/balance-adjustment"


def wallet_adjustment(client, target="100", expected=0, **extra):
    return adjustment(
        target,
        expected,
        expected_accounting_version=client.get("/api/settings/accounting").json()["version"],
        **extra,
    )


def test_merge_moves_every_account_reference_and_preserves_balances_and_reports(
    client, accounts, owner
):
    cash, card = [a["id"] for a in accounts]
    savings = client.post(
        "/api/accounts", json={"name": "Old savings", "opening_balance": "30"}
    ).json()["id"]
    euros = client.post(
        "/api/accounts", json={"name": "EUR", "currency": "EUR", "opening_balance": "20"}
    ).json()["id"]
    client.put(f"/api/accounts/{cash}", json={"name": "Cash", "opening_balance": "100"})
    client.put(f"/api/accounts/{card}", json={"name": "Card", "opening_balance": "200"})
    client.post(f"/api/accounts/{savings}/archive")
    post_tx(client, cash, amount="15")
    transfer = post_tx(client, cash, kind="transfer", target_account_id=card, amount="10")
    foreign_transfer = post_tx(
        client,
        euros,
        kind="transfer",
        target_account_id=cash,
        amount="2",
        target_amount="38",
        fx_rate="19",
    )
    voided = post_tx(client, cash, kind="expense", amount="3")
    client.delete(f"/api/transactions/{voided['id']}?version=1")
    with SessionLocal() as db:
        # Pending/trash originals and recurring plans must remain restorable and usable.
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key="pending",
            account_id=cash,
            deleted_at=m.now(),
            status="failed",
            file_names=["original.jpg"],
            original={"raw": "unchanged"},
        )
        bill = m.Bill(
            organization_id=owner["id"],
            name="Plan",
            kind="income",
            amount_minor=1000,
            currency="MDL",
            account_id=cash,
            start_date=date(2025, 2, 1),
            recurrence="monthly",
        )
        db.add_all([receipt, bill])
        db.commit()
        receipt_id, bill_id = receipt.id, bill.id
    before = client.get("/api/dashboard?month=2025-02").json()
    result = configure(client, card)
    assert result.status_code == 200, result.text
    assert result.json()["accounts_merged"] == 2
    remaining = client.get("/api/accounts").json()
    assert {a["id"] for a in remaining} == {card, euros}
    assert next(a for a in remaining if a["id"] == card)["opening_minor"] == 33000
    assert balance(client, card) == 38300
    assert balance(client, euros) == 1800
    after = client.get("/api/dashboard?month=2025-02").json()
    for key in ("income_minor", "expense_minor", "net_minor", "balances"):
        assert before[key] == after[key]
    with SessionLocal() as db:
        assert db.get(m.Account, cash) is None and db.get(m.Account, savings) is None
        assert db.get(m.Bill, bill_id).account_id == card
        receipt = db.get(m.Receipt, receipt_id)
        assert receipt.account_id == card and receipt.deleted_at
        assert receipt.file_names == ["original.jpg"] and receipt.original == {"raw": "unchanged"}
        old_transfer = db.get(m.Transaction, transfer["id"])
        assert old_transfer.account_id == old_transfer.target_account_id == card
        assert db.get(m.Transaction, foreign_transfer["id"]).target_account_id == card
        assert db.get(m.Transaction, voided["id"]).voided
        for posting in db.scalars(select(m.Posting)):
            assert posting.account_id in {card, euros}
        audits = list(db.scalars(select(m.Audit).where(m.Audit.action == "account.merged")))
        assert len(audits) == 2 and all(a.actor_id == owner["id"] for a in audits)
    assert client.post("/api/accounts", json={"name": "Another"}).status_code == 422
    assert configure(client, card).json()["accounts_merged"] == 0
    assert balance(client, card) == 38300
    # Cached source IDs remain safe for the existing mobile client, but unknown IDs do not.
    assert post_tx(client, cash)["account_id"] == card
    assert configure(client, card, mode="separate").status_code == 200
    assert client.post("/api/accounts", json={"name": "New cash"}).status_code == 200


def test_actual_balance_is_exact_preserves_reports_and_future_movements(client, accounts, owner):
    cash, card = [a["id"] for a in accounts]
    post_tx(client, cash, amount="100")
    assert configure(client, card).status_code == 200
    body = wallet_adjustment(client, "25.29", 10000)
    response = client.post(URL, json=body)
    assert response.status_code == 200, response.text
    assert response.json()["balance_minor"] == 2529
    assert response.json()["adjustment_minor"] == -7471
    assert response.json()["transaction"]["kind"] == "adjustment_out"
    tx_id = response.json()["transaction"]["id"]
    post_tx(client, card, kind="expense", amount="5.01")
    post_tx(client, card, amount="10")
    assert balance(client, card) == 3028
    retry = client.post(URL, json=body)
    assert retry.status_code == 200 and retry.json()["transaction"]["id"] == tx_id
    assert retry.json()["balance_minor"] == 3028
    assert client.post(URL, json={**body, "target_balance": "26"}).status_code == 409
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["income_minor"] == 11000 and report["expense_minor"] == 501
    with SessionLocal() as db:
        audit = db.scalar(select(m.Audit).where(m.Audit.action == "wallet.balance_adjusted"))
        assert audit.actor_id == owner["id"] and audit.details["target_balance_minor"] == 2529
    assert client.delete(f"/api/transactions/{tx_id}?version=1").status_code == 200
    assert balance(client, card) == 10499
    assert client.post(URL, json=body).status_code == 409


def test_wallet_rejects_stale_balance_settings_and_unauthorized_requests(client, accounts, owner):
    card = accounts[1]["id"]
    assert client.post(URL, json=wallet_adjustment(client)).status_code == 409
    assert configure(client, card).status_code == 200
    body = wallet_adjustment(client)
    post_tx(client, card)
    assert client.post(URL, json=body).status_code == 409
    body = wallet_adjustment(client, expected=1000)
    assert configure(client, card).status_code == 200
    assert client.post(URL, json=body).status_code == 409
    body = wallet_adjustment(client, expected=1000)
    assert client.post(URL, json=body, headers={"X-CSRF-Token": "invalid"}).status_code == 403
    other = client.post("/api/organizations", json={"name": "Other"}).json()["id"]
    assert client.post(URL, json=body, headers={"X-Organization-ID": other}).status_code == 409
    with SessionLocal() as db:
        member = db.scalar(
            select(m.Membership).where(
                m.Membership.user_id == owner["id"], m.Membership.organization_id == owner["id"]
            )
        )
        member.role = "user"
        db.commit()
    assert client.post(URL, json=body).status_code == 403
    assert balance(client, card) == 1000


@pytest.mark.parametrize("target", ["0", "-10.29"])
def test_zero_and_negative_actual_balance(client, accounts, target):
    card = accounts[1]["id"]
    post_tx(client, card)
    assert configure(client, card).status_code == 200
    response = client.post(URL, json=wallet_adjustment(client, target, 1000))
    assert response.status_code == 200, response.text
    assert response.json()["balance_minor"] == (0 if target == "0" else -1029)


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Row locks require PostgreSQL")
def test_wallet_reconciliation_serializes_with_concurrent_income(client, accounts):
    card = accounts[1]["id"]
    assert configure(client, card).status_code == 200
    body = wallet_adjustment(client)
    with ThreadPoolExecutor(max_workers=2) as pool:
        adjustment_request = pool.submit(client.post, URL, json=body)
        income_request = pool.submit(post_tx, client, card)
        response = adjustment_request.result()
        assert income_request.result()["amount_minor"] == 1000
    assert response.status_code in {200, 409}
    assert balance(client, card) == (11000 if response.status_code == 200 else 1000)
