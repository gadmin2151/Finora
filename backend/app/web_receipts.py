"""Render public receipt pages through a bounded, DNS-pinned HTTPS tunnel."""

import asyncio
import ipaddress
import re
import socket
from contextlib import suppress
from dataclasses import dataclass
from urllib.parse import urlsplit, urlunsplit


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
    return str(sorted(addresses, key=lambda address: address.version)[0])


@dataclass
class PageCapture:
    image: bytes
    text: str
    final_url: str


class PublicHttpsProxy:
    """Tunnel browser TLS unchanged, pinning every CONNECT to a checked numeric IP.

    Browser redirects and requests missed by Playwright interception still pass
    through this boundary. No HTTP forwarding, TLS interception or trusted CA.
    """

    max_bytes = 25_000_000

    def __init__(self):
        self.addresses: dict[str, str] = {}
        self.host_lock = asyncio.Lock()
        self.bytes_read = 0
        self.tasks: set[asyncio.Task] = set()
        self.server: asyncio.Server | None = None
        self.port = 0
        self.closing = False

    async def __aenter__(self):
        self.server = await asyncio.start_server(self.handle, "127.0.0.1", 0, limit=8192)
        self.port = self.server.sockets[0].getsockname()[1]
        return self

    async def __aexit__(self, *args):
        self.closing = True
        self.server.close()
        await self.server.wait_closed()
        tasks = tuple(self.tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def pump(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        while chunk := await reader.read(65536):
            self.bytes_read += len(chunk)
            if self.bytes_read > self.max_bytes:
                raise WebReceiptError("Страница чека слишком большая")
            writer.write(chunk)
            await writer.drain()

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        task = asyncio.current_task()
        self.tasks.add(task)
        remote = None
        pumps = []
        try:
            if self.closing or len(self.tasks) > 40:
                raise WebReceiptError("Слишком много подключений")
            header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 5)
            first_line = header.split(b"\r\n", 1)[0].decode("ascii")
            match = re.fullmatch(r"CONNECT ([a-zA-Z0-9.\[\]:-]+):443 HTTP/1\.[01]", first_line)
            if not match or len(header) > 8192:
                raise WebReceiptError("Разрешены только HTTPS-подключения")
            host = urlsplit(receipt_link("https://" + match[1])).hostname
            async with self.host_lock:
                if host not in self.addresses:
                    if len(self.addresses) >= 20:
                        raise WebReceiptError("Слишком много внешних ресурсов")
                    self.addresses[host] = await public_ip(host)
                address = self.addresses[host]
            upstream, remote = await asyncio.wait_for(asyncio.open_connection(address, 443), 8)
            writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            await writer.drain()
            pumps = [
                asyncio.create_task(self.pump(reader, remote)),
                asyncio.create_task(self.pump(upstream, writer)),
            ]
            done, _ = await asyncio.wait(pumps, return_when=asyncio.FIRST_COMPLETED)
            for finished in done:
                finished.result()
        except (
            WebReceiptError,
            OSError,
            TimeoutError,
            UnicodeError,
            asyncio.IncompleteReadError,
            asyncio.LimitOverrunError,
        ):
            # Close refused/failed tunnels; never reveal addresses or forward an
            # unvalidated request. Chromium reports the failed navigation normally.
            pass
        finally:
            for pump in pumps:
                pump.cancel()
            await asyncio.gather(*pumps, return_exceptions=True)
            for stream in (writer, remote):
                if stream is not None:
                    stream.close()
                    with suppress(OSError, TimeoutError):
                        await asyncio.wait_for(stream.wait_closed(), 1)
            self.tasks.discard(task)


async def capture_receipt_page(value: str) -> PageCapture:
    from playwright.async_api import Error as BrowserError
    from playwright.async_api import async_playwright

    url = receipt_link(value)
    try:
        async with (
            asyncio.timeout(55),
            PublicHttpsProxy() as proxy,
            async_playwright() as playwright,
        ):
            browser = await playwright.chromium.launch(
                headless=True,
                proxy={"server": f"http://127.0.0.1:{proxy.port}"},
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
                requests = 0

                async def route(request_route):
                    nonlocal requests
                    requests += 1
                    request = request_route.request
                    try:
                        receipt_link(request.url)
                    except WebReceiptError:
                        await request_route.abort()
                        return
                    # Anonymous page scripts may POST a receipt lookup or a site
                    # challenge to their own origin; never submit navigation forms.
                    same_origin_lookup = (
                        request.method == "POST"
                        and request.resource_type in {"xhr", "fetch"}
                        and urlsplit(request.url).netloc == urlsplit(page.url).netloc
                    )
                    if (
                        requests > 80
                        or (request.method not in {"GET", "HEAD"} and not same_origin_lookup)
                        or request.resource_type in {"media", "websocket"}
                    ):
                        await request_route.abort()
                        return
                    # The CONNECT proxy validates/pins every destination, including
                    # redirect chains that Playwright does not intercept a second time.
                    await request_route.continue_()

                await context.route("**/*", route)
                page = await context.new_page()
                page.on("popup", lambda popup: popup.close())
                document_response = None

                def response_received(response):
                    nonlocal document_response
                    if (
                        response.request.is_navigation_request()
                        and response.frame == page.main_frame
                    ):
                        document_response = response

                page.on("response", response_received)
                await page.goto(url, wait_until="domcontentloaded", timeout=35_000)
                try:
                    await page.wait_for_load_state("networkidle", timeout=8_000)
                except BrowserError:
                    pass  # Analytics can keep a fully visible receipt busy.
                await page.wait_for_timeout(1000)
                if proxy.bytes_read > proxy.max_bytes:
                    raise WebReceiptError("Страница чека слишком большая")
                if document_response is None or document_response.status >= 400:
                    raise WebReceiptError("Сайт не отдал чек. Попробуйте фотографию.")
                if "text/html" not in document_response.headers.get("content-type", ""):
                    raise WebReceiptError("По ссылке нет HTML-страницы. Загрузите фото чека.")
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
    except (BrowserError, TimeoutError, OSError) as exc:
        raise WebReceiptError("Сайт не загрузился. Попробуйте фото чека.") from exc
