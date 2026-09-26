"""Read labelled bank payment confirmations, keeping charged and original currencies separate."""

import re
import unicodedata
from datetime import date


def folded(value: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFKD", value.lower()) if not unicodedata.combining(c)
    )


LABELS = {
    "merchant": (
        "denumire comerciant",
        "denumirea comerciantului",
        "beneficiar",
        "получатель",
        "название продавца",
        "merchant",
        "payee",
    ),
    "total": ("suma totala", "общая сумма", "total amount"),
    "final": ("suma finala", "итоговая сумма", "final amount"),
    "charged": (
        "suma in valuta cardului",
        "сумма в валюте карты",
        "сумма списания",
        "amount in card currency",
    ),
    "original": ("suma", "сумма операции", "transaction amount"),
    "date": ("data tranzactiei", "дата транзакции", "дата операции", "transaction date"),
    "status": (
        "statutul tranzactiei",
        "статус транзакции",
        "статус операции",
        "transaction status",
    ),
    "commission": ("comision", "комиссия", "commission"),
    "reference": ("numar de referinta", "номер операции", "reference number"),
    "card": ("numar card", "номер карты", "card number"),
    "payer": ("nume platitor", "имя плательщика", "payer name"),
    "info": ("informatii suplimentare", "дополнительная информация", "additional information"),
}
_LABELS = sorted(
    ((label, field) for field, labels in LABELS.items() for label in labels),
    key=lambda pair: -len(pair[0]),
)


def payment_fields(text: str) -> dict[str, str]:
    lines = [
        unicodedata.normalize("NFC", line).strip() for line in text.splitlines() if line.strip()
    ]
    fields = {}
    for i, line in enumerate(lines):
        normalized = folded(line)
        for label, key in _LABELS:
            if not normalized.startswith(label) or (
                len(normalized) > len(label) and normalized[len(label)].isalpha()
            ):
                continue
            value = line[len(label) :].lstrip(" :·\t")
            if (
                not value
                and i + 1 < len(lines)
                and not any(folded(lines[i + 1]).startswith(other) for other, _ in _LABELS)
            ):
                value = lines[i + 1]
            if value:
                fields[key] = value
            break
    return fields


def payment_money(value: str) -> tuple[str, str] | None:
    match = re.fullmatch(
        r"\s*(\d{1,3}(?:[ \u00a0]\d{3})*[.,]\d{2}|\d+[.,]\d{2})\s*(MDL|LEI|USD|EUR|RON)\s*",
        value,
        re.I,
    )
    if not match:
        return None
    return re.sub(r"\s", "", match[1]).replace(",", "."), "MDL" if match[
        2
    ].upper() == "LEI" else match[2].upper()


def parse_bank_receipt(text: str) -> dict | None:
    fields = payment_fields(text[:50000])
    if (
        not fields.get("merchant")
        or not fields.get("date")
        or not any(key in fields for key in ("card", "status", "reference", "charged"))
    ):
        return None
    paid = (
        payment_money(fields.get("final", ""))
        or payment_money(fields.get("total", ""))
        or payment_money(fields.get("charged", ""))
    )
    original = payment_money(fields.get("original", ""))
    purchased_on = ""
    found = re.search(r"\b(\d{2})[./-](\d{2})[./-](\d{4})\b", fields["date"])
    if found:
        try:
            purchased_on = date(int(found[3]), int(found[2]), int(found[1])).isoformat()
        except ValueError:
            pass  # Keep a partial draft; recognition or the user must supply a valid date.
    warnings = [
        "Банковская квитанция: проверьте получателя, сумму списания и счёт оплаты перед подтверждением."
    ]
    if not paid:
        warnings.append(
            "Не удалось прочитать итоговую сумму в валюте списания. Сумма в исходной валюте не подставлена вместо неё."
        )
    if any(
        payment_money(fields[key]) not in (None, paid)
        for key in ("total", "final", "charged")
        if key in fields
    ):
        warnings.append(
            "Итог и сумма списания на квитанции различаются. Проверьте комиссию и окончательную сумму."
        )
    status = fields.get("status", "")[:100]
    if status:
        warnings.append("Статус банка: " + status + ". Убедитесь, что платёж завершён.")
    else:
        warnings.append("Статус платежа не указан. Проверьте, что списание завершено.")
    if paid and original and original != paid:
        warnings.append(
            f"Исходная сумма: {original[0]} {original[1]}. В расходы войдёт только {paid[0]} {paid[1]}."
        )
    merchant = fields["merchant"][:200]
    return {
        "document_type": "bank_payment",
        "payment_status": status,
        "original_amount": original[0] if original else "",
        "original_currency": original[1] if original else "",
        "merchant": merchant,
        "merchant_address": "",
        "purchased_on": purchased_on,
        "currency": paid[1] if paid else "MDL",
        "total": paid[0] if paid else "",
        "items": [
            {
                "name": "Оплата: " + merchant,
                "quantity": "1",
                "unit": "шт",
                "unit_price": paid[0],
                "total": paid[0],
                "category": "",
            }
        ]
        if paid
        else [],
        "warnings": warnings,
        "readable": bool(paid and purchased_on),
    }
