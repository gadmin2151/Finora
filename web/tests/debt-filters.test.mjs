import assert from "node:assert/strict";
import { test } from "node:test";
import fs from "node:fs";
import ts from "typescript";
const source = fs.readFileSync(
  new URL("../src/debtFilters.ts", import.meta.url),
  "utf8",
);
const code = ts.transpileModule(source, {
  compilerOptions: {
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.ESNext,
  },
}).outputText;
const { debtIsOverdue, filterDebts } = await import(
  `data:text/javascript;base64,${Buffer.from(code).toString("base64")}`
);
const today = "2026-10-10";
const debt = {
  id: "partial",
  direction: "lent",
  currency: "EUR",
  remaining_minor: 320,
  due_date: "2026-10-09",
};

test("overdue means a positive balance with an earlier valid date, not today or closed", () => {
  assert.equal(debtIsOverdue(debt, today), true);
  for (const due_date of [
    null,
    "2026-10-10",
    "2026-10-11",
    "invalid",
    "2026-02-30",
  ])
    assert.equal(debtIsOverdue({ ...debt, due_date }, today), false);
  assert.equal(debtIsOverdue({ ...debt, remaining_minor: 0 }, today), false);
});

test("overdue selection combines direction and closed filters without merging currencies or altering partial balances", () => {
  const rows = [
    debt,
    {
      ...debt,
      id: "mdl",
      currency: "MDL",
      direction: "borrowed",
      remaining_minor: 1000,
    },
    { ...debt, id: "closed", remaining_minor: 0 },
    { ...debt, id: "future", due_date: "2026-10-11" },
  ];
  assert.deepEqual(
    filterDebts(rows, "all", true, true, today).map((d) => d.id),
    ["partial", "mdl"],
  );
  assert.deepEqual(filterDebts(rows, "lent", true, true, today), [debt]);
  assert.equal(rows[0].remaining_minor, 320);
  assert.deepEqual(
    filterDebts(rows, "all", false, true, today).map((d) => d.currency),
    ["EUR", "MDL"],
  );
  assert.equal(filterDebts(rows, "all", true, false, today).length, 4);
});
