"""Reviewed budget copying is scoped, atomic and never overwrites existing limits."""

from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal, engine


def limit(client, category, month="2025-12", amount="100"):
    response = client.put(
        "/api/budgets",
        json={
            "month": month,
            "category_id": category["id"],
            "amount": amount,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["id"]


def preview(client, month="2026-01"):
    response = client.get("/api/budgets/copy-preview", params={"month": month})
    assert response.status_code == 200, response.text
    return response.json()


def copy(client, plan):
    return client.post(
        "/api/budgets/copy",
        json={
            "month": plan["month"],
            "fingerprint": plan["fingerprint"],
        },
    )


def test_copy_previous_year_preview_preserves_current_limits_and_finances(client, categories):
    limit(client, categories[0], amount="100.23")
    limit(client, categories[1], amount="200")
    current = limit(client, categories[0], "2026-01", "300")
    before = client.get("/api/accounts").json()
    plan = preview(client)
    assert plan["source_month"] == "2025-12"
    assert plan["preserved_count"] == 1
    assert plan["items"] == [
        {"category_id": categories[1]["id"], "name": categories[1]["name"], "amount_minor": 20000}
    ]
    assert plan["total_minor"] == 20000
    with SessionLocal() as db:
        assert len(list(db.scalars(select(m.Budget)))) == 3  # Preview is read-only.
    response = copy(client, plan)
    assert response.status_code == 200 and response.json()["copied_count"] == 1
    with SessionLocal() as db:
        rows = list(db.scalars(select(m.Budget).where(m.Budget.month == "2026-01")))
        assert len(rows) == 2
        assert db.get(m.Budget, current).amount_minor == 30000
        assert db.scalar(select(m.Transaction.id)) is None
    assert client.get("/api/accounts").json() == before
    assert copy(client, plan).status_code == 409
    assert preview(client)["items"] == []


@pytest.mark.parametrize("changed_month", ["2025-12", "2026-01"])
def test_changed_source_or_target_requires_new_preview(client, categories, changed_month):
    limit(client, categories[0])
    plan = preview(client)
    limit(client, categories[0], changed_month, "123")
    response = copy(client, plan)
    assert response.status_code == 409
    with SessionLocal() as db:
        assert len(list(db.scalars(select(m.Budget)))) == (1 if changed_month == "2025-12" else 2)


def test_no_source_does_not_add_limits_or_post_money(client):
    plan = preview(client)
    assert plan["items"] == [] and plan["total_minor"] == 0
    response = copy(client, plan)
    assert response.status_code == 200 and response.json()["copied_count"] == 0
    with SessionLocal() as db:
        assert db.scalar(select(m.Budget.id)) is None
        assert db.scalar(select(m.Transaction.id)) is None


def test_member_can_view_but_cannot_copy_limits(client, owner, categories):
    limit(client, categories[0])
    plan = preview(client)
    with SessionLocal() as db:
        other_admin = m.User(
            username="synthetic-budget-admin", name="Synthetic admin", password_hash="unused-test"
        )
        db.add(other_admin)
        db.flush()
        db.add(m.Membership(user_id=other_admin.id, organization_id=owner["id"], role="admin"))
        db.flush()
        db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"])).role = "user"
        db.commit()
    assert client.get("/api/budgets/copy-preview?month=2026-01").status_code == 200
    assert copy(client, plan).status_code == 403


def test_preview_cannot_be_replayed_in_another_organization(client, owner, categories):
    limit(client, categories[0])
    plan = preview(client)
    with SessionLocal() as db:
        other = m.Organization(name="Synthetic other budget workspace")
        db.add(other)
        db.flush()
        db.add(m.Membership(user_id=owner["id"], organization_id=other.id, role="admin"))
        other_id = other.id
        db.commit()
    client.headers["X-Organization-ID"] = other_id
    assert copy(client, plan).status_code == 409
    assert preview(client)["items"] == []
    with SessionLocal() as db:
        assert db.scalar(select(m.Budget.id).where(m.Budget.organization_id == other_id)) is None


@pytest.mark.parametrize("month", ["2026-00", "2026-13", "1989-12", "bad"])
def test_invalid_month_cannot_be_previewed_or_copied(client, month):
    assert client.get("/api/budgets/copy-preview", params={"month": month}).status_code == 422
    assert (
        client.post("/api/budgets/copy", json={"month": month, "fingerprint": "0" * 64}).status_code
        == 422
    )


def test_concurrent_copy_creates_each_limit_once(client, categories):
    if engine.dialect.name != "postgresql":
        pytest.skip("PostgreSQL organization row locks required")
    limit(client, categories[0])
    limit(client, categories[1])
    plan = preview(client)
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _: copy(client, plan), range(2)))
    assert sorted(response.status_code for response in responses) == [200, 409]
    with SessionLocal() as db:
        assert len(list(db.scalars(select(m.Budget).where(m.Budget.month == "2026-01")))) == 2
