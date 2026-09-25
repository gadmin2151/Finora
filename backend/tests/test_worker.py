import asyncio
from uuid import uuid4

import pytest
from sqlalchemy import select

from app import ai
from app import models as m
from app.config import settings
from app.db import SessionLocal
from app.receipts import process_receipt


@pytest.mark.parametrize("fuzzy", [False, True])
def test_local_ocr_posts_without_ai(client, owner, accounts, monkeypatch, fuzzy):
    from app import receipts

    text = "TEST MARKET\n20.09.2025\nLAPTE\n1 x 24.90 24.90\nPAINE\n2 x 8.50 17.00\nOUA\n1 x 34.00 34.00\nTOTAL MDL 75.90"
    if fuzzy:
        text = text.replace("1 x 24.90", "? buc x 24.90")
    monkeypatch.setattr(receipts, "local_ocr", lambda images: text)

    async def forbidden(*args, **kwargs):
        raise AssertionError("Readable OCR must not call an AI provider")

    monkeypatch.setattr(ai, "generate", forbidden)
    folder = settings().data_dir / "receipts" / owner["id"]
    folder.mkdir(parents=True)
    (folder / "receipt.jpg").write_bytes(b"OCR fixture")
    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key=uuid4().hex,
            account_id=accounts[0]["id"],
            file_names=["receipt.jpg"],
            error="Previous processing timed out",
        )
        db.add(receipt)
        db.commit()
        rid = receipt.id
    asyncio.run(process_receipt(rid))
    result = client.get(f"/api/receipts/{rid}").json()
    assert result["status"] == ("review" if fuzzy else "posted")
    assert len(result["items"]) == 3 and result["total_minor"] == 7590
    assert result["error"] is None
    assert [item["name"] for item in result["items"]] == ["LAPTE", "PAINE", "OUA"]
    assert client.get("/api/accounts").json()[0]["balance_minor"] == (0 if fuzzy else -7590)
    with SessionLocal() as db:
        assert db.get(m.Receipt, rid).original["provider"] == "local_ocr"


@pytest.mark.parametrize("uncertain", [False, True])
def test_photo_pipeline_validates_before_automatic_post(
    client, owner, accounts, monkeypatch, uncertain
):
    async def extract(*args, **kwargs):
        return {
            "merchant": "Test Market",
            "purchased_on": "2025-02-15",
            "currency": "MDL",
            "total": "10.00",
            "readable": True,
            "warnings": ["Сомнение в количестве"] if uncertain else [],
            "items": [
                {
                    "name": "LAPTE",
                    "quantity": "1",
                    "unit": "шт",
                    "unit_price": "10.00",
                    "total": "10.00",
                    "category": "Продукты",
                }
            ],
        }, "ollama"

    monkeypatch.setattr(ai, "generate", extract)
    with SessionLocal() as db:
        p = db.scalar(select(m.Preferences))
        p.provider = "ollama"
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key=uuid4().hex,
            account_id=accounts[0]["id"],
        )
        db.add(receipt)
        db.commit()
        rid = receipt.id
    asyncio.run(process_receipt(rid))
    result = client.get(f"/api/receipts/{rid}").json()
    assert result["status"] == ("review" if uncertain else "posted")
    assert len(result["items"]) == 1
    assert client.get("/api/transactions").json()["total"] == (0 if uncertain else 1)
    if not uncertain:
        asyncio.run(process_receipt(rid))
        assert client.get("/api/transactions").json()["total"] == 1


def test_ai_classifies_unfamiliar_ocr_items_without_changing_amounts(
    client, owner, accounts, categories, monkeypatch
):
    from app import receipts

    monkeypatch.setattr(
        receipts,
        "local_ocr",
        lambda images: "TEST MARKET\n20.09.2025\nCIOCOLATA\n1 x 25.00 25.00\nTOTAL 25.00",
    )

    async def classify(organization_id, purpose, system, text, schema, images=None):
        assert purpose == "categories" and images is None
        return {"assignments": [{"index": 0, "category": "Продукты"}]}, "ollama"

    monkeypatch.setattr(ai, "generate", classify)
    folder = settings().data_dir / "receipts" / owner["id"]
    folder.mkdir(parents=True)
    (folder / "receipt.jpg").write_bytes(b"OCR fixture")
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "ollama"
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key=uuid4().hex,
            account_id=accounts[0]["id"],
            file_names=["receipt.jpg"],
        )
        db.add(receipt)
        db.commit()
        rid = receipt.id
    asyncio.run(process_receipt(rid))
    result = client.get(f"/api/receipts/{rid}").json()
    assert result["status"] == "posted" and result["total_minor"] == 2500
    assert result["items"][0]["category_id"] == categories[0]["id"]
    assert result["items"][0]["unit_price_minor"] == 2500


def test_existing_similar_transaction_requires_review(client, owner, accounts, monkeypatch):
    async def extract(*args, **kwargs):
        return {
            "merchant": "Test Market",
            "purchased_on": "2025-02-15",
            "currency": "MDL",
            "total": "10.00",
            "readable": True,
            "warnings": [],
            "items": [
                {
                    "name": "LAPTE",
                    "quantity": "1",
                    "unit": "шт",
                    "unit_price": "10.00",
                    "total": "10.00",
                    "category": "Продукты",
                }
            ],
        }, "ollama"

    monkeypatch.setattr(ai, "generate", extract)
    client.post(
        "/api/transactions",
        json={
            "amount": "10",
            "account_id": accounts[0]["id"],
            "occurred_on": "2025-02-15",
            "idempotency_key": str(uuid4()),
        },
    )
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "ollama"
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key=uuid4().hex,
            account_id=accounts[0]["id"],
        )
        db.add(receipt)
        db.commit()
        rid = receipt.id
    asyncio.run(process_receipt(rid))
    result = client.get(f"/api/receipts/{rid}").json()
    assert result["status"] == "review"
    assert any("Уже есть" in w for w in result["warnings"])
    assert client.get("/api/transactions").json()["total"] == 1


def test_ai_failure_keeps_partial_ocr_draft(client, owner, accounts, monkeypatch):
    from app import receipts

    monkeypatch.setattr(
        receipts,
        "local_ocr",
        lambda images: "TEST MARKET\n20.09.2025\nLAPTE\n1 x 10.00 10.00\nTOTAL 15.00",
    )
    monkeypatch.setattr(receipts, "vision_images", lambda images: images)

    async def unavailable(*args, **kwargs):
        raise ai.AIError("Модель временно недоступна")

    monkeypatch.setattr(ai, "generate", unavailable)
    folder = settings().data_dir / "receipts" / owner["id"]
    folder.mkdir(parents=True)
    (folder / "receipt.jpg").write_bytes(b"OCR fixture")
    with SessionLocal() as db:
        db.scalar(select(m.Preferences)).provider = "ollama"
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key=uuid4().hex,
            account_id=accounts[0]["id"],
            file_names=["receipt.jpg"],
        )
        db.add(receipt)
        db.commit()
        rid = receipt.id
    asyncio.run(process_receipt(rid))
    result = client.get(f"/api/receipts/{rid}").json()
    assert result["status"] == "review" and result["total_minor"] == 1500
    assert len(result["items"]) == 1 and result["items"][0]["name"] == "LAPTE"
    assert any("Модель временно" in warning for warning in result["warnings"])
    assert client.get("/api/transactions").json()["total"] == 0
