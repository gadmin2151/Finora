import logging
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from sqlalchemy.exc import IntegrityError
from starlette.exceptions import HTTPException
from starlette.staticfiles import StaticFiles

from .api import router
from .config import settings
from .feature_api import router as feature_router
from .i18n import LocaleMiddleware, t
from .organizations import router as organization_router
from .request_limits import RequestBodyLimit
from .users import router as user_router

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("finora")
app = FastAPI(title="Finora", version="1.0.0", docs_url=None, redoc_url=None, openapi_url=None)
app.include_router(router)
app.include_router(organization_router)
app.include_router(feature_router)
app.include_router(user_router)
app.add_middleware(RequestBodyLimit)


@app.exception_handler(HTTPException)
async def http_error(request, exc):
    detail = t(exc.detail) if isinstance(exc.detail, str) else exc.detail
    return JSONResponse({"detail": detail}, status_code=exc.status_code, headers=exc.headers)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    # Never echo passwords, uploaded documents, or key values from invalid requests.
    fields = ", ".join(str(e["loc"][-1]) for e in exc.errors()[:5])
    return JSONResponse(
        {"detail": t("Проверьте заполнение полей: {fields}", fields=fields)}, status_code=422
    )


@app.exception_handler(IntegrityError)
async def integrity_error(request, exc):
    logger.warning("data_conflict request_id=%s", getattr(request.state, "request_id", "unknown"))
    return JSONResponse(
        {"detail": t("Такая запись уже существует или связана с другими данными")}, status_code=409
    )


@app.middleware("http")
async def guard(request: Request, call_next):
    request.state.request_id = str(uuid.uuid4())
    if request.method not in {"GET", "HEAD", "OPTIONS"}:
        origin = request.headers.get("origin")
        if origin and origin.rstrip("/") != settings().app_url.rstrip("/"):
            return JSONResponse({"detail": t("Недопустимый адрес приложения")}, status_code=403)
    started = time.monotonic()
    try:
        response = await call_next(request)
    except Exception as exc:
        logger.error(
            "request_failed id=%s error_type=%s", request.state.request_id, type(exc).__name__
        )
        return JSONResponse(
            {
                "detail": t("Ошибка сервера. Повторите действие"),
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


# Outermost application middleware: include security and request-size failures.
app.add_middleware(LocaleMiddleware)


static = Path(__file__).resolve().parent.parent / "static"
if static.exists():
    app.mount("/assets", StaticFiles(directory=static / "assets"), name="assets")

    @app.get("/{path:path}")
    def web(path: str):
        if path.startswith("api/"):
            return JSONResponse({"detail": t("Не найдено")}, status_code=404)
        if path in {"favicon.svg", "finora-icon.png", "manifest.webmanifest", "sw.js"}:
            return FileResponse(static / path)
        return FileResponse(static / "index.html", headers={"Cache-Control": "no-cache"})
