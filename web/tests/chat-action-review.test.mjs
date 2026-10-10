import assert from "node:assert/strict";
import { test } from "node:test";
import {
  parseActionObject,
  chatActionReviewState,
} from "../src/chatActionReview.ts";

const now = Date.parse("2026-10-10T01:35:00Z");
const action = {
  operation: "POST /transactions",
  label: "Synthetic income",
  description: "Review exact values",
  body_json:
    '{"amount":"12.50","account_id":"synthetic-cash","note":"<script>text only</script>"}',
  parameters_json: "{}",
};
const message = {
  created_at: "2026-10-10T01:35:00Z",
  details: { actions: [action] },
};

test("bounded preview preserves exact financial strings without reformatting", () => {
  assert.deepEqual(parseActionObject(action.body_json), {
    amount: "12.50",
    account_id: "synthetic-cash",
    note: "<script>text only</script>",
  });
  assert.equal(chatActionReviewState(message, now), "valid");
  assert.equal(
    chatActionReviewState(
      { ...message, created_at: "2026-10-10T01:35:00" },
      now,
    ),
    "valid",
  );
});

test("the reproduced deep proposal is rejected without overflowing the stack", () => {
  let body = "1";
  for (let i = 0; i < 1900; i++) body = `{"x":${body}}`;
  assert.ok(body.length < 12000);
  assert.equal(parseActionObject(body), null);
  assert.equal(
    chatActionReviewState(
      { ...message, details: { actions: [{ ...action, body_json: body }] } },
      now,
    ),
    "invalid",
  );
});

test("malformed, non-object, oversized and excessive previews cannot be reviewed", () => {
  for (const raw of [
    "broken",
    "null",
    "[]",
    '"text"',
    '{"amount":1e400}',
    " ".repeat(12001),
  ])
    assert.equal(parseActionObject(raw), null);
  const excessiveFields = JSON.stringify({ items: Array(2001).fill(0) });
  assert.ok(excessiveFields.length < 12000);
  assert.equal(parseActionObject(excessiveFields), null);
  assert.equal(
    chatActionReviewState(
      { ...message, details: { actions: Array(7).fill(action) } },
      now,
    ),
    "invalid",
  );
  assert.equal(
    chatActionReviewState(
      {
        ...message,
        details: {
          actions: [{ ...action, operation: "POST https://synthetic.invalid" }],
        },
      },
      now,
    ),
    "invalid",
  );
});

test("expiry boundary matches the existing server and naive UTC timestamp contract", () => {
  assert.equal(chatActionReviewState(message, now + 1800000), "valid");
  assert.equal(chatActionReviewState(message, now + 1800001), "expired");
  assert.equal(
    chatActionReviewState({ ...message, created_at: "unknown" }, now),
    "invalid",
  );
});
