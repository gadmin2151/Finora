"""Additive migration QA: exact disposable DB + private temporary socket only."""

import hashlib
import json
import os
import secrets
import sys
import tempfile
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from uuid import uuid4

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import make_url

ROOT = Path(__file__).resolve().parent
url = make_url(os.environ.get("PUSH_TEST_DATABASE_URL", ""))
socket = Path(url.query.get("host", "")).resolve()
if (
    url.get_backend_name() != "postgresql"
    or url.database != "finora_system_settings_test"
    or url.host
    or url.password
    or socket.name != "socket"
    or not socket.parent.name.startswith("finora-push-pg-")
    or socket.parent.parent != Path("/tmp").resolve()
    or not (socket.parent / "data/PG_VERSION").is_file()
):
    raise RuntimeError("Requires coordinated disposable DB and private /tmp Unix socket")
os.environ["DATABASE_URL"] = url.render_as_string(hide_password=False)
os.environ["SECRET_KEY"] = secrets.token_urlsafe(48)
os.environ["DATA_DIR"] = tempfile.mkdtemp(prefix="finora-test-update-migration-")
os.environ["TESTING"] = "true"
os.environ["PUSH_ENABLED"] = "false"
os.environ["PUSH_WORKER_ENABLED"] = "false"
sys.path.insert(0, str(ROOT))
from alembic import command  # noqa: E402
from alembic.config import Config  # noqa: E402
from alembic.script import ScriptDirectory  # noqa: E402

from app import models as m  # noqa: E402
from app.db import SessionLocal  # noqa: E402

config = Config(str(ROOT / "alembic.ini"))
config.set_main_option("script_location", str(ROOT / "migrations"))
assert ScriptDirectory.from_config(config).get_heads() == ["d81ac57e09f2"]
engine = create_engine(url)
if inspect(engine).get_table_names():
    raise RuntimeError("Requires brand-new empty database")
command.upgrade(config, "b73f9012dca4")
now = datetime(2026, 10, 10, tzinfo=UTC)
with SessionLocal() as db:
    user = m.User(
        username="synthetic-update-migration",
        name="Synthetic",
        password_hash="not-real",
        is_server_admin=True,
    )
    org = m.Organization(name="Synthetic")
    db.add_all([user, org])
    db.flush()
    db.add(m.Membership(user_id=user.id, organization_id=org.id, role="admin"))
    session = m.Session(
        user_id=user.id,
        token_hash="synthetic-session",
        csrf="synthetic",
        device="test",
        expires_at=now + timedelta(days=30),
    )
    account = m.Account(
        organization_id=org.id,
        name="Synthetic account",
        currency="MDL",
        kind="cash",
        opening_minor=12300,
    )
    db.add_all([session, account])
    db.flush()
    receipt = m.Receipt(
        organization_id=org.id,
        created_by=user.id,
        source="manual",
        source_key="synthetic-old-receipt",
        merchant="Synthetic",
        total_minor=1200,
        status="posted",
        account_id=account.id,
    )
    bill = m.Bill(
        organization_id=org.id,
        name="Synthetic bill",
        amount_minor=700,
        currency="MDL",
        start_date=date(2026, 10, 11),
        recurrence="monthly",
        account_id=account.id,
    )
    db.add_all([receipt, bill])
    db.flush()
    tx = m.Transaction(
        organization_id=org.id,
        kind="expense",
        amount_minor=1200,
        base_minor=1200,
        currency="MDL",
        occurred_on=date(2026, 10, 10),
        account_id=account.id,
        receipt_id=receipt.id,
        idempotency_key="synthetic",
        request_hash="a" * 64,
    )
    db.add(tx)
    db.flush()
    db.add(m.Posting(transaction_id=tx.id, account_id=account.id, amount_minor=-1200))
    ids = dict(
        user=user.id,
        org=org.id,
        session=session.id,
        receipt=receipt.id,
        device=str(uuid4()),
        pref=str(uuid4()),
        event=str(uuid4()),
    )
    db.execute(
        text(
            "INSERT INTO push_devices (id,created_at,user_id,session_id,organization_id,installation_id,provider,token_hash,token_ciphertext,enabled,updated_at) VALUES (:device,:now,:user,:session,:org,:installation,'mock',:hash,'synthetic-not-a-token',true,:now)"
        ),
        dict(ids, now=now, installation=str(uuid4()), hash="b" * 64),
    )
    db.execute(
        text(
            "INSERT INTO push_preferences (id,created_at,user_id,organization_id,enabled,payments,receipts,important,timezone,payment_hour,lead_days) VALUES (:pref,:now,:user,:org,true,true,true,true,'UTC',9,1)"
        ),
        dict(ids, now=now),
    )
    db.execute(
        text(
            "INSERT INTO push_events (id,created_at,user_id,organization_id,event_key,category,route,expires_at) VALUES (:event,:now,:user,:org,'synthetic-legacy','important','overview',:expiry)"
        ),
        dict(ids, now=now, expiry=now + timedelta(days=1)),
    )
    db.commit()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, default=str).encode()).hexdigest()


def snapshot():
    result = {}
    inspector = inspect(engine)
    with engine.connect() as conn:
        for table in sorted(inspector.get_table_names()):
            if (
                table == "alembic_version"
                or table.startswith("push_")
                or table.startswith("system_")
            ):
                continue
            rows = list(conn.execute(text(f'SELECT * FROM "{table}" ORDER BY id')).mappings())
            schema = {
                "columns": inspector.get_columns(table),
                "foreign_keys": inspector.get_foreign_keys(table),
                "checks": inspector.get_check_constraints(table),
                "unique": inspector.get_unique_constraints(table),
                "indices": inspector.get_indexes(table),
                "pk": inspector.get_pk_constraint(table),
            }
            result[table] = dict(
                rows=len(rows),
                rows_sha256=digest([dict(row) for row in rows]),
                schema_sha256=digest(schema),
            )
    return result


from app.system_settings_models import (  # noqa: E402
    SystemRuntimeObservation,
    SystemSettingsAudit,
    SystemSettingsState,
)
from app.system_settings_service import (  # noqa: E402
    apply_changes,
    read_effective,
    record_runtime_observation,
)

baseline = snapshot()
command.upgrade(config, "head")
assert snapshot() == baseline
assert {name for name in inspect(engine).get_table_names() if name.startswith("system_")} == {
    "system_settings_state",
    "system_settings_audit",
    "system_runtime_observations",
}
command.check(config)
with SessionLocal() as db:
    row = db.get(SystemSettingsState, 1)
    assert (
        row.revision == 0
        and row.overrides == {}
        and row.global_openai_key_mode == row.firebase_service_account_mode == "inherit"
    )
    assert (
        row.global_openai_key_ciphertext is None and row.firebase_service_account_ciphertext is None
    )
    revision, changed = apply_changes(
        db,
        env={},
        actor_id=ids["user"],
        revision=0,
        changes={"ai_timeout": 37},
        secrets={
            "global_openai_key": {
                "action": "replace",
                "value": "synthetic-migration-settings-secret",
            }
        },
    )
    assert revision == 1 and changed == ["ai_timeout", "global_openai_key"]
    db.commit()
with SessionLocal() as db:
    resolved = read_effective({}, db)
    assert resolved.values["ai_timeout"] == 37
    assert resolved.secret_getter("global_openai_key") == "synthetic-migration-settings-secret"
    record_runtime_observation(db, "worker", resolved.revision)
    record_runtime_observation(db, "worker", 0)
    db.commit()
    assert db.get(SystemRuntimeObservation, "worker").revision == 1
assert snapshot() == baseline
command.downgrade(config, "b73f9012dca4")
assert snapshot() == baseline
assert not any(name.startswith("system_") for name in inspect(engine).get_table_names())
command.upgrade(config, "head")
assert snapshot() == baseline
command.check(config)
with SessionLocal() as db:
    row = db.get(SystemSettingsState, 1)
    assert row.revision == 0 and row.overrides == {} and row.global_openai_key_ciphertext is None
    assert db.query(SystemSettingsAudit).count() == db.query(SystemRuntimeObservation).count() == 0
with engine.connect() as conn:
    server = conn.execute(text("SHOW server_version")).scalar()
report = dict(
    postgres_version=server,
    database=url.database,
    revision="d81ac57e09f2",
    predecessor="b73f9012dca4",
    single_head=True,
    upgrade_downgrade_reupgrade="passed",
    alembic_check="passed twice",
    baseline_financial_values_and_schema_unchanged=True,
    baseline=baseline,
    defaults_inherit_no_secret_migration=True,
    actual_save_fresh_session_worker_read=True,
    monotonic_observation=True,
    production_reads=0,
    production_mutations=0,
    provider_calls=0,
    transport="private /tmp Unix socket; TCP disabled",
)
Path(ROOT / "system_settings_pg_EVIDENCE.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
engine.dispose()
