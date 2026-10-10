"""Bounded seeded sequences check independent cents-based prefix invariants."""

import random
from decimal import ROUND_HALF_UP, Decimal

import pytest
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal


def money(cents):
    return f"{cents // 100}.{cents % 100:02d}"


def balances(client):
    response = client.get("/api/accounts")
    assert response.status_code == 200
    return {row["id"]: row["balance_minor"] for row in response.json()}


def post(client, body):
    response = client.post("/api/transactions", json=body)
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.parametrize("seed", [2718, 31415, 20261010])
def test_seeded_mdl_prefixes_and_reverse_dependency_unwind(client, accounts, categories, seed):
    rng = random.Random(seed)
    account_ids = [account["id"] for account in accounts]
    expected = {account: 0 for account in account_ids}
    records = []
    income = expense = 0

    def assert_prefix():
        assert balances(client) == expected, f"seed={seed}, prefix={len(records)}"
        report = client.get("/api/dashboard?month=2025-02").json()
        assert (report["income_minor"], report["expense_minor"]) == (income, expense)
        assert sum(expected.values()) == income - expense
        assert sum(row["spent_minor"] for row in report["categories"]) == expense

    # Interleaving transfers and refunds checks prefixes, not only a final balance.
    for index, kind in enumerate(
        ["income", "expense", "transfer", "expense", "refund", "transfer"] * 2
    ):
        account = rng.choice(account_ids)
        cents = rng.randint(1, 9999)
        body = {
            "kind": kind,
            "amount": money(cents),
            "account_id": account,
            "occurred_on": "2025-02-15",
            "idempotency_key": f"seed-{seed}-transaction-{index}",
        }
        original = None
        if kind == "refund":
            original = records[-1]  # Expense directly before this refund.
            account = original["body"]["account_id"]
            cents = rng.randint(1, original["cents"])
            body.update(account_id=account, amount=money(cents), refund_of=original["id"])
        elif kind == "transfer":
            body["target_account_id"] = next(value for value in account_ids if value != account)
        elif kind == "expense":
            body["category_id"] = rng.choice(categories[:3])["id"]
        tx = post(client, body)
        assert tx["amount_minor"] == cents and tx["base_minor"] == cents
        sign = 1 if kind in {"income", "refund"} else -1
        expected[account] += sign * cents
        if kind == "transfer":
            expected[body["target_account_id"]] += cents
        elif kind == "income":
            income += cents
        else:
            expense += cents if kind == "expense" else -cents
        records.append({"id": tx["id"], "body": body, "cents": cents})
        assert_prefix()
        # Replay any earlier committed operation after unrelated operations.
        replay = rng.choice(records)
        assert post(client, replay["body"])["id"] == replay["id"]
        assert_prefix()

    for record in reversed(records):
        body, cents = record["body"], record["cents"]
        response = client.delete(f"/api/transactions/{record['id']}?version=1")
        assert response.status_code == 200, response.text
        sign = 1 if body["kind"] in {"income", "refund"} else -1
        expected[body["account_id"]] -= sign * cents
        if body["kind"] == "transfer":
            expected[body["target_account_id"]] -= cents
        elif body["kind"] == "income":
            income -= cents
        else:
            expense -= cents if body["kind"] == "expense" else -cents
        assert_prefix()
        # Neither DELETE retry nor original POST may resurrect a voided operation.
        assert client.delete(f"/api/transactions/{record['id']}?version=1").status_code == 200
        assert client.post("/api/transactions", json=body).status_code == 409
        assert_prefix()
    assert all(value == 0 for value in expected.values())


@pytest.mark.parametrize("direction", ["lent", "borrowed"])
@pytest.mark.parametrize("seed", [2718, 20261010])
def test_seeded_partial_debt_schedule_preserves_each_residual(client, accounts, direction, seed):
    rng = random.Random(seed)
    account = accounts[0]["id"]
    initial = rng.randint(1000, 9999)
    created = client.post(
        "/api/debts",
        json={
            "person": "Synthetic seeded debt",
            "direction": direction,
            "currency": "MDL",
            "mode": "new",
            "amount": money(initial),
            "account_id": account,
            "occurred_on": "2025-02-01",
            "idempotency_key": f"seed-debt-{seed}-{direction}",
        },
    )
    assert created.status_code == 200, created.text
    debt = created.json()["id"]
    residual = initial
    sign = -1 if direction == "lent" else 1
    payments = []

    def assert_residual():
        rows = client.get("/api/debts").json()
        assert next(row["remaining_minor"] for row in rows if row["id"] == debt) == residual
        assert balances(client)[account] == sign * residual
        report = client.get("/api/dashboard?month=2025-02").json()
        assert report["income_minor"] == report["expense_minor"] == 0

    for index in range(6):
        increase = index in {2, 5}
        cents = rng.randint(1, 500) if increase else rng.randint(1, max(1, residual // 2))
        body = {
            "amount": money(cents),
            "account_id": account,
            "occurred_on": "2025-02-02",
            "idempotency_key": f"seed-movement-{seed}-{direction}-{index}",
        }
        endpoint = f"/api/debts/{debt}/{'increase' if increase else 'repay'}"
        response = client.post(endpoint, json=body)
        assert response.status_code == 200, response.text
        residual += cents if increase else -cents
        assert_residual()
        if not increase:
            payments.append((endpoint, body, response.json()["id"]))
        # Replay a partial payment after later top-ups and repayments.
        old_endpoint, old_body, old_id = rng.choice(payments)
        retry = client.post(old_endpoint, json=old_body)
        assert retry.status_code == 200 and retry.json()["id"] == old_id
        assert_residual()
        bad = client.post(
            endpoint.replace("/increase", "/repay"),
            json={
                **body,
                "amount": money(residual + 1),
                "idempotency_key": body["idempotency_key"] + "-over",
            },
        )
        assert bad.status_code == 422
        assert_residual()


@pytest.mark.parametrize(
    "currency,rate", [("EUR", "19.50"), ("RON", "0.60"), ("USD", "1.23456789")]
)
def test_seeded_fx_split_refund_rounds_each_operation_then_unwinds(
    client, categories, currency, rate
):
    rng = random.Random(20261010)
    account_response = client.post(
        "/api/accounts", json={"name": "Synthetic seeded FX", "currency": currency}
    )
    assert account_response.status_code == 200
    account = account_response.json()["id"]
    cents = rng.randint(7, 43)
    pieces = [1, rng.randint(1, cents - 2)]
    pieces.append(cents - sum(pieces))
    body = {
        "amount": money(cents),
        "account_id": account,
        "occurred_on": "2025-02-15",
        "fx_rate": rate,
        "idempotency_key": f"seeded-fx-{currency}",
        "splits": [
            {"category_id": category["id"], "amount": money(value)}
            for category, value in zip(categories[:3], pieces, strict=True)
        ],
    }
    purchase = post(client, body)
    returned = rng.randint(1, cents - 1)
    refund_body = {
        "kind": "refund",
        "amount": money(returned),
        "account_id": account,
        "occurred_on": "2025-02-15",
        "fx_rate": rate,
        "refund_of": purchase["id"],
        "idempotency_key": f"seeded-fx-refund-{currency}",
    }
    refund = post(client, refund_body)

    def expected_base(value):
        return int((Decimal(value) * Decimal(rate)).quantize(Decimal(1), rounding=ROUND_HALF_UP))

    assert purchase["base_minor"] == expected_base(cents)
    assert refund["base_minor"] == expected_base(returned)
    assert balances(client)[account] == -cents + returned
    with SessionLocal() as db:
        for tx, value in [(purchase, cents), (refund, returned)]:
            allocations = list(
                db.scalars(select(m.Allocation).where(m.Allocation.transaction_id == tx["id"]))
            )
            assert sum(row.amount_minor for row in allocations) == value
            assert sum(row.base_minor for row in allocations) == expected_base(value)
            assert all(row.amount_minor >= 0 and row.base_minor >= 0 for row in allocations)
    report = client.get("/api/dashboard?month=2025-02").json()
    # Round each transaction separately; do not assume distributive FX rounding.
    assert report["expense_minor"] == expected_base(cents) - expected_base(returned)
    assert sum(row["spent_minor"] for row in report["categories"]) == report["expense_minor"]
    assert post(client, refund_body)["id"] == refund["id"]
    assert balances(client)[account] == -cents + returned
    for tx in [refund, purchase]:
        assert client.delete(f"/api/transactions/{tx['id']}?version=1").status_code == 200
    assert balances(client)[account] == 0
    assert client.get("/api/dashboard?month=2025-02").json()["expense_minor"] == 0
