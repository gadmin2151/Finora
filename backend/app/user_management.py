from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from . import models as m
from . import schemas as s
from .finance import audit, fail, lock_organization
from .security import hasher, lock_user_credentials


def account_audit(db: Session, actor: m.User, user: m.User, action: str, details=None):
    db.add(m.UserAudit(actor_id=actor.id, user_id=user.id, action=action, details=details or {}))


def lock_accounts(db: Session, actor: m.User):
    # Serialize provisioning without a process-local mutex; this works across API replicas.
    owners = list(
        db.scalars(
            select(m.User)
            .where(m.User.is_server_admin.is_(True))
            .order_by(m.User.id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    )
    if not any(owner.id == actor.id and owner.is_active for owner in owners):
        fail("Нужны права владельца сервера", 403)


def set_memberships(db: Session, user: m.User, desired: list[s.UserMembership], active: bool):
    wanted = {entry.organization_id: entry.role for entry in desired}
    if len(wanted) != len(desired):
        fail("Организация указана несколько раз")
    existing = {
        row.organization_id: row
        for row in db.scalars(select(m.Membership).where(m.Membership.user_id == user.id))
    }
    for org_id in sorted(set(wanted) | set(existing)):
        organization = db.get(m.Organization, org_id)
        if organization is None:
            fail("Организация не найдена", 404)
        lock_organization(db, org_id)
        db.refresh(organization)
        old = existing.get(org_id)
        if organization.deleted_at:
            if org_id in wanted and (old is None or old.role != wanted[org_id]):
                fail("Сначала восстановите организацию из корзины", 409)
            continue
        if old and old.role == "admin" and (not active or wanted.get(org_id) != "admin"):
            others = db.scalar(
                select(func.count())
                .select_from(m.Membership)
                .join(m.User)
                .where(
                    m.Membership.organization_id == org_id,
                    m.Membership.role == "admin",
                    m.Membership.user_id != user.id,
                    m.User.is_active.is_(True),
                    m.User.deleted_at.is_(None),
                )
            )
            if not others:
                fail("В каждой организации должен остаться активный администратор", 409)
    for org_id, old in existing.items():
        if org_id not in wanted:
            audit(db, org_id, "membership.removed", old.id, {"user_id": user.id})
            db.delete(old)
        elif old.role != wanted[org_id]:
            old.role = wanted[org_id]
            audit(db, org_id, "membership.role_changed", old.id, {"role": old.role})
    for org_id, role in wanted.items():
        if org_id not in existing:
            row = m.Membership(user_id=user.id, organization_id=org_id, role=role)
            db.add(row)
            db.flush()
            audit(db, org_id, "membership.added", row.id, {"user_id": user.id, "role": role})


def create_account(db: Session, actor: m.User, data: s.UserCreate) -> m.User:
    lock_accounts(db, actor)
    if db.scalar(select(m.User.id).where(func.lower(m.User.username) == data.username.lower())):
        fail("Такой логин уже занят", 409)
    user = m.User(
        username=data.username,
        name=data.name,
        password_hash=hasher.hash(data.password),
        is_active=data.is_active,
    )
    db.add(user)
    db.flush()
    set_memberships(db, user, data.memberships, data.is_active)
    account_audit(db, actor, user, "user.created")
    db.commit()
    return user


def edit_account(db: Session, actor: m.User, user: m.User, data: s.UserEdit):
    lock_accounts(db, actor)
    user = lock_user_credentials(db, user.id)
    if user is None or user.deleted_at:
        fail("Пользователь не найден", 404)
    if user.is_server_admin and not data.is_active:
        fail("Владельца сервера нельзя заблокировать", 409)
    set_memberships(db, user, data.memberships, data.is_active)
    if not data.is_active:
        db.execute(delete(m.Session).where(m.Session.user_id == user.id))
    user.name = data.name
    user.is_active = data.is_active
    account_audit(
        db,
        actor,
        user,
        "user.updated",
        {
            "is_active": data.is_active,
            "memberships": [entry.model_dump() for entry in data.memberships],
        },
    )
    db.commit()


def reset_password(db: Session, actor: m.User, user: m.User, password: str):
    lock_accounts(db, actor)
    user = lock_user_credentials(db, user.id)
    if user is None or user.deleted_at:
        fail("Пользователь не найден", 404)
    if user.id == actor.id:
        fail("Свой пароль измените в настройках профиля", 409)
    user.password_hash = hasher.hash(password)
    db.execute(delete(m.Session).where(m.Session.user_id == user.id))
    account_audit(db, actor, user, "user.password_reset")
    db.commit()


def delete_account(db: Session, actor: m.User, user: m.User):
    lock_accounts(db, actor)
    user = lock_user_credentials(db, user.id)
    if user is None or user.deleted_at:
        fail("Пользователь не найден", 404)
    if user.is_server_admin:
        fail("Владельца сервера нельзя удалить", 409)
    memberships = [
        s.UserMembership(organization_id=row.organization_id, role=row.role)
        for row in db.scalars(select(m.Membership).where(m.Membership.user_id == user.id))
    ]
    set_memberships(db, user, memberships, False)
    user.deleted_at = m.now()
    user.is_active = False
    db.execute(delete(m.Session).where(m.Session.user_id == user.id))
    account_audit(db, actor, user, "user.deleted")
    db.commit()


def restore_account(db: Session, actor: m.User, user: m.User):
    lock_accounts(db, actor)
    user = lock_user_credentials(db, user.id)
    if user is None or user.deleted_at is None:
        fail("Пользователь не найден в корзине", 404)
    user.deleted_at = None
    # Restore identity and assignments without silently re-enabling sign-in.
    user.is_active = False
    account_audit(db, actor, user, "user.restored")
    db.commit()
