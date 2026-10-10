"""System-admin settings API, with manual safe validation that never echoes input."""

import json

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .config import settings
from .db import get_db
from .models import User
from .security import server_admin
from .system_settings_service import InvalidSetting, apply_changes, public_view

router = APIRouter(prefix="/api/system/settings", tags=["system-settings"])


@router.get("")
def read_system_settings(user: User = Depends(server_admin), db: Session = Depends(get_db)):
    return public_view(settings(), db)


@router.put("")
async def write_system_settings(
    request: Request, user: User = Depends(server_admin), db: Session = Depends(get_db)
):
    # Bounded body and explicit field allowlist prevent generic validation input/value echoes.
    raw = await request.body()
    if len(raw) > 150000:
        raise HTTPException(413, "System settings request too large")
    try:
        data = json.loads(raw)
        if (
            not isinstance(data, dict)
            or set(data) - {"revision", "changes", "secrets"}
            or "revision" not in data
        ):
            raise InvalidSetting()
        revision, changed = apply_changes(
            db,
            env=settings(),
            actor_id=user.id,
            revision=data["revision"],
            changes=data.get("changes", {}),
            secrets=data.get("secrets", {}),
        )
        db.commit()
    except InvalidSetting as error:
        db.rollback()
        raise HTTPException(
            422, dict(message="Invalid system setting", fields=[error.name])
        ) from None
    except (ValueError, TypeError):
        db.rollback()
        raise HTTPException(
            422, dict(message="Invalid system settings request", fields=["request"])
        ) from None
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "System settings changed; reload and retry") from None
    response = public_view(settings(), db)
    return response | dict(saved=True, changed=changed, revision=revision)
