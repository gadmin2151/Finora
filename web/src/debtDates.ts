/** Older database timestamps omit their UTC offset; never interpret them as local time. */
export function debtRecordTime(raw: string, locale: string) {
  if (
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?$/.test(
      raw,
    )
  )
    return null;
  const normalized = /(?:Z|[+-]\d{2}:\d{2})$/.test(raw) ? raw : `${raw}Z`;
  const value = new Date(normalized);
  if (!Number.isFinite(value.getTime())) return null;
  const timeZone = "Europe/Chisinau";
  return {
    iso: value.toISOString(),
    date: new Intl.DateTimeFormat("en-CA", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(value),
    label: new Intl.DateTimeFormat(locale, {
      timeZone,
      dateStyle: "medium",
      timeStyle: "short",
      hour12: false,
    }).format(value),
  };
}
