import asyncio
from datetime import UTC, datetime
from uuid import uuid4

import pytest
from sqlalchemy import func, select

from app import ai, assistant
from app import models as m
from app.analytics import ReportQuery
from app.db import SessionLocal
from app.finance import create_transaction
from app.schemas import TransactionInput


def add_tx(client, accounts, **fields):
    response = client.post(
        "/api/transactions",
        json={
            "account_id": accounts[0]["id"],
            "occurred_on": "2025-02-15",
            "amount": "100",
            "idempotency_key": str(uuid4()),
            **fields,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def fetch_report(client, kind="summary", **filters):
    result = client.get(
        "/api/reports",
        params={"kind": kind, "date_from": "2025-02-01", "date_to": "2025-02-28", **filters},
    )
    assert result.status_code == 200, result.text
    return result.json()


def test_summary_categories_currency_refunds_and_partial_split(client, accounts, categories):
    add_tx(client, accounts, kind="income", amount="1000")
    expense = add_tx(
        client,
        accounts,
        amount="100",
        splits=[
            {"category_id": categories[0]["id"], "amount": "70"},
            {"category_id": categories[1]["id"], "amount": "30"},
        ],
    )
    add_tx(client, accounts, kind="refund", amount="10", refund_of=expense["id"])
    add_tx(client, accounts, kind="transfer", target_account_id=accounts[1]["id"], amount="50")
    add_tx(client, accounts, occurred_on="2025-01-15", amount="500")
    report = fetch_report(client)
    values = {v["label"]: v["value"] for v in report["metrics"]}
    assert values["Доходы"] == "1000.00 MDL"
    assert values["Расходы после возвратов"] == "90.00 MDL"
    assert values["Остаток доходов"] == "910.00 MDL"
    assert values["Операций"] == "3"
    partial = fetch_report(client, category_id=categories[0]["id"])
    assert partial["metrics"][1]["value"] == "63.00 MDL"
    rows = fetch_report(client, "categories")["rows"]
    assert [r["value"] for r in rows] == ["63.00 MDL", "27.00 MDL"]
    assert "Операций: 2" == rows[0]["detail"]
    assert fetch_report(client, currency="EUR")["metrics"][1]["value"] == "0.00 MDL"


def test_aggregates_cover_all_rows_not_just_top_twenty(client, accounts):
    with SessionLocal() as db:
        for number in range(31):
            create_transaction(
                db,
                client.headers["X-Organization-ID"],
                TransactionInput(
                    account_id=accounts[0]["id"],
                    amount="1",
                    occurred_on="2025-02-15",
                    merchant=f"Shop {number:02}",
                    idempotency_key=str(uuid4()),
                ),
            )
        db.commit()
    result = fetch_report(client, "merchants")
    assert result["total_rows"] == 31 and len(result["rows"]) == 20
    assert result["metrics"][1]["value"] == "31.00 MDL"
    assert fetch_report(client, "merchants", merchant="Shop 0")["total_rows"] == 10
    trend = fetch_report(client, "trend")
    assert trend["rows"][0]["label"] == "2025-02"
    assert trend["rows"][0]["value"] == "31.00 MDL"
    assert fetch_report(client, "merchants", merchant="%'")["total_rows"] == 0


def test_report_validation_and_foreign_categories(client):
    for params in [
        {"date_from": "2020-01-01"},
        {"date_to": "2024-01-01"},
        {"kind": "sql"},
        {"search": "milk"},
        {"organization_id": "other"},
    ]:
        response = client.get(
            "/api/reports",
            params={
                "kind": "summary",
                "date_from": "2025-02-01",
                "date_to": "2025-02-28",
                **params,
            },
        )
        assert response.status_code in {403, 422}
    response = client.get(
        "/api/reports",
        params={
            "kind": "summary",
            "date_from": "2025-02-01",
            "date_to": "2025-02-28",
            "category_id": str(uuid4()),
        },
    )
    assert response.status_code == 404


def queued_job(client, **fields):
    response = client.post("/api/chat", json={"month": "2025-02", "text": "Как дела?", **fields})
    assert response.status_code == 200, response.text
    with SessionLocal() as db:
        return db.get(m.Job, response.json()["job_id"])


def test_reports_work_without_ai_and_message_retry_is_idempotent(client, accounts):
    add_tx(client, accounts, amount="25")
    request_key = str(uuid4())
    job = queued_job(client, report="summary", request_key=request_key)
    assert queued_job(client, report="summary", request_key=request_key).id == job.id
    assert (
        client.post(
            "/api/chat",
            json={
                "month": "2025-02",
                "text": "Другой запрос",
                "report": "summary",
                "request_key": request_key,
            },
        ).status_code
        == 409
    )
    asyncio.run(assistant.answer(job))
    asyncio.run(assistant.answer(job))
    messages = client.get("/api/chat")
    assert messages.status_code == 200
    assert len(messages.json()) == 2
    answer = messages.json()[-1]
    assert answer["details"]["provider"] == "reports"
    assert answer["details"]["reports"][0]["metrics"][1]["value"] == "25.00 MDL"
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 1
        assert db.scalar(select(func.count()).select_from(m.AIUsage)) == 0


def test_plan_and_explanation_keep_context_scoped_and_fallback_to_real_results(
    client, accounts, monkeypatch
):
    add_tx(client, accounts, amount="42")
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "openai"
        db.add(
            m.Message(
                organization_id=client.headers["X-Organization-ID"],
                role="user",
                text="Посмотри февраль",
            )
        )
        db.commit()
    seen = []

    async def generated(org, purpose, system, text, **kwargs):
        seen.append((org, purpose, text))
        if purpose == "chat_plan":
            assert "Посмотри февраль" in text
            assert kwargs["schema"]["additionalProperties"] is False
            return {
                "queries": [
                    {"kind": "summary", "date_from": "2025-02-01", "date_to": "2025-02-28"}
                ],
                "clarification": "",
            }, "openai"
        assert "42.00 MDL" in text
        raise ai.AIError("Provider unavailable")

    monkeypatch.setattr(ai, "generate", generated)
    asyncio.run(assistant.answer(queued_job(client)))
    answer = client.get("/api/chat").json()[-1]
    assert len(seen) == 2 and all(v[0] == client.headers["X-Organization-ID"] for v in seen)
    assert answer["details"]["provider"] == "reports"
    assert answer["details"]["reports"][0]["metrics"][1]["value"] == "42.00 MDL"


def test_revoked_requester_never_reaches_provider(client, owner, monkeypatch):
    job = queued_job(client, report="summary")
    with SessionLocal() as db:
        db.get(m.User, owner["id"]).is_active = False
        db.commit()

    async def unavailable(*args, **kwargs):
        pytest.fail("Revoked actor reached provider")

    monkeypatch.setattr(ai, "generate", unavailable)
    with pytest.raises(ai.AIError, match="Доступ"):
        asyncio.run(assistant.answer(job))


def test_chat_cursor_handles_equal_timestamps(client):
    stamp = datetime(2025, 1, 1, tzinfo=UTC)
    with SessionLocal() as db:
        db.add_all(
            m.Message(
                id=f"{i:036}",
                organization_id=client.headers["X-Organization-ID"],
                role="user",
                text=f"Message {i}",
                created_at=stamp,
            )
            for i in range(73)
        )
        db.commit()
    recent = client.get("/api/chat").json()
    earlier = client.get("/api/chat", params={"before": recent[0]["id"]}).json()
    assert len(recent) == 60 and len(earlier) == 13
    assert len({v["id"] for v in recent + earlier}) == 73


def test_planner_rejects_write_tools_and_unbounded_queries():
    with pytest.raises(ValueError):
        assistant.QueryPlan.model_validate({"queries": [], "clarification": "", "sql": "SELECT 1"})
    with pytest.raises(ValueError):
        ReportQuery(kind="summary", date_from="2020-01-01", date_to="2025-01-01")
