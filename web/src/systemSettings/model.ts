export type Scalar = string | number | boolean | null;
export type FieldType = "boolean" | "integer" | "string" | "secret";
export type SecretAction = "keep" | "replace" | "inherit" | "disable";
export type SettingsField = {
  name: string;
  type: FieldType;
  label: string;
  description: string;
  editable: boolean;
  secret: boolean;
  current: Scalar;
  effective: Scalar;
  default: Scalar;
  source: "override" | "environment" | "default" | "disabled";
  configured: boolean;
  mode: "inherit" | "override" | "disable";
  apply: "request" | "worker_boundary" | "restart";
  minimum?: number;
  maximum?: number;
  pattern?: string;
  actions: SecretAction[];
};
export type SettingsGroup = { id: string; fields: SettingsField[] };
export type RuntimeObservation = {
  revision: number | null;
  seen_at: string | null;
  state: "applied" | "pending" | "unknown";
};
export type SettingsSnapshot = {
  schema_version: 1;
  revision: number;
  groups: SettingsGroup[];
  runtime: { api: RuntimeObservation; worker: RuntimeObservation };
  saved?: boolean;
  changed: string[];
};
export type PublicDraft = {
  mode: "inherit" | "override";
  value: string | boolean;
};
export type SecretDraft = {
  action: SecretAction;
  value: string;
  confirmed: boolean;
};
export type GroupDraft = {
  revision: number;
  fields: Record<string, PublicDraft>;
  secrets: Record<string, SecretDraft>;
};
export type SettingsPatch = {
  revision: number;
  changes: Record<string, Scalar>;
  secrets: Record<string, { action: SecretAction; value?: string }>;
};
export type ValidationCode = "invalid" | "range" | "empty" | "confirmation";
export const publicNames = new Set([
  "push_enabled",
  "push_worker_enabled",
  "push_fcm_project_id",
  "ai_timeout",
  "ai_cpu_threads",
  "max_upload_mb",
  "mev_browser",
]);
export const secretNames = new Set([
  "global_openai_key",
  "firebase_service_account",
]);
const groupNames = new Set([
  "firebase",
  "ai",
  "ocr",
  "runtime",
  "infrastructure",
]);
const actions: SecretAction[] = ["keep", "replace", "inherit", "disable"];
const record = (value: unknown): Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
const text = (value: unknown, max = 1024) =>
  typeof value === "string" ? value.slice(0, max) : "";
const scalar = (value: unknown): Scalar =>
  typeof value === "string" ||
  typeof value === "boolean" ||
  (typeof value === "number" && Number.isFinite(value))
    ? value
    : null;
const observation = (value: unknown): RuntimeObservation => {
  const item = record(value);
  return {
    revision:
      typeof item.revision === "number" && Number.isInteger(item.revision)
        ? item.revision
        : null,
    seen_at: typeof item.seen_at === "string" ? text(item.seen_at, 100) : null,
    state:
      item.state === "applied" || item.state === "pending"
        ? item.state
        : "unknown",
  };
};
/** Copy only schema properties. Secret values and unexpected response properties never enter UI state. */
export function parseSnapshot(value: unknown): SettingsSnapshot {
  const root = record(value);
  if (
    root.schema_version !== 1 ||
    !Number.isInteger(root.revision) ||
    Number(root.revision) < 0 ||
    !Array.isArray(root.groups)
  )
    throw new Error("Unsupported settings schema");
  const seen = new Set<string>();
  const groups = root.groups.map((raw) => {
    const group = record(raw);
    if (!groupNames.has(String(group.id)) || !Array.isArray(group.fields))
      throw new Error("Unsupported settings group");
    const fields = group.fields.map((rawField) => {
      const f = record(rawField);
      const name = text(f.name, 100);
      if (
        !name ||
        seen.has(name) ||
        !["boolean", "integer", "string", "secret"].includes(String(f.type))
      )
        throw new Error("Unsupported settings field");
      seen.add(name);
      const secret = f.secret === true || f.type === "secret";
      const allowedActions = Array.isArray(f.actions) ? f.actions : [];
      return {
        name,
        type: secret ? "secret" : (f.type as FieldType),
        label: text(f.label, 200),
        description: text(f.description),
        editable:
          f.editable === true &&
          (secret ? secretNames.has(name) : publicNames.has(name)),
        secret,
        current: secret ? null : scalar(f.current),
        effective: secret ? null : scalar(f.effective),
        default: secret ? null : scalar(f.default),
        source: ["override", "environment", "default", "disabled"].includes(
          String(f.source),
        )
          ? (f.source as SettingsField["source"])
          : "default",
        configured: f.configured === true,
        mode:
          f.mode === "override" || f.mode === "disable" ? f.mode : "inherit",
        apply:
          f.apply === "worker_boundary" || f.apply === "restart"
            ? f.apply
            : "request",
        minimum: typeof f.minimum === "number" ? f.minimum : undefined,
        maximum: typeof f.maximum === "number" ? f.maximum : undefined,
        pattern:
          typeof f.pattern === "string" ? text(f.pattern, 512) : undefined,
        actions: actions.filter((a) => allowedActions.includes(a)),
      } as SettingsField;
    });
    return { id: String(group.id), fields };
  });
  const runtime = record(root.runtime);
  return {
    schema_version: 1,
    revision: Number(root.revision),
    groups,
    runtime: {
      api: observation(runtime.api),
      worker: observation(runtime.worker),
    },
    saved: root.saved === true,
    changed: Array.isArray(root.changed)
      ? root.changed.filter(
          (n): n is string => typeof n === "string" && seen.has(n),
        )
      : [],
  };
}
export function fieldDraft(field: SettingsField): PublicDraft {
  const effective = field.current ?? field.effective ?? field.default;
  return {
    mode: field.current === null ? "inherit" : "override",
    value:
      field.type === "boolean"
        ? effective === true
        : effective === null
          ? ""
          : String(effective),
  };
}
export class SettingsValidationError extends Error {
  fields: Record<string, ValidationCode>;
  constructor(fields: Record<string, ValidationCode>) {
    super("Settings validation failed");
    this.fields = fields;
  }
}
export function buildPatch(
  group: SettingsGroup,
  draft: GroupDraft,
): SettingsPatch {
  const changes: SettingsPatch["changes"] = {};
  const secrets: SettingsPatch["secrets"] = {};
  const errors: Record<string, ValidationCode> = {};
  for (const field of group.fields) {
    if (!field.editable) continue;
    if (field.secret) {
      const secret = draft.secrets[field.name];
      if (!secret || secret.action === "keep") continue;
      if (!field.actions.includes(secret.action)) {
        errors[field.name] = "invalid";
        continue;
      }
      if (secret.action === "replace") {
        if (!secret.value.trim() || secret.value.length > 65536) {
          errors[field.name] = "empty";
          continue;
        }
        secrets[field.name] = { action: "replace", value: secret.value };
      } else {
        if (!secret.confirmed) {
          errors[field.name] = "confirmation";
          continue;
        }
        secrets[field.name] = { action: secret.action };
      }
      continue;
    }
    const input = draft.fields[field.name];
    if (!input) continue;
    let value: Scalar = null;
    if (input.mode === "override") {
      if (field.type === "boolean") {
        if (typeof input.value !== "boolean") {
          errors[field.name] = "invalid";
          continue;
        }
        value = input.value;
      } else if (field.type === "integer") {
        if (
          typeof input.value !== "string" ||
          !/^-?\d+$/.test(input.value) ||
          !Number.isSafeInteger(Number(input.value))
        ) {
          errors[field.name] = "invalid";
          continue;
        }
        value = Number(input.value);
        if (
          (field.minimum !== undefined && value < field.minimum) ||
          (field.maximum !== undefined && value > field.maximum)
        ) {
          errors[field.name] = "range";
          continue;
        }
      } else {
        value = String(input.value).trim();
        if (value.length > 4096) {
          errors[field.name] = "invalid";
          continue;
        }
        if (field.pattern) {
          try {
            if (!new RegExp(field.pattern).test(value)) {
              errors[field.name] = "invalid";
              continue;
            }
          } catch {
            errors[field.name] = "invalid";
            continue;
          }
        }
      }
    }
    if (value !== field.current) changes[field.name] = value;
  }
  if (Object.keys(errors).length) throw new SettingsValidationError(errors);
  return { revision: draft.revision, changes, secrets };
}
export function hasChanges(patch: SettingsPatch) {
  return (
    Object.keys(patch.changes).length + Object.keys(patch.secrets).length > 0
  );
}
export function clearSecrets(draft: GroupDraft): GroupDraft {
  return { ...draft, secrets: {} };
}
export function isApplied(observation: RuntimeObservation, revision: number) {
  return (
    observation.state === "applied" &&
    observation.revision !== null &&
    observation.revision >= revision
  );
}
