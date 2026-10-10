import assert from "node:assert/strict";
import { test } from "node:test";
import fs from "node:fs";
import ts from "typescript";

// Match the browser's TS module resolution while running the pure helper in Node.
const source = fs.readFileSync(
  new URL("../src/receiptAmounts.ts", import.meta.url),
  "utf8",
);
const code = ts.transpileModule(
  source.replace(
    '"./wallet"',
    JSON.stringify(new URL("../src/wallet.ts", import.meta.url).href),
  ),
  {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
    },
  },
).outputText;
const { receiptLineTotal, receiptReviewTotals } = await import(
  `data:text/javascript;base64,${Buffer.from(code).toString("base64")}`
);

test("review distinguishes missing totals from a real match and compares exact cents", () => {
  const line = {
    name: "Discounted item",
    quantity: "2",
    unit_price: "6.00",
    total: "11.50",
  };
  assert.equal(receiptReviewTotals("", []).differenceMinor, null);
  assert.equal(receiptReviewTotals("", [line]).differenceMinor, null);
  assert.equal(receiptReviewTotals("12.50", [line]).differenceMinor, 100);
  assert.equal(receiptReviewTotals("10.50", [line]).differenceMinor, -100);
  const match = receiptReviewTotals("11.50", [line]);
  assert.equal(match.itemsMinor, 1150);
  assert.equal(match.differenceMinor, 0);
  assert.deepEqual(match.invalidLineNumbers, []);
  assert.equal(line.total, "11.50"); // Explicit discount and included tax are not recalculated.
  assert.equal(
    receiptReviewTotals("0.30", [
      { ...line, total: "0.10" },
      { ...line, total: "0.20" },
    ]).differenceMinor,
    0,
  );
});

test("review points to incomplete lines without silently filling their values", () => {
  const result = receiptReviewTotals("12.50", [
    { name: "", quantity: "1", unit_price: "1.00", total: "1.00" },
    { name: "Weighted", quantity: "1.0000001", unit_price: "1.00", total: "" },
  ]);
  assert.deepEqual(result.invalidLineNumbers, [1, 2]);
  assert.equal(result.itemsMinor, null);
  assert.equal(result.differenceMinor, null);
});

test("manual line totals round exact cents for units and weighed goods", () => {
  assert.equal(receiptLineTotal("2", "6.99"), "13.98");
  assert.equal(receiptLineTotal("1.312", "33.99"), "44.59");
  assert.equal(receiptLineTotal("0.5", "0.29"), "0.15");
  assert.equal(receiptLineTotal("1,5", "2,50"), "3.75");
  assert.equal(receiptLineTotal("3", "0.00"), "0.00");
});

test("manual totals reject invalid quantities, money and oversized lines", () => {
  for (const value of ["", "0", "-1", "1e3", "NaN", "100001", "1.0000001"])
    assert.equal(receiptLineTotal(value, "10"), "");
  for (const value of ["", "-1", "1.999", "1e3", "Infinity"])
    assert.equal(receiptLineTotal("2", value), "");
  assert.equal(receiptLineTotal("100000", "999999999.99"), "");
});
