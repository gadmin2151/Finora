"""Runtime wiring uses fake providers only; never probes ADC or sends externally."""

import asyncio
from types import SimpleNamespace

import pytest

from app import push_runtime
from app.push_provider import DisabledProvider


@pytest.fixture(autouse=True)
def reset_runtime():
    push_runtime.configured_provider.cache_clear()
    yield
    push_runtime.configured_provider.cache_clear()


def config(monkeypatch, enabled=True, worker=True):
    monkeypatch.setattr(
        push_runtime,
        "settings",
        lambda: SimpleNamespace(
            push_enabled=enabled, push_worker_enabled=worker, push_fcm_project_id="finora-5fb96"
        ),
    )


@pytest.mark.parametrize("enabled,worker", [(False, False), (False, True), (True, False)])
def test_disabled_never_discovers_credentials(monkeypatch, enabled, worker):
    config(monkeypatch, enabled, worker)
    monkeypatch.setattr(
        push_runtime, "create_fcm_provider", lambda *a: pytest.fail("ADC forbidden")
    )
    assert push_runtime.delivery_capabilities() == {"delivery_configured": False, "provider": None}


def test_valid_provider_is_cached_and_advertised(monkeypatch):
    config(monkeypatch)
    calls = []
    provider = SimpleNamespace(active=True, name="fcm")
    monkeypatch.setattr(
        push_runtime, "create_fcm_provider", lambda value: calls.append(value) or provider
    )
    assert push_runtime.delivery_capabilities() == {"delivery_configured": True, "provider": "fcm"}
    assert push_runtime.configured_provider() is provider
    assert len(calls) == 1


def test_missing_or_invalid_credentials_cannot_advertise(monkeypatch):
    config(monkeypatch)
    monkeypatch.setattr(push_runtime, "create_fcm_provider", lambda value: DisabledProvider())
    assert not push_runtime.delivery_capabilities()["delivery_configured"]


def test_bad_configuration_is_fail_closed(monkeypatch):
    config(monkeypatch)

    def invalid(value):
        raise ValueError("synthetic")

    monkeypatch.setattr(push_runtime, "create_fcm_provider", invalid)
    assert not push_runtime.delivery_capabilities()["delivery_configured"]


def test_disabled_worker_polls_configuration_without_adc_or_dispatch(monkeypatch):
    from app import push_worker
    from app.db import SessionLocal

    config(monkeypatch, False, False)
    stopped = [False]
    monkeypatch.setattr(
        push_runtime, "create_fcm_provider", lambda *a: pytest.fail("ADC forbidden")
    )
    monkeypatch.setattr(push_worker, "deliver_one", lambda *a: pytest.fail("dispatch forbidden"))

    async def no_wait(seconds):
        stopped[0] = True

    monkeypatch.setattr(push_runtime.asyncio, "sleep", no_wait)
    asyncio.run(push_runtime.run_push(SessionLocal, lambda: stopped[0]))


def test_api_uses_runtime_readiness(client, monkeypatch):
    monkeypatch.setattr(
        "app.push_api.delivery_capabilities",
        lambda: {"delivery_configured": True, "provider": "fcm"},
    )
    result = client.get("/api/push/capabilities")
    assert result.status_code == 200
    assert result.json()["delivery_configured"] is True
    assert result.json()["provider"] == "fcm"


def test_active_worker_schedules_and_dispatches_with_same_provider(monkeypatch):
    from app import push_worker

    config(monkeypatch)
    provider = SimpleNamespace(active=True, name="fcm")
    monkeypatch.setattr(push_runtime, "create_fcm_provider", lambda value: provider)
    calls = []
    stopped = [False]
    monkeypatch.setattr(
        push_runtime, "schedule_tick", lambda factory, now: calls.append("schedule")
    )

    def deliver(factory, selected):
        assert selected is provider
        calls.append("dispatch")
        stopped[0] = True
        return "idle"

    monkeypatch.setattr(push_worker, "deliver_one", deliver)

    async def no_wait(seconds):
        pass

    monkeypatch.setattr(push_runtime.asyncio, "sleep", no_wait)
    from app.db import SessionLocal

    asyncio.run(push_runtime.run_push(SessionLocal, lambda: stopped[0]))
    assert calls == ["schedule", "dispatch"]
