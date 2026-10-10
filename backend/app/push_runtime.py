"""Revision-aware process provider; disabled polling never probes ADC or dispatches."""

import asyncio
import logging
from threading import RLock
from time import monotonic

from .config import settings
from .push_fcm import FCMConfig, create_fcm_provider
from .push_provider import DisabledProvider

logger = logging.getLogger("finora.push")


_provider_revision = None
_provider = None
_provider_lock = RLock()
_failed_identity = None
_retry_after = 0.0


def _clear_provider():
    global _provider_revision, _provider, _failed_identity, _retry_after
    _failed_identity, _retry_after = None, 0.0
    old = _provider
    _provider_revision = None
    _provider = None
    client = getattr(old, "client", None)
    if client is not None:
        client.close()


def _configured_provider(*, service="api", session_factory=None):
    """Resolve persisted revision at a delivery boundary; never probe ADC while off."""
    global _provider_revision, _provider, _failed_identity, _retry_after
    from .db import SessionLocal
    from .models import now
    from .system_settings_service import read_effective, record_runtime_observation

    factory = session_factory or SessionLocal
    try:
        with factory() as db:
            snapshot = read_effective(settings(), db)
            identity = (
                snapshot.revision,
                snapshot.values["push_enabled"],
                snapshot.values["push_worker_enabled"],
                snapshot.values["push_fcm_project_id"],
                snapshot.secret_modes["firebase_service_account"],
            )
            if identity != _provider_revision:
                if identity == _failed_identity and monotonic() < _retry_after:
                    return DisabledProvider()
                candidate = DisabledProvider()
                if identity[1] and identity[2] and identity[4] != "disable":
                    credential_json = snapshot.secret_getter("firebase_service_account")
                    if credential_json is None:
                        candidate = create_fcm_provider(FCMConfig(identity[3], True))
                    else:
                        import json

                        from google.oauth2 import service_account

                        payload = json.loads(credential_json)

                        def loader(**kwargs):
                            return (
                                service_account.Credentials.from_service_account_info(
                                    payload, scopes=kwargs["scopes"]
                                ),
                                identity[3],
                            )

                        candidate = create_fcm_provider(
                            FCMConfig(identity[3], True), credentials_loader=loader
                        )
                _clear_provider()
                _provider = candidate
                if (
                    identity[1]
                    and identity[2]
                    and identity[4] != "disable"
                    and not candidate.active
                ):
                    _failed_identity, _retry_after = identity, monotonic() + 30
                    return candidate
                _provider_revision = identity
                _failed_identity, _retry_after = None, 0.0
            if not (identity[1] and identity[2] and identity[4] != "disable") or _provider.active:
                record_runtime_observation(db, service, snapshot.revision, now())
                db.commit()
            return _provider
    except Exception:
        _clear_provider()
        logger.warning("push_configuration_unavailable")
        return DisabledProvider()


def clear_provider():
    with _provider_lock:
        _clear_provider()


def configured_provider(*, service="api", session_factory=None):
    with _provider_lock:
        return _configured_provider(service=service, session_factory=session_factory)


# Existing test/admin callers can explicitly invalidate the process cache.
configured_provider.cache_clear = clear_provider


def delivery_capabilities():
    with _provider_lock:
        provider = configured_provider()
        return {
            "delivery_configured": provider.active,
            "provider": provider.name if provider.active else None,
        }


def schedule_tick(session_factory, now):
    from .push_scheduler import schedule_payments
    from .push_updates import schedule_updates

    cursor = ""
    while True:
        with session_factory() as db:
            _, next_cursor = schedule_payments(db, now, after_id=cursor)
            db.commit()
        if next_cursor is None:
            break
        cursor = next_cursor
    cursor = None
    while True:
        with session_factory() as db:
            _, next_cursor = schedule_updates(db, now=now, after_id=cursor)
            db.commit()
        if next_cursor is None:
            return
        cursor = next_cursor


async def run_push(session_factory, stopping):
    from . import models as m
    from .push_worker import deliver_one

    next_schedule = 0.0
    while not stopping():
        try:
            provider = await asyncio.to_thread(
                configured_provider, service="worker", session_factory=session_factory
            )
            if not provider.active:
                await asyncio.sleep(5)
                continue
            loop = asyncio.get_running_loop()
            if loop.time() >= next_schedule:
                await asyncio.to_thread(schedule_tick, session_factory, m.now())
                next_schedule = loop.time() + 60
            result = await asyncio.to_thread(deliver_one, session_factory, provider)
        except Exception:
            logger.warning("push_tick_failed")
            result = "idle"
        await asyncio.sleep(1 if result not in {"idle", "disabled"} else 5)
