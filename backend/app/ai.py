import base64
import json
from datetime import UTC, datetime

import httpx
from sqlalchemy import func, select

from . import models as m
from .config import settings
from .db import SessionLocal
from .finance import lock_organization
from .i18n import t
from .security import decrypt

MODEL_CATALOG = [
    {
        "name": "qwen3:0.6b",
        "title": "Qwen 3 · 0.6B",
        "size": "0,5 ГБ",
        "ram": "от 2 ГБ",
        "vision": False,
        "description": "Для проверки подключения и простых запросов. Ограниченное качество.",
    },
    {
        "name": "qwen2.5:1.5b",
        "title": "Qwen 2.5 · 1.5B",
        "size": "1 ГБ",
        "ram": "от 4 ГБ",
        "vision": False,
        "description": "Компактный помощник для текста и категорий.",
    },
    {
        "name": "qwen3:4b-instruct",
        "title": "Qwen 3 · 4B Instruct",
        "size": "2,5 ГБ",
        "ram": "от 8 ГБ",
        "vision": False,
        "description": "Анализ расходов и рекомендации на CPU.",
    },
    {
        "name": "gemma3:4b",
        "title": "Gemma 3 · 4B Vision",
        "size": "3,3 ГБ",
        "ram": "от 8 ГБ",
        "vision": True,
        "description": "Текст и фотографии чеков. Требуется больше времени на CPU.",
    },
]


class AIError(Exception):
    def __init__(self, message: str):
        super().__init__(t(message))


def reserve_request(organization_id: str, purpose: str):
    with SessionLocal() as db:
        lock_organization(db, organization_id)
        prefs = db.scalar(
            select(m.Preferences).where(m.Preferences.organization_id == organization_id)
        )
        if prefs.provider == "disabled":
            raise AIError("AI отключён. Выберите локальную модель или OpenAI в настройках.")
        start = datetime.now(UTC).replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        count = db.scalar(
            select(func.count())
            .select_from(m.AIUsage)
            .where(m.AIUsage.organization_id == organization_id, m.AIUsage.created_at >= start)
        )
        if count >= prefs.monthly_request_limit:
            raise AIError("Достигнут установленный лимит AI-запросов за месяц.")
        usage = m.AIUsage(organization_id=organization_id, provider=prefs.provider, purpose=purpose)
        db.add(usage)
        db.commit()
        return prefs, usage.id


async def generate(
    organization_id: str,
    purpose: str,
    system: str,
    text: str,
    schema: dict | None = None,
    images: list[bytes] | None = None,
):
    prefs, usage_id = reserve_request(organization_id, purpose)
    input_tokens = output_tokens = 0
    try:
        async with httpx.AsyncClient(
            timeout=settings().ai_timeout, follow_redirects=False
        ) as client:
            if prefs.provider == "ollama":
                model = prefs.vision_model if images else prefs.model
                message = {"role": "user", "content": text}
                if images:
                    message["images"] = [base64.b64encode(img).decode() for img in images]
                body = {
                    "model": model,
                    "messages": [{"role": "system", "content": system}, message],
                    "stream": False,
                    "options": {
                        "num_gpu": 0,
                        "num_ctx": 8192 if schema else 4096,
                        "num_predict": 4096 if schema else 16 if purpose == "test" else 640,
                        "num_thread": settings().ai_cpu_threads,
                        "temperature": 0,
                    },
                    "keep_alive": "2m",
                }
                if model.startswith("qwen3") and "instruct" not in model:
                    body["think"] = False
                if schema:
                    body["format"] = schema
                response = await client.post(
                    settings().ollama_url.rstrip("/") + "/api/chat", json=body
                )
                if response.status_code == 404:
                    raise AIError("Модель ещё не загружена. Откройте настройки локального AI.")
                if response.is_error:
                    raise AIError(
                        "Локальная модель не ответила. Проверьте память сервера и выбранную модель."
                    )
                result = response.json()
                if result.get("done_reason") == "length":
                    raise AIError(
                        "Модель не завершила ответ. Сократите запрос или выберите Instruct-модель в настройках."
                    )
                content = result["message"]["content"]
                input_tokens, output_tokens = (
                    result.get("prompt_eval_count", 0),
                    result.get("eval_count", 0),
                )
            else:
                if not prefs.openai_key:
                    raise AIError("Добавьте API-ключ OpenAI в настройках.")
                parts = [{"type": "input_text", "text": text}]
                if images:
                    parts.extend(
                        {
                            "type": "input_image",
                            "image_url": "data:image/jpeg;base64," + base64.b64encode(img).decode(),
                            "detail": "high",
                        }
                        for img in images
                    )
                body = {
                    "model": prefs.vision_model if images else prefs.model,
                    "instructions": system,
                    "input": [{"role": "user", "content": parts}],
                    "store": False,
                    "max_output_tokens": 6000,
                }
                if schema:
                    body["text"] = {
                        "format": {
                            "type": "json_schema",
                            "name": purpose,
                            "strict": True,
                            "schema": schema,
                        }
                    }
                response = await client.post(
                    "https://api.openai.com/v1/responses",
                    json=body,
                    headers={"Authorization": "Bearer " + decrypt(prefs.openai_key)},
                )
                if response.status_code == 401:
                    raise AIError("OpenAI отклонил API-ключ. Проверьте его в настройках.")
                if response.status_code == 429:
                    raise AIError("OpenAI: ограничение запросов или недостаточно средств API.")
                if response.is_error:
                    raise AIError(
                        "Запрос к OpenAI не выполнен. Проверьте модель и доступ проекта API."
                    )
                result = response.json()
                if result.get("status") == "incomplete":
                    raise AIError("Ответ AI не завершён. Попробуйте меньший чек или другую модель.")
                content = "".join(
                    part.get("text", "")
                    for block in result.get("output", [])
                    for part in block.get("content", [])
                    if part.get("type") == "output_text"
                )
                input_tokens = result.get("usage", {}).get("input_tokens", 0)
                output_tokens = result.get("usage", {}).get("output_tokens", 0)
            if not content.strip():
                raise AIError("Модель не вернула результат. Попробуйте другую модель.")
            parsed = json.loads(content) if schema else content
        with SessionLocal() as db:
            usage = db.get(m.AIUsage, usage_id)
            usage.success, usage.input_tokens, usage.output_tokens = (
                True,
                input_tokens,
                output_tokens,
            )
            db.commit()
        return parsed, prefs.provider
    except (httpx.TimeoutException, httpx.ConnectError) as exc:
        raise AIError(
            "AI не успел ответить. На CPU обработка может быть медленной; проверьте доступность модели."
        ) from exc
    except (json.JSONDecodeError, KeyError, TypeError) as exc:
        raise AIError("Модель вернула неполный результат. Запись сохранена для проверки.") from exc


async def ollama_models():
    catalog = [
        {
            key: t(value) if key in {"size", "ram", "description"} else value
            for key, value in item.items()
        }
        for item in MODEL_CATALOG
    ]
    try:
        async with httpx.AsyncClient(timeout=8) as client:
            response = await client.get(settings().ollama_url.rstrip("/") + "/api/tags")
            response.raise_for_status()
            return {
                "online": True,
                "models": [
                    {"name": row["name"], "size": row.get("size", 0)}
                    for row in response.json().get("models", [])
                ],
                "catalog": catalog,
            }
    except (httpx.HTTPError, ValueError, KeyError):
        return {"online": False, "models": [], "catalog": catalog}
