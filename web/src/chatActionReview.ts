import type { Message } from "./types";

/** Exact object previews stay bounded even when a server returns malformed AI output. */
export function parseActionObject(
  raw: string,
  limit = 12_000,
): Record<string, unknown> | null {
  if (typeof raw !== "string" || raw.length > limit) return null;
  try {
    const root: unknown = JSON.parse(raw);
    if (root === null || typeof root !== "object" || Array.isArray(root))
      return null;
    let visited = 0;
    function check(value: unknown, depth: number) {
      if (depth > 16 || ++visited > 2000) throw new Error("Preview limit");
      if (typeof value === "number" && !Number.isFinite(value))
        throw new Error("Invalid number");
      if (value !== null && typeof value === "object")
        for (const child of Object.values(value)) check(child, depth + 1);
    }
    check(root, 0);
    return root as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function chatActionReviewState(
  message: Message,
  now = Date.now(),
): "valid" | "expired" | "invalid" {
  const rawDate = message.created_at;
  if (typeof rawDate !== "string") return "invalid";
  // The older API may return an ISO datetime without an offset; its database clock is UTC.
  const created = Date.parse(
    /(?:Z|[+-]\d{2}:\d{2})$/.test(rawDate) ? rawDate : `${rawDate}Z`,
  );
  if (!Number.isFinite(created)) return "invalid";
  if (created < now - 30 * 60 * 1000) return "expired";
  const actions = message.details.actions;
  if (!Array.isArray(actions) || actions.length < 1 || actions.length > 6)
    return "invalid";
  for (const action of actions) {
    if (
      !action ||
      typeof action.operation !== "string" ||
      action.operation.length > 200 ||
      !/^(POST|PUT|PATCH|DELETE) \/[a-zA-Z0-9_/{}/-]+$/.test(
        action.operation,
      ) ||
      typeof action.label !== "string" ||
      action.label.length < 1 ||
      action.label.length > 120 ||
      typeof action.description !== "string" ||
      action.description.length < 1 ||
      action.description.length > 600 ||
      parseActionObject(action.body_json) === null ||
      parseActionObject(action.parameters_json, 2048) === null
    )
      return "invalid";
  }
  return "valid";
}
