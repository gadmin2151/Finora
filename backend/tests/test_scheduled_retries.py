"""Schedule acknowledgements must never silently accept a different financial request."""

from uuid import uuid4

import pytest
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal


def schedule(client, accounts, income=False, currency="MDL"):
    body = {
        "name": "Synthetic planned payment",
        "amount": "25.00",
        "currency": currency,
        "start_date": "2025-02-28",
        "recurrence": "monthly",
        "account_id": accounts[0]["id"],
    }
    if income:
        body["idempotency_key"] = uuid4().hex
    if currency != "MDL":
        body["fx_rate"] = "19.5"
    response = client.post("/api/income/templates" if income else "/api/bills", json=body)
    assert response.status_code == 200, response.text
    data = client.get("/api/income?month=2025-02" if income else "/api/bills?month=2025-02").json()
    occurrence = (data["occurrences"] if income else data)[0]
    return (
        f"/api/income/occurrences/{occurrence['id']}/receive"
        if income
        else f"/api/occurrences/{occurrence['id']}/pay"
    )


@pytest.mark.parametrize("income", [False, True])
@pytest.mark.parametrize(
    "field,value",
    [("amount", "30"), ("occurred_on", "2025-03-01"), ("transaction_id", "another-operation")],
)
def test_paid_schedule_rejects_changed_retry(client, accounts, income, field, value):
    path = schedule(client, accounts, income)
    payment = {
        "account_id": accounts[0]["id"],
        "amount": "25",
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    first = client.post(path, json=payment)
    assert first.status_code == 200, first.text
    balance = client.get("/api/accounts").json()
    changed = client.post(path, json={**payment, field: value})
    assert changed.status_code == 409, changed.text
    assert client.get("/api/accounts").json() == balance
    assert client.get("/api/transactions").json()["total"] == 1
    retry = client.post(path, json=payment)
    assert retry.status_code == 200 and retry.json()["id"] == first.json()["id"]


@pytest.mark.parametrize("income", [False, True])
def test_paid_schedule_rejects_other_account(client, accounts, income):
    path = schedule(client, accounts, income)
    payment = {
        "account_id": accounts[0]["id"],
        "amount": "25",
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    assert client.post(path, json=payment).status_code == 200
    changed = client.post(path, json={**payment, "account_id": accounts[1]["id"]})
    assert changed.status_code == 409, changed.text
    assert client.get("/api/transactions").json()["total"] == 1


@pytest.mark.parametrize("income", [False, True])
def test_voided_scheduled_posting_cannot_be_replayed(client, accounts, income):
    path = schedule(client, accounts, income)
    payment = {
        "account_id": accounts[0]["id"],
        "amount": "25",
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    posted = client.post(path, json=payment).json()
    void = client.delete(f"/api/transactions/{posted['id']}", params={"version": posted["version"]})
    assert void.status_code == 200, void.text
    retry = client.post(path, json=payment)
    assert retry.status_code == 409, retry.text
    assert client.get("/api/transactions").json()["total"] == 0
    with SessionLocal() as db:
        assert db.scalar(select(m.Transaction).where(m.Transaction.id == posted["id"])).voided
    assert all(account["balance_minor"] == 0 for account in client.get("/api/accounts").json())
    replacement = client.post(path, json={**payment, "idempotency_key": uuid4().hex})
    assert replacement.status_code == 200 and replacement.json()["id"] != posted["id"]


@pytest.mark.parametrize("income", [False, True])
def test_acknowledging_existing_payment_does_not_require_unarchiving_account(
    client, accounts, income
):
    path = schedule(client, accounts, income)
    payment = {
        "account_id": accounts[0]["id"],
        "amount": "25",
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    posted = client.post(path, json=payment)
    assert posted.status_code == 200
    with SessionLocal() as db:
        db.get(m.Account, accounts[0]["id"]).archived = True
        db.commit()
    retry = client.post(path, json=payment)
    assert retry.status_code == 200 and retry.json()["id"] == posted.json()["id"]
    assert client.get("/api/transactions").json()["total"] == 1


@pytest.mark.parametrize("income", [False, True])
def test_foreign_currency_retry_checks_original_exchange_rate(client, income):
    account = client.post(
        "/api/accounts", json={"name": "Synthetic EUR", "currency": "EUR", "kind": "card"}
    ).json()
    path = schedule(client, [account], income, currency="EUR")
    payment = {
        "account_id": account["id"],
        "amount": "25",
        "fx_rate": "19.5",
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    posted = client.post(path, json=payment)
    assert posted.status_code == 200, posted.text
    assert client.post(path, json={**payment, "fx_rate": "20"}).status_code == 409
    retry = client.post(
        path,
        json={**payment, "amount": "25.00", "fx_rate": "19.500", "idempotency_key": uuid4().hex},
    )
    assert retry.status_code == 200 and retry.json()["id"] == posted.json()["id"]
    assert client.get("/api/transactions").json()["total"] == 1
