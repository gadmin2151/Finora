import assert from "node:assert/strict";
import { test } from "node:test";
import { randomUUID } from "node:crypto";
import {
  manualReceiptDraftKey,
  parseManualReceiptDraft,
  readManualReceiptDraft,
  saveManualReceiptDraft,
  clearManualReceiptDraft,
  submitManualReceiptDraft,
  ManualDraftPersistenceError,
} from "../src/manualReceiptDraft.ts";

const draft = () => ({
  requestKey: randomUUID(),
  merchant: "Test store",
  address: "",
  date: "2026-10-09",
  currency: "MDL",
  account: "synthetic-account",
  rate: "1",
  items: [
    {
      name: "Unfinished item",
      quantity: "1,",
      unit: "шт",
      unit_price: "",
      total: "",
      category_id: "",
      category_selected: false,
    },
  ],
});
function memoryStorage() {
  const data = new Map();
  return {
    getItem: (key) => data.get(key) ?? null,
    setItem: (key, value) => data.set(key, value),
    removeItem: (key) => data.delete(key),
  };
}

test("closing and reopening preserve incomplete form and retry ID within its owner scope", () => {
  const storage = memoryStorage();
  const original = draft();
  const key = manualReceiptDraftKey("user-1", "org-1");
  saveManualReceiptDraft(storage, key, original);
  assert.deepEqual(readManualReceiptDraft(storage, key), original);
  assert.equal(
    readManualReceiptDraft(storage, manualReceiptDraftKey("user-2", "org-1")),
    null,
  );
  assert.equal(
    readManualReceiptDraft(storage, manualReceiptDraftKey("user-1", "org-2")),
    null,
  );
  assert.notEqual(
    manualReceiptDraftKey("a.b", "c"),
    manualReceiptDraftKey("a", "b.c"),
  );
  clearManualReceiptDraft(storage, key);
  assert.equal(readManualReceiptDraft(storage, key), null);
});

test("malformed, oversized or foreign storage values never populate a financial form", () => {
  for (const raw of [null, "bad JSON", "null", "[]", "0", "x".repeat(300001)])
    assert.equal(parseManualReceiptDraft(raw), null);
  for (const patch of [
    { requestKey: "reuse-unvalidated-key" },
    { items: [] },
    { items: Array(201).fill(draft().items[0]) },
    { merchant: "x".repeat(201) },
    { items: [null] },
    { items: [{ ...draft().items[0], category_selected: "true" }] },
  ])
    assert.equal(
      parseManualReceiptDraft(JSON.stringify({ ...draft(), ...patch })),
      null,
    );
  const original = draft();
  assert.deepEqual(
    parseManualReceiptDraft(
      JSON.stringify({ ...original, injected: "ignored" }),
    ),
    original,
  );
});

test("storage denial and quota errors stay visible to the caller", () => {
  const unavailable = {
    getItem() {
      throw new Error("blocked");
    },
    setItem() {
      throw new Error("quota");
    },
    removeItem() {
      throw new Error("blocked");
    },
  };
  assert.throws(() => readManualReceiptDraft(unavailable, "key"), /blocked/);
  assert.throws(
    () => saveManualReceiptDraft(unavailable, "key", draft()),
    /quota/,
  );
  assert.throws(() => clearManualReceiptDraft(unavailable, "key"), /blocked/);
});

test("unreadable stored drafts are preserved until explicit deletion", () => {
  const storage = memoryStorage();
  const key = manualReceiptDraftKey("owner", "organization");
  for (const raw of [
    "",
    "bad JSON",
    JSON.stringify({ ...draft(), requestKey: "corrupted" }),
  ]) {
    storage.setItem(key, raw);
    assert.throws(() => readManualReceiptDraft(storage, key), /Unreadable/);
    assert.equal(storage.getItem(key), raw);
    clearManualReceiptDraft(storage, key);
    assert.equal(readManualReceiptDraft(storage, key), null);
  }
});

test("storage failure blocks expense POST before it can commit", async () => {
  let posts = 0;
  const storage = {
    ...memoryStorage(),
    setItem() {
      throw new Error("quota");
    },
  };
  await assert.rejects(
    submitManualReceiptDraft(storage, "key", draft(), async () => {
      posts++;
    }),
    ManualDraftPersistenceError,
  );
  assert.equal(posts, 0);
});

test("a lost committed response can resume the saved draft and retry without a second expense", async () => {
  const storage = memoryStorage();
  const key = manualReceiptDraftKey("synthetic-owner", "synthetic-org");
  const original = draft();
  const posted = new Map();
  const firstPost = async () => {
    assert.deepEqual(readManualReceiptDraft(storage, key), original);
    posted.set(original.requestKey, "expense-1");
    throw new Error("response lost after commit");
  };
  await assert.rejects(
    submitManualReceiptDraft(storage, key, original, firstPost),
    /response lost/,
  );
  const resumed = readManualReceiptDraft(storage, key);
  const result = await submitManualReceiptDraft(
    storage,
    key,
    resumed,
    async () => {
      assert.equal(resumed.requestKey, original.requestKey);
      return posted.get(resumed.requestKey);
    },
  );
  assert.equal(result, "expense-1");
  assert.equal(posted.size, 1);
});
