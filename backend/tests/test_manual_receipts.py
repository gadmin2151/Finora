from concurrent.futures import ThreadPoolExecutor, TimeoutError
from datetime import date, timedelta
from threading import Event
from uuid import uuid4

import pytest
from sqlalchemy import func, select

from app import models as m
from app.db import SessionLocal, engine
from app.receipts import create_manual_receipt
from app.schemas import ManualReceipt


def entry(account, category=None):
    return {
        "request_key": str(uuid4()),
        "merchant": "Manual market",
        "merchant_address": "Test street 10",
        "purchased_on": date.today().isoformat(),
        "currency": "MDL",
        "total": "12.50",
        "account_id": account["id"],
        "items": [
            {
                "name": "Bread",
                "quantity": "2",
                "unit": "шт",
                "unit_price": "7.00",
                "total": "12.50",  # Explicit discounted line totals are authoritative.
                "category_id": category,
            }
        ],
    }


def assert_no_receipts():
    with SessionLocal() as db:
        for model in (m.Receipt, m.ReceiptItem, m.Transaction, m.Job):
            assert db.scalar(select(func.count()).select_from(model)) == 0


def test_manual_receipt_posts_once_without_files_jobs_or_bank_transaction(
    client, accounts, categories, owner
):
    body = entry(accounts[0], categories[0]["id"])
    response = client.post("/api/receipts/manual", json=body)
    assert response.status_code == 200, response.text
    result = response.json()
    receipt = result["receipt"]
    assert result["duplicate"] is False
    assert receipt["source"] == "manual" and receipt["status"] == "posted"
    assert receipt["files"] == [] and receipt["source_url"] is None
    assert receipt["document_type"] == "manual_receipt"
    assert receipt["created_by"] == owner["id"]
    assert receipt["merchant_address"] == body["merchant_address"]
    assert receipt["items"][0]["total_minor"] == receipt["total_minor"] == 1250
    assert receipt["items"][0]["category_id"] == categories[0]["id"]
    retry = client.post("/api/receipts/manual", json=body)
    assert retry.status_code == 200 and retry.json()["duplicate"]
    assert retry.json()["receipt"]["id"] == receipt["id"]
    assert client.get("/api/transactions").json()["total"] == 1
    assert client.get("/api/receipts").json()["total"] == 1
    with SessionLocal() as db:
        tx = db.scalar(select(m.Transaction))
        assert tx.receipt_id == receipt["id"] and tx.kind == "expense"
        assert tx.amount_minor == 1250 and tx.base_minor == 1250
        assert db.scalar(select(func.count()).select_from(m.Job)) == 0
        assert db.scalar(select(func.sum(m.Allocation.amount_minor))) == 1250


def test_manual_receipt_requires_admin_even_for_a_server_admin(client, accounts, owner):
    with SessionLocal() as db:
        membership = db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"]))
        membership.role = "user"
        db.commit()
    assert client.post("/api/receipts/manual", json=entry(accounts[0])).status_code == 403
    assert_no_receipts()


def test_manual_receipt_requires_explicit_organization_and_csrf(client, accounts):
    body = entry(accounts[0])
    scope = client.headers.pop("X-Organization-ID")
    assert client.post("/api/receipts/manual", json=body).status_code == 409
    client.headers["X-Organization-ID"] = scope
    client.headers.pop("X-CSRF-Token")
    assert client.post("/api/receipts/manual", json=body).status_code == 403
    assert_no_receipts()


@pytest.mark.parametrize(
    "change",
    [
        {"merchant": " "},
        {"total": "10.00"},
        {"total": "12.501"},
        {"purchased_on": (date.today() + timedelta(days=2)).isoformat()},
        {"items": []},
        {"request_key": "not-a-uuid"},
        {"transaction_id": "any-existing-bank-transaction"},
        {"version": 1},
    ],
)
def test_invalid_manual_receipt_is_atomic(client, accounts, change):
    response = client.post("/api/receipts/manual", json={**entry(accounts[0]), **change})
    assert response.status_code == 422, response.text
    assert_no_receipts()


@pytest.mark.parametrize("foreign_field", ["account_id", "category_id"])
def test_manual_receipt_cannot_use_another_organizations_account_or_category(
    client, accounts, foreign_field
):
    original = client.headers["X-Organization-ID"]
    other = client.post("/api/organizations", json={"name": "Other workspace"})
    assert other.status_code == 200, other.text
    client.headers["X-Organization-ID"] = other.json()["id"]
    resources = client.get(
        "/api/accounts" if foreign_field == "account_id" else "/api/categories"
    ).json()
    client.headers["X-Organization-ID"] = original
    body = entry(accounts[0])
    if foreign_field == "account_id":
        body["account_id"] = resources[0]["id"]
    else:
        body["items"][0]["category_id"] = resources[0]["id"]
    response = client.post("/api/receipts/manual", json=body)
    assert response.status_code == 404, response.text
    assert_no_receipts()


def test_manual_receipt_rejects_archived_account(client, accounts):
    with SessionLocal() as db:
        db.get(m.Account, accounts[0]["id"]).archived = True
        db.commit()
    assert client.post("/api/receipts/manual", json=entry(accounts[0])).status_code == 422
    assert_no_receipts()


def test_request_key_cannot_overwrite_existing_or_deleted_receipt(client, accounts):
    body = entry(accounts[0])
    first = client.post("/api/receipts/manual", json=body).json()["receipt"]
    changed = client.post("/api/receipts/manual", json={**body, "merchant": "Changed"})
    assert changed.status_code == 409
    deleted = client.delete(f"/api/receipts/{first['id']}?version={first['version']}")
    assert deleted.status_code == 200, deleted.text
    assert client.post("/api/receipts/manual", json=body).status_code == 409
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Receipt)) == 1
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 1


def test_two_intentional_identical_purchases_are_distinct(client, accounts):
    body = entry(accounts[0])
    first = client.post("/api/receipts/manual", json=body).json()["receipt"]
    second = client.post("/api/receipts/manual", json={**body, "request_key": str(uuid4())})
    assert second.status_code == 200
    assert second.json()["receipt"]["id"] != first["id"]
    assert client.get("/api/transactions").json()["total"] == 2


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Requires PostgreSQL row locks")
def test_parallel_retry_waits_for_first_commit_and_posts_only_once(client, accounts, owner):
    data = ManualReceipt(**entry(accounts[0]))
    organization_id = client.headers["X-Organization-ID"]
    created, release = Event(), Event()

    def post(hold=False):
        with SessionLocal() as db:
            db.info["actor_id"] = owner["id"]
            result = create_manual_receipt(db, organization_id, data)
            if hold:
                created.set()
                assert release.wait(5)
            db.commit()
            return result

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(post, True)
        assert created.wait(5)
        second = pool.submit(post)
        try:
            with pytest.raises(TimeoutError):
                second.result(timeout=0.2)
        finally:
            release.set()
        initial, retry = first.result(timeout=5), second.result(timeout=5)
    assert not initial["duplicate"] and retry["duplicate"]
    assert initial["receipt"]["id"] == retry["receipt"]["id"]
    assert client.get("/api/receipts").json()["total"] == 1
    assert client.get("/api/transactions").json()["total"] == 1
