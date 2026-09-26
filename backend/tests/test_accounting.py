from uuid import uuid4

import pytest
from sqlalchemy import func, select
from test_manual_receipts import entry
from test_wallet_balance import adjustment, balance, post_tx, url

from app import models as m
from app.db import SessionLocal


def configure(client, account, mode="combined", move=False, version=None):
    current = client.get("/api/settings/accounting").json()
    return client.put(
        "/api/settings/accounting",
        json={
            "mode": mode,
            "default_account_id": account,
            "move_existing_receipts": move,
            "version": version if version is not None else current["version"],
        },
    )


def test_separate_default_and_combined_routes_legacy_clients_without_duplicates(client, accounts):
    cash, card = [a["id"] for a in accounts]
    assert client.get("/api/settings/accounting").json()["mode"] == "separate"
    assert post_tx(client, cash)["account_id"] == cash
    assert configure(client, card).status_code == 200
    body = entry(accounts[0])
    first = client.post("/api/receipts/manual", json=body)
    assert first.status_code == 200, first.text
    receipt = first.json()["receipt"]
    assert receipt["account_id"] == card
    assert client.post("/api/receipts/manual", json=body).json()["duplicate"]
    assert post_tx(client, cash)["account_id"] == card
    assert balance(client, cash) == 1000  # Earlier non-receipt history is preserved.
    assert balance(client, card) == -250
    assert [a["id"] for a in client.get("/api/accounts?for_payment=true").json()] == [card]
    assert len(client.get("/api/accounts").json()) == 2
    assert configure(client, card, "separate").status_code == 200
    assert post_tx(client, cash)["account_id"] == cash


def test_receipt_move_preserves_amounts_items_originals_reports_and_total_balance(
    client, accounts, owner
):
    cash, card = [a["id"] for a in accounts]
    receipt = client.post("/api/receipts/manual", json=entry(accounts[0])).json()["receipt"]
    receipt_id = receipt["id"]
    with SessionLocal() as db:
        row = db.get(m.Receipt, receipt_id)
        row.file_names = ["original.jpg", "page.png"]
        row.original = {"original_total": "12.50", "merchant_address": "Test street 10"}
        db.commit()
    report = client.get("/api/dashboard?month=" + receipt["purchased_on"][:7]).json()
    result = configure(client, card, move=True)
    assert result.status_code == 200, result.text
    assert result.json()["receipts_moved"] == 1
    assert balance(client, cash) == 0 and balance(client, card) == -1250
    after = client.get("/api/dashboard?month=" + receipt["purchased_on"][:7]).json()
    for key in ("income_minor", "expense_minor", "net_minor", "balances"):
        assert report[key] == after[key]
    with SessionLocal() as db:
        row = db.get(m.Receipt, receipt_id)
        tx = db.scalar(select(m.Transaction).where(m.Transaction.receipt_id == receipt_id))
        posting = db.scalar(select(m.Posting).where(m.Posting.transaction_id == tx.id))
        assert row.account_id == tx.account_id == posting.account_id == card
        assert tx.version == 2 and row.version == receipt["version"] + 1
        assert tx.amount_minor == row.total_minor == 1250
        assert posting.amount_minor == -1250
        assert row.file_names == ["original.jpg", "page.png"]
        assert row.original["original_total"] == "12.50"
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 1
        assert db.scalar(select(m.ReceiptItem.total_minor)) == 1250
        change = db.scalar(select(m.Audit).where(m.Audit.action == "receipt.account_moved"))
        assert change.actor_id == owner["id"] and change.details["from_account_id"] == cash
    assert configure(client, card, move=True).json()["receipts_moved"] == 0
    assert configure(client, cash, mode="separate").status_code == 200
    assert balance(client, card) == -1250


def test_accounting_checks_membership_foreign_accounts_versions_and_protects_primary(
    client, accounts, owner
):
    cash, card = [a["id"] for a in accounts]
    with SessionLocal() as db:
        other = m.Organization(name="Other")
        db.add(other)
        db.flush()
        foreign = m.Account(organization_id=other.id, name="Not yours")
        db.add(foreign)
        db.commit()
        foreign_id = foreign.id
    assert configure(client, foreign_id).status_code == 404
    assert configure(client, None).status_code == 422
    assert configure(client, card).status_code == 200
    assert configure(client, cash, version=1).status_code == 409
    assert client.post(f"/api/accounts/{card}/archive").status_code == 422
    assert (
        client.put(f"/api/accounts/{card}", json={"name": "Primary", "currency": "USD"}).status_code
        == 422
    )
    assert client.get("/api/settings/accounting").json()["default_account_id"] == card
    with SessionLocal() as db:
        db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"])).role = "user"
        db.commit()
    assert client.get("/api/settings/accounting").status_code == 200
    assert configure(client, cash).status_code == 403


def test_combined_keeps_foreign_currency_separate_and_blocks_internal_transfer(client, accounts):
    cash, card = [a["id"] for a in accounts]
    usd = client.post("/api/accounts", json={"name": "USD", "currency": "USD"}).json()["id"]
    assert configure(client, usd).status_code == 422
    assert configure(client, card).status_code == 200
    assert post_tx(client, usd, fx_rate="18")["currency"] == "USD"
    assert balance(client, usd) == 1000
    response = client.post(
        "/api/transactions",
        json={
            "kind": "transfer",
            "amount": "5",
            "account_id": cash,
            "target_account_id": card,
            "occurred_on": "2025-02-15",
            "idempotency_key": str(uuid4()),
        },
    )
    assert response.status_code == 422
    result = post_tx(
        client,
        usd,
        kind="transfer",
        target_account_id=cash,
        target_amount="90",
        amount="5",
        fx_rate="18",
    )
    assert result["target_account_id"] == card
    assert balance(client, card) == 9000


def test_combined_balance_correction_still_targets_explicit_account(client, accounts):
    cash, card = [a["id"] for a in accounts]
    assert configure(client, card).status_code == 200
    result = client.post(url(cash), json=adjustment("100", 0, effect="income_expense"))
    assert result.status_code == 200, result.text
    assert balance(client, cash) == 10000 and balance(client, card) == 0


def test_combined_debt_repayment_routes_to_primary(client, accounts):
    cash, card = [a["id"] for a in accounts]
    assert configure(client, card).status_code == 200
    debt = client.post(
        "/api/debts",
        json={
            "person": "Friend",
            "direction": "lent",
            "amount": "50",
            "currency": "MDL",
            "mode": "existing",
            "occurred_on": "2025-02-15",
            "idempotency_key": str(uuid4()),
        },
    ).json()
    result = client.post(
        f"/api/debts/{debt['id']}/repay",
        json={
            "account_id": cash,
            "amount": "20",
            "occurred_on": "2025-02-15",
            "idempotency_key": str(uuid4()),
        },
    )
    assert result.status_code == 200, result.text
    assert balance(client, card) == 2000 and balance(client, cash) == 0


def test_invalid_receipt_posting_rolls_back_entire_setting_and_move(client, accounts):
    receipt = client.post("/api/receipts/manual", json=entry(accounts[0])).json()["receipt"]
    with SessionLocal() as db:
        db.scalar(select(m.Posting)).amount_minor = -100
        db.commit()
    assert configure(client, accounts[1]["id"], move=True).status_code == 409
    assert client.get("/api/settings/accounting").json()["mode"] == "separate"
    assert client.get(f"/api/receipts/{receipt['id']}").json()["account_id"] == accounts[0]["id"]


@pytest.mark.parametrize("mode", ["invalid", "", "ALL"])
def test_reject_invalid_mode(client, accounts, mode):
    assert configure(client, accounts[0]["id"], mode=mode).status_code == 422
