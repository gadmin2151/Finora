"""Disposable-only migration QA. Refuses TCP, non-test DB and non-/tmp socket."""

import hashlib
import json
import os
import secrets
import sys
import tempfile
from pathlib import Path

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import make_url

ROOT = Path(__file__).resolve().parent
url = make_url(os.environ.get("PUSH_TEST_DATABASE_URL", ""))
socket = url.query.get("host", "")
socket_path = Path(socket).resolve()
if (
    url.get_backend_name() != "postgresql"
    or url.database != "finora_push_isolated_test"
    or url.host
    or url.password
    or not socket.startswith("/tmp/finora-push-pg-")
    or socket_path.name != "socket"
    or not socket_path.parent.name.startswith("finora-push-pg-")
    or socket_path.parent.parent not in {Path("/tmp").resolve(), Path("/private/tmp")}
    or not (socket_path.parent / "data/PG_VERSION").is_file()
):
    raise RuntimeError("Use the coordinated disposable PostgreSQL unix socket and exact test DB")
os.environ["DATABASE_URL"] = url.render_as_string(hide_password=False)
os.environ["SECRET_KEY"] = secrets.token_urlsafe(48)  # RAM only; never persisted or printed.
os.environ["DATA_DIR"] = tempfile.mkdtemp(prefix="finora-test-push-migration-")
os.environ["TESTING"] = "true"
sys.path.insert(0, str(ROOT))

from alembic import command  # noqa: E402
from alembic.config import Config  # noqa: E402
from alembic.script import ScriptDirectory  # noqa: E402

from app import models as m  # noqa: E402
from app import push_models  # noqa: E402,F401
from app.db import SessionLocal  # noqa: E402

config = Config(str(ROOT / "alembic.ini"))
config.set_main_option("script_location", str(ROOT / "migrations"))
assert ScriptDirectory.from_config(config).get_heads() == ["a92e7810bc43"]
engine = create_engine(url)
if inspect(engine).get_table_names():
    raise RuntimeError("Migration QA requires a new empty coordinated disposable database")
command.upgrade(config, "16b4029ae831")
with SessionLocal() as db:
    user = m.User(
        username="synthetic-migration-owner", name="Synthetic", password_hash="not-a-real-password"
    )
    org = m.Organization(name="Synthetic test workspace")
    db.add_all([user, org])
    db.flush()
    db.add(m.Membership(user_id=user.id, organization_id=org.id, role="admin"))
    db.add(
        m.Account(
            organization_id=org.id,
            name="Synthetic marker",
            currency="MDL",
            kind="cash",
            opening_minor=12300,
        )
    )
    db.commit()


def snapshot():
    result = {}
    with engine.connect() as connection:
        for table in sorted(inspect(engine).get_table_names()):
            if table == "alembic_version" or table.startswith("push_"):
                continue
            rows = [
                dict(row) for row in connection.execute(text(f'SELECT * FROM "{table}"')).mappings()
            ]
            encoded = json.dumps(
                sorted(rows, key=lambda row: str(row.get("id", ""))), sort_keys=True, default=str
            ).encode()
            result[table] = {"rows": len(rows), "sha256": hashlib.sha256(encoded).hexdigest()}
    return result


baseline = snapshot()
command.upgrade(config, "head")
assert snapshot() == baseline
assert {name for name in inspect(engine).get_table_names() if name.startswith("push_")} == {
    "push_devices",
    "push_preferences",
    "push_events",
    "push_deliveries",
}
command.check(config)
command.downgrade(config, "16b4029ae831")
assert snapshot() == baseline
assert not any(name.startswith("push_") for name in inspect(engine).get_table_names())
command.upgrade(config, "head")
assert snapshot() == baseline
command.check(config)
with engine.connect() as connection:
    server_version = connection.execute(text("SHOW server_version")).scalar()
report = {
    "postgres_version": server_version,
    "database": url.database,
    "transport": "private temporary unix socket; TCP disabled",
    "revision": "a92e7810bc43",
    "predecessor": "16b4029ae831",
    "single_head": True,
    "upgrade_downgrade_reupgrade": "passed",
    "alembic_check": "passed",
    "baseline_rows_unchanged": True,
    "baseline_table_count": len(baseline),
    "new_tables": ["push_devices", "push_preferences", "push_events", "push_deliveries"],
    "provider_calls": 0,
    "credentials": "synthetic test SECRET_KEY in process RAM only",
}
output = Path(os.environ.get("PUSH_TEST_EVIDENCE", str(ROOT / "push_pg_EVIDENCE.json")))
output.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
engine.dispose()
