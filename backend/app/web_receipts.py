"""Read public receipt pages through pinned HTTPS requests; render in an isolated browser."""

import asyncio
import ipaddress
import re
import socket
from contextlib import AsyncExitStack
from dataclasses import dataclass
from urllib.parse import urljoin, urlsplit, urlunsplit

import httpx


class WebReceiptError(ValueError):
    pass


def receipt_link(value: str) -> str:
    value = value.strip()
    if len(value) > 1000 or any(ord(c) < 33 for c in value) or "\\" in value:
        raise WebReceiptError("Некорректная ссылка чека")
    try:
        url = urlsplit(value)
        host = (url.hostname or "").encode("idna").decode("ascii").lower()
        port = url.port
    except (ValueError, UnicodeError) as exc:
        raise WebReceiptError("Некорректная ссылка чека") from exc
    if url.scheme != "https" or not host or url.username or url.password or port not in (None, 443):
        raise WebReceiptError("QR должен содержать HTTPS-ссылку без логина и пароля")
    if "%" in host or host.rstrip(".").endswith(
        (".local", ".localhost", ".internal", ".lan", ".home.arpa")
    ):
        raise WebReceiptError("Нужна публичная ссылка на чек")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if "." not in host.rstrip("."):
            raise WebReceiptError("Нужна публичная ссылка на чек") from None
    else:
        if not public_address(address):
            raise WebReceiptError("Локальные адреса недоступны")
    authority = f"[{host}]" if ":" in host else host
    return urlunsplit(("https", authority, url.path or "/", url.query, url.fragment))


def public_address(address) -> bool:
    return address.is_global and not address.is_multicast and not address.is_reserved


async def public_ip(host: str) -> str:
    try:
        entries = await asyncio.wait_for(
            asyncio.get_running_loop().getaddrinfo(host, 443, type=socket.SOCK_STREAM), 5
        )
    except (OSError, TimeoutError) as exc:
        raise WebReceiptError("Не удалось найти сайт чека") from exc
    addresses = [ipaddress.ip_address(row[4][0]) for row in entries]
    if not addresses or any(not public_address(address) for address in addresses):
        raise WebReceiptError("Сайт чека указывает на закрытый сетевой адрес")
    # Connect to this numeric address, never resolve the host a second time.
    return str(sorted(addresses, key=lambda address: address.version)[0])


@dataclass
class PageCapture:
    image: bytes
    text: str
    final_url: str


class PublicPageFetcher:
    def __init__(self):
        self.stack = AsyncExitStack()
        self.clients: dict[str, httpx.AsyncClient] = {}
        self.addresses: dict[str, str] = {}
        self.bytes_read = 0
        self.requests = 0
        self.limit = asyncio.Semaphore(6)
        self.host_lock = asyncio.Lock()

    async def fetch(self, value: str, headers: dict[str, str]) -> tuple[int, dict[str, str], bytes]:
        url = httpx.URL(receipt_link(value))
        self.requests += 1
        if self.requests > 80:
            raise WebReceiptError("На странице слишком много запросов")
        async with self.limit:
            async with self.host_lock:
                if url.host not in self.clients:
                    if len(self.clients) >= 20:
                        raise WebReceiptError("На странице слишком много внешних ресурсов")
                    address = await public_ip(url.host)
                    client = await self.stack.enter_async_context(
                        httpx.AsyncClient(timeout=12, follow_redirects=False, trust_env=False)
                    )
                    self.addresses[url.host], self.clients[url.host] = address, client
            safe_headers = {
                key: value
                for key, value in headers.items()
                if key.lower()
                in {"accept", "accept-language", "cookie", "user-agent", "origin", "referer"}
            }
            safe_headers["Host"] = url.host
            client = self.clients[url.host]
            async with client.stream(
                "GET",
                url.copy_with(host=self.addresses[url.host], fragment=None),
                headers=safe_headers,
                extensions={"sni_hostname": url.host},
            ) as response:
                data = bytearray()
                async for chunk in response.aiter_bytes():
                    data.extend(chunk)
                    self.bytes_read += len(chunk)
                    if len(data) > 5_000_000 or self.bytes_read > 25_000_000:
                        raise WebReceiptError("Страница чека слишком большая")
                result_headers = {
                    key: value
                    for key, value in response.headers.items()
                    if key.lower()
                    not in {
                        "content-encoding",
                        "content-length",
                        "transfer-encoding",
                        "connection",
                        "alt-svc",
                    }
                }
                return response.status_code, result_headers, bytes(data)

    async def follow(self, value: str, headers: dict[str, str]):
        """Resolve each redirect with the same DNS, TLS and resource limits."""
        url = receipt_link(value)
        for _ in range(6):
            status, response_headers, body = await self.fetch(url, headers)
            if status not in {301, 302, 303, 307, 308}:
                return url, status, response_headers, body
            location = response_headers.get("location")
            if not location:
                raise WebReceiptError("Сайт вернул перенаправление без адреса")
            target = receipt_link(urljoin(url, location))
            if urlsplit(target).hostname != urlsplit(url).hostname:
                headers = {key: value for key, value in headers.items() if key.lower() != "cookie"}
            url = target
        raise WebReceiptError("Слишком много перенаправлений на странице чека")


async def capture_receipt_page(value: str) -> PageCapture:
    from playwright.async_api import Error as BrowserError
    from playwright.async_api import async_playwright

    url = receipt_link(value)
    fetcher = PublicPageFetcher()
    failures: list[str] = []

    # All browser traffic must be fulfilled by the guarded fetcher. Any request
    # missed by interception reaches this closed proxy, never the local network.
    async def refuse_connection(reader, writer):
        writer.close()
        await writer.wait_closed()

    proxy = await asyncio.start_server(refuse_connection, "127.0.0.1", 0)
    proxy_port = proxy.sockets[0].getsockname()[1]
    try:
        async with asyncio.timeout(55), async_playwright() as playwright:
            # Playwright only intercepts the first URL in a browser redirect chain.
            # Resolve it here and navigate to the real document URL, so relative
            # scripts/styles use the right origin and no redirect bypasses the guard.
            url, status, headers, body = await fetcher.follow(url, {})
            prefetched = {url.split("#", 1)[0]: (status, headers, body)}
            browser = await playwright.chromium.launch(
                headless=True,
                proxy={"server": f"http://127.0.0.1:{proxy_port}"},
                args=[
                    "--disable-dev-shm-usage",
                    "--proxy-bypass-list=<-loopback>",
                    "--disable-quic",
                    "--disable-background-networking",
                    "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
                ],
            )
            try:
                context = await browser.new_context(
                    viewport={"width": 1100, "height": 900},
                    device_scale_factor=1,
                    service_workers="block",
                    accept_downloads=False,
                )
                await context.route_web_socket("**/*", lambda route: route.close())

                async def route(request_route):
                    request = request_route.request
                    if request.method not in {"GET", "HEAD"} or request.resource_type in {
                        "media",
                        "websocket",
                    }:
                        await request_route.abort()
                        return
                    try:
                        cached = prefetched.pop(request.url.split("#", 1)[0], None)
                        if cached is not None:
                            status, headers, body = cached
                        else:
                            _, status, headers, body = await fetcher.follow(
                                request.url, request.headers
                            )
                        await request_route.fulfill(status=status, headers=headers, body=body)
                    except (WebReceiptError, httpx.HTTPError, OSError):
                        if request.is_navigation_request():
                            failures.append("Не удалось безопасно загрузить страницу чека")
                        await request_route.abort()

                await context.route("**/*", route)
                page = await context.new_page()
                page.on("popup", lambda popup: popup.close())
                response = await page.goto(url, wait_until="domcontentloaded", timeout=35_000)
                if response is None or response.status >= 400:
                    raise WebReceiptError("Сайт не отдал чек. Попробуйте фотографию.")
                if "text/html" not in response.headers.get("content-type", ""):
                    raise WebReceiptError("По ссылке нет HTML-страницы. Загрузите фото чека.")
                # Let receipt scripts and fonts complete, with a fixed overall deadline.
                try:
                    await page.wait_for_load_state("networkidle", timeout=8_000)
                except BrowserError:
                    pass  # Analytics can keep a page busy; the visible receipt may be complete.
                await page.wait_for_timeout(1000)
                # Consent banners can cover the receipt in a full-page screenshot.
                # Decline optional cookies only; never accept consent or submit receipt forms.
                reject_cookies = page.get_by_role(
                    "button",
                    name=re.compile(
                        r"^(Refuză toate|Respinge toate|Reject all|Decline all|Отклонить все)$",
                        re.I,
                    ),
                )
                if await reject_cookies.count() and await reject_cookies.first.is_visible():
                    try:
                        await reject_cookies.first.click(timeout=2000)
                    except BrowserError:
                        pass  # An obstructed consent button must not discard the receipt.
                final_url = receipt_link(page.url)
                size = await page.evaluate(
                    "({width:document.documentElement.scrollWidth,height:document.documentElement.scrollHeight})"
                )
                if (
                    size["width"] > 2500
                    or size["height"] > 20000
                    or size["width"] * size["height"] > 25_000_000
                ):
                    raise WebReceiptError(
                        "Страница слишком длинная для полного снимка. Загрузите фото чека."
                    )
                image = await page.screenshot(
                    full_page=True, type="jpeg", quality=85, animations="disabled", timeout=10_000
                )
                if len(image) > 15_000_000:
                    raise WebReceiptError("Снимок страницы слишком большой")
                text = (await page.locator("body").inner_text(timeout=5000))[:50000]
                return PageCapture(image, text, final_url)
            finally:
                await browser.close()
    except (BrowserError, TimeoutError, httpx.HTTPError, OSError) as exc:
        raise WebReceiptError(
            failures[-1] if failures else "Сайт не загрузился. Попробуйте фото чека."
        ) from exc
    finally:
        proxy.close()
        await proxy.wait_closed()
        await fetcher.stack.aclose()
