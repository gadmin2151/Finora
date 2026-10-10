"""Synthetic regressions for the receipt and explicit AI confirmation flows."""

import io
import json
from uuid import uuid4

import pytest
from app import models as m
from app.config import settings
from app.db import SessionLocal
from app.receipt_files import append_images
from PIL import Image
from sqlalchemy import func, select


def second_workspace(owner):
    with SessionLocal() as db:
        org = m.Organization(name="Synthetic second workspace")
        db.add(org)
        db.flush()
        db.add(m.Membership(user_id=owner["id"], organization_id=org.id, role="admin"))
        db.commit()
        return org.id


def image():
    output = io.BytesIO()
    Image.new("RGB", (8, 8), "white").save(output, "JPEG")
    return output.getvalue()


def test_receipt_document_stays_in_selected_organization_even_for_same_owner(client, owner):
    other = second_workspace(owner)
    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=owner["id"],
            created_by=owner["id"],
            source="photo",
            source_key=uuid4().hex,
            status="review",
        )
        db.add(receipt)
        db.flush()
        append_images(receipt, [image()])
        db.commit()
        key = receipt.id
    original = client.get(f"/api/receipts/{key}/image/0")
    assert original.status_code == 200
    assert "no-store" in original.headers["cache-control"]
    client.headers["X-Organization-ID"] = other
    assert client.get(f"/api/receipts/{key}").status_code == 404
    assert client.get(f"/api/receipts/{key}/image/0").status_code == 404
    assert (
        client.post(
            f"/api/receipts/{key}/originals",
            files={"files": ("synthetic.jpg", image(), "image/jpeg")},
        ).status_code
        == 404
    )
    client.headers["X-Organization-ID"] = owner["id"]
    assert client.get(f"/api/receipts/{key}/image/0").content == original.content
    with SessionLocal() as db:
        assert len(db.get(m.Receipt, key).file_names) == 1
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0


@pytest.mark.parametrize(
    "raw,expected",
    [
        (b"<svg onload=synthetic()>", 422),
        (b"PK\x03\x04synthetic archive", 422),
        (b"\xff\xd8invalid truncated image", 422),
        (b"x" * (15 * 1024 * 1024 + 1), 413),
    ],
    ids=["svg", "zip", "truncated-jpeg", "oversized"],
)
def test_rejected_uploads_never_create_receipts_or_money_records(client, raw, expected):
    before = set((settings().data_dir / "receipts").glob("**/*.jpg"))
    response = client.post(
        "/api/receipts/upload",
        data={"resolve_qr": "false"},
        files={"files": ("synthetic.jpg", raw, "image/jpeg")},
    )
    assert response.status_code == expected, response.text
    assert "onload" not in response.text and "truncated image" not in response.text
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Receipt)) == 0
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0
    assert set((settings().data_dir / "receipts").glob("**/*.jpg")) == before


def test_ai_proposal_cannot_cross_organization_when_actor_belongs_to_both(client, owner, accounts):
    other = second_workspace(owner)
    with SessionLocal() as db:
        message = m.Message(
            organization_id=owner["id"],
            role="assistant",
            text="Synthetic proposal",
            details={
                "actor_id": owner["id"],
                "action_status": "pending",
                "actions": [
                    {
                        "operation": "POST /transactions",
                        "parameters_json": "{}",
                        "body_json": json.dumps(
                            {
                                "account_id": accounts[0]["id"],
                                "amount": "1.00",
                                "kind": "income",
                                "occurred_on": "2026-10-09",
                            }
                        ),
                        "label": "Synthetic income",
                        "description": "Synthetic boundary fixture",
                    }
                ],
            },
        )
        db.add(message)
        db.commit()
        key = message.id
    client.headers["X-Organization-ID"] = other
    assert client.post(f"/api/chat/{key}/actions", json={"confirm": True}).status_code == 404
    with SessionLocal() as db:
        assert db.get(m.Message, key).details["action_status"] == "pending"
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0
