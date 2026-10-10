"""Runtime consumption with synthetic keys and fake providers only."""

from types import SimpleNamespace

import pytest

from app import push_runtime
from app.config import settings
from app.db import SessionLocal
from app.runtime_config import openai_key, runtime_value
from app.system_settings_models import SystemRuntimeObservation
from app.system_settings_service import apply_changes


@pytest.fixture(autouse=True)
def clear_cache():
    push_runtime.configured_provider.cache_clear()
    yield
    push_runtime.configured_provider.cache_clear()


def save(owner, changes=None, secrets=None, revision=0):
    with SessionLocal() as db:
        from app.models import User

        db.get(User, owner["id"]).is_server_admin = True
        db.flush()
        apply_changes(
            db,
            env=settings(),
            actor_id=owner["id"],
            revision=revision,
            changes=changes or {},
            secrets=secrets or {},
        )
        db.commit()


def test_new_requests_use_persisted_public_values_after_cache_clear(owner):
    save(owner, {"max_upload_mb": 2, "ai_timeout": 45, "ai_cpu_threads": 3, "mev_browser": False})
    assert runtime_value("max_upload_mb") == 2
    assert runtime_value("ai_timeout") == 45
    settings.cache_clear()
    assert runtime_value("ai_cpu_threads") == 3
    assert runtime_value("mev_browser") is False
    with SessionLocal() as db:
        assert db.get(SystemRuntimeObservation, "worker") is None


def test_global_key_fallback_and_explicit_disable(owner):
    from app.security import encrypt

    save(
        owner,
        secrets={
            "global_openai_key": {
                "action": "replace",
                "value": "synthetic-key-not-a-real-credential",
            }
        },
    )
    assert openai_key() == "synthetic-key-not-a-real-credential"
    assert openai_key(encrypt("organization-synthetic-key")) == "organization-synthetic-key"
    save(owner, secrets={"global_openai_key": {"action": "disable"}}, revision=1)
    assert openai_key() is None


def test_revision_changes_rebuild_provider_and_disable_without_adc(owner, monkeypatch):
    builds = []
    monkeypatch.setattr(
        push_runtime,
        "create_fcm_provider",
        lambda cfg: builds.append(cfg) or SimpleNamespace(active=True, name="fcm"),
    )
    save(
        owner,
        {
            "push_enabled": True,
            "push_worker_enabled": True,
            "push_fcm_project_id": "synthetic-project",
        },
    )
    assert push_runtime.configured_provider(service="worker").active
    assert len(builds) == 1
    save(owner, {"push_enabled": False}, revision=1)
    assert not push_runtime.configured_provider(service="worker").active
    assert len(builds) == 1
    with SessionLocal() as db:
        assert db.get(SystemRuntimeObservation, "worker").revision == 2


def test_transient_failure_retries_and_never_marks_applied(owner, monkeypatch):
    from app.push_provider import DisabledProvider

    clock = [0.0]
    calls = []
    monkeypatch.setattr(push_runtime, "monotonic", lambda: clock[0])

    def build(cfg):
        calls.append(cfg)
        return DisabledProvider() if len(calls) == 1 else SimpleNamespace(active=True, name="fcm")

    monkeypatch.setattr(push_runtime, "create_fcm_provider", build)
    save(
        owner,
        {
            "push_enabled": True,
            "push_worker_enabled": True,
            "push_fcm_project_id": "synthetic-project",
        },
    )
    assert not push_runtime.configured_provider(service="worker").active
    with SessionLocal() as db:
        assert db.get(SystemRuntimeObservation, "worker") is None
    clock[0] = 10
    assert not push_runtime.configured_provider(service="worker").active
    assert len(calls) == 1
    clock[0] = 31
    assert push_runtime.configured_provider(service="worker").active
    assert len(calls) == 2


def test_concurrent_capability_resolution_builds_only_one_provider(owner, monkeypatch):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event

    entered, release = Event(), Event()
    builds = []
    provider = SimpleNamespace(active=True, name="fcm")

    def build(cfg):
        builds.append(cfg)
        entered.set()
        assert release.wait(2)
        return provider

    monkeypatch.setattr(push_runtime, "create_fcm_provider", build)
    save(
        owner,
        {
            "push_enabled": True,
            "push_worker_enabled": True,
            "push_fcm_project_id": "synthetic-project",
        },
    )
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(push_runtime.configured_provider)
        assert entered.wait(2)
        second = pool.submit(push_runtime.configured_provider)
        release.set()
        assert first.result(timeout=3) is provider
        assert second.result(timeout=3) is provider
    assert len(builds) == 1
