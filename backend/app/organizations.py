from fastapi import APIRouter, Depends, Query
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
            .where(m.Membership.user_id == user_id, m.Organization.deleted_at.is_(None))
            .order_by(m.Organization.created_at)
        ).mappings()
    ]


def identity(db, user, csrf):
    return {
        "id": user.id,
        "username": user.username,
        "name": user.name,
        "csrf": csrf,
        "is_server_admin": user.is_server_admin,
        "avatar_url": f"/api/users/{user.id}/avatar?v={user.avatar_version}"
        if user.avatar_version
        else None,
        "organizations": organization_list(db, user.id),
    }


def require_admin(db, organization_id, user_id):
    user = db.get(m.User, user_id)
    if user and user.is_server_admin:
        return
    member = db.scalar(
        select(m.Membership)
        .where(m.Membership.organization_id == organization_id, m.Membership.user_id == user_id)
        .execution_options(populate_existing=True)
    )
    if member is None or member.role != "admin":
        fail("Нужны права администратора организации", 403)


@router.get("")
def organizations(user: m.User = ACTOR, db: Session = DB):
    return organization_list(db, user.id)


@router.post("")
def create(data: s.OrganizationInput, user: m.User = ACTOR, db: Session = DB):
    if not user.is_server_admin and not db.scalar(
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
                m.User.is_active,
            )
            .join(m.User, m.User.id == m.Membership.user_id)
            .where(m.Membership.organization_id == organization.id, m.User.deleted_at.is_(None))
            .order_by(m.Membership.created_at)
        ).mappings()
    ]


@router.post("/current/members")
def add_member(data: s.MemberInput, organization: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, organization.id)
    user = db.scalar(select(m.User).where(func.lower(m.User.username) == data.username.lower()))
    if user:
        if user.deleted_at or not user.is_active:
            fail("Аккаунт удалён или заблокирован. Владелец сервера может восстановить доступ", 409)
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


def editable_member(db, organization_id, key, target_role=None):
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
        and target_role != "admin"
        and db.get(m.User, member.user_id).is_active
        and db.scalar(
            select(func.count())
            .select_from(m.Membership)
            .join(m.User, m.User.id == m.Membership.user_id)
            .where(
                m.Membership.organization_id == organization_id,
                m.Membership.role == "admin",
                m.User.is_active.is_(True),
                m.User.deleted_at.is_(None),
            )
        )
        <= 1
    ):
        fail("В организации должен оставаться хотя бы один администратор", 409)
    return member


@router.put("/current/members/{key}")
def change_role(
    key: str, data: s.MemberRole, organization: m.Organization = SCOPE, db: Session = DB
):
    member = editable_member(db, organization.id, key, data.role)
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


@router.get("/manage")
def management_list(
    status: str = Query("active", pattern="^(active|deleted)$"),
    user: m.User = ACTOR,
    db: Session = DB,
):
    query = select(m.Organization)
    if not user.is_server_admin:
        query = query.join(m.Membership).where(m.Membership.user_id == user.id)
    query = query.where(
        m.Organization.deleted_at.is_not(None)
        if status == "deleted"
        else m.Organization.deleted_at.is_(None)
    )
    rows = list(db.scalars(query.order_by(m.Organization.name, m.Organization.id)))
    ids = [row.id for row in rows]
    roles = dict(
        db.execute(
            select(m.Membership.organization_id, m.Membership.role).where(
                m.Membership.user_id == user.id, m.Membership.organization_id.in_(ids)
            )
        ).all()
    )
    counts = dict(
        db.execute(
            select(m.Membership.organization_id, func.count())
            .join(m.User)
            .where(m.Membership.organization_id.in_(ids), m.User.deleted_at.is_(None))
            .group_by(m.Membership.organization_id)
        ).all()
    )
    receipts = dict(
        db.execute(
            select(m.Receipt.organization_id, func.count())
            .where(m.Receipt.organization_id.in_(ids), m.Receipt.deleted_at.is_(None))
            .group_by(m.Receipt.organization_id)
        ).all()
    )
    return [
        {
            "id": row.id,
            "name": row.name,
            "deleted_at": row.deleted_at,
            "role": roles.get(row.id),
            "can_manage": user.is_server_admin or roles.get(row.id) == "admin",
            "members_count": counts.get(row.id, 0),
            "receipts_count": receipts.get(row.id, 0),
        }
        for row in rows
        if status != "deleted" or user.is_server_admin or roles.get(row.id) == "admin"
    ]


def managed_organization(db: Session, key: str, user: m.User, *, deleted=False):
    require_admin(db, key, user.id)
    org = db.scalar(
        select(m.Organization)
        .where(m.Organization.id == key)
        .with_for_update()
        .execution_options(populate_existing=True)
    )
    if org is None or bool(org.deleted_at) != deleted:
        fail("Организация не найдена", 404)
    require_admin(db, key, user.id)
    return org


@router.put("/{organization_id}")
def update_organization(
    organization_id: str, data: s.OrganizationInput, user: m.User = ACTOR, db: Session = DB
):
    return rename(data, managed_organization(db, organization_id, user), db)


@router.get("/{organization_id}/members")
def list_members(organization_id: str, user: m.User = ACTOR, db: Session = DB):
    return members(user, managed_organization(db, organization_id, user), db)


@router.post("/{organization_id}/members")
def invite_member(
    organization_id: str, data: s.MemberInput, user: m.User = ACTOR, db: Session = DB
):
    return add_member(data, managed_organization(db, organization_id, user), db)


@router.put("/{organization_id}/members/{key}")
def update_member(
    organization_id: str, key: str, data: s.MemberRole, user: m.User = ACTOR, db: Session = DB
):
    return change_role(key, data, managed_organization(db, organization_id, user), db)


@router.delete("/{organization_id}/members/{key}")
def revoke_member(organization_id: str, key: str, user: m.User = ACTOR, db: Session = DB):
    return remove_member(key, managed_organization(db, organization_id, user), db)


@router.delete("/{organization_id}")
def delete_organization(organization_id: str, user: m.User = ACTOR, db: Session = DB):
    org = managed_organization(db, organization_id, user)
    if db.scalar(
        select(m.Job.id)
        .where(m.Job.organization_id == org.id, m.Job.status.in_(["queued", "running"]))
        .limit(1)
    ):
        fail("Дождитесь завершения обработки чеков и задач организации", 409)
    org.deleted_at = m.now()
    audit(db, org.id, "organization.deleted", org.id)
    db.commit()
    return {"ok": True}


@router.post("/{organization_id}/restore")
def restore_organization(organization_id: str, user: m.User = ACTOR, db: Session = DB):
    org = managed_organization(db, organization_id, user, deleted=True)
    active_admin = db.scalar(
        select(m.Membership.id)
        .join(m.User)
        .where(
            m.Membership.organization_id == org.id,
            m.Membership.role == "admin",
            m.User.is_active.is_(True),
            m.User.deleted_at.is_(None),
        )
        .limit(1)
    )
    if not active_admin:
        member = db.scalar(
            select(m.Membership).where(
                m.Membership.organization_id == org.id, m.Membership.user_id == user.id
            )
        )
        if member:
            member.role = "admin"
        else:
            db.add(m.Membership(organization_id=org.id, user_id=user.id, role="admin"))
    org.deleted_at = None
    audit(db, org.id, "organization.restored", org.id)
    db.commit()
    return {"ok": True}
