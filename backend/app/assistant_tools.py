"""Discover existing finance APIs; reads are scoped, writes are explicit proposals."""

import asyncio
import hashlib
import json
import re
import secrets
from datetime import UTC, datetime, timedelta
from typing import Literal
from urllib.parse import quote
from uuid import NAMESPACE_URL, uuid5

import httpx
from pydantic import Field
from sqlalchemy import delete, update
from sqlalchemy.orm import Session

from . import models as m
from .config import settings
from .db import SessionLocal
from .finance import audit, fail, owned
from .i18n import current_language, t
from .schemas import Strict

MAX_ACTIONS = 6
MAX_READ_ROUNDS = 3


class ActionDecision(Strict):
    confirm: bool


ROOTS = {
    "accounts",
    "transactions",
    "categories",
    "debts",
    "bills",
    "income",
    "receipts",
    "purchases",
    "reports",
    "budgets",
}
REDACTED = {
    "password",
    "password_hash",
    "csrf",
    "token",
    "token_hash",
    "openai_key",
    "secret_key",
    "source_url",
    "source_key",
    "metadata",
    "raw_text",
    "files",
    "original",
    "file_names",
}


class APIRequest(Strict):
    operation: str = Field(min_length=1, max_length=200)
    parameters_json: str = Field(default="{}", max_length=2048)
    body_json: str = Field(default="{}", max_length=12000)


class ProposedAction(APIRequest):
    label: str = Field(min_length=1, max_length=120)
    description: str = Field(min_length=1, max_length=600)


def catalogue(admin: bool) -> dict:
    from .main import app

    spec = app.openapi()
    entries = {}
    for path, methods in spec["paths"].items():
        if not path.startswith("/api/") or path.split("/")[2] not in ROOTS:
            continue
        if any(part in path for part in ("/files", "/original", "/image")):
            continue
        for method, operation in methods.items():
            if method not in {"get", "post", "put", "patch", "delete"}:
                continue
            if (
                method != "get"
                and not admin
                and not (
                    method == "post"
                    and (
                        path.endswith(("/comments", "/accept", "/review"))
                        or path == "/api/receipts/link"
                    )
                )
            ):
                continue
            body = operation.get("requestBody", {}).get("content", {})
            if body and "application/json" not in body:
                continue
            key = f"{method.upper()} {path.removeprefix('/api')}"
            schema = body.get("application/json", {}).get("schema", {})
            if "$ref" in schema:
                schema = spec["components"]["schemas"][schema["$ref"].split("/")[-1]]
            entries[key] = {
                "parameters": operation.get("parameters", []),
                "body": schema,
                "summary": operation.get("summary", ""),
            }
    # Include only referenced DTOs, never account/credential administration schemas.
    needed = set()

    def collect(value):
        if isinstance(value, dict):
            if "$ref" in value:
                name = value["$ref"].split("/")[-1]
                if name not in needed:
                    needed.add(name)
                    collect(spec["components"]["schemas"].get(name, {}))
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    collect(entries)
    return {
        "operations": entries,
        "schemas": {k: spec["components"]["schemas"][k] for k in sorted(needed)},
    }


def prepared(request: APIRequest, admin: bool, mode: Literal["read", "write"], seed: str = ""):
    entries = catalogue(admin)["operations"]
    if request.operation not in entries:
        raise ValueError("Действие недоступно для вашей роли")
    method, path = request.operation.split(" ", 1)
    if (method == "GET") != (mode == "read"):
        raise ValueError("Изменение данных требует подтверждения")
    parameters, body = json.loads(request.parameters_json), json.loads(request.body_json)
    if not isinstance(parameters, dict) or not isinstance(body, dict):
        raise ValueError("Параметры действия должны быть объектом")
    entry = entries[request.operation]
    declared = {p["name"]: p for p in entry["parameters"]}
    if (
        set(parameters) - set(declared)
        or "organization_id" in parameters
        or "organization_id" in body
    ):
        raise ValueError("Нельзя менять организацию через действие помощника")
    query = {}
    for name, definition in declared.items():
        if definition.get("required") and name not in parameters:
            raise ValueError("Не все параметры действия указаны")
        if name not in parameters:
            continue
        value = parameters[name]
        if isinstance(value, (dict, list)) or value is None:
            raise ValueError("Некорректный параметр действия")
        if definition["in"] == "path":
            if not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", str(value)):
                raise ValueError("Некорректный идентификатор записи")
            path = path.replace("{" + name + "}", quote(str(value), safe=""))
        else:
            query[name] = value
    if "{" in path:
        raise ValueError("Не все параметры действия указаны")
    if mode == "read":
        if "limit" in declared:
            query["limit"] = max(1, min(int(query.get("limit", 20)), 20))
        body = None
    else:
        fields = entry["body"].get("properties", {})
        for key in ("idempotency_key", "request_key"):
            if key in fields:
                body[key] = str(uuid5(NAMESPACE_URL, seed))
    return method, "/api" + path, query, body


def safe_result(value, depth: int = 0):
    if depth > 12:
        return "[truncated]"
    if isinstance(value, dict):
        return {k: safe_result(v, depth + 1) for k, v in value.items() if k.lower() not in REDACTED}
    if isinstance(value, list):
        return [safe_result(v, depth + 1) for v in value[:20]]
    if isinstance(value, str):
        return re.sub(r"https?://\S+", "[URL]", value[:600])
    return value


async def dispatch(
    app,
    request: APIRequest,
    admin: bool,
    mode: Literal["read", "write"],
    token: str,
    csrf: str,
    org: str,
    seed: str = "",
):
    method, path, parameters, body = prepared(request, admin, mode, seed)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=settings().app_url,
        headers={
            "X-Finora-Client": "web",
            "Origin": settings().app_url,
            "X-Organization-ID": org,
            "X-CSRF-Token": csrf,
            "Accept-Language": current_language(),
        },
        cookies={"finance_session": token},
    ) as client:
        response = await client.request(method, path, params=parameters, json=body)
    try:
        value = response.json()
    except ValueError:
        value = {"detail": "Ответ не содержит JSON"}
    return {
        "operation": request.operation,
        "status": response.status_code,
        "data": safe_result(value),
        "row_limit": 20,
    }


async def read_requests(
    requests: list[APIRequest], actor: str, org: str, admin: bool
) -> list[dict]:
    from .main import app

    token, csrf = secrets.token_urlsafe(48), secrets.token_hex(24)
    with SessionLocal() as db:
        session = m.Session(
            user_id=actor,
            token_hash=hashlib.sha256(token.encode()).hexdigest(),
            csrf=csrf,
            expires_at=datetime.now(UTC) + timedelta(minutes=2),
            device="Finora AI read-only tools",
        )
        db.add(session)
        db.commit()
        session_id = session.id
    results = []
    try:
        for request in requests:
            try:
                async with asyncio.timeout(25):
                    result = await dispatch(app, request, admin, "read", token, csrf, org)
            except (ValueError, httpx.HTTPError, TimeoutError):
                result = {
                    "operation": request.operation,
                    "status": 422,
                    "data": {"detail": "Не удалось прочитать данные. Уточните параметры."},
                }
            # Bound context size while retaining an explicit incompleteness marker.
            encoded = json.dumps(result, ensure_ascii=False)
            results.append(
                result
                if len(encoded) <= 8000
                else {
                    "operation": request.operation,
                    "status": result["status"],
                    "truncated": True,
                    "preview": encoded[:8000],
                }
            )
        return results
    finally:
        with SessionLocal() as db:
            db.execute(delete(m.Session).where(m.Session.id == session_id))
            db.commit()


def reviewed_action(action: ProposedAction, admin: bool) -> dict:
    """Persist the exact normalized values the user will approve, not a model-only summary."""
    _, _, _, body = prepared(action, admin, "write")
    original_parameters = json.loads(action.parameters_json)
    # Idempotency is assigned on execution; it is not editable by the model or client.
    for key in ("idempotency_key", "request_key"):
        body.pop(key, None)
    return {
        **action.model_dump(),
        "parameters_json": json.dumps(original_parameters, ensure_ascii=False),
        "body_json": json.dumps(body, ensure_ascii=False),
    }


async def execute_actions(
    db: Session, org: str, actor: str, admin: bool, key: str, confirm: bool, token: str, csrf: str
) -> dict:
    """Claim an immutable proposal once, then invoke the existing authenticated APIs."""
    from .main import app

    row = owned(db, m.Message, key, org, True)
    details = dict(row.details)
    if row.role != "assistant" or not details.get("actions"):
        fail("В этом сообщении нет действий", 422)
    if details.get("actor_id") != actor:
        fail("Выполнить предложение может только автор запроса", 403)
    status = details.get("action_status")
    if status in {"completed", "cancelled", "failed"}:
        return {"status": status, "results": details.get("action_results", [])}
    if status != "pending":
        fail("Действия уже выполняются. Обновите чат; повторное выполнение заблокировано", 409)

    def claim(next_status: str):
        changed = db.execute(
            update(m.Message)
            .where(
                m.Message.id == key,
                m.Message.organization_id == org,
                m.Message.details["action_status"].as_string() == "pending",
            )
            .values(details={**details, "action_status": next_status, "action_results": []})
            .execution_options(synchronize_session=False)
        ).rowcount
        if changed != 1:
            fail("Действия уже выполняются. Обновите чат; повторное выполнение заблокировано", 409)

    if not confirm:
        claim("cancelled")
        db.commit()
        return {"status": "cancelled", "results": []}
    created = (
        row.created_at.replace(tzinfo=UTC) if row.created_at.tzinfo is None else row.created_at
    )
    if created < datetime.now(UTC) - timedelta(minutes=30):
        fail("Предложение устарело. Повторите запрос, чтобы проверить актуальные данные", 409)
    actions = [ProposedAction.model_validate(value) for value in details["actions"]]
    if len(actions) > MAX_ACTIONS:
        fail("Слишком много действий в предложении", 422)
    try:
        for index, action in enumerate(actions):
            prepared(action, admin, "write", f"{key}:{index}")
    except ValueError:
        fail("Действие недоступно для вашей роли. Повторите запрос", 403)
    org_id = org
    # The scope dependency locks the organization. Release it before invoking APIs
    # which take that same lock. The committed claim prevents duplicate execution.
    claim("executing")
    audit(db, org_id, "chat.actions_confirmed", key, {"count": len(actions)})
    db.commit()
    results = []
    deadline = asyncio.get_running_loop().time() + 35
    for index, action in enumerate(actions):
        try:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise TimeoutError
            async with asyncio.timeout(min(25, remaining)):
                result = await dispatch(
                    app,
                    action,
                    admin,
                    "write",
                    token,
                    csrf,
                    org_id,
                    f"{key}:{index}",
                )
            success = 200 <= result["status"] < 300
            detail = result["data"].get("detail") if isinstance(result["data"], dict) else None
            results.append(
                {
                    "label": action.label,
                    "status": result["status"],
                    "detail": t("Выполнено")
                    if success
                    else detail or t("Не удалось выполнить действие"),
                }
            )
        except (httpx.HTTPError, TimeoutError):
            success = False
            results.append(
                {
                    "label": action.label,
                    "status": 504,
                    "detail": t("Ответ не получен. Проверьте запись перед повторным запросом."),
                }
            )
        row = owned(db, m.Message, key, org_id, True)
        row.details = {**row.details, "action_results": results}
        db.commit()
        if not success:
            break
    status = (
        "completed"
        if len(results) == len(actions) and all(200 <= r["status"] < 300 for r in results)
        else "failed"
    )
    row = owned(db, m.Message, key, org_id, True)
    row.details = {**row.details, "action_status": status, "action_results": results}
    db.commit()
    return {"status": status, "results": results}
