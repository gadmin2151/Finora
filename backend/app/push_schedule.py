"""UTC scheduling evaluated in the user's IANA zone, including DST gaps/folds."""

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo


def utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def local_instant(day: date, minute: int, zone: ZoneInfo) -> datetime:
    # Earliest fold wins. A nonexistent wall time advances to the first valid minute.
    naive = datetime.combine(day, time()) + timedelta(minutes=minute)
    for step in range(26 * 60 + 1):
        wall = naive + timedelta(minutes=step)
        choices = []
        for fold in (0, 1):
            candidate = wall.replace(tzinfo=zone, fold=fold).astimezone(UTC)
            if candidate.astimezone(zone).replace(tzinfo=None) == wall:
                choices.append(candidate)
        if choices:
            return min(choices)
    raise ValueError("timezone_gap_too_large")


def after_quiet(now: datetime, timezone: str, start: int | None, end: int | None) -> datetime:
    now = utc(now)
    if start is None or end is None:
        return now
    zone = ZoneInfo(timezone)
    local = now.astimezone(zone)
    minute = local.hour * 60 + local.minute
    quiet = start <= minute < end if start < end else minute >= start or minute < end
    if not quiet:
        return now
    day = local.date() + timedelta(days=int(start > end and minute >= start))
    return max(now, local_instant(day, end, zone))


def payment_time(due: date, timezone: str, lead_days: int, hour: int) -> datetime:
    return local_instant(due - timedelta(days=lead_days), hour * 60, ZoneInfo(timezone))
