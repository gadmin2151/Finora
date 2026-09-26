"""Language is presentation context, never an organization or financial-data mutation."""

import ast
import asyncio
from pathlib import Path
from string import Formatter
from uuid import uuid4

import pytest
from sqlalchemy import select

from app import ai, assistant
from app import models as m
from app.db import SessionLocal
from app.i18n import (
    ENGLISH,
    LocaleMiddleware,
    current_language,
    language_context,
    resolve_language,
    t,
)
from app.receipts import receipt_dict


@pytest.mark.parametrize(
    ("header", "expected"),
    [
        (None, "ru"),
        ("", "ru"),
        ("ru-MD", "ru"),
        ("EN-us", "en"),
        ("ro-MD", "en"),
        ("en-GB;q=0.8,ru-RU;q=0.9", "ru"),
        ("ru;q=0,en", "en"),
        ("ru;q=NaN,en", "en"),
        ("ru;q=invalid", "en"),
        ("en,ru", "en"),
    ],
)
def test_language_negotiation(header, expected):
    assert resolve_language(header) == expected


def test_locale_is_isolated_between_concurrent_tasks_and_reset_after_failure():
    async def run():
        async def request(language):
            with language_context(language):
                await asyncio.sleep(0)
                return current_language(), t("Запись не найдена")

        results = await asyncio.gather(request("ru"), request("en"), request("en"))
        assert results == [
            ("ru", "Запись не найдена"),
            ("en", "Record not found"),
            ("en", "Record not found"),
        ]
        assert current_language() == "ru"
        with pytest.raises(RuntimeError), language_context("en"):
            raise RuntimeError("stop")
        assert current_language() == "ru"

    asyncio.run(run())


def test_asgi_scope_covers_streaming_and_resets():
    async def run():
        sent = []

        async def endpoint(scope, receive, send):
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await asyncio.sleep(0)
            await send({"type": "http.response.body", "body": t("Готово").encode()})

        async def send(message):
            sent.append(message)

        async def receive():
            return {"type": "http.request", "body": b""}

        await LocaleMiddleware(endpoint)(
            {"type": "http", "path": "/api/export.csv", "headers": [(b"accept-language", b"en")]},
            receive,
            send,
        )
        assert (b"content-language", b"en") in sent[0]["headers"]
        assert sent[1]["body"] == b"Done"
        assert current_language() == "ru"

    asyncio.run(run())


def test_validation_security_and_domain_errors_follow_language(client):
    for language, bad_password, invalid_fields, invalid_origin in [
        (
            "en-US",
            "Incorrect username or password",
            "Check these fields:",
            "Invalid application origin",
        ),
        (
            "ru-RU",
            "Неверный логин или пароль",
            "Проверьте заполнение полей:",
            "Недопустимый адрес приложения",
        ),
    ]:
        headers = {"Accept-Language": language, "X-Finora-Client": "web"}
        response = client.post(
            "/api/auth/login",
            json={"username": "absent", "password": "invalid password"},
            headers=headers,
        )
        assert response.status_code == 401
        assert response.json()["detail"] == bad_password
        assert response.headers["Content-Language"] == language[:2]
        invalid = client.post("/api/auth/login", json={}, headers=headers)
        assert invalid.status_code == 422
        assert invalid.json()["detail"].startswith(invalid_fields)
        forbidden = client.post(
            "/api/auth/login", json={}, headers={**headers, "Origin": "https://invalid.example"}
        )
        assert forbidden.status_code == 403
        assert forbidden.json()["detail"] == invalid_origin
    # A later legacy request is not affected by an English client.
    legacy = client.post("/api/auth/login", json={})
    assert legacy.json()["detail"].startswith("Проверьте заполнение полей:")


def test_request_size_errors_are_localized_before_the_body_is_read(client):
    response = client.post(
        "/api/auth/login",
        content=b"{}",
        headers={"content-length": "999999999", "Accept-Language": "en"},
    )
    assert response.status_code == 413
    assert response.json()["detail"] == "The request is too large"
    assert response.headers["Content-Language"] == "en"
    assert "Accept-Language" in response.headers["Vary"]


def test_reports_and_csv_translate_labels_without_changing_user_data(client, accounts):
    merchant = "Русский магазин {not_a_placeholder}"
    response = client.post(
        "/api/transactions",
        json={
            "account_id": accounts[0]["id"],
            "occurred_on": "2025-02-15",
            "amount": "15.20",
            "merchant": merchant,
            "idempotency_key": str(uuid4()),
        },
    )
    assert response.status_code == 200
    params = {"kind": "merchants", "date_from": "2025-02-01", "date_to": "2025-02-28"}
    en = client.get("/api/reports", params=params, headers={"Accept-Language": "en"}).json()
    ru = client.get("/api/reports", params=params).json()
    assert en["title"] == "Expenses by merchant"
    assert ru["title"] == "Расходы по магазинам"
    assert en["rows"][0] == {
        "label": merchant,
        "value": "15.20 MDL",
        "detail": "Transactions: 1",
        "receipt_id": None,
    }
    assert [metric["value"] for metric in en["metrics"]] == [
        metric["value"] for metric in ru["metrics"]
    ]
    csv = client.get("/api/export.csv", headers={"Accept-Language": "en"})
    assert "Date,Type,Amount,Currency,Amount in MDL,Merchant,Note" in csv.text
    assert merchant in csv.text
    download = client.get("/api/export.csv?language=en", headers={"Accept-Language": "ru"})
    assert "Date,Type,Amount" in download.text
    assert download.headers["Content-Language"] == "en"
    fallback = client.get("/api/export.csv?language=invalid", headers={"Accept-Language": "ru"})
    assert "Дата,Тип,Сумма" in fallback.text
    assert (
        client.get("/api/accounts", headers={"Accept-Language": "en"}).json()
        == client.get("/api/accounts").json()
    )


def test_receipt_notices_localize_without_rewriting_merchant_items_or_storage(client):
    organization_id = client.headers["X-Organization-ID"]
    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=organization_id,
            source="photo",
            source_key=str(uuid4()),
            merchant="Мой магазин",
            warnings=["Проверьте валюту чека", "Проверьте количество, цену и скидку: Молоко {1}"],
            original={},
            file_names=[],
        )
        db.add(receipt)
        db.flush()
        original = list(receipt.warnings)
        with language_context("en"):
            result = receipt_dict(db, receipt)
        assert result["merchant"] == "Мой магазин"
        assert result["warnings"] == [
            "Check the receipt currency",
            "Check the quantity, price and discount: Молоко {1}",
        ]
        assert result["error"] is None
        assert receipt.warnings == original


def test_queued_assistant_retains_request_language_and_history(client, monkeypatch):
    question = "Покажи мои расходы, пожалуйста"
    response = client.post(
        "/api/chat",
        json={"month": "2025-02", "text": question, "report": "summary"},
        headers={"Accept-Language": "en"},
    )
    assert response.status_code == 200
    with SessionLocal() as db:
        job = db.get(m.Job, response.json()["job_id"])
        assert job.payload["language"] == "en"
    assert (
        client.get("/api/jobs", headers={"Accept-Language": "en"}).json()[0]["progress"] == "Queued"
    )
    assert client.get("/api/jobs").json()[0]["progress"] == "В очереди"
    asyncio.run(assistant.answer(job))
    messages = client.get("/api/chat").json()
    assert messages[0]["text"] == question
    assert messages[-1]["text"].startswith("Done.")
    assert messages[-1]["details"]["reports"][0]["title"] == "Financial summary"
    assert current_language() == "ru"


def test_ai_instructions_follow_the_job_language(client, monkeypatch):
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "openai"
        db.commit()
    prompts = []

    async def generate(org, purpose, system, text, **kwargs):
        prompts.append(system)
        if purpose == "chat_plan":
            return {"queries": [], "clarification": "Which month?"}, "openai"
        pytest.fail("No report was requested")

    monkeypatch.setattr(ai, "generate", generate)
    result = client.post(
        "/api/chat",
        json={"month": "2025-02", "text": "What happened?"},
        headers={"Accept-Language": "en"},
    )
    with SessionLocal() as db:
        job = db.get(m.Job, result.json()["job_id"])
    asyncio.run(assistant.answer(job))
    assert "Write the answer and clarification in English" in prompts[0]
    assert client.get("/api/chat").json()[-1]["text"] == "Which month?"


def test_translation_catalog_covers_static_public_errors_and_preserves_placeholders():
    root = Path(__file__).parents[1] / "app"
    error_factories = {"fail", "HTTPException", "AIError", "ReceiptError", "WebReceiptError", "t"}
    for path in root.glob("*.py"):
        for node in ast.walk(ast.parse(path.read_text())):
            if (
                not isinstance(node, ast.Call)
                or ast.unparse(node.func).split(".")[-1] not in error_factories
            ):
                continue
            for argument in node.args:
                if (
                    isinstance(argument, ast.Constant)
                    and isinstance(argument.value, str)
                    and any("А" <= ch <= "я" or ch in "Ёё" for ch in argument.value)
                ):
                    assert argument.value in ENGLISH, (
                        f"Missing public error translation: {path}:{argument.lineno}"
                    )
    formatter = Formatter()
    for source, translated in ENGLISH.items():
        source_fields = {field for _, field, _, _ in formatter.parse(source) if field is not None}
        translated_fields = {
            field for _, field, _, _ in formatter.parse(translated) if field is not None
        }
        assert source_fields == translated_fields, source
