import assert from "node:assert/strict";
import { test } from "node:test";
import { randomUUID } from "node:crypto";
import {
  manualReceiptDraftKey,
  parseManualReceiptDraft,
  readManualReceiptDraft,
  saveManualReceiptDraft,
  clearManualReceiptDraft,
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
