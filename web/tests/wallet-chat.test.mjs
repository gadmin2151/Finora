import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { AssistantMarkdown, assistantLink } from "../src/AssistantMarkdown.ts";
import { correctionTarget, moneyMinor, minorDecimal } from "../src/wallet.ts";
import {
  categoryPurchasesHash,
  purchaseHashFilters,
} from "../src/purchaseNavigation.ts";

test("balance corrections keep cents exact for add, subtract and actual balance", () => {
  assert.equal(correctionTarget(110000, "add", "0.29"), 110029);
  assert.equal(correctionTarget(110000, "subtract", "1200.01"), -10001);
  assert.equal(correctionTarget(110000, "set", "0"), 0);
  assert.equal(correctionTarget(110000, "set", "-250,75"), -25075);
  for (const invalid of [
    "",
    "1e3",
    "NaN",
    "Infinity",
    "0.001",
    "1.2.3",
    "1000000000",
  ])
    assert.equal(moneyMinor(invalid), null);
  assert.equal(correctionTarget(110000, "add", "-2"), null);
  assert.equal(correctionTarget(110000, "subtract", "0"), null);
  assert.equal(correctionTarget(99_999_999_999, "add", "0.01"), null);
  for (const value of [0, 1, -1, 29, -25075, 12345678901])
    assert.equal(moneyMinor(minorDecimal(value)), value);
});

test("category drilldown preserves month and uncategorized, rejecting malformed input", () => {
  const category = "41c665f9-af9a-446f-8180-fb9696e2d324";
  assert.deepEqual(
    purchaseHashFilters(categoryPurchasesHash(category, "2026-09")),
    { category, month: "2026-09" },
  );
  assert.deepEqual(
    purchaseHashFilters("#" + categoryPurchasesHash(null, "2024-02")),
    { category: "uncategorized", month: "2024-02" },
  );
  assert.deepEqual(
    purchaseHashFilters("#purchases?category_id=%3Cscript%3E&month=2026-99"),
    { category: "", month: "" },
  );
  assert.deepEqual(
    purchaseHashFilters("#overview?category_id=food&month=2026-09"),
    { category: "", month: "" },
  );
});

const render = (text) =>
  renderToStaticMarkup(createElement(AssistantMarkdown, { text }));
test("assistant Markdown renders headings, emphasis, lists, code and tables", () => {
  const html = render(
    "## Summary\n\n**Food** and *transport*.\n\n1. First\n2. Second\n\n> Keep a buffer\n\n`inline`\n\n```js\nconst n = 1;\n```\n\n| Item | MDL |\n| --- | --- |\n| Food | 30 |\n\n[Reference](https://example.com/report)",
  );
  for (const tag of [
    "<h2>",
    "<strong>",
    "<em>",
    "<ol>",
    "<li>",
    "<blockquote>",
    "<code>",
    "<pre>",
    "<table>",
    "<th>",
    "<td>",
  ])
    assert.ok(html.includes(tag), tag);
  assert.match(html, /rel="noopener noreferrer"/);
  assert.match(html, /href="https:\/\/example.com\/report"/);
});
test("assistant Markdown does not execute HTML, load images or create unsafe links", () => {
  const html = render(
    '<script>alert(1)</script>\n\n<img src="https://tracker.test/pixel" onerror="alert(2)">\n\n![tracking](https://tracker.test/pixel)\n\n[unsafe](javascript:alert%281%29)\n\n[encoded](jav&#x61;script:alert%281%29)\n\n[relative](/api/export.json)\n\n<iframe src="https://tracker.test"></iframe>',
  );
  assert.doesNotMatch(
    html,
    /<(?:script|img|iframe)|onerror=|href="(?:javascript|data|\/api)/i,
  );
  for (const url of [
    "javascript:alert(1)",
    "data:text/html,a",
    "//tracker.test",
    "/api/export.json",
    "https:\n//example.com",
    "file:///etc/passwd",
    "vbscript:foo",
  ])
    assert.equal(assistantLink(url), "");
  for (const url of [
    "https://example.com",
    "http://example.com/a?q=1",
    "mailto:hello@example.com",
  ])
    assert.equal(assistantLink(url), url);
});
