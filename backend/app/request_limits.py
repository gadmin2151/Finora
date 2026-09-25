"""Bound bytes before JSON/multipart parsers allocate or spool the request body."""

from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

MAX_REQUEST_BYTES = 62 * 1024 * 1024
MAX_AVATAR_REQUEST_BYTES = 5 * 1024 * 1024 + 65536
UPLOAD_PATHS = {"/api/receipts/upload", "/api/auth/avatar"}


class RequestBodyLimit:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        path = scope["path"].rstrip("/")
        avatar = path == "/api/auth/avatar"
        limit = MAX_AVATAR_REQUEST_BYTES if avatar else MAX_REQUEST_BYTES
        detail = "Выберите фотографию размером до 5 МБ" if avatar else "Слишком большой запрос"
        lengths = [value for name, value in scope["headers"] if name.lower() == b"content-length"]
        error = None
        if len(lengths) > 1 or (lengths and (not lengths[0].isdigit() or len(lengths[0]) > 20)):
            error = JSONResponse({"detail": "Некорректный размер запроса"}, status_code=400)
        elif lengths and int(lengths[0]) > limit:
            error = JSONResponse({"detail": detail}, status_code=413)
        elif not lengths and scope["method"] == "POST" and path in UPLOAD_PATHS:
            error = JSONResponse({"detail": "Для загрузки требуется размер файла"}, status_code=411)
        if error is not None:
            await error(scope, receive, send)
            return

        received = 0

        async def bounded_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    # Starlette's multipart parser closes partial UploadFiles on exceptions.
                    raise HTTPException(status_code=413, detail=detail)
            return message

        await self.app(scope, bounded_receive, send)
