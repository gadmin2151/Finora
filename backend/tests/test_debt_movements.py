from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from decimal import ROUND_HALF_UP, Decimal
from secrets import token_urlsafe
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal, engine
from app.main import app


def create_debt(client, account, direction="lent", mode="new", amount="500"):
    response = client.post(
        "/api/debts",
        json={
            "person": "Debt test",
            "direction": direction,
            "amount": amount,
            "currency": "MDL",
            "mode": mode,
            "account_id": account,
            "occurred_on": "2025-02-01",
            "idempotency_key": str(uuid4()),
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["id"]


def movement(account, amount, **extra):
    return {
        "account_id": account,
        "amount": amount,
        "occurred_on": "2025-02-02",
        "note": "Recorded payment",
        "idempotency_key": str(uuid4()),
        **extra,
    }


def remaining(client, debt):
    return next(d["remaining_minor"] for d in client.get("/api/debts").json() if d["id"] == debt)


@pytest.mark.parametrize("currency,rate", [("EUR", "19.50"), ("USD", "17.50"), ("RON", "3.90")])
@pytest.mark.parametrize("direction", ["lent", "borrowed"])
def test_foreign_partial_repayment_preserves_currency_cents_retry_and_scope(
    client, currency, rate, direction
):
    account = client.post(
        "/api/accounts",
        json={"name": f"Synthetic {currency}", "currency": currency, "opening_balance": "100"},
    ).json()
    body = {
        "person": "Synthetic foreign debt",
        "direction": direction,
        "amount": "50",
        "currency": currency,
        "mode": "new",
        "account_id": account["id"],
        "occurred_on": "2025-02-01",
        "fx_rate": rate,
        "idempotency_key": uuid4().hex,
    }
    created = client.post("/api/debts", json=body)
    assert created.status_code == 200, created.text
    debt = created.json()["id"]
    payment = movement(account["id"], "12.34", fx_rate=rate)
    paid = client.post(f"/api/debts/{debt}/repay", json=payment)
    assert paid.status_code == 200, paid.text
    result = paid.json()
    assert result["currency"] == currency and result["amount_minor"] == 1234
    assert Decimal(result["fx_rate"]) == Decimal(rate)
    assert result["base_minor"] == int(
        (Decimal(1234) * Decimal(rate)).quantize(Decimal(1), rounding=ROUND_HALF_UP)
    )
    assert remaining(client, debt) == 3766
    assert client.post(f"/api/debts/{debt}/repay", json=payment).json()["id"] == result["id"]
    assert (
        client.post(f"/api/debts/{debt}/repay", json={**payment, "amount": "12.35"}).status_code
        == 409
    )
    balances = {row["id"]: row["balance_minor"] for row in client.get("/api/accounts").json()}
    assert balances[account["id"]] == (6234 if direction == "lent" else 13766)
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["income_minor"] == report["expense_minor"] == 0
    assert client.get("/api/transactions").json()["total"] == 2
    other = client.post("/api/organizations", json={"name": "Synthetic other FX scope"}).json()[
        "id"
    ]
    assert (
        client.post(
            f"/api/debts/{debt}/repay", json=payment, headers={"X-Organization-ID": other}
        ).status_code
        == 404
    )
    assert client.get("/api/debts", headers={"X-Organization-ID": other}).json() == []
    assert (
        client.get("/api/transactions", headers={"X-Organization-ID": other}).json()["total"] == 0
    )
    assert remaining(client, debt) == 3766


@pytest.mark.parametrize("direction", ["lent", "borrowed"])
@pytest.mark.parametrize("mode", ["new", "existing"])
def test_partial_full_increase_and_reopen_preserve_ledger(client, accounts, direction, mode):
    account = accounts[0]["id"]
    debt = create_debt(client, account, direction, mode)
    partial = client.post(f"/api/debts/{debt}/repay", json=movement(account, "200"))
    assert partial.status_code == 200, partial.text
    assert remaining(client, debt) == 30000
    payload = movement(account, "150")
    topup = client.post(f"/api/debts/{debt}/increase", json=payload)
    assert topup.status_code == 200, topup.text
    assert topup.json()["debt_id"] == debt
    assert topup.json()["note"] == "Recorded payment"
    assert topup.json()["kind"] == ("debt_lend" if direction == "lent" else "debt_borrow")
    assert (
        client.post(f"/api/debts/{debt}/increase", json=payload).json()["id"] == topup.json()["id"]
    )
    assert remaining(client, debt) == 45000
    payment = movement(account, "450", full=True)
    full = client.post(f"/api/debts/{debt}/repay", json=payment)
    assert full.status_code == 200, full.text
    assert remaining(client, debt) == 0
    assert client.post(f"/api/debts/{debt}/repay", json=payment).json()["id"] == full.json()["id"]
    reopened = client.post(f"/api/debts/{debt}/increase", json=movement(account, "25.10"))
    assert reopened.status_code == 200, reopened.text
    assert remaining(client, debt) == 2510
    # Retrying a successful full payment must not pay this new advance a second time.
    assert client.post(f"/api/debts/{debt}/repay", json=payment).json()["id"] == full.json()["id"]
    assert remaining(client, debt) == 2510
    assert client.delete(f"/api/transactions/{reopened.json()['id']}?version=1").status_code == 200
    assert remaining(client, debt) == 0
    balances = {a["id"]: a["balance_minor"] for a in client.get("/api/accounts").json()}
    assert balances[account] == (0 if mode == "new" else (50000 if direction == "lent" else -50000))
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["income_minor"] == report["expense_minor"] == 0


def test_full_payment_rejects_changed_balance_without_posting(client, accounts):
    account = accounts[0]["id"]
    debt = create_debt(client, account, amount="100")
    original = movement(account, "100", full=True)
    assert (
        client.post(f"/api/debts/{debt}/increase", json=movement(account, "20")).status_code == 200
    )
    stale = client.post(f"/api/debts/{debt}/repay", json=original)
    assert stale.status_code == 409
    assert remaining(client, debt) == 12000
    partial = client.post(f"/api/debts/{debt}/repay", json=movement(account, "30"))
    assert partial.status_code == 200
    assert client.post(f"/api/debts/{debt}/repay", json=original).status_code == 409
    assert remaining(client, debt) == 9000


def test_debt_increase_rejects_conflicting_retry_and_other_organization(client, accounts):
    account = accounts[0]["id"]
    debt = create_debt(client, account)
    payload = movement(account, "10")
    assert client.post(f"/api/debts/{debt}/increase", json=payload).status_code == 200
    assert (
        client.post(f"/api/debts/{debt}/increase", json={**payload, "amount": "11"}).status_code
        == 409
    )
    assert client.post(f"/api/debts/{debt}/repay", json=payload).status_code == 409
    other = client.post("/api/organizations", json={"name": "Other debt scope"}).json()["id"]
    for endpoint in ["repay", "increase"]:
        assert (
            client.post(
                f"/api/debts/{debt}/{endpoint}",
                json=movement(account, "10"),
                headers={"X-Organization-ID": other},
            ).status_code
            == 404
        )
    assert remaining(client, debt) == 51000


@pytest.mark.parametrize("endpoint", ["repay", "increase"])
def test_debt_movements_validate_money_account_and_date(client, accounts, endpoint):
    account = accounts[0]["id"]
    debt = create_debt(client, account)
    for extra in [
        {"amount": "0"},
        {"amount": "-1"},
        {"amount": "1.001"},
        {"occurred_on": "1980-01-01"},
        {"occurred_on": "2099-01-01"},
    ]:
        payload = {**movement(account, "10"), **extra}
        assert client.post(f"/api/debts/{debt}/{endpoint}", json=payload).status_code == 422
    foreign = client.post("/api/accounts", json={"name": "Foreign", "currency": "EUR"}).json()
    assert (
        client.post(
            f"/api/debts/{debt}/{endpoint}", json=movement(foreign["id"], "10", fx_rate="20")
        ).status_code
        == 422
    )
    assert remaining(client, debt) == 50000


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Requires PostgreSQL row locking")
def test_concurrent_debt_payments_and_topups_do_not_duplicate_money(client, accounts):
    account = accounts[0]["id"]
    debt = create_debt(client, account)
    payments = [movement(account, "500", full=True) for _ in range(2)]
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(
            pool.map(lambda data: client.post(f"/api/debts/{debt}/repay", json=data), payments)
        )
    assert sorted(r.status_code for r in results) == [200, 409]
    assert remaining(client, debt) == 0
    topup = movement(account, "10")
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(
            pool.map(lambda _: client.post(f"/api/debts/{debt}/increase", json=topup), range(4))
        )
    assert all(r.status_code == 200 for r in results)
    assert len({r.json()["id"] for r in results}) == 1
    assert remaining(client, debt) == 1000
    assert (
        next(a["balance_minor"] for a in client.get("/api/accounts").json() if a["id"] == account)
        == -1000
    )


@pytest.mark.parametrize("direction", ["lent", "borrowed"])
@pytest.mark.parametrize("mode", ["new", "existing"])
def test_debt_history_tracks_partial_addition_void_full_payment_and_reopen(
    client, accounts, direction, mode
):
    account = accounts[0]
    debt = create_debt(client, account["id"], direction, mode)
    partial = client.post(f"/api/debts/{debt}/repay", json=movement(account["id"], "200")).json()
    topup = client.post(f"/api/debts/{debt}/increase", json=movement(account["id"], "150")).json()
    assert client.delete(f"/api/transactions/{topup['id']}?version=1").status_code == 200
    full = client.post(
        f"/api/debts/{debt}/repay", json=movement(account["id"], "300", full=True)
    ).json()
    response = client.get(f"/api/debts/{debt}/history")
    assert response.status_code == 200, response.text
    history = response.json()
    assert history["debt_id"] == debt and history["currency"] == "MDL"
    assert history["initial_minor"] == (50000 if mode == "existing" else 0)
    assert history["increased_minor"] == (0 if mode == "existing" else 50000)
    assert history["repaid_minor"] == 50000 and history["remaining_minor"] == 0
    assert history["total"] == (3 if mode == "existing" else 4)
    items = history["items"]
    assert [item["id"] for item in items[:3]] == [full["id"], topup["id"], partial["id"]]
    assert [item["operation"] for item in items[:3]] == ["repayment", "increase", "repayment"]
    assert [item["balance_after_minor"] for item in items[:3]] == [0, 30000, 30000]
    assert [item["voided"] for item in items[:3]] == [False, True, False]
    assert items[1]["amount_minor"] == 15000
    assert items[1]["note"] == "Recorded payment"
    assert items[1]["account_id"] == account["id"]
    assert items[1]["account_name"] == account["name"]
    assert items[1]["occurred_on"] == "2025-02-02"
    assert items[1]["created_at"]
    if mode == "new":
        assert items[-1]["operation"] == "initial"
        assert items[-1]["balance_after_minor"] == 50000
    else:
        assert all(item["operation"] != "initial" for item in items)
    reopened = client.post(
        f"/api/debts/{debt}/increase", json=movement(account["id"], "25.10")
    ).json()
    history = client.get(f"/api/debts/{debt}/history").json()
    assert history["remaining_minor"] == 2510
    assert history["items"][0]["id"] == reopened["id"]
    assert history["items"][0]["balance_after_minor"] == 2510
    assert history["total"] == len(items) + 1


def test_debt_history_pagination_keeps_complete_balances_and_stable_order(client, accounts):
    account = accounts[0]["id"]
    debt = create_debt(client, account, mode="existing")
    for amount, day in [("40", "2025-02-05"), ("30", "2025-02-01"), ("20", "2025-02-03")]:
        response = client.post(
            f"/api/debts/{debt}/repay", json=movement(account, amount, occurred_on=day)
        )
        assert response.status_code == 200
    with SessionLocal() as db:
        transactions = list(db.scalars(select(m.Transaction).where(m.Transaction.debt_id == debt)))
        for tx in transactions:
            tx.created_at = datetime(2025, 2, 10, tzinfo=UTC)
        expected = []
        balance = 50000
        for tx in sorted(transactions, key=lambda row: row.id):
            balance -= tx.amount_minor
            expected.append((tx.id, balance))
        db.commit()
    expected.reverse()
    for offset, (tx_id, balance) in enumerate(expected):
        result = client.get(f"/api/debts/{debt}/history", params={"offset": offset, "limit": 1})
        assert result.status_code == 200, result.text
        page = result.json()
        assert page["total"] == 3 and page["remaining_minor"] == 41000
        assert page["repaid_minor"] == 9000 and page["increased_minor"] == 0
        assert len(page["items"]) == 1 and page["items"][0]["id"] == tx_id
        assert page["items"][0]["balance_after_minor"] == balance
    last = client.get(f"/api/debts/{debt}/history?offset=50&limit=1").json()
    assert last["items"] == [] and last["total"] == 3
    assert last["remaining_minor"] == 41000 and last["repaid_minor"] == 9000


def test_debt_history_existing_opening_needs_no_transaction_or_invented_date(client, accounts):
    debt = create_debt(client, accounts[0]["id"], mode="existing", amount="123.45")
    result = client.get(f"/api/debts/{debt}/history")
    assert result.status_code == 200, result.text
    history = result.json()
    assert history["initial_minor"] == history["remaining_minor"] == 12345
    assert history["increased_minor"] == history["repaid_minor"] == history["total"] == 0
    assert history["items"] == []


def test_debt_history_scope_member_read_access_and_pagination_validation(client, accounts, owner):
    debt = create_debt(client, accounts[0]["id"])
    for params in [{"offset": -1}, {"limit": 0}, {"limit": 201}]:
        assert client.get(f"/api/debts/{debt}/history", params=params).status_code == 422
    assert client.get(f"/api/debts/{uuid4()}/history").status_code == 404
    other = client.post("/api/organizations", json={"name": "History isolation"}).json()["id"]
    assert (
        client.get(f"/api/debts/{debt}/history", headers={"X-Organization-ID": other}).status_code
        == 404
    )
    password = token_urlsafe(20)
    member = client.post(
        "/api/organizations/current/members",
        json={"username": "debt-reader", "name": "Debt reader", "password": password},
    )
    assert member.status_code == 200, member.text
    with TestClient(app) as reader:
        assert reader.get(f"/api/debts/{debt}/history").status_code == 401
        logged_in = reader.post(
            "/api/auth/login",
            json={"username": "debt-reader", "password": password},
            headers={"X-Finora-Client": "web"},
        )
        assert logged_in.status_code == 200, logged_in.text
        reader.headers["X-Organization-ID"] = owner["id"]
        reader.headers["X-CSRF-Token"] = logged_in.json()["csrf"]
        history = reader.get(f"/api/debts/{debt}/history")
        assert history.status_code == 200 and history.json()["total"] == 1
        assert (
            reader.post(
                f"/api/debts/{debt}/repay", json=movement(accounts[0]["id"], "1")
            ).status_code
            == 403
        )
        assert (
            reader.get(
                f"/api/debts/{debt}/history", headers={"X-Organization-ID": other}
            ).status_code
            == 403
        )
