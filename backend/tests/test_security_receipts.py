import io
import json
import secrets
from decimal import Decimal
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image
from sqlalchemy import select

from app import ai
from app import models as m
from app.db import SessionLocal
from app.finance import seed_user
from app.main import app
from app.receipts import ReceiptError, mev_url, parse_mev
from app.security import encrypt, hasher


def test_csrf_origin_login_and_sensitive_errors(client, owner):
    assert (
        client.post(
            "/api/accounts", json={"name": "blocked"}, headers={"X-CSRF-Token": "invalid"}
        ).status_code
        == 403
    )
    assert (
        client.post(
            "/api/accounts",
            json={"name": "blocked"},
            headers={"Origin": "https://attacker.invalid"},
        ).status_code
        == 403
    )
    bad = client.post(
        "/api/auth/login",
        json={
            "username": owner["username"],
            "password": "secret-example",
            "unexpected": "sensitive-key",
        },
        headers={"X-Finora-Client": "web"},
    )
    assert bad.status_code == 422
    assert "secret-example" not in bad.text and "sensitive-key" not in bad.text
    r = client.get("/api/auth/me")
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["x-content-type-options"] == "nosniff"


def test_password_change_revokes_other_sessions(client, owner):
    with TestClient(app) as other:
        assert (
            other.post(
                "/api/auth/login",
                json={"username": owner["username"], "password": owner["password"]},
                headers={"X-Finora-Client": "web"},
            ).status_code
            == 200
        )
        replacement = secrets.token_urlsafe(24)
        assert (
            client.post(
                "/api/auth/password",
                json={"current_password": owner["password"], "new_password": replacement},
            ).status_code
            == 200
        )
        assert other.get("/api/auth/me").status_code == 401
        assert client.get("/api/auth/me").status_code == 200
        assert (
            other.post(
                "/api/auth/login",
                json={"username": owner["username"], "password": owner["password"]},
                headers={"X-Finora-Client": "web"},
            ).status_code
            == 401
        )
        assert (
            other.post(
                "/api/auth/login",
                json={"username": owner["username"], "password": replacement},
                headers={"X-Finora-Client": "web"},
            ).status_code
            == 200
        )


def test_failed_login_rate_limit(client, owner):
    for _ in range(8):
        assert (
            client.post(
                "/api/auth/login",
                json={"username": owner["username"], "password": "wrong-test-password"},
                headers={"X-Finora-Client": "web"},
            ).status_code
            == 401
        )
    assert (
        client.post(
            "/api/auth/login",
            json={"username": owner["username"], "password": "wrong-test-password"},
            headers={"X-Finora-Client": "web"},
        ).status_code
        == 429
    )


def test_ownership_boundaries(client, accounts):
    with SessionLocal() as db:
        other = m.User(
            username="other", name="Other", password_hash=hasher.hash(secrets.token_urlsafe(20))
        )
        db.add(other)
        seed_user(db, other)
        db.commit()
        account = db.scalar(select(m.Account).where(m.Account.organization_id == other.id))
        receipt = m.Receipt(
            organization_id=other.id, source="photo", source_key=uuid4().hex, status="review"
        )
        db.add(receipt)
        db.commit()
        account_id, receipt_id = account.id, receipt.id
    body = {
        "amount": "1",
        "account_id": account_id,
        "occurred_on": "2025-01-01",
        "idempotency_key": str(uuid4()),
    }
    assert client.post("/api/transactions", json=body).status_code == 404
    assert client.get(f"/api/receipts/{receipt_id}").status_code == 404
    assert client.get(f"/api/receipts/{receipt_id}/image/0").status_code == 404


@pytest.mark.parametrize(
    "url",
    [
        "http://mev.sfs.md/receipt-verifier/1234567890123456",
        "https://mev.sfs.md.evil.org/receipt-verifier/1234567890123456",
        "https://127.0.0.1/receipt-verifier/1234567890123456",
        "https://mev.sfs.md:444/receipt-verifier/1234567890123456",
        "https://mev.sfs.md:invalid/receipt-verifier/1234567890123456",
        "https://x@mev.sfs.md/receipt-verifier/1234567890123456",
        "https://mev.sfs.md/receipt-verifier/1234567890123456?url=http://localhost",
        "http://sift-mev.sfs.md/receipt/0123456789ABCDEF0123456789ABCDEF",
        "https://sift-mev.sfs.md.evil.test/receipt/0123456789ABCDEF0123456789ABCDEF",
        "https://sift-mev.sfs.md:444/receipt/0123456789ABCDEF0123456789ABCDEF",
        "https://x@sift-mev.sfs.md/receipt/0123456789ABCDEF0123456789ABCDEF",
        "https://sift-mev.sfs.md/receipt/0123456789ABCDEF0123456789ABCDEF?x=1",
        "https://sift-mev.sfs.md/receipt-verifier/0123456789ABCDEF0123456789ABCDEF",
        "https://sift-mev.sfs.md/receipt/short",
    ],
)
def test_mev_url_allowlist(url):
    with pytest.raises(ReceiptError):
        mev_url(url)


def test_mev_canonical_and_parser():
    assert (
        mev_url("https://mev.sfs.md/en/receipt-verifier/3E98608628B603934F156001C03B9E99")
        == "https://mev.sfs.md/receipt-verifier/3E98608628B603934F156001C03B9E99"
    )
    text = "Orange Moldova S.A.\nCOD FISCAL: 1003600106115\nPLATA SERVICII\n1.000 x 390.00\n390.00 A\nTOTAL\n390.00\nDATA 08.09.2024"
    result = parse_mev(text)
    assert result["purchased_on"] == "2024-09-08"
    assert result["total"] == "390.00"
    assert result["items"][0]["name"] == "PLATA SERVICII"


def test_printed_sift_link_has_same_canonical_receipt():
    receipt_id = "0123456789ABCDEF0123456789ABCDEF"
    canonical = "https://mev.sfs.md/receipt-verifier/" + receipt_id
    assert mev_url("https://sift-mev.sfs.md/receipt/" + receipt_id) == canonical
    assert mev_url("https://sift-mev.sfs.md/receipt/" + receipt_id + "/") == canonical
    assert mev_url(canonical) == canonical


def photo():
    output = io.BytesIO()
    Image.new("RGB", (100, 200), "white").save(output, format="PNG")
    return output.getvalue()


def test_weighted_wrapped_receipt_and_informational_discount():
    text = """TEST MARKET
IDNO: TEST
Casier: 1
PUNGA maieu
mkm 1 buc x 1.50= 1.50 A
PORTOCALE, kg
1.250 kg x 12.32= 15.40 A
CEAPA, kg 0.500 kg x 8.00= 4.00 B
LAPTE concentrat cu
zahar 4 buc x 2.00= 8.00 B
TOTAL LEI 28. 90
NUMERAR LEI 50.00
REST LEI 21.10
Reducere Total - 6.20
Articole 4
25-09-2025 12:09"""
    parsed = parse_mev(text)
    assert parsed["readable"] and parsed["total"] == "28.90"
    assert parsed["purchased_on"] == "2025-09-25"
    assert [i["quantity"] for i in parsed["items"]] == ["1", "1.250", "0.500", "4"]
    assert [i["unit"] for i in parsed["items"]] == ["шт", "кг", "кг", "шт"]
    assert parsed["items"][3]["name"] == "LAPTE concentrat cu zahar"
    assert not parse_mev(text.replace("Articole 4", "Articole 5"))["readable"]


def test_upload_dedup_and_receipt_math(client, accounts, categories):
    files = {"files": ("receipt.png", photo(), "image/png")}
    first = client.post("/api/receipts/upload", files=files, data={"account_id": accounts[0]["id"]})
    assert first.status_code == 200, first.text
    rid = first.json()["receipt"]["id"]
    repeated = client.post(
        "/api/receipts/upload", files=files, data={"account_id": accounts[0]["id"]}
    )
    assert repeated.json()["duplicate"] is True
    assert repeated.json()["receipt"]["id"] == rid
    with SessionLocal() as db:
        db.get(m.Receipt, rid).status = "review"
        db.commit()
    payload = {
        "merchant": "Тест",
        "purchased_on": "2025-02-15",
        "currency": "MDL",
        "total": "15",
        "account_id": accounts[0]["id"],
        "version": 1,
        "items": [
            {
                "name": "Молоко",
                "quantity": "1",
                "unit": "шт",
                "unit_price": "10",
                "total": "10",
                "category_id": categories[0]["id"],
            }
        ],
    }
    assert client.post(f"/api/receipts/{rid}/confirm", json=payload).status_code == 422
    payload["total"] = "10"
    result = client.post(f"/api/receipts/{rid}/confirm", json=payload)
    assert result.status_code == 200, result.text
    assert result.json()["status"] == "posted"
    assert client.post(f"/api/receipts/{rid}/confirm", json=payload).status_code == 200
    assert client.get("/api/transactions").json()["total"] == 1
    assert client.get("/api/accounts").json()[0]["balance_minor"] == -1000


def test_invalid_image(client):
    result = client.post(
        "/api/receipts/upload",
        files={"files": ("receipt.jpg", b"<svg onload=alert(1)>", "image/jpeg")},
    )
    assert result.status_code == 422


def test_linked_receipt_keeps_foreign_category_cents(client, owner, categories):
    account = client.post("/api/accounts", json={"name": "FX", "currency": "RON"}).json()
    transaction = client.post(
        "/api/transactions",
        json={
            "amount": "0.04",
            "account_id": account["id"],
            "occurred_on": "2025-02-15",
            "fx_rate": "0.6",
            "idempotency_key": str(uuid4()),
        },
    ).json()
    with SessionLocal() as db:
        receipt = m.Receipt(
            organization_id=owner["id"], source="photo", source_key=uuid4().hex, status="review"
        )
        db.add(receipt)
        db.commit()
        rid = receipt.id
    response = client.post(
        f"/api/receipts/{rid}/confirm",
        json={
            "merchant": "FX receipt",
            "purchased_on": "2025-02-15",
            "currency": "RON",
            "total": "0.04",
            "fx_rate": "0.7",
            "account_id": account["id"],
            "transaction_id": transaction["id"],
            "version": 1,
            "items": [
                {
                    "name": f"Item {index}",
                    "quantity": "1",
                    "unit": "шт",
                    "unit_price": "0.01",
                    "total": "0.01",
                    "category_id": category["id"],
                }
                for index, category in enumerate(categories[:4])
            ],
        },
    )
    assert response.status_code == 200, response.text
    assert Decimal(response.json()["fx_rate"]) == Decimal("0.6")
    report = client.get("/api/dashboard?month=2025-02").json()
    assert report["expense_minor"] == 2
    assert sum(c["spent_minor"] for c in report["categories"]) == 2
    assert all(c["spent_minor"] >= 0 for c in report["categories"])
    assert client.get("/api/transactions").json()["total"] == 1


def test_test_harness_refuses_preloaded_production_database(monkeypatch):
    from types import SimpleNamespace

    import conftest
    from sqlalchemy.engine import make_url

    monkeypatch.setattr(
        conftest, "engine", SimpleNamespace(url=make_url("postgresql://localhost/finance"))
    )
    with pytest.raises(RuntimeError, match="Refusing to reset"):
        conftest.assert_isolated_database()


def test_settings_never_return_keys(client):
    key = "sk-test-" + secrets.token_hex(10)
    result = client.put(
        "/api/settings",
        json={"provider": "openai", "model": "test", "vision_model": "test", "openai_key": key},
    )
    assert result.status_code == 200
    settings = client.get("/api/settings")
    assert settings.json()["has_openai_key"]
    assert key not in settings.text
    assert key not in client.get("/api/export.json").text
    with SessionLocal() as db:
        assert db.scalar(select(m.Preferences.openai_key)) != key


def test_export_csv_formula_escape(client, accounts):
    client.post(
        "/api/transactions",
        json={
            "amount": "1",
            "account_id": accounts[0]["id"],
            "occurred_on": "2025-02-15",
            "merchant": "=HYPERLINK(attack)",
            "idempotency_key": str(uuid4()),
        },
    )
    csv = client.get("/api/export.csv").text
    assert "'=HYPERLINK" in csv


def test_provider_contract_and_cpu_only(client, owner, monkeypatch):
    captured = []
    real_client = httpx.AsyncClient

    def handle(request):
        body = json.loads(request.content)
        captured.append((request, body))
        if request.url.host == "ollama":
            return httpx.Response(
                200,
                json={
                    "message": {"content": '{"ok":true}'},
                    "prompt_eval_count": 10,
                    "eval_count": 5,
                },
            )
        return httpx.Response(
            200,
            json={
                "status": "completed",
                "output": [{"content": [{"type": "output_text", "text": '{"ok":true}'}]}],
                "usage": {"input_tokens": 10, "output_tokens": 5},
            },
        )

    monkeypatch.setattr(
        ai.httpx,
        "AsyncClient",
        lambda **kwargs: real_client(**kwargs, transport=httpx.MockTransport(handle)),
    )
    schema = {
        "type": "object",
        "properties": {"ok": {"type": "boolean"}},
        "required": ["ok"],
        "additionalProperties": False,
    }
    import asyncio

    with SessionLocal() as db:
        p = db.scalar(select(m.Preferences))
        p.provider, p.model = "ollama", "qwen3:4b"
        db.commit()
    result, provider = asyncio.run(ai.generate(owner["id"], "test", "test", "test", schema))
    assert result == {"ok": True} and provider == "ollama"
    assert captured[0][1]["options"]["num_gpu"] == 0
    assert captured[0][1]["format"] == schema
    with SessionLocal() as db:
        p = db.scalar(select(m.Preferences))
        p.provider, p.openai_key = "openai", encrypt("test-key")
        db.commit()
    asyncio.run(ai.generate(owner["id"], "test", "test", "test", schema, [photo()]))
    assert captured[1][1]["store"] is False
    assert captured[1][1]["text"]["format"]["strict"] is True
    assert captured[1][0].headers["authorization"] == "Bearer test-key"
    assert captured[1][1]["input"][0]["content"][1]["type"] == "input_image"
