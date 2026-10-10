"""Opt-in FCM HTTP v1 adapter. Import/default config never discovers credentials."""

import json
import math
import re
import threading
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from uuid import UUID

import httpx

from .push_provider import (
    DisabledProvider,
    InvalidToken,
    NeutralPayload,
    PermanentDelivery,
    RetryDelivery,
)

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
FCM_PACKAGE = "work.gadmin.finora"
MAX_TTL = 28 * 24 * 60 * 60
MAX_RESPONSE_BYTES = 64 * 1024
CALL_BUDGET_SECONDS = 40.0


@dataclass(frozen=True)
class FCMConfig:
    project_id: str = ""
    enabled: bool = False


@dataclass(frozen=True)
class AuthResponse:
    status: int
    headers: dict
    data: bytes


class GoogleAuthRequest:
    """google.auth.transport.Request interface backed by existing bounded httpx."""

    def __init__(self, client, deadline):
        self.client = client
        self.deadline = deadline

    def __call__(self, url, method="GET", body=None, headers=None, timeout=None, **kwargs):
        from google.auth.exceptions import TransportError

        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TransportError("push_auth_deadline")
        timeout = min(remaining, 10.0, float(timeout or 10.0))
        try:
            with self.client.stream(
                method, url, content=body, headers=headers, timeout=timeout, follow_redirects=False
            ) as response:
                content = bounded_body(response, self.deadline)
                return AuthResponse(response.status_code, dict(response.headers), content)
        except (httpx.HTTPError, ValueError) as error:
            raise TransportError("push_auth_transport") from error


def bounded_body(response, deadline):
    content = bytearray()
    for chunk in response.iter_bytes():
        if time.monotonic() >= deadline or len(content) + len(chunk) > MAX_RESPONSE_BYTES:
            raise ValueError("push_response_limit")
        content.extend(chunk)
    return bytes(content)


def retry_after(value: str | None, *, now: datetime | None = None) -> int:
    if not value:
        return 60
    try:
        seconds = int(value)
    except ValueError:
        try:
            when = parsedate_to_datetime(value)
            if when.tzinfo is None:
                when = when.replace(tzinfo=UTC)
            seconds = math.ceil((when - (now or datetime.now(UTC))).total_seconds())
        except (ValueError, TypeError, OverflowError):
            return 60
    return max(60, min(seconds, MAX_TTL))


class FCMProvider:
    name = "fcm"
    active = True

    def __init__(self, project_id, credentials, client):
        if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", project_id):
            raise ValueError("invalid_fcm_project")
        self.url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
        self.message_prefix = f"projects/{project_id}/messages/"
        self.credentials = credentials
        self.client = client
        self._credential_lock = threading.Lock()

    def send(self, token: str, payload: NeutralPayload, *, ttl_seconds: int) -> None:
        from google.auth.exceptions import GoogleAuthError

        # Only opaque event ID and allowlisted public routing/version hints leave the server.
        try:
            event_id = str(UUID(payload.event_id))
        except (ValueError, TypeError, AttributeError):
            raise PermanentDelivery("provider_rejected") from None
        if not 16 <= len(token) <= 4096 or any(c.isspace() for c in token):
            raise InvalidToken()
        if not isinstance(ttl_seconds, int) or ttl_seconds <= 0:
            raise PermanentDelivery("provider_rejected")
        data = {"event_id": event_id}
        if payload.category == "receipts":
            data["category"] = "receipts"
        elif payload.category == "update_available":
            if (
                type(payload.version_code) is not int
                or not 0 < payload.version_code <= 2100000000
                or not isinstance(payload.version_name, str)
                or not re.fullmatch(r"\d+\.\d+\.\d+", payload.version_name)
                or len(payload.version_name) > 64
            ):
                raise PermanentDelivery("provider_rejected")
            data.update(
                category="update_available",
                version_code=str(payload.version_code),
                version_name=payload.version_name,
            )
        body = {
            "message": {
                "token": token,
                "data": data,
                "android": {
                    "priority": "HIGH",
                    "ttl": f"{min(ttl_seconds, MAX_TTL)}s",
                    "restrictedPackageName": FCM_PACKAGE,
                },
            }
        }
        deadline = time.monotonic() + CALL_BUDGET_SECONDS
        headers = {"Content-Type": "application/json"}
        try:
            remaining = max(0, deadline - time.monotonic())
            if not self._credential_lock.acquire(timeout=remaining):
                raise RetryDelivery(60)
            try:
                # Official library refreshes near-expiry access tokens and caches in RAM.
                self.credentials.before_request(
                    GoogleAuthRequest(self.client, deadline), "POST", self.url, headers
                )
            finally:
                self._credential_lock.release()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RetryDelivery(60)
            with self.client.stream(
                "POST",
                self.url,
                json=body,
                headers=headers,
                timeout=min(10.0, remaining),
                follow_redirects=False,
            ) as response:
                raw = bounded_body(response, deadline)
                status, retry = response.status_code, response.headers.get("Retry-After")
            # Decode without exposing response, which may echo device token/request fields.
            try:
                document = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                document = {}
            if status == 200:
                name = document.get("name") if isinstance(document, dict) else None
                if not isinstance(name, str) or not name.startswith(self.message_prefix):
                    raise RetryDelivery(60)
                return
            error = document.get("error", {}) if isinstance(document, dict) else {}
            details = error.get("details", []) if isinstance(error, dict) else []
            codes = (
                {
                    detail.get("errorCode")
                    for detail in details
                    if isinstance(detail, dict)
                    and detail.get("@type") == "type.googleapis.com/google.firebase.fcm.v1.FcmError"
                }
                if isinstance(details, list)
                else set()
            )
            if "UNREGISTERED" in codes and status == 404:
                raise InvalidToken()
            # Typed FCM invalid argument with our fully validated fixed payload => token.
            if "INVALID_ARGUMENT" in codes and status == 400:
                raise InvalidToken()
            if status == 401:
                with self._credential_lock:
                    self.credentials.token = None
                raise RetryDelivery(retry_after(retry))
            if status in {408, 429, 500, 502, 503, 504}:
                raise RetryDelivery(retry_after(retry))
            raise PermanentDelivery("provider_auth" if status == 403 else "provider_rejected")
        except GoogleAuthError:
            raise RetryDelivery(60) from None
        except (httpx.HTTPError, ValueError):
            raise RetryDelivery(60) from None


def create_fcm_provider(config: FCMConfig, *, client=None, credentials_loader=None):
    """Call only after approved server configuration; no ADC probe while disabled."""
    if not config.enabled:
        return DisabledProvider()
    if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", config.project_id):
        raise ValueError("invalid_fcm_project")
    import google.auth
    from google.auth.exceptions import GoogleAuthError

    owned_client = client is None
    client = client or httpx.Client(timeout=10.0, follow_redirects=False, trust_env=False)
    loader = credentials_loader or google.auth.default
    try:
        credentials, _ = loader(
            scopes=[FCM_SCOPE],
            request=GoogleAuthRequest(client, time.monotonic() + CALL_BUDGET_SECONDS),
        )
        provider = FCMProvider(config.project_id, credentials, client)
        # Readiness requires successfully obtaining scoped short-lived credentials, not a file.
        credentials.before_request(
            GoogleAuthRequest(client, time.monotonic() + CALL_BUDGET_SECONDS),
            "POST",
            provider.url,
            {},
        )
        if not credentials.valid:
            raise GoogleAuthError("push_auth_unavailable")
        return provider
    except GoogleAuthError:
        if owned_client:
            client.close()
        return DisabledProvider()  # Missing/invalid ADC cannot advertise delivery readiness.
