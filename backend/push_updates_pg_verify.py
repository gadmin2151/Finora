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
    or url.database != "finora_push_receipt_update_test"
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
from sqlalchemy.exc import IntegrityError  # noqa: E402

from app import models as m  # noqa: E402
from app import push_models as pm  # noqa: E402
from app.db import SessionLocal  # noqa: E402

config = Config(str(ROOT / "alembic.ini"))
config.set_main_option("script_location", str(ROOT / "migrations"))
assert ScriptDirectory.from_config(config).get_heads() == ["b73f9012dca4"]
engine = create_engine(url)
if inspect(engine).get_table_names():
    raise RuntimeError("Requires brand-new empty database")
command.upgrade(config, "a92e7810bc43")
now = datetime(2026, 10, 10, tzinfo=UTC)
with SessionLocal() as db:
    user = m.User(username="synthetic-update-migration", name="Synthetic", password_hash="not-real")
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
            if table == "alembic_version" or table.startswith("push_"):
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


baseline = snapshot()
command.upgrade(config, "head")
assert snapshot() == baseline
command.check(config)
with SessionLocal() as db:
    device = db.get(pm.PushDevice, ids["device"])
    pref = db.get(pm.PushPreference, ids["pref"])
    assert device.installed_version_code is None and device.installed_version_name is None
    assert device.all_organizations is False and pref.updates is False
    assert db.query(pm.PushReceiptCreation).count() == 0  # no historical backfill
    device.installed_version_code, device.installed_version_name = 100, "1.0.0"
    device.all_organizations, pref.updates = True, True
    db.add(pm.PushReceiptCreation(receipt_id=ids["receipt"], eligible_at=now))
    db.add(
        pm.PushAPKRelease(
            version_code=200, version_name="2.0.0", apk_sha256="c" * 64, published_at=now
        )
    )
    event = pm.PushEvent(
        user_id=ids["user"],
        organization_id=ids["org"],
        event_key="synthetic-update",
        category="update_available",
        route="updater",
        version_code=200,
        version_name="2.0.0",
        expires_at=now + timedelta(days=1),
    )
    db.add(event)
    db.flush()
    db.add(
        pm.PushUpdateNotice(
            user_id=ids["user"],
            installation_id=device.installation_id,
            version_code=200,
            event_id=event.id,
        )
    )
    db.add(pm.PushDelivery(event_id=event.id, device_id=device.id, available_at=now))
    db.commit()
    update_id = event.id
assert snapshot() == baseline
try:
    command.downgrade(config, "a92e7810bc43")
except IntegrityError:
    pass
else:
    raise AssertionError("Downgrade must refuse unhandled update-category events")
with engine.connect() as conn:
    assert conn.execute(text("SELECT version_num FROM alembic_version")).scalar() == "b73f9012dca4"
assert "push_update_notices" in inspect(engine).get_table_names()  # transactional DDL rollback
assert snapshot() == baseline
# Explicitly remove only this fictional update outbox before intentional downgrade.
with SessionLocal() as db:
    db.delete(db.get(pm.PushEvent, update_id))
    db.commit()
command.downgrade(config, "a92e7810bc43")
assert snapshot() == baseline
assert "push_receipt_creations" not in inspect(engine).get_table_names()
with engine.connect() as conn:
    assert (
        conn.execute(
            text("SELECT count(*) FROM push_events WHERE id=:id"), dict(id=ids["event"])
        ).scalar()
        == 1
    )
command.upgrade(config, "head")
assert snapshot() == baseline
command.check(config)
with engine.connect() as conn:
    server = conn.execute(text("SHOW server_version")).scalar()
    assert conn.execute(text("SELECT count(*) FROM push_receipt_creations")).scalar() == 0
    assert (
        conn.execute(
            text("SELECT all_organizations FROM push_devices WHERE id=:id"), dict(id=ids["device"])
        ).scalar()
        is False
    )
    assert (
        conn.execute(
            text("SELECT updates FROM push_preferences WHERE id=:id"), dict(id=ids["pref"])
        ).scalar()
        is False
    )
report = dict(
    postgres_version=server,
    database=url.database,
    transport="private /tmp Unix socket; TCP disabled",
    revision="b73f9012dca4",
    predecessor="a92e7810bc43",
    single_head=True,
    alembic_check="passed twice",
    upgrade_downgrade_reupgrade="passed",
    downgrade_live_update_refusal_and_transactional_rollback="passed",
    legacy_unknown_version_defaults="passed",
    legacy_updates_and_allorg_opt_in_defaults=False,
    historical_receipt_backfill_rows=0,
    financial_rows_and_schema_unchanged=True,
    baseline=baseline,
    provider_calls=0,
    production_mutations=0,
    credentials="synthetic process-RAM SECRET_KEY only",
)
Path(os.environ.get("PUSH_TEST_EVIDENCE", str(ROOT / "push_updates_pg_EVIDENCE.json"))).write_text(
    json.dumps(report, indent=2) + "\n"
)
print(json.dumps(report, indent=2))
engine.dispose()
