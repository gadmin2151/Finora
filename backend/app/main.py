import logging
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from sqlalchemy.exc import IntegrityError
from starlette.staticfiles import StaticFiles

from .api import router
from .config import settings
from .feature_api import router as feature_router
from .organizations import router as organization_router

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("finora")
app = FastAPI(title="Finora", version="1.0.0", docs_url=None, redoc_url=None, openapi_url=None)
app.include_router(router)
app.include_router(organization_router)
app.include_router(feature_router)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    # Never echo passwords, uploaded documents, or key values from invalid requests.
    fields = ", ".join(str(e["loc"][-1]) for e in exc.errors()[:5])
    return JSONResponse({"detail": "Проверьте заполнение полей: " + fields}, status_code=422)


@app.exception_handler(IntegrityError)
async def integrity_error(request, exc):
    logger.warning("data_conflict request_id=%s", getattr(request.state, "request_id", "unknown"))
    return JSONResponse(
        {"detail": "Такая запись уже существует или связана с другими данными"}, status_code=409
    )


@app.middleware("http")
async def guard(request: Request, call_next):
    request.state.request_id = str(uuid.uuid4())
    if request.method not in {"GET", "HEAD", "OPTIONS"}:
        origin = request.headers.get("origin")
        if origin and origin.rstrip("/") != settings().app_url.rstrip("/"):
            return JSONResponse({"detail": "Недопустимый адрес приложения"}, status_code=403)
        try:
            if (
                request.url.path == "/api/receipts/upload"
                and "content-length" not in request.headers
            ):
                return JSONResponse(
                    {"detail": "Для загрузки требуется размер файла"}, status_code=411
                )
            if int(request.headers.get("content-length", "0")) > 62 * 1024 * 1024:
                return JSONResponse({"detail": "Слишком большой запрос"}, status_code=413)
        except ValueError:
            return JSONResponse({"detail": "Некорректный запрос"}, status_code=400)
    started = time.monotonic()
    try:
        response = await call_next(request)
    except Exception as exc:
        logger.error(
            "request_failed id=%s error_type=%s", request.state.request_id, type(exc).__name__
        )
        return JSONResponse(
            {
                "detail": "Ошибка сервера. Повторите действие",
                "request_id": request.state.request_id,
            },
            status_code=500,
        )
    response.headers.update(
        {
            "X-Request-ID": request.state.request_id,
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Options": "DENY",
            "Referrer-Policy": "same-origin",
            "Permissions-Policy": "camera=(self), microphone=(), geolocation=()",
            "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
        }
    )
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    if settings().cookie_secure:
        response.headers["Strict-Transport-Security"] = "max-age=31536000"
    logger.info(
        "http id=%s method=%s status=%s duration_ms=%d",
        request.state.request_id,
        request.method,
        response.status_code,
        (time.monotonic() - started) * 1000,
    )
    return response


static = Path(__file__).resolve().parent.parent / "static"
if static.exists():
    app.mount("/assets", StaticFiles(directory=static / "assets"), name="assets")

    @app.get("/{path:path}")
    def web(path: str):
        if path.startswith("api/"):
            return JSONResponse({"detail": "Не найдено"}, status_code=404)
        if path in {"favicon.svg", "manifest.webmanifest", "sw.js"}:
            return FileResponse(static / path)
        return FileResponse(static / "index.html", headers={"Cache-Control": "no-cache"})
