import assert from "node:assert/strict";
import { test } from "node:test";
import fs from "node:fs";
import path from "node:path";
import ts from "typescript";
import english from "../src/locales/en.json" with { type: "json" };
import {
  resolveLanguage,
  parseLanguagePreference,
  formatMessage,
  pluralIndex,
} from "../src/i18nCore.ts";

test("first visit follows primary device language and otherwise uses English", () => {
  for (const locale of ["ru", "ru-MD", "ru_RU", "RU-ru"])
    assert.equal(resolveLanguage("system", locale), "ru");
  for (const locale of ["en-US", "ro-MD", "uk-UA", "fr", "", "russian"])
    assert.equal(resolveLanguage("system", locale), "en");
  assert.equal(resolveLanguage("en", "ru"), "en");
  assert.equal(resolveLanguage("ru", "en"), "ru");
  for (const value of [null, undefined, "invalid", {}, "RU"])
    assert.equal(parseLanguagePreference(value), "system");
  assert.equal(parseLanguagePreference("ru"), "ru");
});
test("messages interpolate once without translating or reinterpreting user data", () => {
  const source = "Фото профиля: {0}";
  const name = "Елена {1} <script>";
  assert.equal(
    formatMessage(source, "en", { [source]: "Profile photo: {0}" }, [name]),
    `Profile photo: ${name}`,
  );
  assert.equal(
    formatMessage(source, "ru", english, [name]),
    `Фото профиля: ${name}`,
  );
  assert.equal(
    formatMessage("external user value", "en", english),
    "external user value",
  );
});
test("English and Russian counts follow their own plural rules", () => {
  assert.deepEqual(
    [0, 1, 2, 5, 11, 21, 22, 101, 1.5].map((n) => pluralIndex(n, "ru")),
    [2, 0, 1, 2, 2, 0, 1, 0, 2],
  );
  assert.deepEqual(
    [0, 1, 2, 11, 21, 101].map((n) => pluralIndex(n, "en")),
    [2, 0, 2, 2, 2, 2],
  );
});
const placeholders = (value) =>
  [...value.matchAll(/\{(\d+)\}/g)].map((match) => match[1]).sort();
test("every translated message preserves all interpolation slots", () => {
  for (const [source, translated] of Object.entries(english)) {
    assert.ok(translated.trim(), `Empty translation: ${source}`);
    assert.deepEqual(placeholders(translated), placeholders(source), source);
  }
});
test("all UI source messages have English translations; static Russian cannot silently leak", () => {
  let count = 0;
  function walk(folder) {
    for (const entry of fs.readdirSync(folder, { withFileTypes: true })) {
      const file = path.join(folder, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== "locales") walk(file);
        continue;
      }
      if (!/\.tsx?$/.test(file) || entry.name === "i18n.ts") continue;
      const source = ts.createSourceFile(
        file,
        fs.readFileSync(file, "utf8"),
        ts.ScriptTarget.Latest,
        true,
      );
      function visit(node) {
        if (
          ts.isCallExpression(node) &&
          node.expression.getText(source) === "t"
        ) {
          assert.ok(
            ts.isStringLiteral(node.arguments[0]),
            `Use literal message keys: ${file}`,
          );
          const key = node.arguments[0].text;
          assert.ok(Object.hasOwn(english, key), `Missing English: ${key}`);
          const slots = placeholders(key);
          if (slots.length)
            assert.equal(
              Math.max(...slots.map(Number)) + 1,
              node.arguments.length - 1,
              key,
            );
          count++;
        } else if (
          (ts.isStringLiteral(node) || ts.isJsxText(node)) &&
          /[А-Яа-яЁё]/.test(node.text)
        ) {
          const translated =
            ts.isCallExpression(node.parent) &&
            node.parent.expression.getText(source) === "t";
          const languageName =
            entry.name === "Language.tsx" && node.text.trim() === "Русский";
          const storedUnit =
            entry.name === "units.ts" &&
            ["шт", "кг", "г", "л", "мл"].includes(node.text);
          assert.ok(
            translated || languageName || storedUnit,
            `Untranslated UI text in ${file}: ${node.text}`,
          );
        }
        ts.forEachChild(node, visit);
      }
      visit(source);
    }
  }
  walk(new URL("../src/", import.meta.url).pathname);
  assert.ok(count > 1100, "Verify the full application catalog");
});

test("changing language refreshes reports without retrying failed auth or dropping login state", async () => {
  const { QueryClient, QueryObserver } = await import("@tanstack/react-query");
  const { refreshLocalizedQueries } = await import("../src/i18nQueries.ts");
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  let authRequests = 0;
  const auth = new QueryObserver(client, {
    queryKey: ["me"],
    queryFn: async () => {
      authRequests++;
      throw new Error("offline");
    },
  });
  const stopAuth = auth.subscribe(() => {});
  await auth.refetch();
  const before = authRequests;
  let language = "ru";
  const report = new QueryObserver(client, {
    queryKey: ["report"],
    queryFn: async () => ({ title: language, total: 10088 }),
  });
  const stopReport = report.subscribe(() => {});
  await report.refetch();
  language = "en";
  await refreshLocalizedQueries(client);
  assert.equal(authRequests, before);
  assert.equal(auth.getCurrentResult().status, "error");
  assert.deepEqual(report.getCurrentResult().data, {
    title: "en",
    total: 10088,
  });
  stopAuth();
  stopReport();
  client.clear();
});
