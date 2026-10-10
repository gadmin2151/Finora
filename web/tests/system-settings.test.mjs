import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildPatch,
  clearSecrets,
  fieldDraft,
  hasChanges,
  isApplied,
  parseSnapshot,
  SettingsValidationError,
} from "../src/systemSettings/model.ts";
const base = {
  label: "Field",
  description: "Help",
  editable: true,
  secret: false,
  current: null,
  effective: 30,
  default: 30,
  source: "default",
  configured: false,
  mode: "inherit",
  apply: "request",
  actions: [],
};
const timeout = {
  ...base,
  name: "ai_timeout",
  type: "integer",
  minimum: 1,
  maximum: 300,
};
const credential = {
  ...base,
  name: "global_openai_key",
  type: "secret",
  secret: true,
  effective: null,
  default: null,
  configured: true,
  actions: ["keep", "replace", "inherit", "disable"],
};
const group = { id: "ai", fields: [timeout, credential] };
const draft = (fields = {}, secrets = {}) => ({ revision: 8, fields, secrets });
const snapshot = (groups = [group]) => ({
  schema_version: 1,
  revision: 8,
  groups,
  runtime: {
    api: { revision: 8, state: "applied" },
    worker: { revision: 7, state: "pending" },
  },
});
test("response projection discards secret current/effective/default and unexpected values", () => {
  const token = "synthetic-credential-must-not-enter-state";
  const data = snapshot([
    {
      id: "ai",
      fields: [
        {
          ...credential,
          current: token,
          effective: token,
          default: token,
          value: token,
        },
      ],
    },
  ]);
  data.unexpected_secret = token;
  const parsed = parseSnapshot(data);
  assert.equal(JSON.stringify(parsed).includes(token), false);
  assert.equal(parsed.groups[0].fields[0].configured, true);
  assert.deepEqual(
    [
      parsed.groups[0].fields[0].current,
      parsed.groups[0].fields[0].effective,
      parsed.groups[0].fields[0].default,
    ],
    [null, null, null],
  );
});
test("unknown editable names become read-only and cannot enter allowlisted patch", () => {
  const parsed = parseSnapshot(
    snapshot([
      { id: "infrastructure", fields: [{ ...timeout, name: "database_url" }] },
    ]),
  );
  assert.equal(parsed.groups[0].fields[0].editable, false);
  assert.equal(
    hasChanges(
      buildPatch(
        parsed.groups[0],
        draft({ database_url: { mode: "override", value: "99" } }),
      ),
    ),
    false,
  );
});
test("empty/keep credentials never submit placeholders or hidden values", () => {
  assert.deepEqual(
    buildPatch(
      group,
      draft(
        {},
        {
          global_openai_key: {
            action: "keep",
            value: "masked-placeholder",
            confirmed: false,
          },
        },
      ),
    ).secrets,
    {},
  );
  assert.throws(
    () =>
      buildPatch(
        group,
        draft(
          {},
          {
            global_openai_key: {
              action: "replace",
              value: "   ",
              confirmed: false,
            },
          },
        ),
      ),
    SettingsValidationError,
  );
  const patch = buildPatch(
    group,
    draft(
      {},
      {
        global_openai_key: {
          action: "replace",
          value: "synthetic-test-key",
          confirmed: false,
        },
      },
    ),
  );
  assert.deepEqual(patch.secrets, {
    global_openai_key: { action: "replace", value: "synthetic-test-key" },
  });
});
test("inherit and disable require confirmation and never send a value", () => {
  for (const action of ["inherit", "disable"]) {
    assert.throws(
      () =>
        buildPatch(
          group,
          draft(
            {},
            {
              global_openai_key: { action, value: "ignored", confirmed: false },
            },
          ),
        ),
      SettingsValidationError,
    );
    assert.deepEqual(
      buildPatch(
        group,
        draft(
          {},
          { global_openai_key: { action, value: "ignored", confirmed: true } },
        ),
      ).secrets,
      { global_openai_key: { action } },
    );
  }
});
test("numeric changes are typed, bounded and diff-only; inheriting emits null", () => {
  assert.deepEqual(fieldDraft(timeout), { mode: "inherit", value: "30" });
  assert.deepEqual(
    buildPatch(group, draft({ ai_timeout: { mode: "override", value: "45" } }))
      .changes,
    { ai_timeout: 45 },
  );
  for (const value of ["1e2", "NaN", "0", "301", "1.2", "9007199254740992", ""])
    assert.throws(
      () =>
        buildPatch(group, draft({ ai_timeout: { mode: "override", value } })),
      SettingsValidationError,
    );
  const overridden = {
    ...group,
    fields: [{ ...timeout, current: 45, effective: 45 }],
  };
  assert.deepEqual(
    buildPatch(
      overridden,
      draft({ ai_timeout: { mode: "inherit", value: "45" } }),
    ).changes,
    { ai_timeout: null },
  );
  assert.equal(
    hasChanges(
      buildPatch(
        overridden,
        draft({ ai_timeout: { mode: "override", value: "45" } }),
      ),
    ),
    false,
  );
});
test("captured draft revision stays old after another group saves; secrets clear without losing public draft", () => {
  const pending = draft(
    { ai_timeout: { mode: "override", value: "50" } },
    {
      global_openai_key: {
        action: "replace",
        value: "synthetic",
        confirmed: false,
      },
    },
  );
  const next = clearSecrets(pending);
  assert.equal(buildPatch(group, next).revision, 8);
  assert.deepEqual(next.fields, pending.fields);
  assert.deepEqual(next.secrets, {});
});
test("saved does not imply applied: observations need acknowledged revision and applied state", () => {
  assert.equal(isApplied({ state: "applied", revision: 7 }, 8), false);
  assert.equal(isApplied({ state: "pending", revision: 8 }, 8), false);
  assert.equal(isApplied({ state: "unknown", revision: null }, 8), false);
  assert.equal(isApplied({ state: "applied", revision: 8 }, 8), true);
});
