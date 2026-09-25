import asyncio
import io
from datetime import date

from PIL import Image
from sqlalchemy import select

from app import api, receipts
from app import models as m
from app.db import SessionLocal

PAGE_TEXT = (
    "Test Market\nIDNO 123\nPAINE\n1 buc x 12.00=12.00\nTOTAL LEI 12.00\nArticole 1\n25-09-2025"
)
URL = "https://shop.example/receipt?id=phone"


def picture():
    buffer = io.BytesIO()
    Image.new("RGB", (40, 100), "white").save(buffer, "JPEG")
    return buffer.getvalue()


def upload(client, **data):
    return client.post(
        "/api/receipts/upload",
        data={"resolve_qr": "false", "page_url": URL, "page_text": PAGE_TEXT, **data},
        files=[("files", ("page.jpg", picture(), "image/jpeg"))],
    )


def member(owner):
    with SessionLocal() as db:
        membership = db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"]))
        membership.role = "user"
        prefs = db.scalar(select(m.Preferences).where(m.Preferences.organization_id == owner["id"]))
        prefs.provider, prefs.auto_post = "disabled", True
        db.commit()


def forbid_network(*args, **kwargs):
    raise AssertionError("Mobile receipt processing must not open a receipt website")


def correction(receipt, account, category=None):
    return {
        "merchant": "Corrected Market",
        "purchased_on": "2025-09-24",
        "currency": "MDL",
        "total": "11.50",
        "account_id": account["id"],
        "version": receipt["version"],
        "items": [
            {
                "name": "Corrected bread",
                "quantity": "2",
                "unit": "шт",
                "unit_price": "6.00",
                "total": "11.50",  # A discount must not be overwritten by quantity * price.
                "category_id": category,
            }
        ],
    }


def test_phone_page_never_fetches_source_and_member_can_correct_before_posting(
    client, accounts, categories, owner, monkeypatch
):
    member(owner)
    monkeypatch.setattr(receipts, "fetch_mev", forbid_network)
    monkeypatch.setattr(receipts, "capture_receipt_page", forbid_network)
    monkeypatch.setattr(receipts, "local_ocr", forbid_network)
    response = upload(client, account_id=accounts[0]["id"])
    assert response.status_code == 200, response.text
    row = response.json()["receipt"]
    assert row["source"] == "phone_page" and row["review_required"]
    asyncio.run(receipts.process_receipt(row["id"]))
    preview = client.get(f"/api/receipts/{row['id']}").json()
    assert preview["status"] == "review" and preview["total_minor"] == 1200
    assert len(preview["items"]) == 1
    assert client.get("/api/transactions").json()["total"] == 0
    # Reprocessing keeps the original page text, without a network/OCR fallback.
    asyncio.run(receipts.process_receipt(row["id"]))
    preview = client.get(f"/api/receipts/{row['id']}").json()
    with SessionLocal() as db:
        receipt = db.get(m.Receipt, row["id"])
        receipt.warnings = ["Сумма товаров не совпала с итогом чека"]
        original_extraction = receipt.original["extraction"]
        db.commit()
    body = correction(preview, accounts[0], categories[0]["id"])
    endpoint = f"/api/receipts/{row['id']}/review"
    assert client.post(endpoint, json={**body, "version": 1}).status_code == 409
    assert client.post(endpoint, json={**body, "total": "9.00"}).status_code == 422
    assert (
        client.post(endpoint, json={**body, "transaction_id": "another-expense"}).status_code == 422
    )
    assert client.get("/api/transactions").json()["total"] == 0
    for _ in range(2):
        confirmed = client.post(endpoint, json=body)
        assert confirmed.status_code == 200, confirmed.text
        result = confirmed.json()
        assert result["status"] == "posted" and result["merchant"] == "Corrected Market"
        assert result["total_minor"] == 1150 and result["purchased_on"] == "2025-09-24"
        assert result["items"][0]["category_id"] == categories[0]["id"]
        assert result["items"][0]["quantity"].startswith("2")
        assert result["warnings"] == []
    assert client.get("/api/transactions").json()["total"] == 1
    with SessionLocal() as db:
        receipt = db.get(m.Receipt, row["id"])
        assert receipt.original["extraction"] == original_extraction
        assert receipt.original["review_warnings"] == ["Сумма товаров не совпала с итогом чека"]


def test_phone_photo_disables_decoded_qr_network_target(client, owner, monkeypatch):
    member(owner)
    monkeypatch.setattr(
        api,
        "image_bytes",
        lambda data: (data, "https://mev.sfs.md/receipt-verifier/0123456789abcdef"),
    )
    response = upload(client, page_url="", page_text="", review_required="true")
    assert response.status_code == 200
    assert response.json()["receipt"]["source_url"] is None
    assert response.json()["receipt"]["source"] == "photo"
    monkeypatch.setattr(receipts, "fetch_mev", forbid_network)
    monkeypatch.setattr(receipts, "capture_receipt_page", forbid_network)
    monkeypatch.setattr(receipts, "local_ocr", lambda _: PAGE_TEXT)
    asyncio.run(receipts.process_receipt(response.json()["receipt"]["id"]))
    assert client.get("/api/transactions").json()["total"] == 0


def test_phone_page_replaces_only_own_failed_link(client, accounts, owner):
    member(owner)
    linked = client.post("/api/receipts/link", json={"url": URL}).json()["receipt"]
    rid = linked["id"]
    # An active job, valid draft or someone else's failure must never be overwritten.
    assert upload(client).json()["duplicate"]
    with SessionLocal() as db:
        receipt = db.get(m.Receipt, rid)
        receipt.status, receipt.error = "review", "Source blocked"
        db.commit()
    response = upload(client, account_id=accounts[0]["id"])
    assert response.status_code == 200 and not response.json()["duplicate"]
    result = response.json()["receipt"]
    assert result["id"] == rid and result["source"] == "phone_page"
    assert result["status"] == "queued" and result["version"] > linked["version"]
    assert upload(client).json()["duplicate"]


def test_member_cannot_correct_another_authors_receipt(client, accounts, owner):
    member(owner)
    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key="legacy-other",
            status="review",
            created_by=None,
            merchant="Other",
            purchased_on=date(2025, 9, 25),
        )
        db.add(receipt)
        db.commit()
        rid, version = receipt.id, receipt.version
    response = client.post(
        f"/api/receipts/{rid}/review", json=correction({"version": version}, accounts[0])
    )
    assert response.status_code == 403
    assert client.get("/api/transactions").json()["total"] == 0


def test_phone_upload_rejects_unassociated_text_and_private_source(client):
    assert upload(client, page_url="").status_code == 422
    assert upload(client, page_url="https://127.0.0.1/receipt").status_code == 422
