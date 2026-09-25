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
    PublicHttpsProxy,
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


def test_https_tunnel_pins_dns_and_preserves_browser_bytes(monkeypatch):
    import app.web_receipts as web

    resolutions = []

    async def resolve(host):
        resolutions.append(host)
        return "93.184.216.34"

    monkeypatch.setattr(web, "public_ip", resolve)

    async def run():
        async def echo(reader, writer):
            try:
                writer.write(await reader.read(100))
                await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(echo, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        connect = asyncio.open_connection
        destinations = []

        async def pinned(address, port_number):
            destinations.append((address, port_number))
            assert (address, port_number) == ("93.184.216.34", 443)
            return await connect("127.0.0.1", port)

        monkeypatch.setattr(web.asyncio, "open_connection", pinned)
        try:
            async with PublicHttpsProxy() as proxy:
                for _ in range(2):
                    reader, writer = await connect("127.0.0.1", proxy.port)
                    writer.write(
                        b"CONNECT shop.example:443 HTTP/1.1\r\nHost: shop.example:443\r\n\r\n"
                    )
                    await writer.drain()
                    assert (await reader.readuntil(b"\r\n\r\n")).startswith(b"HTTP/1.1 200")
                    # TLS remains opaque: no MITM, header rewriting or certificate bypass.
                    opaque = b"\x16\x03\x01browser-TLS-bytes"
                    writer.write(opaque)
                    await writer.drain()
                    assert await reader.readexactly(len(opaque)) == opaque
                    writer.close()
                    await writer.wait_closed()
                for request in [
                    b"CONNECT 127.0.0.1:443 HTTP/1.1",
                    b"CONNECT shop.example:8080 HTTP/1.1",
                    b"GET http://shop.example/ HTTP/1.1",
                    b"CONNECT user@shop.example:443 HTTP/1.1",
                ]:
                    reader, writer = await connect("127.0.0.1", proxy.port)
                    writer.write(request + b"\r\n\r\n")
                    await writer.drain()
                    assert await reader.read(100) == b""
                    writer.close()
                    await writer.wait_closed()
            assert len(destinations) == 2
            assert resolutions == ["shop.example"]
        finally:
            server.close()
            await server.wait_closed()

    asyncio.run(run())


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
