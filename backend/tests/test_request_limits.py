import asyncio
import json

import pytest
from fastapi import FastAPI, Request, UploadFile
from starlette import formparsers

from app import request_limits


def body_app():
    app = FastAPI()
    app.add_middleware(request_limits.RequestBodyLimit)

    @app.post("/body")
    async def read_body(request: Request):
        return {"size": len(await request.body())}

    @app.post("/api/receipts/upload")
    async def upload(file: UploadFile):
        return {"size": len(await file.read())}

    return app


def send_chunks(app, chunks, *, headers=(), path="/body"):
    sent = []
    reads = 0

    async def run():
        nonlocal reads

        async def receive():
            nonlocal reads
            message = chunks[reads]
            reads += 1
            return message

        async def send(message):
            sent.append(message)

        await app(
            {
                "type": "http",
                "asgi": {"version": "3.0"},
                "http_version": "1.1",
                "method": "POST",
                "scheme": "http",
                "path": path,
                "raw_path": path.encode(),
                "query_string": b"",
                "headers": list(headers),
                "server": ("test", 80),
                "client": ("127.0.0.1", 1234),
                "root_path": "",
            },
            receive,
            send,
        )

    asyncio.run(run())
    status = next(message["status"] for message in sent if message["type"] == "http.response.start")
    body = b"".join(message.get("body", b"") for message in sent)
    return status, body, reads


def chunk(body, more=False):
    return {"type": "http.request", "body": body, "more_body": more}


@pytest.mark.parametrize(
    "headers", [[], [(b"transfer-encoding", b"chunked")], [(b"content-length", b"1")]]
)
@pytest.mark.parametrize(
    "content_type", [b"application/json", b"application/problem+json", b"text/plain"]
)
def test_counts_actual_bytes_and_stops_before_unread_tail(monkeypatch, headers, content_type):
    monkeypatch.setattr(request_limits, "MAX_REQUEST_BYTES", 64)
    status, _, reads = send_chunks(
        body_app(),
        [chunk(b" " * 32, True), chunk(b" " * 33, True), chunk(b"{}")],
        headers=[*headers, (b"content-type", content_type)],
    )
    assert status == 413
    assert reads == 2


def test_exact_limit_and_disconnect_are_preserved(monkeypatch):
    monkeypatch.setattr(request_limits, "MAX_REQUEST_BYTES", 64)
    status, body, reads = send_chunks(body_app(), [chunk(b"x" * 32, True), chunk(b"y" * 32)])
    assert status == 200 and json.loads(body) == {"size": 64} and reads == 2
    from starlette.requests import ClientDisconnect

    with pytest.raises(ClientDisconnect):
        send_chunks(body_app(), [chunk(b"x", True), {"type": "http.disconnect"}])


@pytest.mark.parametrize(
    "lengths", [[b"-1"], [b"+1"], [b"NaN"], [b"1", b"1"], [b"1, 1"], [b"9" * 5000]]
)
def test_invalid_lengths_rejected_before_reading(lengths):
    status, _, reads = send_chunks(
        body_app(), [], headers=[(b"content-length", v) for v in lengths]
    )
    assert status == 400 and reads == 0


def test_declared_oversize_rejected_before_reading():
    status, _, reads = send_chunks(body_app(), [], headers=[(b"content-length", b"999999999")])
    assert status == 413 and reads == 0


@pytest.mark.parametrize(
    "path",
    ["/api/receipts/upload", "/api/receipts/upload/", "/api/auth/avatar", "/api/auth/avatar/"],
)
def test_upload_length_contract_including_aliases(path):
    status, _, reads = send_chunks(body_app(), [], path=path)
    assert status == 411 and reads == 0


def test_partial_multipart_files_closed_on_overflow(monkeypatch):
    files = []
    original = formparsers.SpooledTemporaryFile

    def tracked_file(*args, **kwargs):
        file = original(*args, **kwargs)
        files.append(file)
        return file

    monkeypatch.setattr(formparsers, "SpooledTemporaryFile", tracked_file)
    monkeypatch.setattr(formparsers.MultiPartParser, "spool_max_size", 16)
    prefix = b'--finora\r\nContent-Disposition: form-data; name="file"; filename="receipt.jpg"\r\nContent-Type: image/jpeg\r\n\r\n'
    monkeypatch.setattr(request_limits, "MAX_REQUEST_BYTES", len(prefix) + 200)
    headers = [
        (b"content-length", b"1"),
        (b"content-type", b"multipart/form-data; boundary=finora"),
    ]
    status, _, reads = send_chunks(
        body_app(),
        [chunk(prefix + b"x" * 128, True), chunk(b"y" * 129, True), chunk(b"\r\n--finora--\r\n")],
        headers=headers,
        path="/api/receipts/upload",
    )
    assert status == 413 and reads == 2
    assert len(files) == 1 and files[0].closed and files[0]._rolled


def test_oversized_errors_keep_request_metadata(client):
    response = client.post(
        "/api/auth/login", content=b"{}", headers={"content-length": "999999999"}
    )
    assert response.status_code == 413
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-request-id"]
    assert response.headers["x-content-type-options"] == "nosniff"
