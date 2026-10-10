"""Server-admin API, encrypted synthetic credentials and real DB runtime boundaries."""

import json

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from sqlalchemy import select

from app import models as m
from app.db import SessionLocal
from app.security import encrypt
from app.system_settings_models import (
    SystemRuntimeObservation,
    SystemSettingsAudit,
    SystemSettingsState,
)
from app.system_settings_service import (
    RuntimeSettingsUnavailable,
    public_view,
    read_effective,
    record_runtime_observation,
)

SECRET = "synthetic-openai-secret-never-return-this"


@pytest.fixture
def admin_client(client, owner):
    with SessionLocal() as db:
        db.get(m.User, owner["id"]).is_server_admin = True
        db.commit()
    return client


def view(client):
    response = client.get("/api/system/settings")
    assert response.status_code == 200
    return response.json()


def fields(response):
    return {field["name"]: field for group in response["groups"] for field in group["fields"]}


def put(client, revision=0, changes=None, secrets=None):
    return client.put(
        "/api/system/settings",
        json=dict(revision=revision, changes=changes or {}, secrets=secrets or {}),
    )


@pytest.fixture
def firebase_json():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()
    return dict(
        type="service_account",
        project_id="synthetic-project",
        private_key=private,
        private_key_id="synthetic-key-id",
        client_email="fixture@synthetic-project.iam.gserviceaccount.com",
        client_id="123456789",
        token_uri="https://oauth2.googleapis.com/token",
        auth_uri="https://accounts.google.com/o/oauth2/auth",
        universe_domain="googleapis.com",
    )


def test_org_admin_is_not_system_admin_and_csrf_required(client, admin_client):
    # Fixture client owner is org admin; explicitly remove system role first.
    with SessionLocal() as db:
        user = db.scalar(select(m.User))
        user.is_server_admin = False
        db.commit()
    assert client.get("/api/system/settings").status_code == 403
    assert put(client, changes={"ai_timeout": 30}).status_code == 403
    with SessionLocal() as db:
        db.scalar(select(m.User)).is_server_admin = True
        db.commit()
    headers = dict(client.headers)
    del client.headers["X-CSRF-Token"]
    assert put(client, changes={"ai_timeout": 30}).status_code == 403
    client.headers.update(headers)
    client.cookies.clear()
    assert client.get("/api/system/settings").status_code == 401
    assert put(client).status_code == 401


def test_public_typed_save_db_reload_fresh_session_and_runtime_observation(admin_client):
    initial = view(admin_client)
    assert initial["revision"] == 0
    changed = dict(
        ai_timeout=30,
        ai_cpu_threads=2,
        max_upload_mb=8,
        mev_browser=False,
        push_enabled=False,
        push_worker_enabled=False,
        push_fcm_project_id="synthetic-project",
    )
    saved = put(admin_client, changes=changed)
    assert saved.status_code == 200 and saved.json()["revision"] == 1
    assert saved.json()["saved"] is True
    assert view(admin_client)["revision"] == 1
    with SessionLocal() as db:
        snapshot = read_effective({"ai_timeout": 480}, db)
        assert snapshot.values == changed
        assert db.get(SystemSettingsState, 1).overrides == changed
        assert public_view({}, db)["runtime"]["worker"]["state"] == "unknown"
        record_runtime_observation(db, "api", snapshot.revision)
        record_runtime_observation(db, "worker", snapshot.revision)
        db.commit()
    assert snapshot.values["ai_timeout"] == 30  # usable after DB session closes
    runtime = view(admin_client)["runtime"]
    assert runtime["api"]["state"] == runtime["worker"]["state"] == "applied"
    # New revision must honestly be pending until worker consumes it.
    assert put(admin_client, revision=1, changes={"ai_timeout": 45}).status_code == 200
    assert view(admin_client)["runtime"]["worker"]["state"] == "pending"
    with SessionLocal() as db:
        assert read_effective({}, db).values["ai_timeout"] == 45
        record_runtime_observation(db, "worker", 2)
        record_runtime_observation(db, "worker", 1)  # stale ack cannot move backwards
        db.commit()
        assert db.get(SystemRuntimeObservation, "worker").revision == 2


def test_secret_replace_masked_ciphertext_keep_disable_inherit_and_org_key_untouched(admin_client):
    with SessionLocal() as db:
        org = db.scalar(select(m.Preferences))
        org.openai_key = encrypt("synthetic-org-key-untouched")
        db.commit()
        org_ciphertext = org.openai_key
    saved = put(admin_client, secrets={"global_openai_key": {"action": "replace", "value": SECRET}})
    assert saved.status_code == 200 and SECRET not in saved.text
    secret_field = fields(saved.json())["global_openai_key"]
    assert (
        secret_field["configured"] is True
        and secret_field["effective"] is None
        and secret_field["current"] is None
    )
    with SessionLocal() as db:
        state = db.get(SystemSettingsState, 1)
        assert SECRET not in state.global_openai_key_ciphertext
        snapshot = read_effective({"global_openai_key": "synthetic-environment-fallback"}, db)
        assert db.scalar(select(m.Preferences.openai_key)) == org_ciphertext
        audits = db.scalars(select(SystemSettingsAudit)).all()
        assert SECRET not in repr([audit.changed_fields for audit in audits])
    assert snapshot.secret_getter("global_openai_key") == SECRET  # closed-session getter
    keep = put(
        admin_client, revision=1, secrets={"global_openai_key": {"action": "keep", "value": ""}}
    )
    assert keep.status_code == 200 and keep.json()["revision"] == 1
    disabled = put(admin_client, revision=1, secrets={"global_openai_key": {"action": "disable"}})
    assert disabled.status_code == 200
    with SessionLocal() as db:
        assert (
            read_effective({"global_openai_key": "synthetic-env"}, db).secret_getter(
                "global_openai_key"
            )
            is None
        )
    inherited = put(admin_client, revision=2, secrets={"global_openai_key": {"action": "inherit"}})
    assert inherited.status_code == 200
    with SessionLocal() as db:
        assert (
            read_effective({"global_openai_key": "synthetic-env"}, db).secret_getter(
                "global_openai_key"
            )
            == "synthetic-env"
        )
        assert db.scalar(select(m.Preferences.openai_key)) == org_ciphertext


@pytest.mark.parametrize(
    "changes",
    [
        {"ai_timeout": 14},
        {"ai_timeout": 601},
        {"ai_cpu_threads": 0},
        {"max_upload_mb": 51},
        {"max_upload_mb": True},
        {"mev_browser": "false"},
        {"push_enabled": 1},
        {"push_fcm_project_id": "https://evil.test"},
        {"ollama_url": "http://evil.test"},
        {"secret_key": "do-not-echo-this-secret"},
        {"arbitrary-do-not-echo-this-secret": "invalid"},
    ],
)
def test_invalid_allowlist_atomic_no_audit_and_no_input_echo(admin_client, changes):
    response = put(
        admin_client,
        changes={"ai_timeout": 30} | changes,
        secrets={"global_openai_key": {"action": "replace", "value": SECRET}},
    )
    assert response.status_code == 422
    assert SECRET not in response.text and "do-not-echo-this-secret" not in response.text
    with SessionLocal() as db:
        assert db.get(SystemSettingsState, 1) is None
        assert db.scalar(select(SystemSettingsAudit)) is None


def test_revision_conflict_and_reset_inheritance(admin_client):
    assert put(admin_client, changes={"ai_timeout": 30}).status_code == 200
    stale = put(
        admin_client,
        changes={"ai_timeout": 40},
        secrets={"global_openai_key": {"action": "replace", "value": SECRET}},
    )
    assert stale.status_code == 409 and SECRET not in stale.text
    assert put(admin_client, revision=1, changes={"ai_timeout": None}).status_code == 200
    with SessionLocal() as db:
        assert read_effective({"ai_timeout": 123}, db).values["ai_timeout"] == 123
        assert db.get(SystemSettingsState, 1).global_openai_key_ciphertext is None


def test_firebase_strict_validation_encrypted_override_no_adc_move(admin_client, firebase_json):
    payload = json.dumps(firebase_json)
    response = put(
        admin_client,
        changes={"push_fcm_project_id": "synthetic-project"},
        secrets={"firebase_service_account": {"action": "replace", "value": payload}},
    )
    assert response.status_code == 200 and firebase_json["private_key"] not in response.text
    with SessionLocal() as db:
        snapshot = read_effective({}, db)
        assert (
            "BEGIN PRIVATE KEY"
            not in db.get(SystemSettingsState, 1).firebase_service_account_ciphertext
        )
    assert (
        json.loads(snapshot.secret_getter("firebase_service_account"))["project_id"]
        == "synthetic-project"
    )
    assert snapshot.secret_modes["firebase_service_account"] == "override"
    mismatch = put(admin_client, revision=1, changes={"push_fcm_project_id": "another-project"})
    assert mismatch.status_code == 422
    assert (
        put(
            admin_client, revision=1, secrets={"firebase_service_account": {"action": "inherit"}}
        ).status_code
        == 200
    )
    with SessionLocal() as db:
        inherited = read_effective(
            {"google_application_credentials": "/unchanged/mounted/reference"}, db
        )
        assert inherited.secret_modes["firebase_service_account"] == "inherit"
        assert inherited.secret_getter("firebase_service_account") is None


@pytest.mark.parametrize(
    "name,value",
    [
        ("token_uri", "https://evil.test/token"),
        ("auth_uri", "http://localhost/auth"),
        ("universe_domain", "evil.test"),
        ("auth_provider_x509_cert_url", "http://169.254.169.254"),
        ("unknown_url", "https://evil.test"),
        ("client_x509_cert_url", "https://evil.test"),
        ("project_id", "wrong-project"),
        ("type", "authorized_user"),
        ("private_key", "bad-private-key-do-not-echo"),
    ],
)
def test_firebase_rejects_arbitrary_urls_and_bad_keys_no_echo(
    admin_client, firebase_json, name, value
):
    firebase_json[name] = value
    response = put(
        admin_client,
        changes={"push_fcm_project_id": "synthetic-project"},
        secrets={
            "firebase_service_account": {"action": "replace", "value": json.dumps(firebase_json)}
        },
    )
    assert response.status_code == 422
    assert value not in response.text and "BEGIN PRIVATE KEY" not in response.text
    with SessionLocal() as db:
        assert db.get(SystemSettingsState, 1) is None


def test_corrupt_explicit_ciphertext_safe_failure_never_env_fallback(admin_client):
    assert (
        put(
            admin_client, secrets={"global_openai_key": {"action": "replace", "value": SECRET}}
        ).status_code
        == 200
    )
    with SessionLocal() as db:
        db.get(
            SystemSettingsState, 1
        ).global_openai_key_ciphertext = "invalid-ciphertext-do-not-log"
        db.commit()
        snapshot = read_effective({"global_openai_key": "synthetic-fallback"}, db)
    with pytest.raises(RuntimeSettingsUnavailable) as exc:
        snapshot.secret_getter("global_openai_key")
    assert SECRET not in str(exc.value) and "invalid-ciphertext" not in str(exc.value)
    assert view(admin_client)["revision"] == 1


def test_request_shape_errors_sanitized(admin_client):
    for body in [
        "not-json-secret",
        {"revision": False},
        {"revision": 0, "unknown-private": "do-not-echo"},
        {"revision": 0, "secrets": {"global_openai_key": {"action": "replace", "value": ""}}},
    ]:
        r = admin_client.put(
            "/api/system/settings",
            content=body if isinstance(body, str) else json.dumps(body),
            headers={"Content-Type": "application/json"},
        )
        assert (
            r.status_code == 422 and "do-not-echo" not in r.text and "not-json-secret" not in r.text
        )


def test_adc_reference_presence_only_and_secret_never_displayed(admin_client):
    with SessionLocal() as db:
        public = public_view(
            {"google_application_credentials": "/synthetic/reference/do-not-display.json"}, db
        )
        field = fields(public)["firebase_adc"]
        assert field["configured"] is True and field["effective"] is None
        assert "do-not-display" not in json.dumps(public, default=str)


def test_postgres_parallel_admin_revision_compare_and_swap(owner):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    from fastapi import HTTPException

    from app.db import engine
    from app.system_settings_service import apply_changes

    if engine.dialect.name != "postgresql":
        pytest.skip("PostgreSQL row locking required")
    with SessionLocal() as db:
        user = db.get(m.User, owner["id"])
        user.is_server_admin = True
        other = m.User(
            username="synthetic-second-system-admin",
            name="Synthetic",
            password_hash="synthetic-not-real",
            is_server_admin=True,
        )
        db.add(other)
        db.add(SystemSettingsState(id=1, revision=0, overrides={}))
        db.commit()
        actors = [user.id, other.id]
    barrier = Barrier(2)

    def save(actor, value):
        with SessionLocal() as db:
            barrier.wait(timeout=5)
            try:
                apply_changes(
                    db,
                    env={},
                    actor_id=actor,
                    revision=0,
                    changes={"ai_timeout": value},
                    secrets={},
                )
                db.commit()
                return 200
            except HTTPException as error:
                db.rollback()
                return error.status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [
            pool.submit(save, actor, value) for actor, value in zip(actors, (30, 45), strict=True)
        ]
        assert sorted(f.result(timeout=15) for f in futures) == [200, 409]
    with SessionLocal() as db:
        assert db.get(SystemSettingsState, 1).revision == 1
        assert db.get(SystemSettingsState, 1).overrides["ai_timeout"] in {30, 45}
        assert db.query(SystemSettingsAudit).count() == 1
