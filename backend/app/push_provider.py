"""Provider-neutral seam. Deliberately no network adapter or credential loading."""

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class NeutralPayload:
    event_id: str
    title: str = "Finora"
    body: str = "Open Finora to view a new notification."
    category: str | None = None
    version_code: int | None = None
    version_name: str | None = None


class InvalidToken(Exception):
    pass


class RetryDelivery(Exception):
    def __init__(self, retry_after: int = 60):
        self.retry_after = max(1, min(retry_after, 28 * 86400))
        super().__init__("provider_retry")


class PermanentDelivery(Exception):
    def __init__(self, code: str = "provider_rejected"):
        self.code = code if code in {"provider_auth", "provider_rejected"} else "provider_rejected"
        super().__init__(self.code)


class PushProvider(Protocol):
    name: str
    active: bool

    def send(self, token: str, payload: NeutralPayload, *, ttl_seconds: int) -> None: ...


class DisabledProvider:
    name = "disabled"
    active = False

    def send(self, token: str, payload: NeutralPayload, *, ttl_seconds: int) -> None:
        raise RuntimeError("push_provider_not_configured")
