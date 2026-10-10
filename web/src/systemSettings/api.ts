import { api, ApiError } from "../api";
import { parseSnapshot, type SettingsPatch } from "./model";
export async function readSettings(signal: AbortSignal) {
  return parseSnapshot(await api<unknown>("/system/settings", { signal }));
}
export async function writeSettings(patch: SettingsPatch, signal: AbortSignal) {
  const snapshot = parseSnapshot(
    await api<unknown>("/system/settings", {
      method: "PUT",
      body: JSON.stringify(patch),
      signal,
    }),
  );
  if (!snapshot.saved) throw new Error("Unconfirmed settings save");
  return snapshot;
}
export type FailureKind =
  "conflict" | "validation" | "forbidden" | "authentication" | "uncertain";
/** Never render transport error text: write-only credentials may appear in upstream errors. */
export function failureKind(error: unknown): FailureKind {
  if (error instanceof ApiError) {
    if (error.status === 409) return "conflict";
    if (error.status === 422) return "validation";
    if (error.status === 403) return "forbidden";
    if (error.status === 401) return "authentication";
  }
  return "uncertain";
}
export function failureFields(error: unknown, allowed: Set<string>): string[] {
  if (!(error instanceof ApiError) || error.status !== 422) return [];
  const fields = (error as ApiError & { fields?: unknown }).fields;
  return Array.isArray(fields)
    ? fields.filter(
        (name): name is string => typeof name === "string" && allowed.has(name),
      )
    : [];
}
