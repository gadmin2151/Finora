"""FCM tests: official google-auth + synthetic credentials + MockTransport; no network."""

import json
import threading
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import httpx
import pytest

# Optional until the integrator applies the separate dependency patch.
google_auth = pytest.importorskip("google.auth")
from google.auth.credentials import Credentials  # noqa: E402
from google.auth.exceptions import DefaultCredentialsError, RefreshError  # noqa: E402

from app.push_fcm import (  # noqa: E402
    FCM_PACKAGE,
    FCM_SCOPE,
    MAX_TTL,
    FCMConfig,
    FCMProvider,
    create_fcm_provider,
    retry_after,
)
from app.push_provider import (  # noqa: E402
    InvalidToken,
    NeutralPayload,
    PermanentDelivery,
    RetryDelivery,
)

PROJECT = "finora-5fb96"
TOKEN = "fictional-device-token"


class SyntheticCredentials(Credentials):
    def __init__(self):
        super().__init__()
        self.refreshes = 0

    def refresh(self, request):
        self.refreshes += 1
        self.token = f"synthetic-short-lived-{self.refreshes}"
        self.expiry = datetime.now(UTC).replace(tzinfo=None) + timedelta(hours=1)


@pytest.fixture
def credentials():
    return SyntheticCredentials()


def response(status=200, *, code=None, headers=None, document=None):
    if document is None:
        document = {"name": f"projects/{PROJECT}/messages/fake-id"}
        if code:
            document = {
                "error": {
                    "message": "TOKEN_SHOULD_NOT_ESCAPE",
                    "details": [
                        {
                            "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
                            "errorCode": code,
                        }
                    ],
                }
            }
    return httpx.Response(status, json=document, headers=headers)


def provider(credentials, handler):
    client = httpx.Client(transport=httpx.MockTransport(handler), trust_env=False)
    return FCMProvider(PROJECT, credentials, client)


def test_default_is_disabled_without_discovery():
    def forbidden_loader(**kwargs):
        pytest.fail("ADC discovery must not run when disabled")

    assert not create_fcm_provider(
        FCMConfig(project_id=PROJECT), credentials_loader=forbidden_loader
    ).active


def test_missing_adc_is_disabled_and_does_not_send():
    def missing(**kwargs):
        raise DefaultCredentialsError("synthetic missing; no credential file inspected")

    client = httpx.Client(
        transport=httpx.MockTransport(lambda request: pytest.fail("network forbidden"))
    )
    assert not create_fcm_provider(
        FCMConfig(project_id=PROJECT, enabled=True), client=client, credentials_loader=missing
    ).active


def test_adc_factory_scopes_explicit_target(credentials):
    seen = []
    client = httpx.Client(
        transport=httpx.MockTransport(lambda request: pytest.fail("no send during discovery"))
    )

    def loader(**kwargs):
        seen.append(kwargs)
        return credentials, "another-approved-credential-project"

    result = create_fcm_provider(
        FCMConfig(project_id=PROJECT, enabled=True), client=client, credentials_loader=loader
    )
    assert result.active and result.name == "fcm"
    assert seen[0]["scopes"] == [FCM_SCOPE]
    assert result.url == f"https://fcm.googleapis.com/v1/projects/{PROJECT}/messages:send"
    assert credentials.refreshes == 1


def test_only_event_id_leaves_server_and_token_cached(credentials):
    sent = []

    def handler(request):
        sent.append(request)
        return response()

    adapter = provider(credentials, handler)
    event_id = str(uuid4())
    payload = NeutralPayload(event_id, title="PRIVATE vendor", body="PRIVATE amount")
    adapter.send(TOKEN, payload, ttl_seconds=172800)
    adapter.send(TOKEN, payload, ttl_seconds=172800)
    assert credentials.refreshes == 1
    assert str(sent[0].url) == f"https://fcm.googleapis.com/v1/projects/{PROJECT}/messages:send"
    assert sent[0].headers["authorization"] == "Bearer synthetic-short-lived-1"
    assert json.loads(sent[0].content) == {
        "message": {
            "token": TOKEN,
            "data": {"event_id": event_id},
            "android": {"priority": "HIGH", "ttl": "172800s", "restrictedPackageName": FCM_PACKAGE},
        }
    }
    assert b"PRIVATE" not in sent[0].content
    assert "notification" not in json.loads(sent[0].content)["message"]
    credentials.expiry = datetime.now(UTC).replace(tzinfo=None) - timedelta(seconds=1)
    adapter.send(TOKEN, payload, ttl_seconds=MAX_TTL + 1)
    assert credentials.refreshes == 2
    assert json.loads(sent[-1].content)["message"]["android"]["ttl"] == f"{MAX_TTL}s"


@pytest.mark.parametrize("status,code", [(404, "UNREGISTERED"), (400, "INVALID_ARGUMENT")])
def test_only_typed_token_errors_revoke(credentials, status, code):
    adapter = provider(credentials, lambda request: response(status, code=code))
    with pytest.raises(InvalidToken):
        adapter.send(TOKEN, NeutralPayload(str(uuid4())), ttl_seconds=60)


@pytest.mark.parametrize(
    "status,document",
    [
        (400, {"error": {"status": "INVALID_ARGUMENT"}}),
        (404, {"error": {"status": "NOT_FOUND"}}),
        (403, {"error": {"status": "PERMISSION_DENIED"}}),
        (302, {}),
    ],
)
def test_other_errors_do_not_revoke_token_or_follow_redirect(credentials, status, document):
    seen = []

    def handler(request):
        seen.append(request)
        return response(
            status, document=document, headers={"Location": "https://untrusted.example"}
        )

    adapter = provider(credentials, handler)
    with pytest.raises(PermanentDelivery) as error:
        adapter.send(TOKEN, NeutralPayload(str(uuid4())), ttl_seconds=60)
    assert "TOKEN_SHOULD_NOT_ESCAPE" not in str(error.value)
    assert len(seen) == 1


@pytest.mark.parametrize("status", [408, 429, 500, 502, 503, 504])
def test_transient_retry_after(credentials, status):
    adapter = provider(
        credentials, lambda request: response(status, headers={"Retry-After": "180"})
    )
    with pytest.raises(RetryDelivery) as error:
        adapter.send(TOKEN, NeutralPayload(str(uuid4())), ttl_seconds=60)
    assert error.value.retry_after == 180


def test_retry_after_date_and_malformed():
    now = datetime(2026, 10, 10, 9, tzinfo=UTC)
    assert retry_after("Sat, 10 Oct 2026 09:02:30 GMT", now=now) == 150
    assert retry_after("-3", now=now) == 60
    assert retry_after("malformed", now=now) == 60
    assert retry_after(str(2 * 86400)) == 2 * 86400


def test_unauthorized_invalidates_cached_access_token(credentials):
    adapter = provider(credentials, lambda request: response(401))
    with pytest.raises(RetryDelivery):
        adapter.send(TOKEN, NeutralPayload(str(uuid4())), ttl_seconds=60)
    assert credentials.token is None
    assert credentials.refreshes == 1


def test_auth_error_is_scrubbed(credentials, monkeypatch):
    def failed(request):
        raise RefreshError("SECRET_REFRESH_DETAIL")

    monkeypatch.setattr(credentials, "refresh", failed)
    adapter = provider(credentials, lambda request: pytest.fail("must not send"))
    with pytest.raises(RetryDelivery) as error:
        adapter.send(TOKEN, NeutralPayload(str(uuid4())), ttl_seconds=60)
    assert str(error.value) == "provider_retry"
    assert error.value.__cause__ is None


def test_oversized_response_retries(credentials):
    adapter = provider(credentials, lambda request: httpx.Response(200, content=b"x" * 65537))
    with pytest.raises(RetryDelivery):
        adapter.send(TOKEN, NeutralPayload(str(uuid4())), ttl_seconds=60)


def test_invalid_event_never_refreshes_or_sends(credentials):
    adapter = provider(credentials, lambda request: pytest.fail("must not send"))
    with pytest.raises(PermanentDelivery):
        adapter.send(TOKEN, NeutralPayload("https://untrusted.example"), ttl_seconds=60)
    assert credentials.refreshes == 0


def test_concurrent_send_refreshes_once(credentials):
    adapter = provider(credentials, lambda request: response())
    barrier = threading.Barrier(4)
    errors = []

    def run():
        try:
            barrier.wait(timeout=5)
            adapter.send(TOKEN, NeutralPayload(str(uuid4())), ttl_seconds=60)
        except Exception as error:
            errors.append(type(error).__name__)

    threads = [threading.Thread(target=run) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=5)
    assert not errors and not any(thread.is_alive() for thread in threads)
    assert credentials.refreshes == 1


def test_credentials_refresh_failure_cannot_advertise_readiness():
    class UnusableCredentials(SyntheticCredentials):
        def refresh(self, request):
            raise RefreshError("synthetic unusable credentials")

    client = httpx.Client(transport=httpx.MockTransport(lambda r: pytest.fail("no real send")))
    result = create_fcm_provider(
        FCMConfig(project_id=PROJECT, enabled=True),
        client=client,
        credentials_loader=lambda **kwargs: (UnusableCredentials(), PROJECT),
    )
    assert not result.active


@pytest.mark.parametrize("category", ["receipts", "update_available"])
def test_only_public_allowlisted_hints_leave_server(credentials, category):
    captured = []
    adapter = provider(
        credentials, lambda request: captured.append(json.loads(request.content)) or response()
    )
    event_id = str(uuid4())
    adapter.send(
        TOKEN,
        NeutralPayload(
            event_id,
            title="PRIVATE",
            body="PRIVATE",
            category=category,
            version_code=32,
            version_name="1.18.0",
        ),
        ttl_seconds=60,
    )
    expected = {"event_id": event_id, "category": category}
    if category == "update_available":
        expected.update(version_code="32", version_name="1.18.0")
    assert captured[0]["message"]["data"] == expected
    assert "PRIVATE" not in json.dumps(captured)


def test_update_invalid_public_version_never_sends(credentials):
    adapter = provider(
        credentials, lambda request: pytest.fail("invalid version must not dispatch")
    )
    with pytest.raises(PermanentDelivery):
        adapter.send(
            TOKEN,
            NeutralPayload(
                str(uuid4()),
                category="update_available",
                version_code=32,
                version_name="https://untrusted.example",
            ),
            ttl_seconds=60,
        )
