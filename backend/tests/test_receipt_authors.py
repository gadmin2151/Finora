"""Author filters use receipt ownership, including legacy data, never the last editor."""

from datetime import UTC, datetime
from uuid import uuid4

from sqlalchemy import select

from app import models as m
from app.api import register_receipt
from app.db import SessionLocal


def manual(client, account, category, amount="12.50"):
    response = client.post(
        "/api/receipts/manual",
        json={
            "request_key": str(uuid4()),
            "merchant": "Market",
            "purchased_on": "2025-02-15",
            "total": amount,
            "account_id": account["id"],
            "items": [
                {
                    "name": "Product",
                    "quantity": "1",
                    "unit_price": amount,
                    "total": amount,
                    "category_id": category["id"],
                }
            ],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["receipt"]


def add_user(db, name="Former member", **fields):
    user = m.User(username=uuid4().hex, name=name, password_hash="unused-test-hash", **fields)
    db.add(user)
    db.flush()
    return user


def test_creation_preserves_actual_author_and_upload_retry_does_not_claim_receipt(
    client, accounts, categories, owner
):
    created = manual(client, accounts[0], categories[0])
    assert created["created_by"] == owner["id"]
    assert created["creator"] == {
        "id": owner["id"],
        "name": "Тест",
        "username": owner["username"],
        "active": True,
    }
    with SessionLocal() as db:
        db.info["actor_id"] = owner["id"]
        key = uuid4().hex
        initial = register_receipt(db, owner["id"], "photo", key, None, None, 1, [])
        other = add_user(db)
        db.info["actor_id"] = other.id
        retry = register_receipt(db, owner["id"], "photo", key, None, None, 1, [])
        assert retry["duplicate"]
        assert retry["receipt"]["creator"] == initial["receipt"]["creator"]
        audit = db.scalar(
            select(m.Audit).where(
                m.Audit.entity_id == initial["receipt"]["id"],
                m.Audit.action == "receipt.registered",
            )
        )
        assert audit.actor_id == owner["id"]


def test_author_list_scoped_without_membership_and_includes_legacy_unknown(
    client, accounts, categories, owner
):
    manual(client, accounts[0], categories[0])
    inactive = manual(client, accounts[0], categories[0])
    unknown = manual(client, accounts[0], categories[0])
    with SessionLocal() as db:
        former = add_user(db, is_active=False, deleted_at=datetime.now(UTC))
        former_id = former.id
        db.get(m.Receipt, inactive["id"]).created_by = former_id
        db.get(m.Receipt, unknown["id"]).created_by = None
        foreign_user = add_user(db, name="Foreign user")
        foreign_id = foreign_user.id
        foreign_org = m.Organization(name="Private")
        db.add(foreign_org)
        db.flush()
        db.add(
            m.Receipt(
                organization_id=foreign_org.id,
                source="photo",
                source_key=uuid4().hex,
                created_by=foreign_id,
            )
        )
        db.commit()
    result = client.get("/api/receipt-authors")
    assert result.status_code == 200
    authors = {row["id"]: row for row in result.json()["items"]}
    assert set(authors) == {owner["id"], former_id, None}
    assert authors[former_id]["name"] == "Former member" and not authors[former_id]["active"]
    assert authors[None]["name"] == "Автор не указан"
    assert all(set(row) == {"id", "name", "username", "active"} for row in authors.values())
    assert client.get("/api/receipts", params={"created_by": foreign_id}).json()["total"] == 0
    member_filter = client.get("/api/receipts", params={"created_by": former_id}).json()
    assert member_filter["total"] == 1
    assert member_filter["items"][0]["creator"] == authors[former_id]
    legacy = client.get("/api/receipts", params={"created_by": "unknown"}).json()
    assert [item["id"] for item in legacy["items"]] == [unknown["id"]]
    assert (
        client.get("/api/receipt-authors", headers={"X-Organization-ID": str(uuid4())}).status_code
        == 403
    )
    with SessionLocal() as db:
        member = db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"]))
        member.role = "user"
        db.commit()
    assert client.get("/api/receipt-authors").status_code == 200


def test_purchase_author_aggregates_cover_all_pages_without_changing_totals(
    client, accounts, categories, owner
):
    manual(client, accounts[0], categories[0], "12.50")
    manual(client, accounts[0], categories[1], "7.50")
    other = manual(client, accounts[0], categories[0], "4.00")
    with SessionLocal() as db:
        db.get(m.Receipt, other["id"]).created_by = None
        db.commit()
    result = client.get("/api/purchases", params={"created_by": owner["id"], "limit": 1})
    assert result.status_code == 200, result.text
    body = result.json()
    assert len(body["items"]) == 1 and body["total"] == 2
    assert body["items"][0]["creator"]["id"] == owner["id"]
    assert body["totals"] == [{"currency": "MDL", "total_minor": 2000}]
    assert {row["category_id"]: row["total_minor"] for row in body["creator_categories"]} == {
        categories[0]["id"]: 1250,
        categories[1]["id"]: 750,
    }
    assert all(row["creator"]["id"] == owner["id"] for row in body["creator_categories"])
    assert not body["creator_categories_truncated"]
    all_rows = client.get("/api/purchases").json()
    assert all_rows["totals"] == [{"currency": "MDL", "total_minor": 2400}]
    assert {row["creator"]["id"] for row in all_rows["creator_categories"]} == {owner["id"], None}
    legacy = client.get("/api/purchases", params={"created_by": "unknown"}).json()
    assert legacy["total"] == 1 and legacy["items"][0]["receipt_id"] == other["id"]
    assert client.get("/api/purchases", params={"created_by": str(uuid4())}).json()["total"] == 0


def test_author_reports_include_receipt_refunds_but_not_unattributed_transactions(
    client, accounts, categories, owner
):
    receipt = manual(client, accounts[0], categories[0], "12.50")
    legacy = manual(client, accounts[0], categories[0], "4.00")
    with SessionLocal() as db:
        db.get(m.Receipt, legacy["id"]).created_by = None
        db.commit()
    for extra in [
        {"kind": "refund", "amount": "2.50", "refund_of": receipt["transaction_id"]},
        {"kind": "expense", "amount": "10.00"},
    ]:
        result = client.post(
            "/api/transactions",
            json={
                "account_id": accounts[0]["id"],
                "occurred_on": "2025-02-15",
                "idempotency_key": str(uuid4()),
                **extra,
            },
        )
        assert result.status_code == 200, result.text
    params = {"kind": "categories", "date_from": "2025-02-01", "date_to": "2025-02-28"}
    author_report = client.get("/api/reports", params={**params, "created_by": owner["id"]})
    assert author_report.status_code == 200, author_report.text
    body = author_report.json()
    assert body["query"]["created_by"] == owner["id"]
    assert body["metrics"][1]["value"] == "10.00 MDL"
    assert body["rows"][0]["value"] == "10.00 MDL"
    unknown_report = client.get("/api/reports", params={**params, "created_by": "unknown"}).json()
    assert unknown_report["metrics"][1]["value"] == "4.00 MDL"
    assert client.get("/api/reports", params=params).json()["metrics"][1]["value"] == "24.00 MDL"
