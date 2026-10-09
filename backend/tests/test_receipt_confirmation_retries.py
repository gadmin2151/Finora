"""Timeout acknowledgements and explicit re-confirmation must preserve financial history."""

from copy import deepcopy
from uuid import uuid4

import pytest
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal


def manual(client, accounts, categories):
    body = {
        "request_key": str(uuid4()),
        "merchant": "Synthetic Market",
        "merchant_address": "Test street",
        "purchased_on": "2025-02-15",
        "currency": "MDL",
        "total": "25.00",
        "account_id": accounts[0]["id"],
        "items": [
            {
                "name": "Synthetic product",
                "quantity": "2",
                "unit_price": "12.50",
                "total": "25.00",
                "category_id": categories[0]["id"],
            }
        ],
    }
    response = client.post("/api/receipts/manual", json=body)
    assert response.status_code == 200, response.text
    saved = response.json()["receipt"]
    saved = client.get(f"/api/receipts/{saved['id']}").json()
    body.pop("request_key")
    return saved, {**body, "version": saved["version"] - 1}


@pytest.mark.parametrize(
    "change",
    [
        {"merchant": "Different store"},
        {"total": "26"},
        {"merchant_address": "Different address"},
        {"purchased_on": "2025-02-16"},
    ],
)
def test_posted_receipt_rejects_changed_retry(client, accounts, categories, change):
    saved, body = manual(client, accounts, categories)
    balance = client.get("/api/accounts").json()
    response = client.post(f"/api/receipts/{saved['id']}/confirm", json={**body, **change})
    assert response.status_code == 409, response.text
    assert client.get(f"/api/receipts/{saved['id']}").json() == saved
    assert client.get("/api/accounts").json() == balance
    assert client.get("/api/transactions").json()["total"] == 1


def test_posted_receipt_checks_item_fields_and_acknowledges_same_values(
    client, accounts, categories
):
    saved, body = manual(client, accounts, categories)
    wrong = deepcopy(body)
    wrong["items"][0]["name"] = "Not saved product"
    assert client.post(f"/api/receipts/{saved['id']}/confirm", json=wrong).status_code == 409
    wrong = deepcopy(body)
    wrong["items"][0]["category_id"] = categories[1]["id"]
    assert client.post(f"/api/receipts/{saved['id']}/confirm", json=wrong).status_code == 409
    retry = client.post(f"/api/receipts/{saved['id']}/confirm", json=body)
    assert retry.status_code == 200 and retry.json() == saved
    accept = client.post(
        f"/api/receipts/{saved['id']}/accept",
        json={"account_id": accounts[0]["id"], "version": saved["version"]},
    )
    assert accept.status_code == 200 and accept.json() == saved


def test_explicit_reconfirmation_after_cancellation_creates_one_new_posting(
    client, accounts, categories
):
    saved, body = manual(client, accounts, categories)
    original_tx = client.get("/api/transactions").json()["items"][0]
    assert (
        client.delete(
            f"/api/transactions/{original_tx['id']}", params={"version": original_tx["version"]}
        ).status_code
        == 200
    )
    review = client.get(f"/api/receipts/{saved['id']}").json()
    assert review["status"] == "review" and review["transaction_id"] is None
    assert all(row["balance_minor"] == 0 for row in client.get("/api/accounts").json())
    body["version"] = review["version"]
    reconfirm = client.post(f"/api/receipts/{saved['id']}/confirm", json=body)
    assert reconfirm.status_code == 200, reconfirm.text
    updated = reconfirm.json()
    assert updated["transaction_id"] != original_tx["id"]
    for key in ["source", "created_by", "merchant_address", "total_minor", "files"]:
        assert updated[key] == saved[key]
    retry = client.post(f"/api/receipts/{saved['id']}/confirm", json=body)
    assert retry.status_code == 200 and retry.json()["transaction_id"] == updated["transaction_id"]
    assert client.get("/api/transactions").json()["total"] == 1
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -2500
    with SessionLocal() as db:
        original = db.get(m.Transaction, original_tx["id"])
        assert original.voided and original.receipt_id is None
        assert len(list(db.scalars(select(m.Transaction)))) == 2


def test_posted_receipt_rejects_different_account(client, accounts, categories):
    saved, body = manual(client, accounts, categories)
    response = client.post(
        f"/api/receipts/{saved['id']}/confirm", json={**body, "account_id": accounts[1]["id"]}
    )
    assert response.status_code == 409
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -2500
