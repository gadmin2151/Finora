"""Explicit database runtime overlay; bootstrap/security settings stay environment-only."""

from .config import settings


def runtime_snapshot(db=None, *, service="api", observe=False):
    from .db import SessionLocal
    from .system_settings_service import read_effective, record_runtime_observation

    if db is not None:
        return read_effective(settings(), db)
    with SessionLocal() as session:
        snapshot = read_effective(settings(), session)
        if observe:
            from .models import now

            record_runtime_observation(session, service, snapshot.revision, now())
            session.commit()
        return snapshot


def runtime_value(name, db=None, *, service="api"):
    return runtime_snapshot(db, service=service).values[name]


def openai_key(org_encrypted=None, db=None, *, service="worker"):
    from .security import decrypt

    if org_encrypted:
        return decrypt(org_encrypted)
    return runtime_snapshot(db, service=service).secret_getter("global_openai_key")
