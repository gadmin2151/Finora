"""Receipt attribution: shared within its organization, never inferred from later editors."""

from collections.abc import Iterable, Mapping
from typing import TypedDict

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models as m
from .i18n import t


class Creator(TypedDict):
    id: str | None
    name: str
    username: str | None
    active: bool


def creator_data(user, user_id: str | None = None) -> Creator:
    if user is None:
        return {
            "id": user_id,
            "name": t("Удалённый пользователь") if user_id else t("Автор не указан"),
            "username": None,
            "active": False,
        }
    return {
        "id": user.id,
        "name": user.name or user.username,
        "username": user.username,
        "active": user.is_active and user.deleted_at is None,
    }


def load_creators(db: Session, user_ids: Iterable[str | None]) -> dict[str, Creator]:
    ids = {value for value in user_ids if value}
    if not ids:
        return {}
    return {
        user.id: creator_data(user)
        for user in db.execute(
            select(
                m.User.id, m.User.name, m.User.username, m.User.is_active, m.User.deleted_at
            ).where(m.User.id.in_(ids))
        )
    }


def receipt_creator(
    db: Session, receipt: m.Receipt, preloaded: Mapping[str, Creator] | None = None
) -> Creator:
    creators = preloaded if preloaded is not None else load_creators(db, [receipt.created_by])
    return creators.get(receipt.created_by) or creator_data(None, receipt.created_by)


def creator_filter(created_by: str):
    return (
        m.Receipt.created_by.is_(None)
        if created_by == "unknown"
        else m.Receipt.created_by == created_by
    )


def organization_creators(db: Session, organization_id: str):
    ids = list(
        db.scalars(
            select(m.Receipt.created_by)
            .where(m.Receipt.organization_id == organization_id, m.Receipt.deleted_at.is_(None))
            .distinct()
            .order_by(m.Receipt.created_by)
            .limit(501)
        )
    )
    creators = load_creators(db, ids[:500])
    return {
        "items": sorted(
            [creators.get(key) or creator_data(None, key) for key in ids[:500]],
            key=lambda creator: (
                creator["id"] is None,
                creator["name"].casefold(),
                creator["id"] or "",
            ),
        ),
        "has_more": len(ids) > 500,
    }
