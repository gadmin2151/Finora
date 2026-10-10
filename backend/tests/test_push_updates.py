"""APK update events use fictional published metadata; no provider or network calls."""

from datetime import UTC, timedelta

import pytest
import test_push_backend as foundation
from sqlalchemy import select

from app.db import SessionLocal
from app.push_models import (
    PushAPKRelease,
    PushDelivery,
    PushDevice,
    PushEvent,
    PushPreference,
    PushUpdateNotice,
)
from app.push_updates import (
    CERTIFICATE,
    MANIFEST_URL,
    PACKAGE,
    publish_verified_release,
    schedule_updates,
    update_delivery_eligible,
)

NOW = foundation.NOW


@pytest.fixture
def context(owner):
    return foundation.context.__wrapped__(owner)


def publish(db, code=200, name="1.20.0", **overrides):
    args = dict(
        manifest=dict(
            schemaVersion=1,
            packageName=PACKAGE,
            versionCode=code,
            versionName=name,
            apkUrl=f"https://finora.gadmin.work/assets/mobile/finora-{name}.apk",
            sha256="a" * 64,
            sizeBytes=100,
            signingCertificateSha256=CERTIFICATE,
        ),
        manifest_url=MANIFEST_URL,
        apk_sha256="a" * 64,
        apk_size=100,
        apk_package=PACKAGE,
        apk_version_code=code,
        apk_version_name=name,
        apk_certificate=CERTIFICATE,
        manifest_http_status=200,
        apk_http_status=200,
        now=NOW,
    )
    args.update(overrides)
    return publish_verified_release(db, **args)


def enable(db, context, version=100):
    device = db.get(PushDevice, context["device_id"])
    device.installed_version_code = version
    device.installed_version_name = "1.10.0" if version else None
    prefs = db.scalar(select(PushPreference))
    prefs.updates = True
    db.flush()
    return device, prefs


@pytest.mark.parametrize("version", [None, 200, 201])
def test_unknown_current_newer_installation_skipped(context, version):
    with SessionLocal() as db:
        publish(db)
        enable(db, context, version)
        assert schedule_updates(db, now=NOW) == (0, None)
        assert db.scalar(select(PushEvent)) is None


def test_update_one_device_opt_in_quiet_and_durable_dedup(context):
    with SessionLocal() as db:
        release = publish(db)
        device, prefs = enable(db, context)
        prefs.quiet_start, prefs.quiet_end = 8 * 60, 10 * 60
        assert schedule_updates(db, now=NOW) == (1, None)
        assert schedule_updates(db, now=NOW) == (0, None)
        event = db.scalar(select(PushEvent))
        delivery = db.scalar(select(PushDelivery))
        assert event.category == "update_available" and event.route == "updater"
        assert event.entity_id is None and event.due_date is None
        assert (event.version_code, event.version_name) == (
            release.version_code,
            release.version_name,
        )
        assert delivery.device_id == device.id
        assert delivery.available_at.astimezone(UTC).hour == 10
        assert update_delivery_eligible(db, event, device, now=NOW)
        device.installed_version_code = 200
        assert not update_delivery_eligible(db, event, device, now=NOW)
        device.installed_version_code = 100
        # Registry re-creation retains owner/installation/version dedup.
        db.delete(delivery)
        db.delete(device)
        db.flush()
        assert db.scalar(select(PushUpdateNotice)) is not None


@pytest.mark.parametrize("field", ["updates", "enabled"])
def test_preferences_disabled_skip_without_dedup_marker(context, field):
    with SessionLocal() as db:
        publish(db)
        _, prefs = enable(db, context)
        setattr(prefs, field, False)
        assert schedule_updates(db, now=NOW) == (0, None)
        assert db.scalar(select(PushUpdateNotice)) is None


def test_withdrawn_latest_and_expired_release_do_not_send(context):
    with SessionLocal() as db:
        publish(db, 199, "1.19.0")
        release = publish(db)
        device, _ = enable(db, context)
        schedule_updates(db, now=NOW)
        event = db.scalar(select(PushEvent))
        release.withdrawn_at = NOW
        assert not update_delivery_eligible(db, event, device, now=NOW)
        assert schedule_updates(db, now=NOW) == (0, None)
        release.withdrawn_at = None
        assert not update_delivery_eligible(db, event, device, now=NOW + timedelta(days=28))


@pytest.mark.parametrize(
    "overrides",
    [
        dict(apk_http_status=404),
        dict(manifest_http_status=404),
        dict(apk_sha256="b" * 64),
        dict(apk_size=99),
        dict(apk_package="wrong"),
        dict(apk_certificate="b" * 64),
        dict(apk_version_code=199),
        dict(apk_version_name="1.0.0"),
        dict(manifest_url="https://evil.example/update.json"),
    ],
)
def test_unverified_publication_rejected(overrides):
    with SessionLocal() as db:
        with pytest.raises(ValueError, match="Unverified"):
            publish(db, **overrides)
        assert db.scalar(select(PushAPKRelease)) is None


def test_publication_retry_immutable_and_no_reactivate():
    with SessionLocal() as db:
        release = publish(db)
        release.withdrawn_at = NOW
        assert publish(db) is release
        assert release.withdrawn_at == NOW
        with pytest.raises(ValueError, match="immutable"):
            publish(db, name="1.21.0")


def test_fanout_excludes_other_device_current_or_unknown(context):
    from uuid import uuid4

    with SessionLocal() as db:
        publish(db)
        source, _ = enable(db, context)
        for version in (None, 200, 201):
            db.add(
                PushDevice(
                    user_id=source.user_id,
                    session_id=source.session_id,
                    organization_id=source.organization_id,
                    installation_id=str(uuid4()),
                    provider="mock",
                    token_hash=str(uuid4()),
                    token_ciphertext="fictional",
                    enabled=True,
                    updated_at=NOW,
                    installed_version_code=version,
                )
            )
        db.flush()
        assert schedule_updates(db, now=NOW) == (1, None)
        assert list(db.scalars(select(PushDelivery.device_id))) == [source.id]


def test_expired_session_or_revocation_skips(context):
    from app import models as m

    with SessionLocal() as db:
        publish(db)
        device, _ = enable(db, context)
        db.get(m.Session, device.session_id).expires_at = NOW
        assert schedule_updates(db, now=NOW) == (0, None)
        db.get(m.Session, device.session_id).expires_at = NOW + timedelta(days=1)
        device.revoked_at = NOW
        assert schedule_updates(db, now=NOW) == (0, None)
        assert db.scalar(select(PushUpdateNotice)) is None


def test_page_cursor_bounded_and_no_publication_no_event(context):
    with SessionLocal() as db:
        enable(db, context)
        assert schedule_updates(db, now=NOW, limit=1) == (0, None)
        publish(db)
        count, cursor = schedule_updates(db, now=NOW, limit=1)
        assert count == 1 and cursor == context["device_id"]
        assert schedule_updates(db, now=NOW, after_id=cursor, limit=1) == (0, None)
        with pytest.raises(ValueError):
            schedule_updates(db, now=NOW, limit=1001)
