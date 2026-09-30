import assert from "node:assert/strict";
import { test } from "node:test";
import {
  nextTheme,
  parseThemePreference,
  resolveTheme,
} from "../src/themeCore.ts";

test("existing appearance preferences remain compatible and invalid values follow the system", () => {
  for (const value of ["dark", "light", "material", "system"])
    assert.equal(parseThemePreference(value), value);
  for (const value of [null, undefined, "black", "", {}, "DARK"])
    assert.equal(parseThemePreference(value), "system");
  assert.equal(resolveTheme("system", true), "dark");
  assert.equal(resolveTheme("system", false), "light");
  for (const value of ["dark", "light", "material"])
    for (const systemDark of [true, false])
      assert.equal(resolveTheme(value, systemDark), value);
});

test("toolbar cycles all three themes without dropping Material Black", () => {
  assert.equal(nextTheme("dark"), "light");
  assert.equal(nextTheme("light"), "material");
  assert.equal(nextTheme("material"), "dark");
  for (const value of ["dark", "light", "material"])
    assert.equal(nextTheme(nextTheme(nextTheme(value))), value);
});
