from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import models as m
from . import schemas as s
from .db import get_db
from .finance import audit, fail, lock_organization, seed_organization
from .security import current_organization, current_user, hasher

router = APIRouter(prefix="/api/organizations")
DB = Depends(get_db)
ACTOR = Depends(current_user)
SCOPE = Depends(current_organization)


def organization_list(db, user_id):
    return [
        dict(row)
        for row in db.execute(
            select(m.Organization.id, m.Organization.name, m.Membership.role)
            .join(m.Membership, m.Membership.organization_id == m.Organization.id)
            .where(m.Membership.user_id == user_id)
            .order_by(m.Organization.created_at)
        ).mappings()
    ]


def identity(db, user, csrf):
    return {
        "id": user.id,
        "username": user.username,
        "name": user.name,
        "csrf": csrf,
        "organizations": organization_list(db, user.id),
    }


def require_admin(db, organization_id, user_id):
    member = db.scalar(
        select(m.Membership).where(
            m.Membership.organization_id == organization_id, m.Membership.user_id == user_id
        )
    )
    if member is None or member.role != "admin":
        fail("Нужны права администратора организации", 403)


@router.get("")
def organizations(user: m.User = ACTOR, db: Session = DB):
    return organization_list(db, user.id)


@router.post("")
def create(data: s.OrganizationInput, user: m.User = ACTOR, db: Session = DB):
    if not db.scalar(
        select(m.Membership.id)
        .where(m.Membership.user_id == user.id, m.Membership.role == "admin")
        .limit(1)
    ):
        fail("Создавать организации может администратор", 403)
    # Serialize concurrent creates and cap accidental unlimited provisioning.
    db.scalar(select(m.User).where(m.User.id == user.id).with_for_update())
    if (
        db.scalar(
            select(func.count()).select_from(m.Membership).where(m.Membership.user_id == user.id)
        )
        >= 100
    ):
        fail("Достигнут лимит 100 организаций")
    organization = m.Organization(name=data.name)
    db.add(organization)
    db.flush()
    db.add(m.Membership(user_id=user.id, organization_id=organization.id, role="admin"))
    seed_organization(db, organization)
    audit(db, organization.id, "organization.created", organization.id)
    db.commit()
    return {"id": organization.id, "name": organization.name, "role": "admin"}


@router.put("/current")
def rename(data: s.OrganizationInput, organization: m.Organization = SCOPE, db: Session = DB):
    organization.name = data.name
    audit(db, organization.id, "organization.renamed", organization.id)
    db.commit()
    return {"ok": True}


@router.get("/current/members")
def members(user: m.User = ACTOR, organization: m.Organization = SCOPE, db: Session = DB):
    require_admin(db, organization.id, user.id)
    return [
        dict(row)
        for row in db.execute(
            select(
                m.Membership.id,
                m.User.username,
                m.User.name,
                m.Membership.role,
                m.User.id.label("user_id"),
            )
            .join(m.User, m.User.id == m.Membership.user_id)
            .where(m.Membership.organization_id == organization.id)
            .order_by(m.Membership.created_at)
        ).mappings()
    ]


@router.post("/current/members")
def add_member(data: s.MemberInput, organization: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, organization.id)
    user = db.scalar(select(m.User).where(m.User.username == data.username))
    if user:
        if data.password:
            fail(
                "Пользователь уже существует. Добавьте его по логину без пароля; существующий пароль не изменится",
                409,
            )
    else:
        if not data.password:
            fail("Для нового пользователя задайте пароль от 12 символов")
        user = m.User(
            username=data.username,
            name=data.name or data.username,
            password_hash=hasher.hash(data.password),
        )
        db.add(user)
        db.flush()
    old = db.scalar(
        select(m.Membership).where(
            m.Membership.user_id == user.id, m.Membership.organization_id == organization.id
        )
    )
    if old:
        fail("Пользователь уже состоит в организации", 409)
    member = m.Membership(user_id=user.id, organization_id=organization.id, role=data.role)
    db.add(member)
    db.flush()
    audit(
        db, organization.id, "membership.added", member.id, {"user_id": user.id, "role": data.role}
    )
    db.commit()
    return {"id": member.id}


def editable_member(db, organization_id, key):
    lock_organization(db, organization_id)
    member = db.scalar(
        select(m.Membership).where(
            m.Membership.id == key, m.Membership.organization_id == organization_id
        )
    )
    if member is None:
        fail("Участник не найден", 404)
    if (
        member.role == "admin"
        and db.scalar(
            select(func.count())
            .select_from(m.Membership)
            .where(m.Membership.organization_id == organization_id, m.Membership.role == "admin")
        )
        <= 1
    ):
        fail("В организации должен оставаться хотя бы один администратор", 409)
    return member


@router.put("/current/members/{key}")
def change_role(
    key: str, data: s.MemberRole, organization: m.Organization = SCOPE, db: Session = DB
):
    member = editable_member(db, organization.id, key)
    member.role = data.role
    audit(db, organization.id, "membership.role_changed", member.id, {"role": data.role})
    db.commit()
    return {"ok": True}


@router.delete("/current/members/{key}")
def remove_member(key: str, organization: m.Organization = SCOPE, db: Session = DB):
    member = editable_member(db, organization.id, key)
    audit(db, organization.id, "membership.removed", member.id, {"user_id": member.user_id})
    db.delete(member)
    db.commit()
    return {"ok": True}
