"""Creation-only receipt notifications owned by the receipt transaction."""

import logging
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from . import models as m
from .push_service import enqueue

logger = logging.getLogger("finora.push")


def receipt_created(db: Session, receipt: m.Receipt, *, now: datetime) -> list[str]:
    """Called only by fresh upload/manual creation, never by OCR, retry or review.

    The durable marker is recorded even when preferences/devices suppress every send.
    Rollout does not populate markers for existing receipts.
    """
    from .push_models import PushReceiptCreation

    if not receipt.created_by or receipt.deleted_at:
        return []
    db.flush()
    if db.get(PushReceiptCreation, receipt.id):
        return []
    # Persist eligibility with the document, outside the best-effort notification savepoint.
    db.add(PushReceiptCreation(receipt_id=receipt.id, eligible_at=now))
    db.flush()
    recipients = list(
        db.scalars(
            select(m.Membership.user_id)
            .where(
                m.Membership.organization_id == receipt.organization_id,
                m.Membership.user_id != receipt.created_by,
            )
            .order_by(m.Membership.user_id)
        )
    )
    result = []
    for user_id in recipients:
        try:
            with db.begin_nested():
                event = enqueue(
                    db,
                    user_id=user_id,
                    organization_id=receipt.organization_id,
                    event_key=f"receipt-created:{receipt.id}",
                    category="receipts",
                    entity_id=receipt.id,
                    now=now,
                    expires_at=now + timedelta(days=2),
                )
                if event:
                    result.append(event.id)
        except SQLAlchemyError:
            logger.warning("push_enqueue_failed")
    return result


def receipt_ready(db: Session, receipt: m.Receipt, *, now: datetime) -> None:
    """Compatibility no-op: completion/reprocess must never notify historical receipts."""
    return None
