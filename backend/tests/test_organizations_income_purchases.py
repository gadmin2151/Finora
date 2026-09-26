import secrets
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal
from app.main import app


def plan(client, accounts, **extra):
    payload = {
        "name": "Salary",
        "amount": "1000.00",
        "account_id": accounts[0]["id"],
        "start_date": "2025-01-31",
        "recurrence": "monthly",
        "idempotency_key": uuid4().hex,
        **extra,
    }
    response = client.post("/api/income/templates", json=payload)
    assert response.status_code == 200, response.text
    return response.json(), payload


def receipt(
    client,
    accounts,
    categories,
    owner,
    name="LAPTE",
    total="20.00",
    merchant="Market",
    quantity="2",
    unit="l",
    second=True,
):
    with SessionLocal() as db:
        row = m.Receipt(
            organization_id=owner["id"], source="photo", source_key=uuid4().hex, status="review"
        )
        db.add(row)
        db.commit()
        rid = row.id
    items = [
        {
            "name": name,
            "quantity": quantity,
            "unit": unit,
            "unit_price": total,
            "total": total,
            "category_id": categories[0]["id"],
        }
    ]
    if second:
        items.append(
            {
                "name": "Punga",
                "quantity": "1",
                "unit": "buc",
                "unit_price": "1.00",
                "total": "1.00",
                "category_id": categories[1]["id"],
            }
        )
    from decimal import Decimal

    response = client.post(
        f"/api/receipts/{rid}/confirm",
        json={
            "merchant": merchant,
            "purchased_on": "2025-02-15",
            "currency": "MDL",
            "total": str(Decimal(total) + (1 if second else 0)),
            "account_id": accounts[0]["id"],
            "version": 1,
            "items": items,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


@pytest.fixture
def member(client, owner):
    password = secrets.token_urlsafe(20)
    response = client.post(
        "/api/organizations/current/members",
        json={"username": "member", "name": "Member", "password": password},
    )
    assert response.status_code == 200, response.text
    with TestClient(app) as member:
        response = member.post(
            "/api/auth/login",
            json={"username": "member", "password": password},
            headers={"X-Finora-Client": "web"},
        )
        assert response.status_code == 200
        member.headers["X-CSRF-Token"] = response.json()["csrf"]
        member.headers["X-Organization-ID"] = owner["id"]
        yield member


def test_member_accepts_own_receipt_only(client, member, accounts, owner):
    from datetime import date
    from decimal import Decimal

    with SessionLocal() as db:
        actor = db.scalar(select(m.User).where(m.User.username == "member"))
        ids = []
        for author in (actor.id, owner["id"]):
            row = m.Receipt(
                organization_id=owner["id"],
                source="photo",
                source_key=uuid4().hex,
                status="review",
                review_required=True,
                created_by=author,
                merchant="Market",
                purchased_on=date(2025, 9, 25),
                total_minor=1200,
            )
            db.add(row)
            db.flush()
            db.add(
                m.ReceiptItem(
                    receipt_id=row.id,
                    name="PAINE",
                    normalized_name="paine",
                    quantity=Decimal(1),
                    unit="шт",
                    unit_price_minor=1200,
                    total_minor=1200,
                )
            )
            ids.append(row.id)
        db.commit()
    body = {"version": 1, "account_id": accounts[0]["id"]}
    assert member.post(f"/api/receipts/{ids[1]}/accept", json=body).status_code == 403
    assert (
        member.post(f"/api/receipts/{ids[0]}/accept", json={**body, "total": "1"}).status_code
        == 422
    )
    assert member.post(f"/api/receipts/{ids[0]}/accept", json=body).status_code == 200
    assert member.post(f"/api/receipts/{ids[0]}/accept", json=body).status_code == 200
    assert client.get("/api/transactions").json()["total"] == 1


def test_income_plan_receive_and_occasional(client, accounts):
    source, payload = plan(client, accounts)
    assert client.post("/api/income/templates", json=payload).json()["id"] == source["id"]
    report = client.get("/api/income?month=2025-02").json()
    assert report["received_minor"] == 0 and report["expected_minor"] == 100000
    assert report["occurrences"][0]["due_date"] == "2025-02-28"
    assert client.get("/api/bills?month=2025-02").json() == []
    occurrence = report["occurrences"][0]["id"]
    data = {
        "account_id": accounts[0]["id"],
        "amount": "950",
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    first = client.post(f"/api/income/occurrences/{occurrence}/receive", json=data)
    assert first.status_code == 200, first.text
    assert (
        client.post(f"/api/income/occurrences/{occurrence}/receive", json=data).json()["id"]
        == first.json()["id"]
    )
    assert (
        client.post(
            "/api/transactions",
            json={**data, "kind": "income", "amount": "50", "idempotency_key": uuid4().hex},
        ).status_code
        == 200
    )
    report = client.get("/api/income?month=2025-02").json()
    assert (
        report["received_minor"],
        report["regular_minor"],
        report["occasional_minor"],
        report["expected_minor"],
    ) == (100000, 95000, 5000, 0)
    assert report["occurrences"][0]["received_minor"] == 95000
    assert client.get("/api/accounts").json()[0]["balance_minor"] == 100000
    assert (
        client.get("/api/income?month=2025-03").json()["occurrences"][0]["due_date"] == "2025-03-31"
    )


def test_income_edit_pause_and_link_existing(client, accounts):
    source, payload = plan(client, accounts)
    report = client.get("/api/income?month=2025-02").json()
    edited = client.put(
        f"/api/income/templates/{source['id']}", json={**payload, "amount": "1100", "version": 1}
    )
    assert edited.status_code == 200, edited.text
    assert client.get("/api/income?month=2025-02").json()["expected_minor"] == 110000
    assert (
        client.put(
            f"/api/income/templates/{source['id']}/active", json={"active": False, "version": 2}
        ).status_code
        == 200
    )
    assert client.get("/api/income?month=2025-02").json()["expected_minor"] == 0
    assert (
        client.put(
            f"/api/income/templates/{source['id']}/active", json={"active": True, "version": 2}
        ).status_code
        == 409
    )
    assert (
        client.put(
            f"/api/income/templates/{source['id']}/active", json={"active": True, "version": 3}
        ).status_code
        == 200
    )
    data = {
        "account_id": accounts[0]["id"],
        "amount": "1100",
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
    }
    tx = client.post("/api/transactions", json={**data, "kind": "income"}).json()
    received = client.post(
        f"/api/income/occurrences/{report['occurrences'][0]['id']}/receive",
        json={**data, "transaction_id": tx["id"]},
    )
    assert received.status_code == 200, received.text
    assert client.get("/api/transactions").json()["total"] == 1
    assert (
        client.put(
            f"/api/income/templates/{source['id']}",
            json={**payload, "start_date": "2025-01-15", "version": 4},
        ).status_code
        == 422
    )


@pytest.mark.parametrize("language", ["ru", "en"])
def test_purchase_filters_totals_comparisons(client, accounts, categories, owner, language):
    client.headers["Accept-Language"] = language
    cheapest = receipt(
        client, accounts, categories, owner, name="Lápte", total="20", merchant="Cheap"
    )
    receipt(client, accounts, categories, owner, name="LAPTE", total="30", merchant="Other")
    receipt(client, accounts, categories, owner, name="LAPTE", total="40", unit="buc", second=False)
    response = client.get("/api/purchases", params={"search": "lapte", "unit": "л", "limit": 1})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["total"] == 2 and len(data["items"]) == 1
    assert data["totals"] == [{"currency": "MDL", "total_minor": 5000}]
    assert float(data["comparisons"][0]["min_unit_minor"]) == 1000
    assert data["comparisons"][0]["best_merchant"] == "Cheap"
    assert data["comparisons"][0]["best_receipt_id"] == cheapest["id"]
    assert data["comparisons"][0]["potential_minor"] == 1000
    assert (
        client.get(
            "/api/purchases", params={"merchant": "cheap", "category_id": categories[1]["id"]}
        ).json()["total"]
        == 1
    )
    assert client.get("/api/purchases?date_from=2025-03-01").json()["total"] == 0
    assert client.get("/api/purchases?search=%25").json()["total"] == 0
    assert client.get("/api/purchases?date_from=2025-03-01&date_to=2025-02-01").status_code == 422
    reports = client.get("/api/reports?kind=prices&date_from=2025-02-01&date_to=2025-02-28").json()
    assert reports["rows"][0]["receipt_id"] == cheapest["id"]
    insight = next(
        card for card in client.get("/api/insights?month=2025-02").json() if card["kind"] == "price"
    )
    assert "Cheap" in insight["text"]
    assert ("2 receipts" if language == "en" else "2 разных чеков") in insight["basis"]


def test_receipt_delete_item_and_whole_recalculate(client, accounts, categories, owner):
    row = receipt(client, accounts, categories, owner)
    item = row["items"][0]
    response = client.delete(
        f"/api/receipts/{row['id']}/items/{item['id']}?version={row['version']}"
    )
    assert response.status_code == 200, response.text
    corrected = response.json()
    assert corrected["total_minor"] == 100
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -100
    assert client.get("/api/dashboard?month=2025-02").json()["expense_minor"] == 100
    assert client.get("/api/purchases").json()["total"] == 1
    assert client.delete(f"/api/receipts/{row['id']}?version={row['version']}").status_code == 409
    assert (
        client.delete(f"/api/receipts/{row['id']}?version={corrected['version']}").status_code
        == 200
    )
    assert client.get("/api/receipts").json()["total"] == 0
    assert client.get("/api/accounts").json()[0]["balance_minor"] == 0
    assert client.get("/api/purchases").json()["total"] == 0
    assert client.get(f"/api/receipts/{row['id']}").status_code == 404
    with SessionLocal() as db:
        assert db.get(m.Receipt, row["id"]).deleted_at is not None
        assert (
            db.scalar(select(m.Audit).where(m.Audit.action == "receipt.item_deleted")).actor_id
            == owner["id"]
        )


def test_shared_receipts_comments_and_isolated_organizations(
    client, member, accounts, categories, owner
):
    row = receipt(client, accounts, categories, owner)
    assert member.get("/api/purchases").json()["total"] == 2
    comment = member.post(f"/api/receipts/{row['id']}/comments", json={"text": "Office purchase"})
    assert comment.status_code == 200, comment.text
    assert client.get(f"/api/receipts/{row['id']}/comments").json()[0]["author"] == "Member"
    other = client.post("/api/organizations", json={"name": "Other"}).json()
    assert (
        member.get("/api/purchases", headers={"X-Organization-ID": other["id"]}).status_code == 403
    )
    client.headers["X-Organization-ID"] = other["id"]
    assert client.get("/api/purchases").json()["total"] == 0
    assert client.get(f"/api/receipts/{row['id']}").status_code == 404
    assert (
        client.post(f"/api/receipts/{row['id']}/comments", json={"text": "Wrong scope"}).status_code
        == 404
    )
    assert (
        client.post(
            "/api/organizations/current/members", json={"username": "member", "role": "admin"}
        ).status_code
        == 200
    )
    member.headers["X-Organization-ID"] = other["id"]
    assert member.post("/api/accounts", json={"name": "Allowed here"}).status_code == 200
    member.headers["X-Organization-ID"] = owner["id"]
    assert member.post("/api/accounts", json={"name": "Not allowed here"}).status_code == 403
    del member.headers["X-Organization-ID"]
    assert member.get("/api/dashboard?month=2025-02").status_code == 409


@pytest.mark.parametrize(
    "method,path",
    [
        ("POST", "/transactions"),
        ("PUT", "/settings"),
        ("POST", "/accounts"),
        ("POST", "/bills"),
        ("POST", "/debts"),
        ("POST", "/debts/fake/increase"),
        ("POST", "/debts/fake/repay"),
        ("PUT", "/budgets"),
        ("POST", "/income/templates"),
        ("POST", "/receipts/fake/confirm"),
        ("POST", "/receipts/fake/retry"),
        ("DELETE", "/receipts/fake?version=1"),
        ("DELETE", "/receipts/fake/items/fake?version=1"),
        ("POST", "/organizations/current/members"),
        ("GET", "/audit"),
        ("GET", "/export.json"),
        ("GET", "/organizations/current/members"),
    ],
)
def test_member_privileges_enforced_on_server(member, method, path):
    assert member.request(method, "/api" + path).status_code == 403


def test_member_can_request_read_only_assistant_reports(member):
    response = member.post(
        "/api/chat", json={"text": "Покажи категории", "month": "2025-02", "report": "categories"}
    )
    assert response.status_code == 200
    assert member.get("/api/chat").status_code == 200
    assert (
        member.get(
            "/api/reports",
            params={"kind": "summary", "date_from": "2025-02-01", "date_to": "2025-02-28"},
        ).status_code
        == 200
    )
    assert member.post("/api/transactions", json={}).status_code == 403


def test_receipt_upload_requires_explicit_organization_and_member_can_add(member):
    del member.headers["X-Organization-ID"]
    payload = {"url": "https://mev.sfs.md/receipt-verifier/" + "a" * 24}
    assert member.post("/api/receipts/link", json=payload).status_code == 409
    organization_id = member.get("/api/organizations").json()[0]["id"]
    member.headers["X-Organization-ID"] = organization_id
    result = member.post("/api/receipts/link", json=payload)
    assert result.status_code == 200, result.text
    with SessionLocal() as db:
        assert db.get(m.Job, result.json()["job_id"]).organization_id == organization_id


def test_last_admin_and_revocation(client, member):
    members = client.get("/api/organizations/current/members").json()
    admin = next(m for m in members if m["role"] == "admin")
    regular = next(m for m in members if m["role"] == "user")
    assert client.delete(f"/api/organizations/current/members/{admin['id']}").status_code == 409
    assert (
        client.put(
            f"/api/organizations/current/members/{admin['id']}", json={"role": "user"}
        ).status_code
        == 409
    )
    assert client.delete(f"/api/organizations/current/members/{regular['id']}").status_code == 200
    assert member.get("/api/accounts").status_code == 403
    assert member.get("/api/auth/me").status_code == 200


def test_existing_account_password_is_not_overwritten(client, member):
    other = client.post("/api/organizations", json={"name": "Second"}).json()
    client.headers["X-Organization-ID"] = other["id"]
    assert (
        client.post(
            "/api/organizations/current/members",
            json={"username": "member", "password": secrets.token_urlsafe(20)},
        ).status_code
        == 409
    )
    assert member.get("/api/auth/me").status_code == 200
