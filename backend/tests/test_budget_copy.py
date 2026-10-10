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


@pytest.mark.parametrize("amount", ["0", "-0.01", "-100"])
def test_nonpositive_budget_input_is_atomic(client, categories, amount):
    response = client.put(
        "/api/budgets",
        json={"month": "2025-12", "category_id": categories[0]["id"], "amount": amount},
    )
    assert response.status_code == 422
    assert preview(client)["items"] == []
    with SessionLocal() as db:
        assert db.scalar(select(m.Budget.id)) is None
        assert db.scalar(select(m.Transaction.id)) is None


@pytest.mark.parametrize("invalid_minor", [0, -1, 100000000001])
def test_invalid_saved_source_limit_rolls_back_entire_copy(client, categories, invalid_minor):
    limit(client, categories[0])
    invalid_id = limit(client, categories[1])
    with SessionLocal() as db:
        db.get(m.Budget, invalid_id).amount_minor = invalid_minor
        db.commit()
    plan = preview(client)
    assert copy(client, plan).status_code == 422
    with SessionLocal() as db:
        assert db.scalar(select(m.Budget.id).where(m.Budget.month == "2026-01")) is None
        assert db.scalar(select(m.Audit.id).where(m.Audit.action == "budget.copied")) is None
        assert db.scalar(select(m.Transaction.id)) is None


def test_fresh_preview_repeat_preserves_limits_without_duplicate_audit(client, categories):
    limit(client, categories[0], amount="10.01")
    assert copy(client, preview(client)).json()["copied_count"] == 1
    plan = preview(client)
    for _ in range(2):
        result = copy(client, plan)
        assert result.status_code == 200 and result.json()["copied_count"] == 0
    with SessionLocal() as db:
        assert len(list(db.scalars(select(m.Budget).where(m.Budget.month == "2026-01")))) == 1
        assert len(list(db.scalars(select(m.Audit).where(m.Audit.action == "budget.copied")))) == 1


def test_deleted_source_before_apply_requires_new_preview(client, categories):
    source_id = limit(client, categories[0])
    plan = preview(client)
    assert client.delete(f"/api/budgets/{source_id}").status_code == 200
    assert copy(client, plan).status_code == 409
    assert preview(client)["items"] == []
    with SessionLocal() as db:
        assert db.scalar(select(m.Budget.id)) is None


def test_other_organization_budget_id_cannot_be_deleted(client, owner, categories):
    source_id = limit(client, categories[0])
    with SessionLocal() as db:
        other = m.Organization(name="Synthetic isolated budget workspace")
        db.add(other)
        db.flush()
        db.add(m.Membership(user_id=owner["id"], organization_id=other.id, role="admin"))
        other_id = other.id
        db.commit()
    client.headers["X-Organization-ID"] = other_id
    assert client.delete(f"/api/budgets/{source_id}").status_code == 404
    with SessionLocal() as db:
        assert db.get(m.Budget, source_id).amount_minor == 10000


def test_source_delete_waits_for_inflight_reviewed_copy(client, categories, monkeypatch):
    import threading
    import time

    from sqlalchemy import text

    from app import budgets

    if engine.dialect.name != "postgresql":
        pytest.skip("PostgreSQL organization row locks required")
    source_id = limit(client, categories[0])
    plan = preview(client)
    snapshot_ready = threading.Event()
    release_copy = threading.Event()
    original_preview = budgets.copy_preview

    def paused_preview(*args):
        result = original_preview(*args)
        snapshot_ready.set()
        assert release_copy.wait(5), "Test did not release the paused copy"
        return result

    monkeypatch.setattr(budgets, "copy_preview", paused_preview)
    with ThreadPoolExecutor(max_workers=2) as pool:
        copying = pool.submit(copy, client, plan)
        assert snapshot_ready.wait(5)
        deleting = pool.submit(client.delete, f"/api/budgets/{source_id}")
        try:
            blocked = False
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline and not deleting.done():
                with engine.connect() as connection:
                    blocked = connection.scalar(
                        text(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND query LIKE '%organizations%')"
                        )
                    )
                if blocked:
                    break
                time.sleep(0.01)
            assert blocked, "Deleting a reviewed source bypassed the organization's copy lock"
        finally:
            release_copy.set()
        assert copying.result(timeout=5).status_code == 200
        assert deleting.result(timeout=5).status_code == 200
    with SessionLocal() as db:
        assert db.get(m.Budget, source_id) is None
        copied = db.scalar(select(m.Budget).where(m.Budget.month == "2026-01"))
        assert copied.amount_minor == 10000
        assert db.scalar(select(m.Transaction.id)) is None
