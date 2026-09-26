from datetime import date
from decimal import Decimal
from uuid import uuid4

import pytest
from sqlalchemy import func, select

from app import models as m
from app.category_catalog import DAILY_CATEGORIES
from app.category_refresh import refresh_receipt_categories
from app.db import SessionLocal
from app.finance import create_transaction, money, seed_organization
from app.receipts import categorize, parse_mev, receipt_extraction_schema
from app.schemas import TransactionInput

# A fictional seller/identifier, with the exact SFS page structure that used to
# attach the registration heading and separator to the first product.
PAGE = """ELECTRONIC SERVICE
Verificarea bonului fiscal
Numărul de înregistrare ECC
help
Suma totală a bonului
search
Ajutor cu completarea
"TEST MARKET" S.R.L.
COD FISCAL: 1000000000000
mun. Chisinau, or. Codru, str.
Testului 176
NUMARUL DE ÎNREGISTRARE: S00000000000
````````````````````````````
Bautura energizanta 'Red Bull' 250ml
2.000 x 31.90
63.80 A
Terea Starling Pearl
10.000 x 70.00
700.00 A
````````````````````````````
TOTAL
763.80
TVA A 20.00%
127.30
DATA 26.09.2026
BON FISCAL
"""


def test_sfs_page_separates_merchant_address_and_products():
    parsed = parse_mev(PAGE)
    assert parsed["readable"]
    assert parsed["merchant"] == '"TEST MARKET" S.R.L.'
    assert parsed["merchant_address"] == "mun. Chisinau, or. Codru, str. Testului 176"
    assert [i["name"] for i in parsed["items"]] == [
        "Bautura energizanta 'Red Bull' 250ml",
        "Terea Starling Pearl",
    ]
    assert parsed["total"] == "763.80"
    schema = receipt_extraction_schema()
    assert set(schema["required"]) == set(schema["properties"])
    assert "default" not in schema["properties"]["merchant_address"]


def test_wrapped_address_does_not_consume_first_product():
    parsed = parse_mev(
        "SHOP S.R.L.\nmun. Chisinau\nstr. Testului 1\nLAPTE\n1 x 10.00 10.00\nTOTAL 10.00\n25.09.2026"
    )
    assert parsed["merchant_address"] == "mun. Chisinau str. Testului 1"
    assert parsed["items"][0]["name"] == "LAPTE"


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("Băutură energizantă Red Bull 250ml", "Энергетики"),
        ("Red Boll", "Энергетики"),
        ("Terea Starling Pearl", "Сигареты и табак"),
        ("Țigări Kent Blue", "Сигареты и табак"),
        ("LUPPO Biscuit sandwich cherry", "Сладости"),
        ("BIG LAPIK Brinz.glaz. cocos 50g", "Сладости"),
        ("Ceai verde", "Кофе и чай"),
        ("Чай чёрный", "Кофе и чай"),
        ("Cafea boabe", "Кофе и чай"),
        ("LETTO Bautura racorit. Pepene verde", "Вода и напитки"),
        ("CEAPA Moldova kg", "Овощи и фрукты"),
        ("DMK Lapte integral concentrat cu zahar", "Молочные продукты и яйца"),
        ("Йогурт и яйцо", "Молочные продукты и яйца"),
        ("Detergent rufe", "Бытовая химия"),
        ("Шампунь детский", "Гигиена и уход"),
        ("Punga maieu", "Товары для дома"),
        ("Something unknown", None),
        ("Papaya", None),
        ("Steak", None),
        ("Cocoș", None),
    ],
)
def test_specific_categories_and_word_boundaries(owner, name, expected):
    with SessionLocal() as db:
        category = categorize(db, owner["id"], name, "Shop")
        assert (db.get(m.Category, category).name if category else None) == expected


def test_rules_take_priority_over_specific_categories(owner):
    with SessionLocal() as db:
        custom = m.Category(organization_id=owner["id"], name="Моё правило")
        db.add(custom)
        db.flush()
        db.add(m.Rule(organization_id=owner["id"], pattern="Red Bull", category_id=custom.id))
        db.flush()
        assert categorize(db, owner["id"], "Red Bull", "Shop", "Продукты") == custom.id


def stored_receipt(owner, account, db, status="posted", currency="MDL", rate=Decimal(1)):
    category = db.scalar(
        select(m.Category).where(
            m.Category.organization_id == owner["id"], m.Category.name == "Продукты"
        )
    )
    receipt = m.Receipt(
        organization_id=owner["id"],
        source="phone_page",
        source_key=uuid4().hex,
        merchant="ELECTRONIC SERVICE",
        status=status,
        total_minor=76380,
        currency=currency,
        purchased_on=date(2026, 9, 26),
        original={"mev_text": PAGE},
        file_names=["original.jpg"],
    )
    db.add(receipt)
    db.flush()
    parsed = parse_mev(PAGE)
    for index, line in enumerate(parsed["items"]):
        name = ("NUMARUL DE ÎNREGISTRARE: S00000000000 ```` " if index == 0 else "") + line["name"]
        db.add(
            m.ReceiptItem(
                receipt_id=receipt.id,
                name=name,
                normalized_name=name.lower(),
                quantity=Decimal(line["quantity"]),
                unit="шт",
                unit_price_minor=int(Decimal(line["unit_price"]) * 100),
                total_minor=int(Decimal(line["total"]) * 100),
                category_id=category.id,
            )
        )
    tx = None
    if status == "posted":
        tx = create_transaction(
            db,
            owner["id"],
            TransactionInput(
                kind="expense",
                amount="763.80",
                account_id=account["id"],
                occurred_on=receipt.purchased_on,
                merchant=receipt.merchant,
                category_id=category.id,
                fx_rate=rate,
                idempotency_key=uuid4().hex,
            ),
            receipt_id=receipt.id,
        )
    db.flush()
    return receipt, tx


@pytest.mark.parametrize("currency", ["MDL", "EUR"])
def test_refresh_keeps_money_and_originals_updates_reports_and_is_idempotent(
    client, owner, accounts, currency
):
    with SessionLocal() as db:
        db.get(m.Account, accounts[0]["id"]).currency = currency
        receipt, tx = stored_receipt(
            owner, accounts[0], db, currency=currency, rate=Decimal("19.3737")
        )
        rid, tid = receipt.id, tx.id
        before_money = (tx.amount_minor, tx.base_minor, tx.fx_rate, tx.account_id)
        original = receipt.original.copy()
        postings = [(p.id, p.amount_minor) for p in db.scalars(select(m.Posting))]
        db.commit()
    response = client.post("/api/categories/refresh", json={})
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["receipts_updated"] == 1 and result["items_updated"] == 2
    assert result["names_repaired"] == (1 if currency == "MDL" else 0)
    with SessionLocal() as db:
        receipt, tx = db.get(m.Receipt, rid), db.get(m.Transaction, tid)
        assert (tx.amount_minor, tx.base_minor, tx.fx_rate, tx.account_id) == before_money
        assert [(p.id, p.amount_minor) for p in db.scalars(select(m.Posting))] == postings
        assert receipt.total_minor == 76380 and receipt.file_names == ["original.jpg"]
        assert receipt.original["mev_text"] == original["mev_text"]
        shares = list(db.scalars(select(m.Allocation).where(m.Allocation.transaction_id == tid)))
        assert sum(a.amount_minor for a in shares) == tx.amount_minor
        assert sum(a.base_minor for a in shares) == tx.base_minor
        if currency == "MDL":
            assert receipt.merchant == '"TEST MARKET" S.R.L.' and tx.merchant == receipt.merchant
            assert "Testului 176" in receipt.original["merchant_address"]
        version = receipt.version
    again = client.post("/api/categories/refresh", json={}).json()
    assert again["receipts_updated"] == again["items_updated"] == 0
    with SessionLocal() as db:
        assert db.get(m.Receipt, rid).version == version


def test_presets_are_idempotent_org_scoped_and_admin_only(client, owner):
    with SessionLocal() as db:
        other = m.Organization(name="Other")
        db.add(other)
        seed_organization(db, other)
        before = db.scalar(
            select(func.count())
            .select_from(m.Category)
            .where(m.Category.organization_id == other.id)
        )
        daily = db.scalar(
            select(m.Category).where(
                m.Category.organization_id == owner["id"], m.Category.name == "Сладости"
            )
        )
        daily.color = "#123456"
        db.commit()
        oid, cid = other.id, daily.id
    assert client.post("/api/categories/daily").json() == {"added": 0}
    assert client.post("/api/categories/refresh", json={}).status_code == 200
    with SessionLocal() as db:
        assert db.get(m.Category, cid).color == "#123456"
        assert (
            db.scalar(
                select(func.count())
                .select_from(m.Category)
                .where(m.Category.organization_id == oid)
            )
            == before
        )
        membership = db.scalar(select(m.Membership).where(m.Membership.user_id == owner["id"]))
        membership.role = "user"
        db.commit()
    assert client.post("/api/categories/daily").status_code == 403
    assert client.post("/api/categories/refresh", json={}).status_code == 403
    assert len(DAILY_CATEGORIES) == 22


def test_review_draft_stays_unposted_and_refunded_receipt_is_not_guessed(client, owner, accounts):
    with SessionLocal() as db:
        draft, _ = stored_receipt(owner, accounts[0], db, status="review")
        receipt, tx = stored_receipt(owner, accounts[0], db)
        create_transaction(
            db,
            owner["id"],
            TransactionInput(
                kind="refund",
                amount=money(1000),
                account_id=accounts[0]["id"],
                occurred_on=receipt.purchased_on,
                refund_of=tx.id,
                idempotency_key=uuid4().hex,
            ),
        )
        result = refresh_receipt_categories(db, owner["id"])
        assert result["receipts_updated"] == 1 and result["skipped"] == {"refund": 1}
        assert draft.status == "review" and receipt.version == 1
        db.commit()


def test_inconsistent_posted_amounts_are_not_modified(owner, client, accounts):
    with SessionLocal() as db:
        receipt, tx = stored_receipt(owner, accounts[0], db)
        tx.amount_minor += 1
        result = refresh_receipt_categories(db, owner["id"])
        assert result["skipped"] == {"amount_mismatch": 1}
        assert receipt.version == 1 and result["receipts_updated"] == 0
