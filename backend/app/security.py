import base64
import hashlib
import secrets
from datetime import UTC, datetime, timedelta

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from cryptography.fernet import Fernet
from fastapi import Depends, HTTPException, Request, Response
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import models as m
from .config import settings
from .db import get_db

hasher = PasswordHasher()
dummy_hash = hasher.hash(secrets.token_urlsafe(32))


def encrypt(value: str) -> str:
    key = base64.urlsafe_b64encode(hashlib.sha256(settings().secret_key.encode()).digest())
    return Fernet(key).encrypt(value.encode()).decode()


def decrypt(value: str) -> str:
    key = base64.urlsafe_b64encode(hashlib.sha256(settings().secret_key.encode()).digest())
    return Fernet(key).decrypt(value.encode()).decode()


def verify_password(password: str, hashed: str) -> bool:
    try:
        return hasher.verify(hashed, password)
    except (VerificationError, InvalidHashError):
        return False


def lock_login_attempts(db: Session, keys: list[str]) -> None:
    """Serialize overlapping admission checks across API processes, before Argon2."""
    if db.bind.dialect.name == "postgresql":
        for key in sorted(keys):
            lock_id = int.from_bytes(
                hashlib.sha256(("finora-login:" + key).encode()).digest()[:8], signed=True
            )
            db.execute(select(func.pg_advisory_xact_lock(lock_id)))
    # SQLite's following DELETE acquires the database write lock before the count.


def lock_user_credentials(db: Session, user_id: str) -> m.User | None:
    # NO KEY UPDATE does not block concurrent foreign-key references (memberships).
    return db.scalar(
        select(m.User)
        .where(m.User.id == user_id)
        .with_for_update(key_share=True)
        .execution_options(populate_existing=True)
    )


def issue_session(db: Session, user: m.User, request: Request, response: Response):
    token = secrets.token_urlsafe(48)
    session = m.Session(
        user_id=user.id,
        token_hash=hashlib.sha256(token.encode()).hexdigest(),
        csrf=secrets.token_hex(24),
        expires_at=datetime.now(UTC) + timedelta(days=settings().session_days),
        device=request.headers.get("user-agent", "Unknown")[:200],
    )
    db.add(session)
    response.set_cookie(
        "finance_session",
        token,
        httponly=True,
        secure=settings().cookie_secure,
        samesite="strict",
        max_age=settings().session_days * 86400,
        path="/",
    )
    return session


def current_user(request: Request, db: Session = Depends(get_db)) -> m.User:
    token = request.cookies.get("finance_session", "")
    session = (
        db.scalar(
            select(m.Session).where(
                m.Session.token_hash == hashlib.sha256(token.encode()).hexdigest(),
                m.Session.expires_at > datetime.now(UTC),
            )
        )
        if token
        else None
    )
    if session is None:
        raise HTTPException(401, "Войдите в свой аккаунт")
    if request.method not in {"GET", "HEAD", "OPTIONS"}:
        if not secrets.compare_digest(request.headers.get("x-csrf-token", ""), session.csrf):
            raise HTTPException(403, "Обновите страницу и повторите действие")
    request.state.session = session
    user = db.get(m.User, session.user_id)
    if user is None or not user.is_active:
        raise HTTPException(401, "Войдите в свой аккаунт")
    db.info["actor_id"] = user.id
    return user


def server_admin(user: m.User = Depends(current_user)) -> m.User:
    if not user.is_server_admin:
        raise HTTPException(403, "Нужны права владельца сервера")
    return user


def current_organization(
    request: Request, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> m.Organization:
    key = request.headers.get("x-organization-id") or request.query_params.get("organization_id")
    memberships = select(m.Membership).where(m.Membership.user_id == user.id)
    if key:
        memberships = memberships.where(m.Membership.organization_id == key)
    rows = list(db.scalars(memberships.limit(2)))
    if not rows:
        raise HTTPException(403, "Нет доступа к этой организации")
    if len(rows) != 1:
        raise HTTPException(409, "Выберите организацию")
    membership = rows[0]
    path = request.scope.get("route").path
    writes = request.method not in {"GET", "HEAD", "OPTIONS"}
    receipt_add = path in {"/api/receipts/upload", "/api/receipts/link"}
    if receipt_add and not key:
        raise HTTPException(409, "Перед добавлением чека выберите организацию")
    if membership.role != "admin":
        allowed_write = receipt_add or path in {
            "/api/chat",
            "/api/receipts/{key}/comments",
            "/api/receipts/{key}/accept",
            "/api/receipts/{key}/review",
        }
        admin_read = path.startswith(("/api/ai/", "/api/audit", "/api/export", "/api/rules"))
        if (writes and not allowed_write) or admin_read:
            raise HTTPException(403, "Это действие доступно администратору организации")
    request.state.membership = membership
    db.info["membership_role"] = membership.role
    return db.get(m.Organization, membership.organization_id)
