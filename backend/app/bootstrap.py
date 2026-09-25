from pathlib import Path

from sqlalchemy import select

from . import models as m
from .config import settings
from .db import SessionLocal
from .finance import seed_user
from .security import hasher


def main():
    with SessionLocal() as db:
        if db.scalar(select(m.User.id).limit(1)):
            return
        password = Path(settings().admin_password_file).read_text().strip()
        if len(password) < 12:
            raise RuntimeError("Initial password must be at least 12 characters")
        user = m.User(
            username=settings().admin_username, name="Владелец", password_hash=hasher.hash(password)
        )
        db.add(user)
        seed_user(db, user)
        db.commit()
        print("Owner account created. Read the initial password from the configured secret file.")


if __name__ == "__main__":
    main()
