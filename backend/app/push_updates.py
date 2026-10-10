"""Trusted publication -> per-installation outbox. No network, commits or financial writes."""

import re
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from .push_models import PushAPKRelease, PushDelivery, PushDevice, PushEvent, PushUpdateNotice
from .push_schedule import after_quiet, utc
from .push_service import authorized, lock_owner, preference

PACKAGE = "work.gadmin.finora"
CERTIFICATE = "3a3c14d603cc9cfeff5092ece5101acc60512787e90f1330292d98e4f45ee6c0"
MANIFEST_URL = "https://finora.gadmin.work/assets/mobile/update.json"


def publish_verified_release(
    db: Session,
    *,
    manifest: dict,
    manifest_url: str,
    apk_sha256: str,
    apk_size: int,
    apk_package: str,
    apk_version_code: int,
    apk_version_name: str,
    apk_certificate: str,
    manifest_http_status: int,
    apk_http_status: int,
    now: datetime,
) -> PushAPKRelease:
    """Internal publisher only, AFTER HTTPS publication + downloaded APK signer verification.

    Actual observed downloaded APK metadata is mandatory. A locally generated manifest
    is insufficient. Caller owns verification, publication ordering, transaction and commit.
    Never expose this function through a user-accessible API.
    """
    code, name = manifest.get("versionCode"), manifest.get("versionName")
    if not (
        manifest_url == MANIFEST_URL
        and manifest_http_status == apk_http_status == 200
        and manifest.get("schemaVersion") == 1
        and manifest.get("packageName") == apk_package == PACKAGE
        and type(code) is int
        and 0 < code <= 2100000000
        and code == apk_version_code
        and isinstance(name, str)
        and re.fullmatch(r"\d+\.\d+\.\d+", name)
        and len(name) <= 64
        and name == apk_version_name
        and manifest.get("apkUrl") == f"https://finora.gadmin.work/assets/mobile/finora-{name}.apk"
        and manifest.get("signingCertificateSha256") == apk_certificate == CERTIFICATE
        and isinstance(apk_sha256, str)
        and re.fullmatch(r"[a-f0-9]{64}", apk_sha256)
        and manifest.get("sha256") == apk_sha256
        and type(apk_size) is int
        and 0 < apk_size <= 512 * 1024 * 1024
        and manifest.get("sizeBytes") == apk_size
    ):
        raise ValueError("Unverified APK publication")
    existing = db.get(PushAPKRelease, code)
    if existing:
        if (existing.version_name, existing.apk_sha256) != (name, apk_sha256):
            raise ValueError("Published version is immutable")
        return existing  # Retries cannot reactivate withdrawn releases.
    release = PushAPKRelease(
        version_code=code, version_name=name, apk_sha256=apk_sha256, published_at=utc(now)
    )
    db.add(release)
    db.flush()
    return release


def latest_release(db: Session, *, now: datetime) -> PushAPKRelease | None:
    release = db.scalar(
        select(PushAPKRelease)
        .where(PushAPKRelease.published_at <= utc(now))
        .order_by(PushAPKRelease.version_code.desc())
        .limit(1)
    )
    return release if release and release.withdrawn_at is None else None


def update_event_current(db: Session, event: PushEvent, *, now: datetime) -> bool:
    release = latest_release(db, now=now)
    return bool(
        release
        and event.category == "update_available"
        and event.route == "updater"
        and event.entity_id is None
        and event.due_date is None
        and event.version_code == release.version_code
        and event.version_name == release.version_name
        and utc(event.expires_at) > utc(now)
    )


def update_delivery_eligible(
    db: Session, event: PushEvent, device: PushDevice, *, now: datetime
) -> bool:
    """Recheck before event fetch AND dispatch; normal session/scope/prefs checks still required."""
    return bool(
        update_event_current(db, event, now=now)
        and device.installed_version_code is not None
        and 0 < device.installed_version_code < event.version_code
        and device.user_id == event.user_id
        and device.organization_id == event.organization_id
        and db.scalar(
            select(PushUpdateNotice.id).where(
                PushUpdateNotice.user_id == device.user_id,
                PushUpdateNotice.installation_id == device.installation_id,
                PushUpdateNotice.version_code == event.version_code,
                PushUpdateNotice.event_id == event.id,
            )
        )
    )


def schedule_updates(
    db: Session, *, now: datetime, after_id: str | None = None, limit: int = 100
) -> tuple[int, str | None]:
    """Bounded keyset page; caller commits page then uses returned cursor (None restarts)."""
    if not 1 <= limit <= 1000:
        raise ValueError("Invalid page size")
    release = latest_release(db, now=now)
    if release is None:
        return 0, None
    query = select(PushDevice.id).where(
        PushDevice.enabled.is_(True),
        PushDevice.revoked_at.is_(None),
        PushDevice.installed_version_code > 0,
        PushDevice.installed_version_code < release.version_code,
    )
    if after_id:
        query = query.where(PushDevice.id > after_id)
    ids = list(db.scalars(query.order_by(PushDevice.id).limit(limit)))
    count = 0
    for device_id in ids:
        snapshot = db.get(PushDevice, device_id)
        if snapshot is None:
            continue
        lock_owner(db, snapshot.user_id)
        device = db.scalar(
            select(PushDevice)
            .where(PushDevice.id == device_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
        if (
            not device
            or not device.enabled
            or device.revoked_at is not None
            or not device.installed_version_code
        ):
            continue
        if device.installed_version_code >= release.version_code or not authorized(
            db, device.user_id, device.session_id, device.organization_id, now
        ):
            continue
        prefs = preference(db, device.user_id, device.organization_id)
        if not prefs or not prefs.enabled or not prefs.updates:
            continue
        if db.scalar(
            select(PushUpdateNotice.id).where(
                PushUpdateNotice.user_id == device.user_id,
                PushUpdateNotice.installation_id == device.installation_id,
                PushUpdateNotice.version_code == release.version_code,
            )
        ):
            continue
        expires_at = utc(release.published_at) + timedelta(days=28)
        available_at = after_quiet(now, prefs.timezone, prefs.quiet_start, prefs.quiet_end)
        if available_at >= expires_at:
            continue
        event = PushEvent(
            user_id=device.user_id,
            organization_id=device.organization_id,
            event_key=f"update:{device.installation_id}:{release.version_code}",
            category="update_available",
            route="updater",
            entity_id=None,
            due_date=None,
            version_code=release.version_code,
            version_name=release.version_name,
            expires_at=expires_at,
        )
        db.add(event)
        db.flush()
        db.add(
            PushUpdateNotice(
                user_id=device.user_id,
                installation_id=device.installation_id,
                version_code=release.version_code,
                event_id=event.id,
            )
        )
        db.add(PushDelivery(event_id=event.id, device_id=device.id, available_at=available_at))
        db.flush()
        count += 1
    return count, ids[-1] if len(ids) == limit else None
