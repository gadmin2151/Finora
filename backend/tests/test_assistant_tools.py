"""Assistant actions use real role/scoped APIs, with no writes until explicit approval."""

import asyncio
import json
from datetime import UTC, date, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import func, select
from test_assistant_reports import queued_job

from app import ai, assistant
from app import models as m
from app.analytics import ReportQuery
from app.assistant_tools import APIRequest, catalogue, prepared, read_requests, safe_result
from app.db import SessionLocal
from app.security import hasher
from app.workspace_reports import select_receipt


def query(**fields):
    return ReportQuery(kind="receipts", date_from="2025-02-01", date_to="2025-02-28", **fields)


def add_receipt(db, org, total, **fields):
    row = m.Receipt(
        organization_id=org,
        source="photo",
        source_key=uuid4().hex,
        merchant="Shop",
        purchased_on=date(2025, 2, 15),
        total_minor=total,
        **fields,
    )
    db.add(row)
    db.flush()
    return row


def test_receipt_selection_uses_whole_scope_and_excludes_deleted_and_foreign(client, owner):
    org = client.headers["X-Organization-ID"]
    with SessionLocal() as db:
        expensive = add_receipt(db, org, 23508, created_by=owner["id"])
        expected = expensive.id
        for i in range(25):
            add_receipt(db, org, 100 + i)
        add_receipt(db, org, 900000, deleted_at=datetime.now(UTC))
        add_receipt(db, org, None)
        foreign = m.Organization(name="Private")
        db.add(foreign)
        db.flush()
        add_receipt(db, foreign.id, 999999)
        largest = select_receipt(db, org, query(), "largest")
        assert largest.rows[0].receipt_id == expected
        assert largest.rows[0].value == "235.08 MDL"
        assert largest.metrics[0].value == "27"
        assert (
            select_receipt(db, org, query(created_by=owner["id"]), "smallest").rows[0].receipt_id
            == expected
        )
        assert select_receipt(db, org, query(merchant="Missing"), "largest").rows == []


def test_receipt_selection_never_compares_unknown_fx(client):
    with SessionLocal() as db:
        org = client.headers["X-Organization-ID"]
        add_receipt(db, org, 200, currency="USD")
        add_receipt(db, org, 1000)
        result = select_receipt(db, org, query(), "largest")
        assert not result.rows and "валют" in result.notices[0]
        assert select_receipt(db, org, query(currency="USD"), "largest").rows[0].value == "2.00 USD"


def test_open_receipt_returns_real_id_and_full_previous_calendar_month(client, monkeypatch):
    monkeypatch.setattr(assistant, "today", lambda: date(2025, 3, 7))
    with SessionLocal() as db:
        expected = add_receipt(db, client.headers["X-Organization-ID"], 23508).id
        db.scalar(select(m.Preferences)).provider = "openai"
        db.commit()

    async def generated(org, purpose, system, text, **kwargs):
        assert purpose == "chat_plan"
        context = json.loads(text)
        assert context["previous_month"] == {"date_from": "2025-02-01", "date_to": "2025-02-28"}
        return {
            "queries": [],
            "clarification": "",
            "open_receipt": {
                "query": {"kind": "receipts", **context["previous_month"]},
                "order": "largest",
            },
        }, "openai"

    monkeypatch.setattr(ai, "generate", generated)
    asyncio.run(
        assistant.answer(queued_job(client, text="Открой самый дорогой чек прошлого месяца"))
    )
    result = client.get("/api/chat").json()[-1]
    assert result["details"]["open_receipt_id"] == expected
    assert "235.08 MDL" in result["text"]
    with pytest.raises(ai.AIError, match="явно"):
        asyncio.run(assistant.answer(queued_job(client, text="Сколько стоят чеки?")))


def test_tool_catalogue_and_parameter_boundary():
    assert "POST /transactions" in catalogue(True)["operations"]
    assert "POST /transactions" not in catalogue(False)["operations"]
    assert "POST /receipts/{key}/comments" in catalogue(False)["operations"]
    for operation, params, mode in [
        ("GET /auth/me", {}, "read"),
        ("GET /preferences", {}, "read"),
        ("GET /receipts/{key}/image/{index}", {"key": "x", "index": 0}, "read"),
        ("POST /transactions", {}, "read"),
        ("GET /accounts", {"organization_id": "foreign"}, "read"),
        ("GET /receipts/{key}", {"key": "../../settings"}, "read"),
    ]:
        with pytest.raises(ValueError):
            prepared(
                APIRequest(operation=operation, parameters_json=json.dumps(params)), True, mode
            )
    assert safe_result(
        {"token": "secret", "raw_text": "secret", "files": ["secret"], "amount": 235.08}
    ) == {"amount": 235.08}


def test_read_tools_use_scoped_authenticated_apis_and_remove_sessions(client, owner):
    org = client.headers["X-Organization-ID"]
    with SessionLocal() as db:
        foreign = m.Organization(name="Private")
        db.add(foreign)
        db.flush()
        private_id = add_receipt(db, foreign.id, 9999).id
        sessions = db.scalar(select(func.count()).select_from(m.Session))
        db.commit()
    result = asyncio.run(
        read_requests(
            [
                APIRequest(operation="GET /accounts"),
                APIRequest(
                    operation="GET /receipts/{key}", parameters_json=json.dumps({"key": private_id})
                ),
            ],
            owner["id"],
            org,
            True,
        )
    )
    assert result[0]["status"] == 200 and result[0]["data"]
    assert result[1]["status"] == 404
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Session)) == sessions
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0


def proposal(client, owner, accounts, **details):
    action = {
        "operation": "POST /transactions",
        "parameters_json": "{}",
        "body_json": json.dumps(
            {
                "account_id": accounts[0]["id"],
                "amount": "25.50",
                "kind": "income",
                "occurred_on": "2025-02-15",
                "merchant": "Bonus",
            }
        ),
        "label": "Доход 25.50 MDL",
        "description": "Добавить доход на основной счёт",
    }
    with SessionLocal() as db:
        row = m.Message(
            organization_id=client.headers["X-Organization-ID"],
            role="assistant",
            text="Подготовлено",
            details={
                "actions": [action],
                "actor_id": owner["id"],
                "action_status": "pending",
                **details,
            },
        )
        db.add(row)
        db.commit()
        return row.id


def test_actions_are_explicit_once_only_and_keep_audit_actor(client, owner, accounts):
    key = proposal(client, owner, accounts)
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0
    response = client.post(f"/api/chat/{key}/actions", json={"confirm": True})
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "completed", response.text
    retry = client.post(f"/api/chat/{key}/actions", json={"confirm": True})
    assert retry.json() == response.json()
    with SessionLocal() as db:
        rows = list(db.scalars(select(m.Transaction)))
        assert len(rows) == 1 and rows[0].amount_minor == 2550
        assert (
            db.scalar(select(m.Audit).where(m.Audit.action == "chat.actions_confirmed")).actor_id
            == owner["id"]
        )
    cancelled = proposal(client, owner, accounts)
    assert (
        client.post(f"/api/chat/{cancelled}/actions", json={"confirm": False}).json()["status"]
        == "cancelled"
    )
    assert (
        client.post(f"/api/chat/{cancelled}/actions", json={"confirm": True}).json()["status"]
        == "cancelled"
    )


def test_actions_recheck_role_origin_and_scope(client, owner, accounts):
    key = proposal(client, owner, accounts)
    assert (
        client.post(
            f"/api/chat/{key}/actions", json={"confirm": True}, headers={"X-CSRF-Token": "bad"}
        ).status_code
        == 403
    )
    assert (
        client.post(
            f"/api/chat/{key}/actions",
            json={"confirm": True},
            headers={"X-Organization-ID": str(uuid4())},
        ).status_code
        == 403
    )
    with SessionLocal() as db:
        db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"])).role = "user"
        db.commit()
    assert client.post(f"/api/chat/{key}/actions", json={"confirm": True}).status_code == 403
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0


def test_shared_chat_members_cannot_confirm_another_users_proposal(client, owner, accounts):
    key = proposal(client, owner, accounts)
    with SessionLocal() as db:
        member = m.User(
            username="another", name="Another", password_hash=hasher.hash("long-test-password")
        )
        db.add(member)
        db.flush()
        db.add(
            m.Membership(
                user_id=member.id, organization_id=client.headers["X-Organization-ID"], role="admin"
            )
        )
        db.commit()
    login = client.post(
        "/api/auth/login",
        json={"username": "another", "password": "long-test-password"},
        headers={"X-Finora-Client": "web"},
    )
    client.headers["X-CSRF-Token"] = login.json()["csrf"]
    assert client.post(f"/api/chat/{key}/actions", json={"confirm": True}).status_code == 403


def test_stale_and_failed_batch_are_not_replayed(client, owner, accounts):
    key = proposal(client, owner, accounts)
    with SessionLocal() as db:
        db.get(m.Message, key).created_at = datetime.now(UTC) - timedelta(hours=1)
        db.commit()
    assert client.post(f"/api/chat/{key}/actions", json={"confirm": True}).status_code == 409
    failed = proposal(client, owner, accounts)
    with SessionLocal() as db:
        row = db.get(m.Message, failed)
        actions = row.details["actions"]
        actions[0]["body_json"] = json.dumps(
            {"account_id": str(uuid4()), "amount": "25.50", "occurred_on": "2025-02-15"}
        )
        row.details = {**row.details, "actions": actions + actions}
        db.commit()
    result = client.post(f"/api/chat/{failed}/actions", json={"confirm": True}).json()
    assert result["status"] == "failed" and len(result["results"]) == 1
    assert client.post(f"/api/chat/{failed}/actions", json={"confirm": True}).json() == result


def test_generic_planning_reads_before_proposing_without_writing(
    client, owner, accounts, monkeypatch
):
    calls = []
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "openai"
        db.commit()

    async def generated(org, purpose, system, text, **kwargs):
        calls.append(purpose)
        context = json.loads(text)
        if not context["tool_results"]:
            return {
                "queries": [],
                "clarification": "",
                "read_requests": [{"operation": "GET /accounts"}],
            }, "openai"
        account = context["tool_results"][0]["data"][0]
        return {
            "queries": [],
            "clarification": "Подготовил доход",
            "actions": [
                {
                    "operation": "POST /transactions",
                    "parameters_json": "{}",
                    "body_json": json.dumps(
                        {
                            "account_id": account["id"],
                            "amount": "25.50",
                            "occurred_on": "2025-02-15",
                            "kind": "income",
                        }
                    ),
                    "label": "Доход",
                    "description": "Добавить 25.50 MDL",
                }
            ],
        }, "openai"

    monkeypatch.setattr(ai, "generate", generated)
    asyncio.run(
        assistant.answer(queued_job(client, text="Добавь доход 25.50 на основной счёт 15 февраля"))
    )
    result = client.get("/api/chat").json()[-1]
    assert calls == ["chat_plan", "chat_plan"]
    assert result["details"]["action_status"] == "pending"
    assert result["details"]["actions"][0]["body_json"]
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0


def test_concurrent_confirmations_create_only_one_non_idempotent_record(client, owner, accounts):
    from concurrent.futures import ThreadPoolExecutor

    key = proposal(client, owner, accounts)
    with SessionLocal() as db:
        row = db.get(m.Message, key)
        row.details = {
            **row.details,
            "actions": [
                {
                    "operation": "POST /accounts",
                    "parameters_json": "{}",
                    "body_json": json.dumps(
                        {
                            "name": "AI account",
                            "currency": "EUR",
                            "kind": "cash",
                            "opening_balance": "0",
                        }
                    ),
                    "label": "Новый счёт",
                    "description": "Создать один EUR счёт",
                }
            ],
        }
        db.commit()

    def confirm():
        return client.post(f"/api/chat/{key}/actions", json={"confirm": True})

    with ThreadPoolExecutor(max_workers=2) as pool:
        replies = list(pool.map(lambda _: confirm(), range(2)))
    assert all(row.status_code in {200, 409} for row in replies), [row.text for row in replies]
    with SessionLocal() as db:
        assert (
            db.scalar(
                select(func.count()).select_from(m.Account).where(m.Account.name == "AI account")
            )
            == 1
        )


def test_tool_read_rounds_are_bounded(client, monkeypatch):
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "openai"
        db.commit()
    calls = []

    async def generated(*args, **kwargs):
        calls.append(1)
        return {
            "queries": [],
            "clarification": "",
            "read_requests": [{"operation": "GET /accounts"}],
        }, "openai"

    monkeypatch.setattr(ai, "generate", generated)
    with pytest.raises(ai.AIError, match="слишком много"):
        asyncio.run(assistant.answer(queued_job(client, text="Проверь данные")))
    assert len(calls) == 4
    with SessionLocal() as db:
        assert not db.scalar(
            select(m.Session.id).where(m.Session.device == "Finora AI read-only tools")
        )
