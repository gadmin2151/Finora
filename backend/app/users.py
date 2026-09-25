from fastapi import APIRouter, Depends, Query, Request, Response, UploadFile
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from . import models as m
from . import schemas as s
from .avatars import MAX_AVATAR_BYTES, profile_photo
from .db import get_db
from .finance import fail
from .organizations import identity
from .security import current_user, server_admin
from .user_management import account_audit, create_account, edit_account, reset_password

router = APIRouter(prefix="/api")
DB = Depends(get_db)
OWNER = Depends(server_admin)
ACTOR = Depends(current_user)


def get_account(db: Session, key: str) -> m.User:
    user = db.get(m.User, key)
    if user is None:
        fail("Пользователь не найден", 404)
    return user


@router.get("/admin/users")
def users(
    q: str = Query("", max_length=80),
    status: str = Query("all", pattern="^(all|active|blocked)$"),
    offset: int = Query(0, ge=0),
    limit: int = Query(25, ge=1, le=100),
    owner: m.User = OWNER,
    db: Session = DB,
):
    query = select(m.User)
    if q.strip():
        query = query.where(
            or_(
                m.User.username.icontains(q.strip(), autoescape=True),
                m.User.name.icontains(q.strip(), autoescape=True),
            )
        )
    if status != "all":
        query = query.where(m.User.is_active.is_(status == "active"))
    total = db.scalar(select(func.count()).select_from(query.subquery()))
    rows = list(
        db.scalars(query.order_by(m.User.created_at, m.User.id).offset(offset).limit(limit))
    )
    ids = [row.id for row in rows]
    memberships: dict[str, list] = {key: [] for key in ids}
    for row in db.execute(
        select(m.Membership.user_id, m.Organization.id, m.Organization.name, m.Membership.role)
        .join(m.Organization)
        .where(m.Membership.user_id.in_(ids))
        .order_by(m.Organization.name)
    ).mappings():
        memberships[row["user_id"]].append(
            {"organization_id": row["id"], "name": row["name"], "role": row["role"]}
        )
    sessions = dict(
        db.execute(
            select(m.Session.user_id, func.count())
            .where(m.Session.user_id.in_(ids), m.Session.expires_at > m.now())
            .group_by(m.Session.user_id)
        ).all()
    )
    return {
        "total": total,
        "items": [
            {
                "id": row.id,
                "username": row.username,
                "name": row.name,
                "is_active": row.is_active,
                "is_server_admin": row.is_server_admin,
                "created_at": row.created_at,
                "avatar_url": f"/api/users/{row.id}/avatar?v={row.avatar_version}"
                if row.avatar_version
                else None,
                "sessions": sessions.get(row.id, 0),
                "memberships": memberships[row.id],
            }
            for row in rows
        ],
    }


@router.get("/admin/organizations")
def organization_options(
    q: str = Query("", max_length=100), owner: m.User = OWNER, db: Session = DB
):
    query = select(m.Organization.id, m.Organization.name)
    if q.strip():
        query = query.where(m.Organization.name.icontains(q.strip(), autoescape=True))
    return [
        dict(row) for row in db.execute(query.order_by(m.Organization.name).limit(1000)).mappings()
    ]


@router.post("/admin/users", status_code=201)
def create(data: s.UserCreate, owner: m.User = OWNER, db: Session = DB):
    return {"id": create_account(db, owner, data).id}


@router.put("/admin/users/{key}")
def update(key: str, data: s.UserEdit, owner: m.User = OWNER, db: Session = DB):
    edit_account(db, owner, get_account(db, key), data)
    return {"ok": True}


@router.post("/admin/users/{key}/password")
def password(key: str, data: s.PasswordReset, owner: m.User = OWNER, db: Session = DB):
    reset_password(db, owner, get_account(db, key), data.password)
    return {"ok": True}


@router.get("/admin/users/{key}/audit")
def history(key: str, owner: m.User = OWNER, db: Session = DB):
    get_account(db, key)
    return [
        dict(row)
        for row in db.execute(
            select(
                m.UserAudit.id,
                m.UserAudit.action,
                m.UserAudit.created_at,
                m.User.username.label("actor"),
            )
            .join(m.User, m.User.id == m.UserAudit.actor_id)
            .where(m.UserAudit.user_id == key)
            .order_by(m.UserAudit.created_at.desc())
            .limit(25)
        ).mappings()
    ]


@router.put("/auth/profile")
def profile(data: s.ProfileEdit, request: Request, user: m.User = ACTOR, db: Session = DB):
    user.name = data.name
    account_audit(db, user, user, "profile.updated")
    db.commit()
    return identity(db, user, request.state.session.csrf)


@router.post("/auth/avatar")
def upload_avatar(file: UploadFile, request: Request, user: m.User = ACTOR, db: Session = DB):
    user.avatar_data = profile_photo(file.file.read(MAX_AVATAR_BYTES + 1))
    user.avatar_version = m.uid()
    account_audit(db, user, user, "profile.photo_updated")
    db.commit()
    return identity(db, user, request.state.session.csrf)


@router.delete("/auth/avatar")
def remove_avatar(request: Request, user: m.User = ACTOR, db: Session = DB):
    user.avatar_data = None
    user.avatar_version = None
    account_audit(db, user, user, "profile.photo_removed")
    db.commit()
    return identity(db, user, request.state.session.csrf)


@router.get("/users/{key}/avatar")
def avatar(key: str, user: m.User = ACTOR, db: Session = DB):
    if user.id != key and not user.is_server_admin:
        shared = select(m.Membership.organization_id).where(m.Membership.user_id == user.id)
        if not db.scalar(
            select(m.Membership.id)
            .where(m.Membership.user_id == key, m.Membership.organization_id.in_(shared))
            .limit(1)
        ):
            fail("Фотография недоступна", 404)
    target = get_account(db, key)
    if not target.avatar_data:
        fail("Фотография не добавлена", 404)
    return Response(target.avatar_data, media_type="image/jpeg")
