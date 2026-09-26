"""Current wallet balances are ledger balances, independent of monthly profit."""

import secrets
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app import models as m
from app.db import SessionLocal, engine
from app.main import app


def adjustment(target="100.00", expected=0, **extra):
    return {
        "target_balance": target,
        "expected_balance_minor": expected,
        "occurred_on": "2025-02-15",
        "idempotency_key": str(uuid4()),
        **extra,
    }


def url(account):
    return f"/api/accounts/{account}/balance-adjustment"


def balance(client, account):
    return next(
        a["balance_minor"] for a in client.get("/api/accounts").json() if a["id"] == account
    )


def post_tx(client, account, **extra):
    response = client.post(
        "/api/transactions",
        json={
            "kind": "income",
            "amount": "10",
            "account_id": account,
            "occurred_on": "2025-02-15",
            "idempotency_key": str(uuid4()),
            **extra,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def test_debt_repayment_is_in_current_wallet_not_monthly_income(client, accounts):
    account = accounts[0]["id"]
    debt = client.post(
        "/api/debts",
        json={
            "person": "Friend",
            "direction": "lent",
            "amount": "5000",
            "currency": "MDL",
            "mode": "existing",
            "occurred_on": "2025-01-01",
            "idempotency_key": str(uuid4()),
        },
    )
    assert debt.status_code == 200
    payment = client.post(
        f"/api/debts/{debt.json()['id']}/repay",
        json={
            "account_id": account,
            "amount": "5000",
            "occurred_on": "2025-02-15",
            "idempotency_key": str(uuid4()),
        },
    )
    assert payment.status_code == 200
    for month in ["2025-01", "2025-02", "2025-03"]:
        report = client.get(f"/api/dashboard?month={month}").json()
        assert report["wallet"]["balances"]["MDL"] == 500000
        assert report["wallet"]["accounts"] == report["accounts"]
        assert report["wallet"]["as_of"] == report["as_of"]
        assert report["income_minor"] == report["expense_minor"] == report["net_minor"] == 0
    assert balance(client, account) == 500000


def test_adjustments_preserve_opening_balance_and_do_not_create_income_or_expense(client, owner):
    account = client.post(
        "/api/accounts", json={"name": "My wallet", "opening_balance": "50"}
    ).json()["id"]
    up = client.post(url(account), json=adjustment("100", 5000, note="Counted cash"))
    assert up.status_code == 200, up.text
    assert up.json()["adjustment_minor"] == 5000
    assert up.json()["previous_balance_minor"] == 5000
    assert up.json()["transaction"]["kind"] == "adjustment"
    down = client.post(url(account), json=adjustment("-10", 10000))
    assert down.status_code == 200, down.text
    assert down.json()["adjustment_minor"] == -11000
    assert down.json()["transaction"]["kind"] == "adjustment_out"
    assert down.json()["account"]["balance_minor"] == -1000
    assert down.json()["account"]["opening_minor"] == 5000
    zero = client.post(url(account), json=adjustment("0", -1000))
    assert zero.status_code == 200
    assert balance(client, account) == 0
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["income_minor"] == report["expense_minor"] == report["net_minor"] == 0
    assert all(c["spent_minor"] == 0 for c in report["categories"])
    assert all(d["income"] == d["expense"] == 0 for d in report["chart"])
    with SessionLocal() as db:
        entry = db.scalar(
            select(m.Audit).where(
                m.Audit.entity_id == up.json()["transaction"]["id"],
                m.Audit.action == "account.balance_adjusted",
            )
        )
        assert entry.actor_id == owner["id"]
        assert entry.details["previous_balance_minor"] == 5000
        assert entry.details["target_balance_minor"] == 10000
        assert entry.details["adjustment_minor"] == 5000
        assert entry.details["effect"] == "adjustment"
        assert db.scalar(select(func.count()).select_from(m.Allocation)) == 0


def test_explicit_income_expense_effect_is_counted_in_monthly_profit(client, accounts):
    account = accounts[0]["id"]
    for data, kind in [
        (adjustment("100", effect="income_expense"), "income"),
        (adjustment("40", 10000, effect="income_expense"), "expense"),
    ]:
        result = client.post(url(account), json=data)
        assert result.status_code == 200, result.text
        assert result.json()["transaction"]["kind"] == kind
    report = client.get("/api/dashboard?month=2025-02").json()
    assert (report["income_minor"], report["expense_minor"], report["net_minor"]) == (
        10000,
        6000,
        4000,
    )
    assert sum(c["spent_minor"] for c in report["categories"]) == 6000


def test_retry_returns_original_difference_without_overwriting_later_money(client, accounts):
    account = accounts[0]["id"]
    body = adjustment()
    first = client.post(url(account), json=body)
    assert first.status_code == 200
    post_tx(client, account)
    retry = client.post(url(account), json=body)
    assert retry.status_code == 200
    assert retry.json()["transaction"]["id"] == first.json()["transaction"]["id"]
    assert retry.json()["previous_balance_minor"] == 0
    assert retry.json()["adjustment_minor"] == 10000
    assert retry.json()["account"]["balance_minor"] == 11000
    assert client.get("/api/transactions").json()["total"] == 2
    for extra in [
        {"target_balance": "110"},
        {"expected_balance_minor": 11000},
        {"effect": "income_expense"},
        {"note": "Changed"},
        {"occurred_on": "2025-02-14"},
    ]:
        assert client.post(url(account), json={**body, **extra}).status_code == 409
    assert client.post(url(accounts[1]["id"]), json=body).status_code == 409
    assert balance(client, account) == 11000


def test_idempotency_key_cannot_be_reused_between_regular_and_adjustment_requests(client, accounts):
    account = accounts[0]["id"]
    key = str(uuid4())
    post_tx(client, account, idempotency_key=key)
    assert (
        client.post(url(account), json=adjustment("50", 1000, idempotency_key=key)).status_code
        == 409
    )
    body = adjustment("50", 1000)
    assert client.post(url(account), json=body).status_code == 200
    regular = client.post(
        "/api/transactions",
        json={
            "kind": "adjustment",
            "amount": "41",
            "account_id": account,
            "occurred_on": body["occurred_on"],
            "idempotency_key": body["idempotency_key"],
        },
    )
    assert regular.status_code == 409
    assert balance(client, account) == 5000


@pytest.mark.parametrize(
    "language,fragment", [("en", "balance has changed"), ("ru", "Остаток счёта изменился")]
)
def test_stale_balance_rejected_without_writes_and_localized(client, accounts, language, fragment):
    account = accounts[0]["id"]
    result = client.post(
        url(account), json=adjustment(expected=1), headers={"Accept-Language": language}
    )
    assert result.status_code == 409
    assert fragment in result.json()["detail"]
    assert balance(client, account) == 0
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0
        assert (
            db.scalar(
                select(func.count())
                .select_from(m.Audit)
                .where(m.Audit.action == "account.balance_adjusted")
            )
            == 0
        )


@pytest.mark.parametrize(
    "extra",
    [
        {"target_balance": "0"},
        {"target_balance": "1.001"},
        {"target_balance": "NaN"},
        {"target_balance": "1000000001"},
        {"expected_balance_minor": 0.5},
        {"expected_balance_minor": "0"},
        {"effect": "transfer"},
        {"occurred_on": "2099-01-01"},
        {"occurred_on": "1980-01-01"},
        {"idempotency_key": "short"},
    ],
)
def test_invalid_adjustments_never_post(client, accounts, extra):
    account = accounts[0]["id"]
    result = client.post(url(account), json=adjustment(**extra))
    assert result.status_code == 422, result.text
    assert balance(client, account) == 0


def test_delta_limit_is_checked_even_when_both_balances_are_allowed(client):
    account = client.post(
        "/api/accounts", json={"name": "Negative wallet", "opening_balance": "-1000000000"}
    ).json()["id"]
    result = client.post(url(account), json=adjustment("1000000000", -100000000000))
    assert result.status_code == 422
    assert balance(client, account) == -100000000000


def test_foreign_currency_and_archived_accounts(client):
    account = client.post("/api/accounts", json={"name": "EUR", "currency": "EUR"}).json()["id"]
    assert client.post(url(account), json=adjustment()).status_code == 422
    body = adjustment(fx_rate="20")
    result = client.post(url(account), json=body)
    assert result.status_code == 200
    assert result.json()["transaction"]["base_minor"] == 200000
    assert result.json()["account"]["currency"] == "EUR"
    assert client.post(f"/api/accounts/{account}/archive").status_code == 200
    assert client.post(url(account), json=adjustment("200", 10000, fx_rate="20")).status_code == 422
    assert client.post(url(account), json=body).status_code == 200
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["wallet"]["balances"]["EUR"] == 10000


def test_negative_adjustment_edit_and_void_restore_ledger(client, accounts):
    account = accounts[0]["id"]
    body = adjustment("-30")
    result = client.post(url(account), json=body)
    assert result.status_code == 200
    tx = result.json()["transaction"]
    edit = client.put(
        f"/api/transactions/{tx['id']}",
        json={
            "kind": "adjustment_out",
            "amount": "20",
            "account_id": account,
            "occurred_on": "2025-02-15",
            "idempotency_key": str(uuid4()),
            "version": 1,
        },
    )
    assert edit.status_code == 200, edit.text
    assert balance(client, account) == -2000
    assert client.post(url(account), json=body).status_code == 409
    assert client.delete(f"/api/transactions/{tx['id']}?version=2").status_code == 200
    assert balance(client, account) == 0
    assert client.post(url(account), json=body).status_code == 409
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["income_minor"] == report["expense_minor"] == 0


def test_adjustment_requires_org_admin_and_csrf_and_owned_account(client, accounts):
    account = accounts[0]["id"]
    other = client.post("/api/organizations", json={"name": "Other"}).json()["id"]
    assert (
        client.post(
            url(account), json=adjustment(), headers={"X-Organization-ID": other}
        ).status_code
        == 404
    )
    assert (
        client.post(
            url(account), json=adjustment(), headers={"X-CSRF-Token": "invalid"}
        ).status_code
        == 403
    )
    password = secrets.token_urlsafe(20)
    assert (
        client.post(
            "/api/organizations/current/members",
            json={"username": "wallet-user", "name": "Member", "password": password},
        ).status_code
        == 200
    )
    with TestClient(app) as member:
        logged = member.post(
            "/api/auth/login",
            json={"username": "wallet-user", "password": password},
            headers={"X-Finora-Client": "web"},
        ).json()
        member.headers["X-CSRF-Token"] = logged["csrf"]
        member.headers["X-Organization-ID"] = client.headers["X-Organization-ID"]
        assert member.post(url(account), json=adjustment()).status_code == 403
    assert balance(client, account) == 0


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Row locks require PostgreSQL")
def test_concurrent_adjustments_serialize_stale_guard_and_idempotency(client, accounts):
    account = accounts[0]["id"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(
            pool.map(
                lambda body: client.post(url(account), json=body),
                [adjustment("100"), adjustment("200")],
            )
        )
    assert sorted(r.status_code for r in results) == [200, 409]
    current = balance(client, account)
    body = adjustment("300", current)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: client.post(url(account), json=body), range(4)))
    assert all(r.status_code == 200 for r in results)
    assert len({r.json()["transaction"]["id"] for r in results}) == 1
    assert balance(client, account) == 30000
    assert client.get("/api/transactions").json()["total"] == 2


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Row locks require PostgreSQL")
def test_adjustment_cannot_overwrite_a_concurrent_income(client, accounts):
    account = accounts[0]["id"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        correction = pool.submit(client.post, url(account), json=adjustment("100"))
        income = pool.submit(post_tx, client, account)
        result = correction.result()
        assert income.result()["amount_minor"] == 1000
    assert result.status_code in {200, 409}
    # Whichever request wins the organization lock, the income is never lost.
    assert balance(client, account) == (11000 if result.status_code == 200 else 1000)
    assert client.get("/api/dashboard?month=2025-02").json()["income_minor"] == 1000
