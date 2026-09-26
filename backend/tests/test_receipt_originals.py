import asyncio
import io
from datetime import date
from uuid import uuid4

from PIL import Image
from sqlalchemy import select

from app import models as m
from app import receipts
from app.config import settings
from app.db import SessionLocal
from app.receipt_files import append_images
from app.web_receipts import PageCapture, WebReceiptError


def picture(color="white"):
    buffer = io.BytesIO()
    Image.new("RGB", (80, 240), color).save(buffer, "JPEG")
    return buffer.getvalue()


def add_original(client, key, color="white"):
    return client.post(
        f"/api/receipts/{key}/originals",
        files=[("files", ("photo.jpg", picture(color), "image/jpeg"))],
    )


def stored_receipt(owner, **extra):
    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key=uuid4().hex,
            status="review",
            created_by=owner["id"],
            merchant="Test Market",
            total_minor=1000,
            purchased_on=date(2025, 9, 25),
            **extra,
        )
        db.add(receipt)
        db.commit()
        return receipt.id


def test_attach_preserves_financial_fields_is_idempotent_and_private(client, owner):
    key = stored_receipt(owner)
    initial = client.get(f"/api/receipts/{key}").json()
    added = add_original(client, key)
    assert added.status_code == 200, added.text
    result = added.json()
    assert len(result["files"]) == 1
    for field in ("items", "merchant", "purchased_on", "status", "version", "total_minor"):
        assert result[field] == initial[field]
    assert len(add_original(client, key).json()["files"]) == 1
    assert len(add_original(client, key, "yellow").json()["files"]) == 2
    response = client.get(result["files"][0])
    assert response.status_code == 200 and response.headers["content-type"] == "image/jpeg"
    assert "no-store" in response.headers["cache-control"]
    org = client.post("/api/organizations", json={"name": "Other"}).json()["id"]
    assert client.get(result["files"][0], headers={"X-Organization-ID": org}).status_code == 404
    assert (
        client.post(
            f"/api/receipts/{key}/originals",
            files=[("files", ("x.html", b"<script>oops</script>", "image/jpeg"))],
        ).status_code
        == 422
    )


def test_member_can_attach_only_to_own_receipt(client, owner):
    mine = stored_receipt(owner)
    other = stored_receipt(owner)
    with SessionLocal() as db:
        db.get(m.Receipt, other).created_by = None
        db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"])).role = "user"
        db.commit()
    assert add_original(client, mine).status_code == 200
    assert add_original(client, other).status_code == 403


def test_limits_original_count_and_deleted_receipts(client, owner, monkeypatch):
    key = stored_receipt(owner)
    monkeypatch.setattr("app.receipt_files.MAX_RECEIPT_FILES", 1)
    assert add_original(client, key).status_code == 200
    assert add_original(client, key, "blue").status_code == 422
    with SessionLocal() as db:
        db.get(m.Receipt, key).deleted_at = m.now()
        db.commit()
    assert add_original(client, key).status_code == 404


def test_duplicate_upload_retains_additional_images_without_reposting(client, owner):
    url = "https://shop.example/receipt?id=duplicate"

    def upload(color):
        return client.post(
            "/api/receipts/upload",
            data={"page_url": url, "page_text": "TOTAL 10.00", "resolve_qr": "false"},
            files=[("files", ("page.jpg", picture(color), "image/jpeg"))],
        )

    first = upload("white").json()
    key = first["receipt"]["id"]
    with SessionLocal() as db:
        db.get(m.Receipt, key).status = "posted"
        db.commit()
    second = upload("blue").json()
    assert second["duplicate"] and second["receipt"]["id"] == key
    assert len(second["receipt"]["files"]) == 2
    assert second["receipt"]["status"] == "posted"


def test_mev_screenshot_saved_without_replacing_photo(client, owner, monkeypatch):
    url = "https://mev.sfs.md/receipt-verifier/0123456789abcdef"
    text = (
        "Test Market\nIDNO 123\nPAINE\n1 buc x 12.00=12.00\nTOTAL LEI 12.00\nArticole 1\n25-09-2025"
    )
    original, screenshot = picture("yellow"), picture("blue")

    async def fetch(_):
        return text

    async def capture(_):
        return PageCapture(screenshot, text, url)

    monkeypatch.setattr(receipts, "fetch_mev", fetch)
    monkeypatch.setattr(receipts, "capture_receipt_page", capture)
    key = stored_receipt(owner, source_url=url)
    with SessionLocal() as db:
        row = db.get(m.Receipt, key)
        append_images(row, [original])
        db.commit()
    asyncio.run(receipts.process_receipt(key))
    result = client.get(f"/api/receipts/{key}").json()
    assert result["status"] == "review" and result["total_minor"] == 1200
    assert len(result["files"]) == 2
    assert client.get(result["files"][0]).content == original
    assert client.get(result["files"][1]).content == screenshot
    asyncio.run(receipts.process_receipt(key))
    assert len(client.get(f"/api/receipts/{key}").json()["files"]) == 2
    assert client.get("/api/transactions").json()["total"] == 0


def test_no_source_capture_does_not_discard_uploaded_photo(client, owner, monkeypatch):
    key = stored_receipt(owner, source_url="https://shop.example/receipt")
    with SessionLocal() as db:
        row = db.get(m.Receipt, key)
        append_images(row, [picture()])
        db.commit()

    async def unavailable(_):
        raise WebReceiptError("Offline")

    monkeypatch.setattr(receipts, "capture_receipt_page", unavailable)
    monkeypatch.setattr(
        receipts,
        "local_ocr",
        lambda images: (
            "Test Market\nIDNO 123\nPAINE\n1 buc x 12.00=12.00\nTOTAL LEI 12.00\nArticole 1\n25-09-2025"
        ),
    )
    asyncio.run(receipts.process_receipt(key))
    result = client.get(f"/api/receipts/{key}").json()
    assert result["total_minor"] == 1200 and len(result["files"]) == 1
    with SessionLocal() as db:
        row = db.get(m.Receipt, key)
        assert (settings().data_dir / "receipts" / owner["id"] / row.file_names[0]).exists()
