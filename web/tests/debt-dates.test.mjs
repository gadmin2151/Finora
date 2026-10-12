import assert from "node:assert/strict";
import { test } from "node:test";
import { debtRecordTime } from "../src/debtDates.ts";

test("debt history treats old naive timestamps as UTC and displays the accounting timezone", () => {
  const naive = debtRecordTime("2026-10-12T21:30:00", "en-GB");
  assert.deepEqual(naive, debtRecordTime("2026-10-12T21:30:00Z", "en-GB"));
  assert.equal(naive.iso, "2026-10-12T21:30:00.000Z");
  assert.equal(naive.date, "2026-10-13");
  assert.match(naive.label, /00:30/);
  assert.equal(
    debtRecordTime("2026-01-12T21:30:00Z", "en-GB").date,
    "2026-01-12",
  );
  assert.match(debtRecordTime("2026-01-12T21:30:00Z", "ru-RU").label, /23:30/);
});

test("explicit offsets represent the same recorded instant, while absent or malformed times are not fabricated", () => {
  assert.deepEqual(
    debtRecordTime("2026-10-12T23:30:00+02:00", "en-GB"),
    debtRecordTime("2026-10-12T21:30:00Z", "en-GB"),
  );
  for (const raw of [
    "",
    "not-a-date",
    "2026-10-12",
    "2026-13-45T40:80:99",
    "12 October 2026",
  ])
    assert.equal(debtRecordTime(raw, "en-GB"), null);
});
