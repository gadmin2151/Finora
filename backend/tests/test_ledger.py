from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest

from app.db import engine


def post_tx(client, account, **extra):
    body = {
        "amount": "100.00",
        "account_id": account,
        "occurred_on": "2025-02-15",
        "idempotency_key": str(uuid4()),
        **extra,
    }
    result = client.post("/api/transactions", json=body)
    assert result.status_code == 200, result.text
    return result.json()


def test_income_expense_transfer_and_refund(client, accounts, categories):
    a, b = accounts
    post_tx(client, a["id"], kind="income", amount="1000")
    expense = post_tx(client, a["id"], category_id=categories[0]["id"], amount="120.25")
    post_tx(client, a["id"], kind="transfer", target_account_id=b["id"], amount="300")
    post_tx(client, a["id"], kind="refund", refund_of=expense["id"], amount="20.25")
    balances = {r["id"]: r["balance_minor"] for r in client.get("/api/accounts").json()}
    assert balances == {a["id"]: 60000, b["id"]: 30000}
    report = client.get("/api/dashboard?month=2025-02").json()
    assert (report["income_minor"], report["expense_minor"], report["net_minor"]) == (
        100000,
        10000,
        90000,
    )
    assert report["categories"][0]["spent_minor"] == 10000


def test_idempotency_and_conflicting_retry(client, accounts):
    payload = {
        "amount": "0.10",
        "account_id": accounts[0]["id"],
        "occurred_on": "2025-01-01",
        "idempotency_key": str(uuid4()),
    }
    a = client.post("/api/transactions", json=payload)
    b = client.post("/api/transactions", json=payload)
    assert a.status_code == b.status_code == 200
    assert a.json()["id"] == b.json()["id"]
    assert client.post("/api/transactions", json={**payload, "amount": "0.20"}).status_code == 409
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -10


def test_decimal_precision_and_fx_required(client, accounts):
    bad = {
        "amount": "0.001",
        "account_id": accounts[0]["id"],
        "occurred_on": "2025-01-01",
        "idempotency_key": str(uuid4()),
    }
    assert client.post("/api/transactions", json=bad).status_code == 422
    foreign = client.post(
        "/api/accounts", json={"name": "EUR account", "currency": "EUR", "opening_balance": "0.30"}
    ).json()
    assert (
        client.post(
            "/api/transactions", json={**bad, "amount": "0.1", "account_id": foreign["id"]}
        ).status_code
        == 422
    )
    tx = post_tx(client, foreign["id"], amount="0.10", fx_rate="19.12345678")
    assert tx["base_minor"] == 191
    assert tx["amount_minor"] == 10


def test_split_rounding_and_edit_preserve_balances(client, accounts, categories):
    tx = post_tx(
        client,
        accounts[0]["id"],
        amount="15.10",
        splits=[
            {"category_id": categories[0]["id"], "amount": "10.05"},
            {"category_id": categories[1]["id"], "amount": "5.05"},
        ],
    )
    result = client.put(
        f"/api/transactions/{tx['id']}",
        json={
            "kind": "expense",
            "amount": "20.00",
            "account_id": accounts[0]["id"],
            "occurred_on": "2025-02-15",
            "category_id": categories[1]["id"],
            "idempotency_key": str(uuid4()),
            "version": 1,
        },
    )
    assert result.status_code == 200, result.text
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -2000
    assert client.get("/api/transactions").json()["total"] == 1
    assert client.delete(f"/api/transactions/{tx['id']}?version=1").status_code == 409
    assert client.delete(f"/api/transactions/{tx['id']}?version=2").status_code == 200
    assert client.get("/api/accounts").json()[0]["balance_minor"] == 0


@pytest.mark.parametrize("direction", ["lent", "borrowed"])
def test_debts_partial_repayment_no_income_expense(client, accounts, direction):
    payload = {
        "person": "Тестовый человек",
        "direction": direction,
        "amount": "500",
        "currency": "MDL",
        "mode": "new",
        "account_id": accounts[0]["id"],
        "occurred_on": "2025-02-15",
        "idempotency_key": str(uuid4()),
    }
    r = client.post("/api/debts", json=payload)
    assert r.status_code == 200, r.text
    debt = r.json()
    assert client.post("/api/debts", json=payload).json()["id"] == debt["id"]
    payment = {
        "amount": "200",
        "account_id": accounts[0]["id"],
        "occurred_on": "2025-02-20",
        "idempotency_key": str(uuid4()),
    }
    a = client.post(f"/api/debts/{debt['id']}/repay", json=payment)
    assert a.status_code == 200, a.text
    assert (
        client.post(f"/api/debts/{debt['id']}/repay", json=payment).json()["id"] == a.json()["id"]
    )
    assert client.get("/api/debts").json()[0]["remaining_minor"] == 30000
    assert (
        client.post(
            f"/api/debts/{debt['id']}/repay",
            json={**payment, "amount": "301", "idempotency_key": str(uuid4())},
        ).status_code
        == 422
    )
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["income_minor"] == report["expense_minor"] == 0
    assert client.get("/api/accounts").json()[0]["balance_minor"] == (
        -30000 if direction == "lent" else 30000
    )


def test_existing_debt_does_not_move_cash(client, accounts):
    r = client.post(
        "/api/debts",
        json={
            "person": "Ранее",
            "direction": "lent",
            "amount": "50",
            "mode": "existing",
            "occurred_on": "2025-01-01",
            "idempotency_key": str(uuid4()),
        },
    )
    assert r.status_code == 200
    assert client.get("/api/accounts").json()[0]["balance_minor"] == 0
    assert client.get("/api/debts").json()[0]["remaining_minor"] == 5000


def test_month_end_recurrence_and_payment_idempotency(client, accounts, categories):
    r = client.post(
        "/api/bills",
        json={
            "name": "Internet",
            "amount": "100",
            "start_date": "2025-01-31",
            "recurrence": "monthly",
            "account_id": accounts[0]["id"],
            "category_id": categories[7]["id"],
        },
    )
    assert r.status_code == 200, r.text
    feb = client.get("/api/bills?month=2025-02").json()
    march = client.get("/api/bills?month=2025-03").json()
    assert feb[0]["due_date"] == "2025-02-28"
    assert march[0]["due_date"] == "2025-03-31"
    assert client.get("/api/transactions").json()["total"] == 0
    payment = {
        "account_id": accounts[0]["id"],
        "amount": "110",
        "occurred_on": "2025-02-28",
        "idempotency_key": str(uuid4()),
    }
    first = client.post(f"/api/occurrences/{feb[0]['id']}/pay", json=payment)
    assert first.status_code == 200, first.text
    assert (
        client.post(f"/api/occurrences/{feb[0]['id']}/pay", json=payment).json()["id"]
        == first.json()["id"]
    )
    assert client.get("/api/bills?month=2025-02").json()[0]["status"] == "paid"
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -11000


def test_cannot_overrefund_or_void_refunded_expense(client, accounts):
    purchase = post_tx(client, accounts[0]["id"], amount="10")
    refund = post_tx(client, accounts[0]["id"], kind="refund", amount="5", refund_of=purchase["id"])
    bad = {
        "kind": "refund",
        "amount": "6",
        "account_id": accounts[0]["id"],
        "occurred_on": "2025-02-15",
        "refund_of": purchase["id"],
        "idempotency_key": str(uuid4()),
    }
    assert client.post("/api/transactions", json=bad).status_code == 422
    assert client.delete(f"/api/transactions/{purchase['id']}?version=1").status_code == 422
    assert client.delete(f"/api/transactions/{refund['id']}?version=1").status_code == 200


def test_partial_split_refund_preserves_category_cents(client, accounts, categories):
    purchase = post_tx(
        client,
        accounts[0]["id"],
        amount="30.03",
        splits=[
            {"category_id": categories[0]["id"], "amount": "10.01"},
            {"category_id": categories[1]["id"], "amount": "20.02"},
        ],
    )
    post_tx(client, accounts[0]["id"], kind="refund", amount="3.00", refund_of=purchase["id"])
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["categories"][0]["spent_minor"] == 901
    assert report["categories"][1]["spent_minor"] == 1802
    assert sum(c["spent_minor"] for c in report["categories"]) == report["expense_minor"] == 2703


def test_bill_link_does_not_double_spend(client, accounts, categories):
    tx = post_tx(client, accounts[0]["id"], amount="50.00", category_id=categories[0]["id"])
    client.post(
        "/api/bills",
        json={
            "name": "Оплачено ранее",
            "amount": "50",
            "start_date": "2025-02-15",
            "recurrence": "once",
        },
    )
    occurrence = client.get("/api/bills?month=2025-02").json()[0]
    response = client.post(
        f"/api/occurrences/{occurrence['id']}/pay",
        json={
            "account_id": accounts[0]["id"],
            "amount": "50",
            "occurred_on": "2025-02-15",
            "transaction_id": tx["id"],
            "idempotency_key": str(uuid4()),
        },
    )
    assert response.status_code == 200
    assert client.get("/api/transactions").json()["total"] == 1
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -5000


def test_small_foreign_splits_never_create_negative_allocations(client, categories):
    account = client.post("/api/accounts", json={"name": "FX", "currency": "RON"}).json()
    post_tx(
        client,
        account["id"],
        amount="0.04",
        fx_rate="0.6",
        splits=[{"category_id": c["id"], "amount": "0.01"} for c in categories[:4]],
    )
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["expense_minor"] == 2
    assert sum(c["spent_minor"] for c in report["categories"]) == 2
    assert all(c["spent_minor"] >= 0 for c in report["categories"])


def test_opening_balance_correction_does_not_change_monthly_profit(client, accounts):
    a = accounts[0]
    post_tx(client, a["id"], amount="15")
    body = {"name": a["name"], "currency": a["currency"], "opening_balance": "100"}
    assert client.put(f"/api/accounts/{a['id']}", json=body).status_code == 200
    assert client.get("/api/accounts").json()[0]["balance_minor"] == 8500
    assert client.get("/api/dashboard?month=2025-02").json()["expense_minor"] == 1500
    assert (
        client.put(f"/api/accounts/{a['id']}", json={**body, "currency": "EUR"}).status_code == 422
    )


@pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="Requires real PostgreSQL row locking"
)
def test_concurrent_retry_serialized(client, accounts):
    body = {
        "amount": "1",
        "account_id": accounts[0]["id"],
        "occurred_on": "2025-02-15",
        "idempotency_key": str(uuid4()),
    }
    with ThreadPoolExecutor(max_workers=5) as pool:
        results = list(pool.map(lambda _: client.post("/api/transactions", json=body), range(5)))
    assert all(r.status_code == 200 for r in results)
    assert len({r.json()["id"] for r in results}) == 1
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -100
