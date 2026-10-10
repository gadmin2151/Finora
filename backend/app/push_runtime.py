"""Opt-in process-local provider; disabled configuration never probes ADC or the database."""

import asyncio
import logging
from functools import lru_cache

from .config import settings
from .push_fcm import FCMConfig, create_fcm_provider
from .push_provider import DisabledProvider

logger = logging.getLogger("finora.push")


@lru_cache(maxsize=1)
def configured_provider():
    config = settings()
    if not config.push_enabled or not config.push_worker_enabled:
        return DisabledProvider()
    try:
        return create_fcm_provider(FCMConfig(config.push_fcm_project_id, True))
    except Exception:
        # Never log ADC paths, credential values, provider response or device tokens.
        logger.warning("push_configuration_unavailable")
        return DisabledProvider()


def delivery_capabilities():
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

    provider = configured_provider()
    if not provider.active:
        return
    next_schedule = 0.0
    while not stopping():
        try:
            loop = asyncio.get_running_loop()
            if loop.time() >= next_schedule:
                await asyncio.to_thread(schedule_tick, session_factory, m.now())
                next_schedule = loop.time() + 60
            result = await asyncio.to_thread(deliver_one, session_factory, provider)
        except Exception:
            logger.warning("push_tick_failed")
            result = "idle"
        await asyncio.sleep(1 if result not in {"idle", "disabled"} else 5)
