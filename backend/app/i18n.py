"""Translate application-owned text without rewriting financial or user-entered data."""

import json
import math
import re
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from pathlib import Path
from typing import Literal
from urllib.parse import parse_qs

from starlette.types import ASGIApp, Message, Receive, Scope, Send

Language = Literal["ru", "en"]
_language: ContextVar[Language] = ContextVar("finora_language", default="ru")
ENGLISH: dict[str, str] = json.loads(
    Path(__file__).with_name("translations.json").read_text(encoding="utf-8")
)
RUSSIAN = {english: source for source, english in ENGLISH.items()}


def resolve_language(header: str | None) -> Language:
    """Keep legacy clients Russian; use English for unsupported explicit languages."""
    if not header:
        return "ru"
    choices: list[tuple[float, int, Language]] = []
    for index, part in enumerate(header[:1024].split(",")):
        tag, *parameters = part.strip().lower().split(";")
        primary = tag.replace("_", "-").split("-")[0]
        if primary not in {"ru", "en"}:
            continue
        quality = 1.0
        for parameter in parameters:
            if parameter.strip().startswith("q="):
                try:
                    quality = float(parameter.strip()[2:])
                except ValueError:
                    quality = 0.0
        if math.isfinite(quality) and 0 < quality <= 1:
            choices.append((quality, -index, "ru" if primary == "ru" else "en"))
    return max(choices)[2] if choices else "en"


def current_language() -> Language:
    return _language.get()


@contextmanager
def language_context(language: str | None) -> Iterator[None]:
    token = _language.set(resolve_language(language))
    try:
        yield
    finally:
        _language.reset(token)


def t(message: str, **values: object) -> str:
    template = ENGLISH.get(message, message) if current_language() == "en" else message
    return template.format_map(values) if values else template


def unit_label(unit: str) -> str:
    """Format known receipt units while retaining canonical units in stored data."""
    if current_language() != "en":
        return unit
    return {"шт": "pcs", "кг": "kg", "г": "g", "л": "l", "мл": "ml"}.get(unit, unit)


class LocaleMiddleware:
    """Pure ASGI context also covers streaming responses and body-limit errors."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        header = next(
            (
                value.decode("latin-1")
                for key, value in scope["headers"]
                if key.lower() == b"accept-language"
            ),
            None,
        )
        if scope["path"] in {"/api/export.csv", "/api/export.json"}:
            # Native download links cannot attach the app's language header.
            query = parse_qs(scope.get("query_string", b"").decode("latin-1"))
            language = query.get("language", [])
            if len(language) == 1 and language[0] in {"ru", "en"}:
                header = language[0]
        with language_context(header):

            async def localized_send(message: Message) -> None:
                if message["type"] == "http.response.start" and scope["path"].startswith("/api/"):
                    headers = list(message.get("headers", []))
                    headers.append((b"content-language", current_language().encode("ascii")))
                    headers.append((b"vary", b"Accept-Language"))
                    message = {**message, "headers": headers}
                await send(message)

            await self.app(scope, receive, localized_send)


# Only known application-owned receipt notices are translated at serialization.
# Matching placeholders are passed through unchanged, including names and amounts.
NOTICE_PATTERNS = (
    (
        r"Проверьте количество (.+) у «(.+)»: рассчитано по цене и сумме строки, цифра на фото не прочитана\.",
        "Check quantity {0} for “{1}”: it was calculated from the price and line amount because the digit in the photo could not be read.",
    ),
    (
        r"Напечатанная скидка (\S+) (\S+) распределена по товарам пропорционально их суммам\. Цены за единицу сохранены до скидки\. Проверьте распределение перед подтверждением\.",
        "The printed discount of {0} {1} was allocated proportionally across items. Unit prices remain before the discount. Review the allocation before confirming.",
    ),
    (
        r"Статус банка: (.+)\. Убедитесь, что платёж завершён\.",
        "Bank status: {0}. Make sure the payment is complete.",
    ),
    (
        r"Исходная сумма: (\S+) (\S+)\. В расходы войдёт только (\S+) (\S+)\.",
        "Original amount: {0} {1}. Only {2} {3} will be recorded as an expense.",
    ),
    (r"Проверьте количество, цену и скидку: (.+)", "Check the quantity, price and discount: {0}"),
    (r"Не удалось разобрать строку: (.+)", "Could not read the line: {0}"),
)


def translated_notice(message: str | None) -> str | None:
    if message is None:
        return None
    if current_language() == "ru":
        return RUSSIAN.get(message, message)
    translated = t(message)
    if current_language() != "en" or translated != message or len(message) > 2000:
        return translated
    for pattern, template in NOTICE_PATTERNS:
        match = re.fullmatch(pattern, message, flags=re.DOTALL)
        if match:
            return template.format(*match.groups())
    return message
