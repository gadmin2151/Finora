import asyncio
import ipaddress
from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal
from app.receipts import process_receipt
from app.web_receipts import (
    PageCapture,
    PublicPageFetcher,
    WebReceiptError,
    public_address,
    public_ip,
    receipt_link,
)


@pytest.mark.parametrize(
    "url",
    [
        "http://example.com/receipt",
        "https://user:pass@example.com/receipt",
        "https://127.0.0.1/",
        "https://10.0.0.1/",
        "https://169.254.169.254/latest",
        "https://[::1]/",
        "https://[::ffff:127.0.0.1]/",
        "https://router.local/receipt",
        "https://localhost/",
        "https://example.com:8443/",
        "file:///etc/passwd",
        "https://example.com/\\@localhost",
        "https://example.com/\nfoo",
    ],
)
def test_reject_non_public_receipt_links(url):
    with pytest.raises(WebReceiptError):
        receipt_link(url)


def test_preserve_provider_parameters_and_fragment():
    value = "https://shop.example/receipt?id=123&token=abc#/view"
    assert receipt_link(value) == value
    assert not public_address(ipaddress.ip_address("100.64.0.1"))
    assert not public_address(ipaddress.ip_address("224.0.0.1"))


def test_dns_rebinding_private_answer_is_rejected(monkeypatch):
    async def run():
        async def resolve(*args, **kwargs):
            return [(2, 1, 6, "", ("93.184.216.34", 443)), (2, 1, 6, "", ("127.0.0.1", 443))]

        monkeypatch.setattr(asyncio.get_running_loop(), "getaddrinfo", resolve)
        with pytest.raises(WebReceiptError):
            await public_ip("shop.example")

    asyncio.run(run())


def test_https_transport_pins_checked_ip_and_keeps_tls_hostname(monkeypatch):
    import httpx

    import app.web_receipts as web

    requests = []

    async def resolved(host):
        return "93.184.216.34"

    def respond(request):
        requests.append(request)
        return httpx.Response(200, text="receipt")

    factory = httpx.AsyncClient
    monkeypatch.setattr(web, "public_ip", resolved)
    monkeypatch.setattr(
        web.httpx,
        "AsyncClient",
        lambda **kwargs: factory(**kwargs, transport=httpx.MockTransport(respond)),
    )

    async def run():
        fetcher = PublicPageFetcher()
        try:
            assert (
                await fetcher.fetch(
                    "https://shop.example/receipt?id=1", {"authorization": "must-not-forward"}
                )
            )[0] == 200
        finally:
            await fetcher.stack.aclose()

    asyncio.run(run())
    assert requests[0].url.host == "93.184.216.34"
    assert requests[0].headers["host"] == "shop.example"
    assert requests[0].extensions["sni_hostname"] == "shop.example"
    assert "authorization" not in requests[0].headers


def test_redirects_are_bounded_and_never_forward_cross_host_cookies(monkeypatch):
    seen = []

    async def fetch(self, url, headers):
        seen.append((url, headers))
        if len(seen) == 1:
            return 302, {"location": "https://other.example/view"}, b""
        return 200, {"content-type": "text/html"}, b"receipt"

    monkeypatch.setattr(PublicPageFetcher, "fetch", fetch)
    result = asyncio.run(
        PublicPageFetcher().follow("https://shop.example/receipt", {"cookie": "private=1"})
    )
    assert result[0] == "https://other.example/view" and result[1] == 200
    assert "cookie" not in seen[1][1]

    async def redirect_local(self, url, headers):
        return 302, {"location": "https://127.0.0.1/secret"}, b""

    monkeypatch.setattr(PublicPageFetcher, "fetch", redirect_local)
    with pytest.raises(WebReceiptError):
        asyncio.run(PublicPageFetcher().follow("https://shop.example/receipt", {}))

    async def redirect_loop(self, url, headers):
        return 302, {"location": "/receipt"}, b""

    monkeypatch.setattr(PublicPageFetcher, "fetch", redirect_loop)
    with pytest.raises(WebReceiptError, match="перенаправлений"):
        asyncio.run(PublicPageFetcher().follow("https://shop.example/receipt", {}))


def test_concurrent_resources_share_one_pinned_connection_pool(monkeypatch):
    import httpx

    import app.web_receipts as web

    resolutions = []

    async def resolve(host):
        resolutions.append(host)
        await asyncio.sleep(0)
        return "93.184.216.34"

    factory = httpx.AsyncClient
    monkeypatch.setattr(web, "public_ip", resolve)
    monkeypatch.setattr(
        web.httpx,
        "AsyncClient",
        lambda **kwargs: factory(
            **kwargs,
            transport=httpx.MockTransport(lambda request: httpx.Response(200, text="resource")),
        ),
    )

    async def run():
        fetcher = PublicPageFetcher()
        try:
            responses = await asyncio.gather(
                *(fetcher.fetch(f"https://shop.example/{i}", {}) for i in range(6))
            )
            assert all(response[0] == 200 for response in responses)
        finally:
            await fetcher.stack.aclose()

    asyncio.run(run())
    assert resolutions == ["shop.example"]


def test_web_capture_requires_review_before_any_expense(client, accounts, owner, monkeypatch):
    import app.receipts as receipts

    response = client.post(
        "/api/receipts/link",
        json={"url": "https://shop.example/receipt?id=1", "account_id": accounts[0]["id"]},
    )
    assert response.status_code == 200, response.text
    rid = response.json()["receipt"]["id"]

    async def capture(url):
        return PageCapture(b"test-jpeg", "visible receipt", url)

    monkeypatch.setattr(receipts, "capture_receipt_page", capture)
    monkeypatch.setattr(
        receipts,
        "local_ocr",
        lambda images: (
            "Test Market\nIDNO 123\nPAINE\n1 buc x 12.00=12.00\nTOTAL LEI 12.00\nArticole 1\n25-09-2025"
        ),
    )
    with SessionLocal() as db:
        prefs = db.scalar(select(m.Preferences).where(m.Preferences.organization_id == owner["id"]))
        prefs.provider, prefs.auto_post = "disabled", True
        db.commit()
    asyncio.run(process_receipt(rid))
    preview = client.get(f"/api/receipts/{rid}").json()
    assert preview["status"] == "review"
    assert preview["review_required"] and preview["created_by"] == owner["id"]
    assert preview["total_minor"] == 1200 and len(preview["items"]) == 1
    assert len(preview["files"]) == 1
    assert client.get("/api/transactions").json()["total"] == 0
    body = {"version": preview["version"], "account_id": accounts[0]["id"]}
    assert client.post(f"/api/receipts/{rid}/accept", json=body).status_code == 200
    assert client.post(f"/api/receipts/{rid}/accept", json=body).status_code == 200
    assert client.get("/api/transactions").json()["total"] == 1


def test_accept_stale_version_and_no_price_override(client, accounts, owner):
    with SessionLocal() as db:
        row = m.Receipt(
            organization_id=owner["id"],
            source="web",
            source_key="review",
            status="review",
            created_by=owner["id"],
            review_required=True,
            merchant="Shop",
            purchased_on=date(2025, 9, 25),
            total_minor=1200,
            version=3,
        )
        db.add(row)
        db.flush()
        db.add(
            m.ReceiptItem(
                receipt_id=row.id,
                name="PAINE",
                normalized_name="paine",
                quantity=Decimal(1),
                unit="шт",
                unit_price_minor=1200,
                total_minor=1200,
            )
        )
        db.commit()
        rid = row.id
    response = client.post(
        f"/api/receipts/{rid}/accept", json={"version": 2, "account_id": accounts[0]["id"]}
    )
    assert response.status_code == 409
    response = client.post(
        f"/api/receipts/{rid}/accept",
        json={"version": 3, "account_id": accounts[0]["id"], "total": "1"},
    )
    assert response.status_code == 422
    assert client.get("/api/transactions").json()["total"] == 0
