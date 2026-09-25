import os
import secrets
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete
from sqlalchemy.engine import make_url

test_dir = Path(tempfile.mkdtemp(prefix="finora-test-"))
os.environ["DATABASE_URL"] = os.environ.get(
    "TEST_DATABASE_URL", f"sqlite:///{test_dir / 'test.db'}"
)
database_name = make_url(os.environ["DATABASE_URL"]).database or ""
if not ("finora-test-" in database_name or database_name.endswith("_test")):
    raise RuntimeError("Tests require an isolated database ending in _test")
os.environ["SECRET_KEY"] = secrets.token_urlsafe(48)
os.environ["DATA_DIR"] = str(test_dir / "data")
os.environ["TESTING"] = "true"

from app.config import settings  # noqa: E402

# The runner may have read configuration before pytest was loaded.
settings.cache_clear()

from app import models as m  # noqa: E402
from app.db import Base, SessionLocal, engine  # noqa: E402
from app.finance import seed_user  # noqa: E402
from app.main import app  # noqa: E402
from app.security import hasher  # noqa: E402


def assert_isolated_database():
    actual_name = engine.url.database or ""
    if actual_name != database_name or not (
        "finora-test-" in actual_name or actual_name.endswith("_test")
    ):
        raise RuntimeError("Refusing to reset the connected database: it is not the test database")
    if settings().data_dir != test_dir / "data":
        raise RuntimeError("Tests require isolated temporary file storage")


@pytest.fixture(autouse=True)
def clean_database():
    assert_isolated_database()
    Base.metadata.create_all(engine)
    with engine.begin() as connection:
        for table in reversed(Base.metadata.sorted_tables):
            connection.execute(delete(table))
    yield


@pytest.fixture
def owner():
    password = secrets.token_urlsafe(20)
    with SessionLocal() as db:
        user = m.User(username="test-owner", name="Тест", password_hash=hasher.hash(password))
        db.add(user)
        seed_user(db, user)
        db.commit()
        return {"id": user.id, "username": user.username, "password": password}


@pytest.fixture
def client(owner):
    with TestClient(app) as client:
        response = client.post(
            "/api/auth/login",
            json={"username": owner["username"], "password": owner["password"]},
            headers={"X-Finora-Client": "web"},
        )
        assert response.status_code == 200
        client.headers["X-CSRF-Token"] = response.json()["csrf"]
        client.headers["X-Organization-ID"] = response.json()["organizations"][0]["id"]
        yield client


@pytest.fixture
def accounts(client):
    return client.get("/api/accounts").json()


@pytest.fixture
def categories(client):
    return client.get("/api/categories").json()
