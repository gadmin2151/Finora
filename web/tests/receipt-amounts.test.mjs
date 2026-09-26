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
const { receiptLineTotal } = await import(
  `data:text/javascript;base64,${Buffer.from(code).toString("base64")}`
);

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
