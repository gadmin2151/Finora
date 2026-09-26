from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest

from app.db import engine


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
