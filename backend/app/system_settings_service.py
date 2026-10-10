"""Explicit, transactional settings overrides. Public view never decrypts secrets."""

import json
import re
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from . import models as m
from .security import decrypt, encrypt
from .system_settings_models import (
    SystemRuntimeObservation,
    SystemSettingsAudit,
    SystemSettingsState,
)

# Only these keys can become runtime overrides. URLs and infrastructure are never editable.
PUBLIC_FIELDS = {
    "push_enabled": dict(
        category="firebase",
        type="boolean",
        default=False,
        apply="worker_boundary",
        label="Push delivery",
        description="Enable server push delivery; worker and credentials are also required.",
    ),
    "push_worker_enabled": dict(
        category="firebase",
        type="boolean",
        default=False,
        apply="worker_boundary",
        label="Push worker",
        description="Allow the worker to process notifications; disabling does not remove registrations.",
    ),
    "push_fcm_project_id": dict(
        category="firebase",
        type="string",
        default="",
        apply="worker_boundary",
        label="Firebase project ID",
        description="Google Cloud project for FCM. Must match an explicitly supplied service account.",
        pattern=r"^$|^[a-z][a-z0-9-]{4,28}[a-z0-9]$",
    ),
    "ai_timeout": dict(
        category="ai",
        type="integer",
        default=480,
        apply="request",
        label="AI timeout",
        description="Maximum seconds for the next AI request.",
        minimum=15,
        maximum=600,
    ),
    "ai_cpu_threads": dict(
        category="ai",
        type="integer",
        default=4,
        apply="request",
        label="AI CPU threads",
        description="CPU threads requested for the next local inference.",
        minimum=1,
        maximum=64,
    ),
    "max_upload_mb": dict(
        category="ocr",
        type="integer",
        default=15,
        apply="request",
        label="Receipt upload limit",
        description="Maximum receipt upload size in MiB; existing files are unchanged.",
        minimum=1,
        maximum=50,
    ),
    "mev_browser": dict(
        category="ocr",
        type="boolean",
        default=True,
        apply="request",
        label="Receipt browser extraction",
        description="Allow browser-based extraction from the existing receipt source.",
    ),
}
SECRET_FIELDS = ("global_openai_key", "firebase_service_account")
READ_ONLY_FIELDS = {
    "ollama_url": dict(
        category="ai", type="string", default="http://ollama:11434", label="Local AI endpoint"
    ),
    "app_url": dict(
        category="infrastructure",
        type="string",
        default="http://localhost:8088",
        label="Application URL",
    ),
    "cookie_secure": dict(
        category="infrastructure", type="boolean", default=False, label="Secure cookies"
    ),
    "session_days": dict(
        category="infrastructure", type="integer", default=14, label="Session lifetime"
    ),
    "data_dir": dict(
        category="infrastructure", type="string", default="/data", label="Data directory"
    ),
    "admin_username": dict(
        category="infrastructure", type="string", default="admin", label="Bootstrap administrator"
    ),
    "admin_password_file": dict(
        category="infrastructure", type="secret", default=None, label="Bootstrap password reference"
    ),
    "database_url": dict(
        category="infrastructure", type="secret", default=None, label="Database connection"
    ),
    "secret_key": dict(
        category="infrastructure", type="secret", default=None, label="Existing encryption key"
    ),
    "firebase_adc": dict(
        category="firebase", type="secret", default=None, label="Existing Firebase ADC reference"
    ),
    "firebase_android_configuration": dict(
        category="firebase",
        type="string",
        default="packaged",
        label="Android Firebase configuration",
    ),
}


class InvalidSetting(Exception):
    def __init__(self, name="request"):
        self.name = name if name in PUBLIC_FIELDS or name in SECRET_FIELDS else "request"
        super().__init__("Invalid system setting")


class RuntimeSettingsUnavailable(Exception):
    """Safe error: consumers must not silently fall back after a corrupt explicit override."""


def env_value(env, name, default=None):
    if isinstance(env, dict):
        return env.get(name, env.get(name.upper(), default))
    return getattr(env, name, default)


def env_source(env, name):
    if isinstance(env, dict):
        return "environment" if name in env or name.upper() in env else "default"
    return "environment" if name in getattr(env, "model_fields_set", set()) else "default"


@dataclass(frozen=True)
class RuntimeSettingsSnapshot:
    revision: int
    values: dict
    secret_modes: dict
    secret_getter: Callable[[str], str | None] = field(repr=False)


def read_effective(env, db: Session) -> RuntimeSettingsSnapshot:
    row = db.get(SystemSettingsState, 1)
    overrides = dict(row.overrides) if row else {}
    values = {
        name: overrides.get(name, env_value(env, name, spec["default"]))
        for name, spec in PUBLIC_FIELDS.items()
    }
    modes = {name: getattr(row, name + "_mode") if row else "inherit" for name in SECRET_FIELDS}
    # Copy ciphertext now: getter remains valid after the caller closes its DB session.
    ciphertexts = {
        name: getattr(row, name + "_ciphertext") if row else None for name in SECRET_FIELDS
    }
    inherited_openai = env_value(env, "global_openai_key", env_value(env, "openai_api_key"))

    def secret_getter(name):
        if name not in SECRET_FIELDS:
            raise RuntimeSettingsUnavailable("Unknown runtime secret")
        mode = modes[name]
        if mode == "disable":
            return None
        if mode == "inherit":
            return (
                inherited_openai if name == "global_openai_key" else None
            )  # Existing ADC stays external.
        try:
            if mode != "override" or not ciphertexts[name]:
                raise ValueError()
            return decrypt(ciphertexts[name])
        except Exception:
            raise RuntimeSettingsUnavailable(
                "Encrypted runtime configuration unavailable"
            ) from None

    return RuntimeSettingsSnapshot(row.revision if row else 0, values, modes, secret_getter)


def record_runtime_observation(
    db: Session, service: str, revision: int, now: datetime | None = None
):
    if service not in {"api", "worker"} or type(revision) is not int or revision < 0:
        raise ValueError("Invalid runtime observation")
    seen = now or datetime.now(UTC)
    # Atomic monotonic upsert: concurrent request acknowledgements cannot move backwards.
    if db.bind.dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    statement = insert(SystemRuntimeObservation).values(
        service=service, revision=revision, seen_at=seen
    )
    statement = statement.on_conflict_do_update(
        index_elements=["service"],
        set_={"revision": revision, "seen_at": seen},
        where=SystemRuntimeObservation.revision <= revision,
    )
    db.execute(statement)
    db.flush()  # Caller owns commit; never acknowledge before resolving/consuming configuration.


def validate_public(name, value):
    if name not in PUBLIC_FIELDS:
        raise InvalidSetting()
    if value is None:
        return
    spec = PUBLIC_FIELDS[name]
    expected = {"boolean": bool, "integer": int, "string": str}[spec["type"]]
    if type(value) is not expected:
        raise InvalidSetting(name)
    if spec["type"] == "integer" and not spec["minimum"] <= value <= spec["maximum"]:
        raise InvalidSetting(name)
    if spec["type"] == "string" and (len(value) > 64 or not re.fullmatch(spec["pattern"], value)):
        raise InvalidSetting(name)


def validate_firebase(value: str, project_id: str) -> str:
    if len(value.encode()) > 65536:
        raise InvalidSetting("firebase_service_account")
    try:
        info = json.loads(value)
        allowed = {
            "type",
            "project_id",
            "private_key_id",
            "private_key",
            "client_email",
            "client_id",
            "auth_uri",
            "token_uri",
            "auth_provider_x509_cert_url",
            "client_x509_cert_url",
            "universe_domain",
        }
        if (
            not isinstance(info, dict)
            or set(info) - allowed
            or any(not isinstance(v, str) for v in info.values())
        ):
            raise ValueError()
        if (
            info.get("type") != "service_account"
            or info.get("project_id") != project_id
            or not project_id
        ):
            raise ValueError()
        if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", project_id):
            raise ValueError()
        email = info.get("client_email", "")
        if not re.fullmatch(
            r"[a-zA-Z0-9_-]+@" + re.escape(project_id) + r"\.iam\.gserviceaccount\.com", email
        ):
            raise ValueError()
        if info.get("token_uri") != "https://oauth2.googleapis.com/token":
            raise ValueError()
        if (
            info.get("auth_uri", "https://accounts.google.com/o/oauth2/auth")
            != "https://accounts.google.com/o/oauth2/auth"
        ):
            raise ValueError()
        if info.get("universe_domain", "googleapis.com") != "googleapis.com":
            raise ValueError()
        if (
            info.get("auth_provider_x509_cert_url", "https://www.googleapis.com/oauth2/v1/certs")
            != "https://www.googleapis.com/oauth2/v1/certs"
        ):
            raise ValueError()
        cert = info.get("client_x509_cert_url")
        if cert and cert not in {
            f"https://www.googleapis.com/robot/v1/metadata/x509/{email}",
            f"https://www.googleapis.com/robot/v1/metadata/x509/{email.replace('@', '%40')}",
            f"https://www.googleapis.com/robot/v1/metadata/x509/{email.replace('@', '%40').replace('.', '%2E')}",
        }:
            raise ValueError()
        key = serialization.load_pem_private_key(info["private_key"].encode(), password=None)
        if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 2048:
            raise ValueError()
    except Exception:
        raise InvalidSetting("firebase_service_account") from None
    return json.dumps(info, separators=(",", ":"), sort_keys=True)


def apply_changes(
    db: Session, *, env, actor_id: str, revision: int, changes: dict, secrets: dict
) -> tuple[int, list[str]]:
    if (
        type(revision) is not int
        or revision < 0
        or not isinstance(changes, dict)
        or not isinstance(secrets, dict)
    ):
        raise InvalidSetting()
    if set(secrets) - set(SECRET_FIELDS):
        raise InvalidSetting()
    # Serialize admin mutations with credential changes/deletion and re-check current authority.
    actor = db.scalar(
        select(m.User)
        .where(m.User.id == actor_id)
        .with_for_update()
        .execution_options(populate_existing=True)
    )
    if not actor or not actor.is_server_admin or not actor.is_active or actor.deleted_at:
        raise HTTPException(403, "System administrator required")
    row = db.scalar(
        select(SystemSettingsState)
        .where(SystemSettingsState.id == 1)
        .with_for_update()
        .execution_options(populate_existing=True)
    )
    actual_revision = row.revision if row else 0
    if actual_revision != revision:
        raise HTTPException(409, "System settings changed; reload and retry")
    overrides = dict(row.overrides) if row else {}
    changed = []
    for name, value in changes.items():
        validate_public(name, value)
        old = overrides.get(name)
        if value is None:
            if name in overrides:
                overrides.pop(name)
                changed.append(name)
        elif old != value or name not in overrides:
            overrides[name] = value
            changed.append(name)
    effective_project = overrides.get(
        "push_fcm_project_id", env_value(env, "push_fcm_project_id", "")
    )
    modes = {name: getattr(row, name + "_mode") if row else "inherit" for name in SECRET_FIELDS}
    payloads = {name: getattr(row, name + "_ciphertext") if row else None for name in SECRET_FIELDS}
    firebase_project = row.firebase_project_id if row else None
    for name, change in secrets.items():
        if not isinstance(change, dict) or set(change) - {"action", "value"}:
            raise InvalidSetting(name)
        action, value = change.get("action"), change.get("value")
        if action not in {"keep", "replace", "inherit", "disable"}:
            raise InvalidSetting(name)
        if action == "keep":
            continue
        if action in {"inherit", "disable"}:
            mode = action
            if modes[name] != mode or payloads[name] is not None:
                changed.append(name)
            modes[name], payloads[name] = mode, None
            if name == "firebase_service_account":
                firebase_project = None
            continue
        if (
            not isinstance(value, str)
            or not value
            or len(value.encode()) > (65536 if name == "firebase_service_account" else 512)
        ):
            raise InvalidSetting(name)
        if name == "global_openai_key":
            if any(c.isspace() or ord(c) < 32 for c in value) or len(value) < 16:
                raise InvalidSetting(name)
        else:
            value = validate_firebase(value, effective_project)
            firebase_project = effective_project
        modes[name], payloads[name] = "override", encrypt(value)
        changed.append(name)
    if modes["firebase_service_account"] == "override" and firebase_project != effective_project:
        raise InvalidSetting("push_fcm_project_id")
    if not changed:
        return actual_revision, []
    new_revision = actual_revision + 1
    values = dict(revision=new_revision, overrides=overrides, firebase_project_id=firebase_project)
    for name in SECRET_FIELDS:
        values[name + "_mode"], values[name + "_ciphertext"] = modes[name], payloads[name]
    if row is None:
        db.add(SystemSettingsState(id=1, **values))
        db.flush()
    else:
        result = db.execute(
            update(SystemSettingsState)
            .where(SystemSettingsState.id == 1, SystemSettingsState.revision == revision)
            .values(**values)
            .execution_options(synchronize_session=False)
        )
        if result.rowcount != 1:
            raise HTTPException(409, "System settings changed; reload and retry")
        db.expire(row)
    changed = sorted(set(changed))
    db.add(SystemSettingsAudit(actor_id=actor_id, revision=new_revision, changed_fields=changed))
    db.flush()
    return new_revision, changed


def public_view(env, db: Session) -> dict[str, Any]:
    row = db.get(SystemSettingsState, 1)
    overrides = row.overrides if row else {}
    groups = {name: [] for name in ("firebase", "ai", "ocr", "runtime", "infrastructure")}
    for name, spec in PUBLIC_FIELDS.items():
        value = overrides.get(name, env_value(env, name, spec["default"]))
        groups[spec["category"]].append(
            dict(
                name=name,
                **{k: v for k, v in spec.items() if k != "category"},
                editable=True,
                secret=False,
                current=overrides.get(name),
                effective=value,
                source="override" if name in overrides else env_source(env, name),
                configured=True,
            )
        )
    for name in SECRET_FIELDS:
        mode = getattr(row, name + "_mode") if row else "inherit"
        inherited = (
            bool(env_value(env, "global_openai_key", env_value(env, "openai_api_key")))
            if name == "global_openai_key"
            else bool(
                env_value(
                    env, "firebase_adc_configured", env_value(env, "google_application_credentials")
                )
            )
        )
        category = "ai" if name == "global_openai_key" else "firebase"
        groups[category].append(
            dict(
                name=name,
                type="secret",
                label="Global OpenAI fallback key"
                if category == "ai"
                else "Firebase service account override",
                description="Write-only encrypted override. Inherit keeps the existing environment/ADC reference; disable suppresses fallback.",
                editable=True,
                secret=True,
                current=None,
                effective=None,
                default=None,
                source="override"
                if mode == "override"
                else "disabled"
                if mode == "disable"
                else "environment",
                mode=mode,
                configured=mode == "override" or (mode == "inherit" and inherited),
                apply="request" if category == "ai" else "worker_boundary",
                actions=["keep", "replace", "inherit", "disable"],
            )
        )
    for name, spec in READ_ONLY_FIELDS.items():
        private = spec["type"] == "secret"
        value = (
            None
            if private
            else str(env_value(env, name, spec["default"]))
            if spec["type"] == "string"
            else env_value(env, name, spec["default"])
        )
        groups[spec["category"]].append(
            dict(
                name=name,
                **{k: v for k, v in spec.items() if k != "category"},
                description="Managed by deployment infrastructure; cannot be changed in this editor.",
                editable=False,
                secret=private,
                current=None,
                effective=value,
                source="environment" if private else env_source(env, name),
                configured=(
                    bool(env_value(env, "google_application_credentials"))
                    if name == "firebase_adc"
                    else bool(env_value(env, name))
                )
                if private
                else True,
                apply="restart",
                actions=[],
            )
        )
    revision = row.revision if row else 0
    now = datetime.now(UTC)
    runtime = {}
    for service in ("api", "worker"):
        observation = db.get(SystemRuntimeObservation, service)
        seen = (
            observation.seen_at.replace(tzinfo=UTC)
            if observation and observation.seen_at.tzinfo is None
            else observation.seen_at
            if observation
            else None
        )
        state = (
            "unknown"
            if observation is None
            else "applied"
            if observation.revision == revision and seen >= now - timedelta(minutes=2)
            else "pending"
        )
        runtime[service] = dict(
            description="Last consumed configuration boundary; provider readiness and all request consumers are not implied.",
            revision=observation.revision if observation else None,
            seen_at=seen,
            state=state,
        )
    return dict(
        schema_version=1,
        revision=revision,
        groups=[dict(id=name, fields=fields) for name, fields in groups.items()],
        runtime=runtime,
    )
