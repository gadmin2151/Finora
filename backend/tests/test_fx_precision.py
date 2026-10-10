"""Exchange rates must have their persisted precision before calculation or retry."""

import hashlib
import json
from datetime import date
from decimal import Decimal
from uuid import uuid4

import pytest
from sqlalchemy import select
from test_scheduled_retries import schedule

from app import models as m
from app import schemas as s
from app.db import SessionLocal
from app.finance import base_value

RAW = "19.123456789"
STORED = Decimal("19.12345679")


def foreign_account(client):
    response = client.post(
        "/api/accounts", json={"name": "Synthetic precision EUR", "currency": "EUR", "kind": "card"}
    )
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.parametrize("income", [False, True])
@pytest.mark.parametrize("legacy", [False, True])
def test_scheduled_retry_uses_stored_precision_without_rewriting_history(client, income, legacy):
    account = foreign_account(client)
    path = schedule(client, [account], income, currency="EUR")
    body = {
        "account_id": account["id"],
        "amount": "100000.13",
        "fx_rate": RAW,
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    first = client.post(path, json=body)
    assert first.status_code == 200, first.text
    with SessionLocal() as db:
        tx = db.get(m.Transaction, first.json()["id"])
        assert tx.fx_rate == STORED
        assert tx.base_minor == base_value(tx.amount_minor, STORED)
        assert tx.base_minor != base_value(tx.amount_minor, Decimal(RAW))
        if legacy:
            # Reproduce a previous version's calculation from its unrounded rate.
            tx.base_minor = base_value(tx.amount_minor, Decimal(RAW))
            db.commit()
        original_base = tx.base_minor
    before = client.get("/api/accounts").json()
    retry = client.post(path, json=body)
    assert retry.status_code == 200, retry.text
    assert retry.json()["id"] == first.json()["id"]
    assert client.get("/api/accounts").json() == before
    with SessionLocal() as db:
        assert db.get(m.Transaction, first.json()["id"]).base_minor == original_base
        assert len(list(db.scalars(select(m.Transaction)))) == 1
    changed = client.post(path, json={**body, "fx_rate": "19.12345680"})
    assert changed.status_code == 409


@pytest.mark.parametrize("legacy", ["current", "raw", "zero"])
def test_receipt_confirmation_retry_uses_stored_precision(client, categories, legacy):
    account = foreign_account(client)
    body = {
        "request_key": str(uuid4()),
        "merchant": "Synthetic precision store",
        "purchased_on": "2025-02-15",
        "currency": "EUR",
        "total": "100000.13",
        "account_id": account["id"],
        "fx_rate": RAW,
        "items": [
            {
                "name": "Synthetic product",
                "quantity": "1",
                "unit_price": "100000.13",
                "total": "100000.13",
                "category_id": categories[0]["id"],
            }
        ],
    }
    first = client.post("/api/receipts/manual", json=body)
    assert first.status_code == 200, first.text
    receipt = first.json()["receipt"]
    with SessionLocal() as db:
        row = db.get(m.Receipt, receipt["id"])
        assert row.fx_rate == STORED
        tx = db.get(m.Transaction, receipt["transaction_id"])
        assert tx.fx_rate == STORED
        assert tx.base_minor == base_value(tx.amount_minor, STORED)
        assert tx.base_minor != base_value(tx.amount_minor, Decimal(RAW))
        if legacy == "raw":
            tx.base_minor = base_value(tx.amount_minor, Decimal(RAW))
            db.commit()
        elif legacy == "zero":
            row.fx_rate, tx.fx_rate, tx.base_minor = Decimal(0), Decimal(0), 0
            body["fx_rate"] = "0.000000001"
            original = s.ManualReceipt(**body).model_dump(mode="json", exclude={"request_key"})
            row.original = {
                **row.original,
                "manual_signature": hashlib.sha256(
                    json.dumps(original, sort_keys=True).encode()
                ).hexdigest(),
            }
            db.commit()
        original_base = tx.base_minor
    confirm = {key: value for key, value in body.items() if key != "request_key"}
    confirm["version"] = receipt["version"] - 1
    retry = client.post(f"/api/receipts/{receipt['id']}/confirm", json=confirm)
    assert retry.status_code == 200, retry.text
    assert retry.json()["transaction_id"] == receipt["transaction_id"]
    duplicate = client.post("/api/receipts/manual", json=body)
    assert duplicate.status_code == 200 and duplicate.json()["duplicate"]
    with SessionLocal() as db:
        assert db.get(m.Transaction, receipt["transaction_id"]).base_minor == original_base
        assert len(list(db.scalars(select(m.Transaction)))) == 1


@pytest.mark.parametrize("income", [False, True])
def test_plan_creation_persists_canonical_rate(client, income):
    account = foreign_account(client)
    body = {
        "name": "Synthetic precision plan",
        "amount": "25",
        "currency": "EUR",
        "start_date": "2025-02-28",
        "recurrence": "monthly",
        "account_id": account["id"],
        "fx_rate": RAW,
    }
    if income:
        body["idempotency_key"] = uuid4().hex
    path = "/api/income/templates" if income else "/api/bills"
    response = client.post(path, json=body)
    assert response.status_code == 200, response.text
    with SessionLocal() as db:
        assert db.scalar(select(m.Bill)).fx_rate == STORED
    if income:
        assert client.post(path, json=body).status_code == 200
    rejected_body = {**body, "fx_rate": "0.000000001"}
    if income:
        rejected_body["idempotency_key"] = uuid4().hex
    rejected = client.post(path, json=rejected_body)
    assert rejected.status_code == 422
    with SessionLocal() as db:
        assert len(list(db.scalars(select(m.Bill)))) == 1


@pytest.mark.parametrize("income", [False, True])
def test_payment_rejects_rate_that_would_store_as_zero(client, income):
    account = foreign_account(client)
    path = schedule(client, [account], income, currency="EUR")
    body = {
        "account_id": account["id"],
        "amount": "25",
        "fx_rate": "0.000000001",
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    response = client.post(path, json=body)
    assert response.status_code == 422, response.text
    assert client.get("/api/transactions").json()["total"] == 0
    assert client.get("/api/accounts").json()[0]["balance_minor"] == 0


@pytest.mark.parametrize("rate,expected", [(RAW, STORED), ("0.000000005", Decimal("0.00000001"))])
def test_uploaded_receipt_stores_rate_before_processing(owner, rate, expected):
    from app.api import register_receipt

    with SessionLocal() as db:
        result = register_receipt(
            db,
            owner["id"],
            "photo",
            uuid4().hex,
            None,
            None,
            Decimal(rate),
            [],
            review_required=True,
        )
        assert db.get(m.Receipt, result["receipt"]["id"]).fx_rate == expected
        assert len(list(db.scalars(select(m.Transaction)))) == 0


def test_legacy_income_creation_retry_acknowledges_without_rewriting_zero_rate(client, owner):
    account = foreign_account(client)
    body = {
        "name": "Synthetic legacy precision plan",
        "amount": "25",
        "currency": "EUR",
        "start_date": "2025-02-28",
        "recurrence": "monthly",
        "account_id": account["id"],
        "fx_rate": "0.000000001",
        "idempotency_key": uuid4().hex,
    }
    payload = s.IncomePlanInput(**body)
    fingerprint = hashlib.sha256(payload.model_dump_json(exclude={"version"}).encode()).hexdigest()
    with SessionLocal() as db:
        row = m.Bill(
            organization_id=owner["id"],
            kind="income",
            name=body["name"],
            amount_minor=2500,
            currency="EUR",
            start_date=date(2025, 2, 28),
            recurrence="monthly",
            account_id=account["id"],
            fx_rate=Decimal(0),
            creation_key=body["idempotency_key"],
            request_hash=fingerprint,
        )
        db.add(row)
        db.commit()
        plan_id = row.id
    retry = client.post("/api/income/templates", json=body)
    assert retry.status_code == 200 and retry.json()["id"] == plan_id
    with SessionLocal() as db:
        assert db.get(m.Bill, plan_id).fx_rate == 0
        assert len(list(db.scalars(select(m.Bill)))) == 1
        assert db.scalar(select(m.Transaction.id)) is None
    changed = client.post("/api/income/templates", json={**body, "amount": "26"})
    assert changed.status_code == 409


@pytest.mark.parametrize("income", [False, True])
def test_legacy_zero_rate_payment_acknowledgement_does_not_recalculate(client, income):
    account = foreign_account(client)
    path = schedule(client, [account], income, currency="EUR")
    body = {
        "account_id": account["id"],
        "amount": "25",
        "fx_rate": RAW,
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    posted = client.post(path, json=body)
    assert posted.status_code == 200
    with SessionLocal() as db:
        row = db.get(m.Transaction, posted.json()["id"])
        row.fx_rate, row.base_minor = Decimal(0), 0
        db.commit()
    before = client.get("/api/accounts").json()
    retry = client.post(path, json={**body, "fx_rate": "0.000000001"})
    assert retry.status_code == 200 and retry.json()["id"] == posted.json()["id"]
    assert client.get("/api/accounts").json() == before
    with SessionLocal() as db:
        assert db.get(m.Transaction, posted.json()["id"]).base_minor == 0
