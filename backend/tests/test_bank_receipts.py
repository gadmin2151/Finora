import asyncio
from uuid import uuid4

import pytest
from sqlalchemy import func, select

from app import models as m
from app.bank_receipts import parse_bank_receipt
from app.db import SessionLocal
from app.receipts import parse_mev, process_receipt

PAYMENT = """maib
Suma totală 449.24 MDL
Denumire comerciant GOOGLE *Play
Număr Card ****0000
Nume plătitor TEST CUSTOMER
Informații suplimentare MOUNTAIN VIEW US
Suma 25.00 USD
Comision -
Suma în valuta cardului 449.24 MDL
Suma finală 449.24 MDL
Data tranzacției 26/09/2026 08:47:42
Statutul tranzacției În procesare
Număr de referință TEST123
"""


@pytest.mark.parametrize("separate_lines", [False, True])
def test_bank_payment_is_one_charge_not_two_currencies(separate_lines):
    text = PAYMENT
    if separate_lines:
        for value in (
            "449.24 MDL",
            "GOOGLE *Play",
            "25.00 USD",
            "26/09/2026 08:47:42",
            "În procesare",
        ):
            text = text.replace(" " + value, "\n" + value)
    parsed = parse_mev(text)
    assert parsed["document_type"] == "bank_payment" and parsed["readable"]
    assert parsed["merchant"] == "GOOGLE *Play" and parsed["merchant_address"] == ""
    assert parsed["total"] == "449.24" and parsed["currency"] == "MDL"
    assert parsed["original_amount"] == "25.00" and parsed["original_currency"] == "USD"
    assert parsed["payment_status"] == "În procesare"
    assert parsed["purchased_on"] == "2026-09-26"
    assert len(parsed["items"]) == 1 and parsed["items"][0]["total"] == "449.24"
    assert parsed["items"][0]["quantity"] == "1"
    assert "TEST CUSTOMER" not in str(parsed) and "****0000" not in str(parsed)


def test_original_currency_does_not_substitute_missing_card_amount():
    parsed = parse_bank_receipt(PAYMENT.replace("449.24 MDL", "unreadable"))
    assert parsed["total"] == "" and not parsed["items"] and not parsed["readable"]


def test_conflicting_charges_and_invalid_date_require_review():
    parsed = parse_bank_receipt(
        PAYMENT.replace("Suma finală 449.24", "Suma finală 459.24").replace(
            "26/09/2026", "31/02/2026"
        )
    )
    assert parsed["total"] == "459.24" and not parsed["purchased_on"]
    assert not parsed["readable"] and any(
        "различаются" in warning for warning in parsed["warnings"]
    )


@pytest.mark.parametrize("status", ["În procesare", "Executat", "Respins"])
def test_bank_recognition_never_posts_automatically_or_edits_manual_record(
    client, owner, accounts, monkeypatch, status
):
    from app import receipts

    def no_network(*args, **kwargs):
        raise AssertionError("The phone supplied the bank page; do not refetch it")

    monkeypatch.setattr(receipts, "fetch_mev", no_network)
    monkeypatch.setattr(receipts, "capture_receipt_page", no_network)
    with SessionLocal() as db:
        prefs = db.scalar(select(m.Preferences).where(m.Preferences.organization_id == owner["id"]))
        prefs.auto_post = True
        receipt = m.Receipt(
            organization_id=owner["id"],
            source="phone_page",
            source_key=uuid4().hex,
            account_id=accounts[0]["id"],
            original={"phone_page_text": PAYMENT.replace("În procesare", status)},
            file_names=["original.jpg"],
        )
        manual = m.Receipt(
            organization_id=owner["id"],
            source="photo",
            source_key=uuid4().hex,
            status="posted",
            merchant="Manual payment",
            total_minor=44924,
        )
        db.add_all([receipt, manual])
        db.commit()
        rid, mid = receipt.id, manual.id
    asyncio.run(process_receipt(rid))
    result = client.get(f"/api/receipts/{rid}").json()
    assert result["status"] == "review" and result["review_required"]
    assert result["total_minor"] == 44924 and len(result["items"]) == 1
    assert result["payment_status"] == status
    assert any(status in warning for warning in result["warnings"])
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Transaction)) == 0
        assert db.get(m.Category, result["items"][0]["category_id"]).name == "Связь и подписки"
    asyncio.run(process_receipt(mid))
    with SessionLocal() as db:
        assert (
            db.get(m.Receipt, mid).merchant == "Manual payment"
            and db.get(m.Receipt, mid).version == 1
        )


def test_ordinary_receipt_does_not_become_a_bank_payment():
    assert parse_bank_receipt("SHOP\nPAINE\n1 x 10.00 10.00\nTOTAL 10.00\n26/09/2026") is None
